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

    data class QueueConstruction(
        override val commandId: String,
        override val expectedRevision: Long,
        override val observedStateHash: String,
        val cityId: String,
        val constructionName: String,
    ) : PendingAuthoritativeCommand

    data class QueueConstructionAtTile(
        override val commandId: String,
        override val expectedRevision: Long,
        override val observedStateHash: String,
        val cityId: String,
        val constructionName: String,
        val x: Int,
        val y: Int,
    ) : PendingAuthoritativeCommand

    data class SetPerpetualConstruction(
        override val commandId: String,
        override val expectedRevision: Long,
        override val observedStateHash: String,
        val cityId: String,
        val constructionName: String,
    ) : PendingAuthoritativeCommand

    data class RemoveConstruction(
        override val commandId: String,
        override val expectedRevision: Long,
        override val observedStateHash: String,
        val cityId: String,
        val queueIndex: Int,
        val expectedConstructionName: String,
    ) : PendingAuthoritativeCommand

    data class MoveConstruction(
        override val commandId: String,
        override val expectedRevision: Long,
        override val observedStateHash: String,
        val cityId: String,
        val fromIndex: Int,
        val toIndex: Int,
        val expectedConstructionName: String,
    ) : PendingAuthoritativeCommand

    data class PurchaseConstruction(
        override val commandId: String,
        override val expectedRevision: Long,
        override val observedStateHash: String,
        val cityId: String,
        val constructionName: String,
        val currencyName: String,
        val queueIndex: Int?,
    ) : PendingAuthoritativeCommand

    data class BuyCityTile(
        override val commandId: String,
        override val expectedRevision: Long,
        override val observedStateHash: String,
        val cityId: String,
        val x: Int,
        val y: Int,
    ) : PendingAuthoritativeCommand

    data class SetResearchPath(
        override val commandId: String,
        override val expectedRevision: Long,
        override val observedStateHash: String,
        val technologyName: String,
    ) : PendingAuthoritativeCommand

    data class AdoptPolicy(
        override val commandId: String,
        override val expectedRevision: Long,
        override val observedStateHash: String,
        val policyName: String,
    ) : PendingAuthoritativeCommand

    data class ChooseFreeTechnology(
        override val commandId: String,
        override val expectedRevision: Long,
        override val observedStateHash: String,
        val technologyName: String,
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

    suspend fun queueConstruction(cityId: String, constructionName: String) = mutex.withLock {
        val current = requireSynchronized()
        val city = current.projection.ownCities.singleOrNull { it.id == cityId }
            ?: error("City is absent from the current player projection")
        require(constructionName in city.availableConstructions) {
            "Construction is absent from the current player projection"
        }
        submitLocked(PendingAuthoritativeCommand.QueueConstruction(
            commandId = commandIdFactory(),
            expectedRevision = current.committedRevision,
            observedStateHash = current.canonicalStateHash,
            cityId = cityId,
            constructionName = constructionName,
        ), current)
    }

    suspend fun queueConstructionAtTile(
        cityId: String,
        constructionName: String,
        x: Int,
        y: Int,
    ) = mutex.withLock {
        val current = requireSynchronized()
        val city = current.projection.ownCities.singleOrNull { it.id == cityId }
            ?: error("City is absent from the current player projection")
        require(constructionName in city.availableConstructions) {
            "Construction is absent from the current player projection"
        }
        require(current.projection.exploredTiles.any { it.x == x && it.y == y }) {
            "Tile is absent from the current player projection"
        }
        submitLocked(PendingAuthoritativeCommand.QueueConstructionAtTile(
            commandIdFactory(), current.committedRevision, current.canonicalStateHash,
            cityId, constructionName, x, y,
        ), current)
    }

    suspend fun setPerpetualConstruction(cityId: String, constructionName: String) = mutex.withLock {
        val current = requireSynchronized()
        val city = current.projection.ownCities.singleOrNull { it.id == cityId }
            ?: error("City is absent from the current player projection")
        require(constructionName in city.availableConstructions) {
            "Perpetual construction is absent from the current player projection"
        }
        submitLocked(PendingAuthoritativeCommand.SetPerpetualConstruction(
            commandIdFactory(), current.committedRevision, current.canonicalStateHash,
            cityId, constructionName,
        ), current)
    }

    suspend fun removeConstruction(cityId: String, queueIndex: Int, expectedConstructionName: String) = mutex.withLock {
        val current = requireSynchronized()
        requireProjectedQueueEntry(current, cityId, queueIndex, expectedConstructionName)
        submitLocked(PendingAuthoritativeCommand.RemoveConstruction(
            commandIdFactory(), current.committedRevision, current.canonicalStateHash,
            cityId, queueIndex, expectedConstructionName,
        ), current)
    }

    suspend fun moveConstruction(
        cityId: String,
        fromIndex: Int,
        toIndex: Int,
        expectedConstructionName: String,
    ) = mutex.withLock {
        val current = requireSynchronized()
        val city = requireProjectedQueueEntry(current, cityId, fromIndex, expectedConstructionName)
        require(toIndex in city.constructionQueue.indices && kotlin.math.abs(fromIndex - toIndex) == 1) {
            "Construction destination is absent from the current player projection"
        }
        submitLocked(PendingAuthoritativeCommand.MoveConstruction(
            commandIdFactory(), current.committedRevision, current.canonicalStateHash,
            cityId, fromIndex, toIndex, expectedConstructionName,
        ), current)
    }

    suspend fun purchaseConstruction(
        cityId: String,
        constructionName: String,
        currencyName: String,
        queueIndex: Int?,
    ) = mutex.withLock {
        val current = requireSynchronized()
        val city = current.projection.ownCities.singleOrNull { it.id == cityId }
            ?: error("City is absent from the current player projection")
        if (queueIndex == null) {
            require(constructionName in city.availableConstructions) {
                "Construction is absent from the current player projection"
            }
        } else {
            require(queueIndex in city.constructionQueue.indices &&
                city.constructionQueue[queueIndex] == constructionName) {
                "Construction queue entry is absent from the current player projection"
            }
        }
        require(currencyName.isNotBlank() && currencyName.length <= 32) {
            "Purchase currency is invalid"
        }
        submitLocked(PendingAuthoritativeCommand.PurchaseConstruction(
            commandIdFactory(), current.committedRevision, current.canonicalStateHash,
            cityId, constructionName, currencyName, queueIndex,
        ), current)
    }

    suspend fun buyCityTile(cityId: String, x: Int, y: Int) = mutex.withLock {
        val current = requireSynchronized()
        require(current.projection.ownCities.any { it.id == cityId }) {
            "City is absent from the current player projection"
        }
        require(current.projection.exploredTiles.any { it.x == x && it.y == y }) {
            "Tile is absent from the current player projection"
        }
        submitLocked(PendingAuthoritativeCommand.BuyCityTile(
            commandIdFactory(), current.committedRevision, current.canonicalStateHash,
            cityId, x, y,
        ), current)
    }

    private fun requireProjectedQueueEntry(
        current: ApiV3GameProjection,
        cityId: String,
        queueIndex: Int,
        expectedConstructionName: String,
    ): ProjectedCity {
        val city = current.projection.ownCities.singleOrNull { it.id == cityId }
            ?: error("City is absent from the current player projection")
        require(queueIndex in city.constructionQueue.indices &&
            city.constructionQueue[queueIndex] == expectedConstructionName) {
            "Construction queue entry is absent from the current player projection"
        }
        return city
    }

    suspend fun setResearchPath(technologyName: String) = mutex.withLock {
        val current = requireSynchronized()
        require(technologyName in current.projection.research.selectableTargets) {
            "Technology is absent from the current player projection"
        }
        submitLocked(PendingAuthoritativeCommand.SetResearchPath(
            commandId = commandIdFactory(),
            expectedRevision = current.committedRevision,
            observedStateHash = current.canonicalStateHash,
            technologyName = technologyName,
        ), current)
    }

    suspend fun adoptPolicy(policyName: String) = mutex.withLock {
        val current = requireSynchronized()
        require(policyName in current.projection.policies.selectablePolicies) {
            "Policy is absent from the current player projection"
        }
        submitLocked(PendingAuthoritativeCommand.AdoptPolicy(
            commandId = commandIdFactory(),
            expectedRevision = current.committedRevision,
            observedStateHash = current.canonicalStateHash,
            policyName = policyName,
        ), current)
    }

    suspend fun chooseFreeTechnology(technologyName: String) = mutex.withLock {
        val current = requireSynchronized()
        require(technologyName in current.projection.research.freeTechnologyChoices) {
            "Technology is absent from the current free-technology projection"
        }
        submitLocked(PendingAuthoritativeCommand.ChooseFreeTechnology(
            commandId = commandIdFactory(),
            expectedRevision = current.committedRevision,
            observedStateHash = current.canonicalStateHash,
            technologyName = technologyName,
        ), current)
    }

    suspend fun retryPending(): AuthoritativeCommandOutcome = mutex.withLock {
        val retryable = state as? AuthoritativeSyncState.Retryable
            ?: error("There is no ambiguous command to retry")
        submitLocked(retryable.pending, retryable.current)
    }

    /** Returns a refreshed projection only when this hint proves the cached
     * view may be stale. Duplicate and older hints are intentionally ignored. */
    suspend fun reconcile(notification: ApiV3RevisionNotification): ApiV3GameProjection? =
        mutex.withLock {
            val current = cachedProjection()
            when (notification.type) {
                "resync_required" -> refreshLocked(current)
                "revision_committed" -> {
                    if (notification.gameId != gameId) return@withLock null
                    val notifiedRevision = notification.committedRevision ?: return@withLock null
                    if (current != null && notifiedRevision < current.committedRevision) {
                        return@withLock null
                    }
                    if (current != null
                        && notifiedRevision == current.committedRevision
                        && notification.canonicalStateHash == current.canonicalStateHash
                    ) return@withLock null
                    refreshLocked(current)
                }
                else -> null
            }
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
                is PendingAuthoritativeCommand.QueueConstruction -> transport.queueConstruction(
                    gameId,
                    ApiV3QueueConstructionRequest(
                        pending.commandId,
                        pending.expectedRevision,
                        pending.observedStateHash,
                        pending.cityId,
                        pending.constructionName,
                    ),
                )
                is PendingAuthoritativeCommand.QueueConstructionAtTile -> transport.queueConstructionAtTile(
                    gameId,
                    ApiV3QueueConstructionAtTileRequest(
                        pending.commandId, pending.expectedRevision, pending.observedStateHash,
                        pending.cityId, pending.constructionName, pending.x, pending.y,
                    ),
                )
                is PendingAuthoritativeCommand.SetPerpetualConstruction -> transport.setPerpetualConstruction(
                    gameId,
                    ApiV3SetPerpetualConstructionRequest(
                        pending.commandId,
                        pending.expectedRevision,
                        pending.observedStateHash,
                        pending.cityId,
                        pending.constructionName,
                    ),
                )
                is PendingAuthoritativeCommand.RemoveConstruction -> transport.removeConstruction(
                    gameId,
                    ApiV3RemoveConstructionRequest(
                        pending.commandId, pending.expectedRevision, pending.observedStateHash,
                        pending.cityId, pending.queueIndex, pending.expectedConstructionName,
                    ),
                )
                is PendingAuthoritativeCommand.MoveConstruction -> transport.moveConstruction(
                    gameId,
                    ApiV3MoveConstructionRequest(
                        pending.commandId, pending.expectedRevision, pending.observedStateHash,
                        pending.cityId, pending.fromIndex, pending.toIndex,
                        pending.expectedConstructionName,
                    ),
                )
                is PendingAuthoritativeCommand.PurchaseConstruction -> transport.purchaseConstruction(
                    gameId,
                    ApiV3PurchaseConstructionRequest(
                        pending.commandId, pending.expectedRevision, pending.observedStateHash,
                        pending.cityId, pending.constructionName, pending.currencyName,
                        pending.queueIndex,
                    ),
                )
                is PendingAuthoritativeCommand.BuyCityTile -> transport.buyCityTile(
                    gameId,
                    ApiV3BuyCityTileRequest(
                        pending.commandId, pending.expectedRevision, pending.observedStateHash,
                        pending.cityId, pending.x, pending.y,
                    ),
                )
                is PendingAuthoritativeCommand.SetResearchPath -> transport.setResearchPath(
                    gameId,
                    ApiV3SetResearchPathRequest(
                        pending.commandId,
                        pending.expectedRevision,
                        pending.observedStateHash,
                        pending.technologyName,
                    ),
                )
                is PendingAuthoritativeCommand.AdoptPolicy -> transport.adoptPolicy(
                    gameId,
                    ApiV3AdoptPolicyRequest(
                        pending.commandId,
                        pending.expectedRevision,
                        pending.observedStateHash,
                        pending.policyName,
                    ),
                )
                is PendingAuthoritativeCommand.ChooseFreeTechnology -> transport.chooseFreeTechnology(
                    gameId,
                    ApiV3ChooseFreeTechnologyRequest(
                        pending.commandId,
                        pending.expectedRevision,
                        pending.observedStateHash,
                        pending.technologyName,
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
        check(refreshed.projectionVersion == PlayerProjection.CURRENT_PROJECTION_VERSION) {
            "Server returned an incompatible projection version"
        }
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
