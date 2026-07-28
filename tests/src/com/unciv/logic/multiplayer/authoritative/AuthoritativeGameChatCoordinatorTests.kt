package com.unciv.logic.multiplayer.authoritative

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

class AuthoritativeGameChatCoordinatorTests {
    @Test
    fun exactSendRetryKeepsOneMessageId() = runBlocking {
        val sent = mutableListOf<ApiV3PostGameChatRequest>()
        var attempts = 0
        val coordinator = coordinator(
            send = { _, request ->
                sent += request
                attempts++
                if (attempts == 1) throw IOException("response lost")
            },
        )

        assertThrows<IOException> { coordinator.send(GAME_ID, " hello ") }
        coordinator.send(GAME_ID, "hello")

        assertEquals(2, sent.size)
        assertEquals(sent[0].messageId, sent[1].messageId)
        assertEquals("hello", sent[0].body)
    }

    @Test
    fun malformedPagesAndOversizedUtf8MessagesFailClosed() = runBlocking {
        val duplicate = message("first")
        val coordinator = coordinator(
            load = { _, _, _ ->
                ApiV3GameChatPage(listOf(duplicate, duplicate))
            },
        )
        assertThrows<IllegalArgumentException> { coordinator.refresh(GAME_ID) }

        val utf8Oversized = "é".repeat(501)
        assertThrows<IllegalArgumentException> { coordinator.send(GAME_ID, utf8Oversized) }
        Unit
    }

    @Test
    fun olderPagesUseOnlyServerIssuedUuidCursor() = runBlocking {
        val calls = mutableListOf<Triple<String, String?, Int>>()
        val coordinator = coordinator(
            load = { gameId, cursor, limit ->
                calls += Triple(gameId, cursor, limit)
                ApiV3GameChatPage(emptyList())
            },
        )

        coordinator.older(GAME_ID, MESSAGE_ID)

        assertEquals(
            listOf(Triple(GAME_ID, MESSAGE_ID, 100)),
            calls,
        )
    }

    private fun coordinator(
        load: suspend (String, String?, Int) -> ApiV3GameChatPage =
            { _, _, _ -> ApiV3GameChatPage(emptyList()) },
        send: suspend (String, ApiV3PostGameChatRequest) -> Unit = { _, _ -> },
    ) = AuthoritativeGameChatCoordinator(load, send) { MESSAGE_ID }

    private fun message(body: String) = ApiV3GameChatMessage(
        messageId = MESSAGE_ID,
        senderUsername = "sender",
        body = body,
        createdAtMillis = 1,
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

    companion object {
        const val GAME_ID = "2b15f5b4-0294-4cee-bad2-23e538e678bf"
        const val MESSAGE_ID = "8d44dfb9-b895-43a6-a911-129eaa12e37e"
    }
}
