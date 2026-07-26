package com.unciv.logic.multiplayer.authoritative

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * Retry-safe owner administration for one account session.
 *
 * Kick remains a revisioned gameplay command and therefore delegates to the
 * command bus retry state. The remaining lifecycle operations use durable
 * operation IDs bound to one exact meaning.
 */
class AuthoritativeAdministrationCoordinator(
    private val kickMember: suspend (String, String) -> AuthoritativeCommandOutcome?,
    private val forceResignPlayer: suspend (String) -> AuthoritativeCommandOutcome?,
    private val transferOwnership: suspend (String, String, String) -> Unit,
    private val closeGame: suspend (String, String) -> Unit,
    private val archiveGame: suspend (String, String) -> Unit,
    private val newOperationId: () -> String = { UUID.randomUUID().toString() },
) {
    constructor(session: AuthoritativeMultiplayerSession) : this(
        { gameId, username ->
            if (!session.isGameOpen(gameId)) session.openGame(gameId)
            session.kickMemberIfOpen(gameId, username)
        },
        { gameId ->
            if (!session.isGameOpen(gameId)) session.openGame(gameId)
            session.forceResignIfOpen(gameId)
        },
        session::transferOwnership,
        session::closeAuthoritativeGame,
        session::archiveAuthoritativeGame,
    )

    private val operationIds = mutableMapOf<AdministrationMeaning, String>()
    private val operationMutex = Mutex()

    suspend fun kick(gameId: String, username: String): AuthoritativeCommandOutcome {
        validateGameId(gameId)
        val target = validateUsername(username)
        return requireNotNull(kickMember(gameId, target)) {
            "Open the authoritative projection before kicking a member"
        }
    }

    suspend fun transfer(gameId: String, username: String) {
        validateGameId(gameId)
        val target = validateUsername(username)
        perform(AdministrationMeaning(gameId, Operation.Transfer, target)) {
            transferOwnership(gameId, target, it)
        }
    }

    suspend fun forceResign(gameId: String): AuthoritativeCommandOutcome {
        validateGameId(gameId)
        return requireNotNull(forceResignPlayer(gameId)) {
            "Open the authoritative projection before force-resigning"
        }
    }

    suspend fun close(gameId: String) {
        validateGameId(gameId)
        perform(AdministrationMeaning(gameId, Operation.Close)) {
            closeGame(gameId, it)
        }
    }

    suspend fun archive(gameId: String) {
        validateGameId(gameId)
        perform(AdministrationMeaning(gameId, Operation.Archive)) {
            archiveGame(gameId, it)
        }
    }

    private suspend fun perform(
        meaning: AdministrationMeaning,
        operation: suspend (String) -> Unit,
    ) {
        val operationId = operationMutex.withLock {
            operationIds.getOrPut(meaning, newOperationId)
        }
        operation(operationId)
        operationMutex.withLock {
            operationIds.remove(meaning, operationId)
        }
    }

    private fun validateGameId(gameId: String) {
        require(gameId.isNotBlank()) { "Authoritative game ID must not be blank" }
    }

    private fun validateUsername(username: String): String {
        val target = username.trim()
        require(target.isNotEmpty()) { "Target username must not be blank" }
        require(target.length <= MAXIMUM_USERNAME_LENGTH) { "Target username is too long" }
        return target
    }

    private data class AdministrationMeaning(
        val gameId: String,
        val operation: Operation,
        val username: String? = null,
    )

    private enum class Operation {
        Transfer,
        Close,
        Archive,
    }

    companion object {
        const val MAXIMUM_USERNAME_LENGTH = 64
    }
}
