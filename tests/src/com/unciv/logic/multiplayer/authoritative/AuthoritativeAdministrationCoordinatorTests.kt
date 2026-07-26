package com.unciv.logic.multiplayer.authoritative

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class AuthoritativeAdministrationCoordinatorTests {
    @Test
    fun exactTransferRetryKeepsOperationId() = runBlocking {
        val calls = mutableListOf<Triple<String, String, String>>()
        var attempts = 0
        val coordinator = coordinator(
            transfer = { gameId, username, operationId ->
                calls += Triple(gameId, username, operationId)
                attempts++
                if (attempts == 1) throw IOException("response lost")
            },
        )

        assertThrows<IOException> { coordinator.transfer("game-a", " Successor ") }
        coordinator.transfer("game-a", " Successor ")

        assertEquals(2, calls.size)
        assertEquals("Successor", calls[0].second)
        assertEquals(calls[0].third, calls[1].third)
    }

    @Test
    fun changedTransferTargetUsesDifferentOperationId() = runBlocking {
        val calls = mutableListOf<Triple<String, String, String>>()
        val coordinator = coordinator(
            transfer = { gameId, username, operationId ->
                calls += Triple(gameId, username, operationId)
                throw IOException("response lost")
            },
        )

        assertThrows<IOException> { coordinator.transfer("game-a", "first") }
        assertThrows<IOException> { coordinator.transfer("game-a", "second") }

        assertNotEquals(calls[0].third, calls[1].third)
    }

    @Test
    fun exactSpectatorRevocationRetryKeepsOperationIdAndChangedTargetDoesNot() = runBlocking {
        val calls = mutableListOf<Triple<String, String, String>>()
        val coordinator = coordinator(
            revokeSpectator = { gameId, username, operationId ->
                calls += Triple(gameId, username, operationId)
                throw IOException("response lost")
            },
        )

        assertThrows<IOException> { coordinator.revokeSpectator("game-a", " Viewer ") }
        assertThrows<IOException> { coordinator.revokeSpectator("game-a", " Viewer ") }
        assertThrows<IOException> { coordinator.revokeSpectator("game-a", "Other") }

        assertEquals("Viewer", calls[0].second)
        assertEquals(calls[0].third, calls[1].third)
        assertNotEquals(calls[1].third, calls[2].third)
    }

    @Test
    fun spectatorInvitationTrimsTheAccountName() = runBlocking {
        var call: Pair<String, String>? = null
        val coordinator = coordinator(addSpectator = { gameId, username ->
            call = gameId to username
        })

        coordinator.inviteSpectator("game-a", " Viewer ")

        assertEquals("game-a" to "Viewer", call)
    }

    @Test
    fun closeAndArchiveHaveDistinctRetryIdentities() = runBlocking {
        val closeIds = mutableListOf<String>()
        val archiveIds = mutableListOf<String>()
        val coordinator = coordinator(
            close = { _, operationId -> closeIds += operationId },
            archive = { _, operationId -> archiveIds += operationId },
        )

        coordinator.close("game-a")
        coordinator.archive("game-a")

        assertEquals(1, closeIds.size)
        assertEquals(1, archiveIds.size)
        assertNotEquals(closeIds.single(), archiveIds.single())
    }

    @Test
    fun kickUsesRevisionedCommandBoundaryAndTrimsTarget() = runBlocking {
        var call: Pair<String, String>? = null
        val expected = AuthoritativeCommandOutcome.Rejected("membership_changed")
        val coordinator = coordinator(
            kick = { gameId, username ->
                call = gameId to username
                expected
            },
        )

        val outcome = coordinator.kick("game-a", " Player-One ")

        assertEquals(expected, outcome)
        assertEquals("game-a" to "Player-One", call)
    }

    @Test
    fun kickFailsClosedWhenProjectionWasNotOpened() = runBlocking {
        val coordinator = coordinator(kick = { _, _ -> null })

        val error = assertThrows<IllegalArgumentException> {
            coordinator.kick("game-a", "player")
        }

        assertTrue(error.message.orEmpty().contains("Open the authoritative projection"))
    }

    @Test
    fun forceResignationUsesOnlyTheGameIdentityAndRevisionedCommandBoundary() = runBlocking {
        val calls = mutableListOf<String>()
        val expected = AuthoritativeCommandOutcome.Rejected("force_resign_too_early")
        val coordinator = coordinator(forceResign = {
            calls += it
            expected
        })

        assertEquals(expected, coordinator.forceResign("game-a"))
        assertEquals(listOf("game-a"), calls)
    }

    private fun coordinator(
        kick: suspend (String, String) -> AuthoritativeCommandOutcome? =
            { _, _ -> AuthoritativeCommandOutcome.Rejected("test") },
        forceResign: suspend (String) -> AuthoritativeCommandOutcome? =
            { AuthoritativeCommandOutcome.Rejected("test") },
        addSpectator: suspend (String, String) -> Unit = { _, _ -> },
        revokeSpectator: suspend (String, String, String) -> Unit = { _, _, _ -> },
        transfer: suspend (String, String, String) -> Unit = { _, _, _ -> },
        close: suspend (String, String) -> Unit = { _, _ -> },
        archive: suspend (String, String) -> Unit = { _, _ -> },
    ): AuthoritativeAdministrationCoordinator {
        var id = 0
        return AuthoritativeAdministrationCoordinator(
            kick, forceResign, addSpectator, revokeSpectator, transfer, close, archive,
        ) {
            "operation-${++id}"
        }
    }

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
