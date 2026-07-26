package com.unciv.logic.multiplayer.authoritative

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.io.IOException

class AuthoritativeInvitationCoordinatorTests {
    @Test
    fun exactAcceptanceRetryKeepsOneCommandId() = runBlocking {
        val invitation = invitation(revision = 7)
        val commandIds = mutableListOf<String>()
        var attempts = 0
        val coordinator = coordinator(
            invitations = { listOf(invitation) },
            accept = { request, commandId ->
                commandIds += commandId
                attempts++
                if (attempts == 1) throw IOException("response lost")
                accepted(request, commandId)
            },
        )

        assertThrows<IOException> { coordinator.accept(invitation) }
        coordinator.accept(invitation)

        assertEquals(2, commandIds.size)
        assertEquals(commandIds[0], commandIds[1])
    }

    @Test
    fun refreshedStaleInvitationRotatesCommandIdWithRevisionMeaning() = runBlocking {
        var current = invitation(revision = 7)
        val commandIds = mutableListOf<String>()
        val coordinator = coordinator(
            invitations = { listOf(current) },
            accept = { request, commandId ->
                commandIds += commandId
                if (request.committedRevision == 7L) throw ApiV3Exception(
                    409,
                    ApiV3ErrorResponse("stale_revision", 8),
                )
                accepted(request, commandId)
            },
        )

        coordinator.refresh()
        assertThrows<ApiV3Exception> { coordinator.accept(current) }
        current = invitation(revision = 8)
        val refreshed = coordinator.refresh().single()
        coordinator.accept(refreshed)

        assertEquals(2, commandIds.size)
        assertNotEquals(commandIds[0], commandIds[1])
    }

    @Test
    fun ownerInviteRetryKeepsOperationIdForExactUsername() = runBlocking {
        val calls = mutableListOf<Triple<String, String, String>>()
        var attempts = 0
        val coordinator = coordinator(
            send = { gameId, username, operationId ->
                calls += Triple(gameId, username, operationId)
                attempts++
                if (attempts == 1) throw IOException("response lost")
            },
        )

        assertThrows<IOException> { coordinator.invite("game-a", " Player-One ") }
        coordinator.invite("game-a", " Player-One ")

        assertEquals(2, calls.size)
        assertEquals("Player-One", calls[0].second)
        assertEquals(calls[0].third, calls[1].third)
    }

    @Test
    fun invitationInboxRejectsDuplicateGames() = runBlocking {
        val coordinator = coordinator(
            invitations = {
                listOf(
                    invitation(revision = 7),
                    invitation(revision = 8).copy(invitationId = "invitation-2"),
                )
            },
        )

        assertThrows<IllegalArgumentException> { coordinator.refresh() }
        Unit
    }

    private fun coordinator(
        invitations: suspend () -> List<ApiV3PlayerInvitation> = { emptyList() },
        accept: suspend (ApiV3PlayerInvitation, String) -> ApiV3CommandAccepted =
            { invitation, commandId -> accepted(invitation, commandId) },
        send: suspend (String, String, String) -> Unit = { _, _, _ -> },
    ): AuthoritativeInvitationCoordinator {
        var id = 0
        return AuthoritativeInvitationCoordinator(invitations, accept, send) {
            "operation-${++id}"
        }
    }

    private fun invitation(revision: Long) = ApiV3PlayerInvitation(
        gameId = "game-a",
        invitationId = "invitation-1",
        invitedBy = "owner",
        committedRevision = revision,
        canonicalStateHash = "hash-$revision",
    )

    private fun accepted(
        invitation: ApiV3PlayerInvitation,
        commandId: String,
    ) = ApiV3CommandAccepted(
        gameId = invitation.gameId,
        commandId = commandId,
        previousRevision = invitation.committedRevision,
        committedRevision = invitation.committedRevision + 1,
        canonicalStateHash = "accepted-hash",
    )

    private suspend inline fun <reified T : Throwable> assertThrows(
        crossinline block: suspend () -> Unit,
    ): T {
        try {
            block()
        } catch (throwable: Throwable) {
            if (throwable is T) return throwable
            throw AssertionError("Expected ${T::class.simpleName}, got $throwable", throwable)
        }
        throw AssertionError("Expected ${T::class.simpleName}")
    }
}
