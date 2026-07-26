package com.unciv.logic.multiplayer.authoritative

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthoritativeResignationCoordinatorTests {
    @Test
    fun unopenedGameIsOpenedBeforeTypedResignation() = runBlocking {
        var opened = false
        val calls = mutableListOf<String>()
        val expected = AuthoritativeCommandOutcome.Rejected("not_current_turn")
        val coordinator = AuthoritativeResignationCoordinator(
            isOpen = { opened },
            open = {
                calls += "open:$it"
                opened = true
            },
            resignIfOpen = {
                calls += "resign:$it"
                expected
            },
        )

        assertEquals(expected, coordinator.resign("game-a"))
        assertEquals(listOf("open:game-a", "resign:game-a"), calls)
    }

    @Test
    fun retryableOpenGameDoesNotReopenOrChangeRoute() = runBlocking {
        var openCalls = 0
        var resignCalls = 0
        val coordinator = AuthoritativeResignationCoordinator(
            isOpen = { true },
            open = { openCalls++ },
            resignIfOpen = {
                resignCalls++
                AuthoritativeCommandOutcome.RetryRequired
            },
        )

        assertEquals(AuthoritativeCommandOutcome.RetryRequired, coordinator.resign("game-a"))
        assertEquals(AuthoritativeCommandOutcome.RetryRequired, coordinator.resign("game-a"))
        assertEquals(0, openCalls)
        assertEquals(2, resignCalls)
    }

    @Test
    fun disappearingGameFailsClosed() = runBlocking {
        val coordinator = AuthoritativeResignationCoordinator(
            isOpen = { true },
            open = {},
            resignIfOpen = { null },
        )

        val error = assertThrows<IllegalArgumentException> {
            coordinator.resign("game-a")
        }

        assertTrue(error.message.orEmpty().contains("did not remain open"))
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
