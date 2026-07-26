package com.unciv.logic.multiplayer.authoritative

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * Retry-safe orchestration for the account-scoped player invitation lifecycle.
 *
 * A command ID is reused only while the exact invitation revision/hash is
 * unchanged. Owner invitation operation IDs are similarly bound to one exact
 * game and username string.
 */
class AuthoritativeInvitationCoordinator(
    private val listInvitations: suspend () -> List<ApiV3PlayerInvitation>,
    private val acceptInvitation:
        suspend (ApiV3PlayerInvitation, String) -> ApiV3CommandAccepted,
    private val sendInvitation: suspend (String, String, String) -> Unit,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    constructor(session: AuthoritativeMultiplayerSession) : this(
        session::listPlayerInvitations,
        session::acceptPlayerInvitation,
        { gameId, username, operationId ->
            session.invitePlayer(gameId, username, operationId)
        },
    )

    private val acceptanceIds = mutableMapOf<String, BoundAcceptance>()
    private val ownerInvitationIds = mutableMapOf<OwnerInvitationMeaning, String>()
    private val retryStateMutex = Mutex()

    suspend fun refresh(): List<ApiV3PlayerInvitation> {
        val invitations = listInvitations()
        require(invitations.size <= MAXIMUM_INVITATIONS) {
            "Authoritative invitation inbox exceeded $MAXIMUM_INVITATIONS entries"
        }
        val invitationIds = mutableSetOf<String>()
        val gameIds = mutableSetOf<String>()
        for (invitation in invitations) {
            validate(invitation)
            require(invitationIds.add(invitation.invitationId)) {
                "Authoritative invitation inbox repeated an invitation ID"
            }
            require(gameIds.add(invitation.gameId)) {
                "Authoritative invitation inbox repeated a game"
            }
        }
        return invitations.sortedWith(compareBy(ApiV3PlayerInvitation::invitedBy, ApiV3PlayerInvitation::gameId))
    }

    suspend fun accept(invitation: ApiV3PlayerInvitation): ApiV3CommandAccepted {
        validate(invitation)
        val meaning = meaning(invitation)
        val commandId = retryStateMutex.withLock {
            acceptanceIds[invitation.invitationId]
                ?.takeIf { it.meaning == meaning }
                ?.commandId
                ?: newId().also {
                    acceptanceIds[invitation.invitationId] = BoundAcceptance(meaning, it)
                }
        }
        val accepted = acceptInvitation(invitation, commandId)
        require(accepted.gameId == invitation.gameId && accepted.commandId == commandId) {
            "Authoritative join response does not match the invitation request"
        }
        require(accepted.previousRevision == invitation.committedRevision) {
            "Authoritative join response does not extend the invited revision"
        }
        retryStateMutex.withLock {
            acceptanceIds.remove(
                invitation.invitationId,
                BoundAcceptance(meaning, commandId),
            )
        }
        return accepted
    }

    suspend fun invite(gameId: String, username: String) {
        require(gameId.isNotBlank()) { "Authoritative game ID must not be blank" }
        val trimmedUsername = username.trim()
        require(trimmedUsername.isNotEmpty()) { "Invited username must not be blank" }
        require(trimmedUsername.length <= MAXIMUM_USERNAME_LENGTH) {
            "Invited username is too long"
        }
        val meaning = OwnerInvitationMeaning(gameId, trimmedUsername)
        val operationId = retryStateMutex.withLock {
            ownerInvitationIds.getOrPut(meaning, newId)
        }
        sendInvitation(gameId, trimmedUsername, operationId)
        retryStateMutex.withLock {
            ownerInvitationIds.remove(meaning, operationId)
        }
    }

    private fun validate(invitation: ApiV3PlayerInvitation) {
        require(invitation.gameId.isNotBlank()) { "Invitation game ID must not be blank" }
        require(invitation.invitationId.isNotBlank()) {
            "Invitation ID must not be blank"
        }
        require(invitation.invitedBy.isNotBlank()) { "Invitation owner must not be blank" }
        require(invitation.committedRevision >= 0) {
            "Invitation revision must not be negative"
        }
        require(invitation.canonicalStateHash.isNotBlank()) {
            "Invitation state hash must not be blank"
        }
    }

    private fun meaning(invitation: ApiV3PlayerInvitation) = InvitationMeaning(
        invitation.gameId,
        invitation.invitationId,
        invitation.committedRevision,
        invitation.canonicalStateHash,
    )

    private data class InvitationMeaning(
        val gameId: String,
        val invitationId: String,
        val committedRevision: Long,
        val canonicalStateHash: String,
    )

    private data class BoundAcceptance(
        val meaning: InvitationMeaning,
        val commandId: String,
    )

    private data class OwnerInvitationMeaning(val gameId: String, val username: String)

    companion object {
        const val MAXIMUM_INVITATIONS = 100
        const val MAXIMUM_USERNAME_LENGTH = 64
    }
}
