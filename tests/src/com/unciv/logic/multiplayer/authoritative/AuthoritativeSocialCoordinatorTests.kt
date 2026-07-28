package com.unciv.logic.multiplayer.authoritative

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

class AuthoritativeSocialCoordinatorTests {
    @Test
    fun exactRequestRetryKeepsOneRequestId() = runBlocking {
        val sent = mutableListOf<ApiV3CreateFriendRequest>()
        var attempts = 0
        val coordinator = coordinator(
            send = {
                sent += it
                attempts++
                if (attempts == 1) throw IOException("response lost")
            },
        )

        assertThrows<IOException> { coordinator.request(" Friend-One ") }
        coordinator.request("friend-one")

        assertEquals(2, sent.size)
        assertEquals(sent[0].requestId, sent[1].requestId)
        assertEquals("Friend-One", sent[0].username)
        assertEquals("friend-one", sent[1].username)
    }

    @Test
    fun outgoingRequestsCannotBeAccepted() = runBlocking {
        val coordinator = coordinator()
        val outgoing = ApiV3FriendRequest(
            requestId = REQUEST_ID,
            username = "friend-one",
            direction = AuthoritativeSocialCoordinator.OUTGOING,
        )

        assertThrows<IllegalArgumentException> { coordinator.accept(outgoing) }
        Unit
    }

    @Test
    fun malformedOrDuplicateGraphEntriesFailClosed() = runBlocking {
        val duplicate = ApiV3SocialGraph(
            friends = listOf(ApiV3Friend("same"), ApiV3Friend("same")),
            requests = emptyList(),
        )
        val coordinator = coordinator(load = { duplicate })

        assertThrows<IllegalArgumentException> { coordinator.refresh() }
        Unit
    }

    private fun coordinator(
        load: suspend () -> ApiV3SocialGraph = {
            ApiV3SocialGraph(emptyList(), emptyList())
        },
        send: suspend (ApiV3CreateFriendRequest) -> Unit = {},
        accept: suspend (String) -> Unit = {},
        removeRequest: suspend (String) -> Unit = {},
        removeFriend: suspend (String) -> Unit = {},
    ) = AuthoritativeSocialCoordinator(
        load,
        send,
        accept,
        removeRequest,
        removeFriend,
    ) { REQUEST_ID }

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
        const val REQUEST_ID = "a84dfd73-a734-4c48-9a76-24f69a7f11c1"
    }
}
