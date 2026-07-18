package com.unciv.logic.multiplayer.authoritative

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

sealed interface AuthoritativeSyncState {
    data object Uninitialized : AuthoritativeSyncState
    data class Refreshing(val cached: ApiV3GameProjection?) : AuthoritativeSyncState
    data class Synchronized(val current: ApiV3GameProjection) : AuthoritativeSyncState
    data class Submitting(val current: ApiV3GameProjection, val commandId: String) : AuthoritativeSyncState
    data class Retryable(
        val current: ApiV3GameProjection,
        val pending: PendingAuthoritativeCommand,
        val cause: Throwable,
    ) : AuthoritativeSyncState
    data class Rejected(
        val current: ApiV3GameProjection,
        val code: String,
    ) : AuthoritativeSyncState
}

sealed interface PendingAuthoritativeCommand {
    val commandId: String
    val expectedRevision: Long
    val observedStateHash: String

    data class MoveUnit(
        override val commandId: String,
        override val expectedRevision: Long,
        override val observedStateHash: String,
        val unitId: Int,
        val destinationX: Int,
        val destinationY: Int,
    ) : PendingAuthoritativeCommand

    data class EndTurn(
        override val commandId: String,
        override val expectedRevision: Long,
        override val observedStateHash: String,
    ) : PendingAuthoritativeCommand
}

sealed interface AuthoritativeCommandOutcome {
    data class Accepted(
        val result: ApiV3CommandAccepted,
        val current: ApiV3GameProjection,
    ) : AuthoritativeCommandOutcome
    data class StaleRefreshed(val current: ApiV3GameProjection) : AuthoritativeCommandOutcome
    data class Rejected(val code: String) : AuthoritativeCommandOutcome
    data object RetryRequired : AuthoritativeCommandOutcome
}

/**
 * Serializes commands from one client and treats its projection as disposable.
 * There is no optimistic canonical mutation here: a rejected command leaves the
 * last server projection intact, while ambiguous network failures retain the
 * exact command ID for safe idempotent retry.
 */
class AuthoritativeGameCommandBus(
    private val gameId: String,
    private val transport: ApiV3Transport,
    private val commandIdFactory: () -> String = { UUID.randomUUID().toString() },
) {
    private val mutex = Mutex()
    var state: AuthoritativeSyncState = AuthoritativeSyncState.Uninitialized
        private set

    suspend fun refresh(): ApiV3GameProjection = mutex.withLock {
        refreshLocked(cachedProjection())
    }

    suspend fun moveUnit(unitId: Int, destinationX: Int, destinationY: Int) = mutex.withLock {
        val current = requireSynchronized()
        submitLocked(PendingAuthoritativeCommand.MoveUnit(
            commandId = commandIdFactory(),
            expectedRevision = current.committedRevision,
            observedStateHash = current.canonicalStateHash,
            unitId = unitId,
            destinationX = destinationX,
            destinationY = destinationY,
        ), current)
    }

    suspend fun endTurn() = mutex.withLock {
        val current = requireSynchronized()
        submitLocked(PendingAuthoritativeCommand.EndTurn(
            commandId = commandIdFactory(),
            expectedRevision = current.committedRevision,
            observedStateHash = current.canonicalStateHash,
        ), current)
    }

    suspend fun retryPending(): AuthoritativeCommandOutcome = mutex.withLock {
        val retryable = state as? AuthoritativeSyncState.Retryable
            ?: error("There is no ambiguous command to retry")
        submitLocked(retryable.pending, retryable.current)
    }

    private suspend fun submitLocked(
        pending: PendingAuthoritativeCommand,
        current: ApiV3GameProjection,
    ): AuthoritativeCommandOutcome {
        state = AuthoritativeSyncState.Submitting(current, pending.commandId)
        val accepted = try {
            when (pending) {
                is PendingAuthoritativeCommand.MoveUnit -> transport.moveUnit(
                    gameId,
                    ApiV3MoveUnitRequest(
                        pending.commandId,
                        pending.expectedRevision,
                        pending.observedStateHash,
                        pending.unitId,
                        pending.destinationX,
                        pending.destinationY,
                    ),
                )
                is PendingAuthoritativeCommand.EndTurn -> transport.endTurn(
                    gameId,
                    ApiV3EndTurnRequest(
                        pending.commandId,
                        pending.expectedRevision,
                        pending.observedStateHash,
                    ),
                )
            }
        } catch (exception: ApiV3Exception) {
            if (exception.httpStatus == 409 && exception.error.code == "stale_revision") {
                val refreshed = refreshLocked(current)
                return AuthoritativeCommandOutcome.StaleRefreshed(refreshed)
            }
            state = AuthoritativeSyncState.Rejected(current, exception.error.code)
            return AuthoritativeCommandOutcome.Rejected(exception.error.code)
        } catch (exception: Throwable) {
            state = AuthoritativeSyncState.Retryable(current, pending, exception)
            return AuthoritativeCommandOutcome.RetryRequired
        }

        check(accepted.gameId == gameId) { "Server accepted a command for a different game" }
        check(accepted.commandId == pending.commandId) { "Server returned a different command ID" }
        check(accepted.previousRevision == pending.expectedRevision) { "Server returned an invalid parent revision" }
        return try {
            val refreshed = transport.projection(gameId)
            check(refreshed.committedRevision == accepted.committedRevision) {
                "Projection did not reconcile to the accepted revision"
            }
            check(refreshed.canonicalStateHash == accepted.canonicalStateHash) {
                "Projection canonical hash did not match the accepted command"
            }
            state = AuthoritativeSyncState.Synchronized(refreshed)
            AuthoritativeCommandOutcome.Accepted(accepted, refreshed)
        } catch (exception: Throwable) {
            // The command may already be durable. Retain its ID and retry it;
            // the server will return the original result before we refresh.
            state = AuthoritativeSyncState.Retryable(current, pending, exception)
            AuthoritativeCommandOutcome.RetryRequired
        }
    }

    private suspend fun refreshLocked(cached: ApiV3GameProjection?): ApiV3GameProjection {
        state = AuthoritativeSyncState.Refreshing(cached)
        val refreshed = transport.projection(gameId)
        check(refreshed.gameId == gameId) { "Server returned a projection for a different game" }
        state = AuthoritativeSyncState.Synchronized(refreshed)
        return refreshed
    }

    private fun requireSynchronized() =
        (state as? AuthoritativeSyncState.Synchronized)?.current
            ?: error("Refresh the authoritative game before submitting a command")

    private fun cachedProjection() = when (val current = state) {
        is AuthoritativeSyncState.Synchronized -> current.current
        is AuthoritativeSyncState.Refreshing -> current.cached
        is AuthoritativeSyncState.Submitting -> current.current
        is AuthoritativeSyncState.Retryable -> current.current
        is AuthoritativeSyncState.Rejected -> current.current
        AuthoritativeSyncState.Uninitialized -> null
    }
}
