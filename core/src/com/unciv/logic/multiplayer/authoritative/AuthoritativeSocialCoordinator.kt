package com.unciv.logic.multiplayer.authoritative

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale
import java.util.UUID

/**
 * Validates the bounded account social graph and preserves request IDs across
 * transport retries. Social data is intentionally separate from canonical
 * game revisions and never enters the gameplay command bus.
 */
class AuthoritativeSocialCoordinator(
    private val loadGraph: suspend () -> ApiV3SocialGraph,
    private val sendRequest: suspend (ApiV3CreateFriendRequest) -> Unit,
    private val acceptRequest: suspend (String) -> Unit,
    private val removeRequest: suspend (String) -> Unit,
    private val deleteFriend: suspend (String) -> Unit,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    constructor(transport: ApiV3Transport) : this(
        transport::socialGraph,
        transport::requestFriend,
        transport::acceptFriendRequest,
        transport::removeFriendRequest,
        transport::removeFriend,
    )

    private val pendingIds = mutableMapOf<String, String>()
    private val pendingIdsMutex = Mutex()

    suspend fun refresh(): ApiV3SocialGraph {
        val graph = loadGraph()
        require(graph.friends.size <= MAXIMUM_FRIENDS) {
            "Authoritative friend list exceeded $MAXIMUM_FRIENDS entries"
        }
        require(graph.requests.size <= MAXIMUM_REQUESTS) {
            "Authoritative friend request list exceeded $MAXIMUM_REQUESTS entries"
        }
        require(graph.friends.map { it.username }.toSet().size == graph.friends.size) {
            "Authoritative friend list repeated a username"
        }
        require(graph.requests.map { it.requestId }.toSet().size == graph.requests.size) {
            "Authoritative friend request list repeated a request ID"
        }
        graph.friends.forEach { requireValidUsername(it.username) }
        graph.requests.forEach {
            requireUuid(it.requestId, "Friend request ID")
            requireValidUsername(it.username)
            require(it.direction == INCOMING || it.direction == OUTGOING) {
                "Authoritative friend request direction is invalid"
            }
        }
        return graph
    }

    suspend fun request(username: String) {
        val normalizedMeaning = requireValidUsername(username)
        val requestId = pendingIdsMutex.withLock {
            pendingIds.getOrPut(normalizedMeaning, newId)
        }
        requireUuid(requestId, "Friend request ID")
        sendRequest(ApiV3CreateFriendRequest(requestId, username.trim()))
        pendingIdsMutex.withLock {
            pendingIds.remove(normalizedMeaning, requestId)
        }
    }

    suspend fun accept(request: ApiV3FriendRequest) {
        require(request.direction == INCOMING) {
            "Only incoming friend requests can be accepted"
        }
        requireUuid(request.requestId, "Friend request ID")
        acceptRequest(request.requestId)
    }

    suspend fun rejectOrCancel(request: ApiV3FriendRequest) {
        requireUuid(request.requestId, "Friend request ID")
        removeRequest(request.requestId)
    }

    suspend fun remove(friend: ApiV3Friend) {
        deleteFriend(requireValidUsername(friend.username))
    }

    private fun requireValidUsername(username: String): String {
        val trimmed = username.trim()
        require(trimmed.isNotEmpty()) { "Friend username must not be blank" }
        require(trimmed.length <= MAXIMUM_USERNAME_LENGTH) {
            "Friend username is too long"
        }
        require(trimmed.none(Char::isISOControl)) {
            "Friend username contains control characters"
        }
        return trimmed.lowercase(Locale.ROOT)
    }

    private fun requireUuid(value: String, label: String) {
        require(runCatching { UUID.fromString(value) }.isSuccess) {
            "$label must be a UUID"
        }
    }

    companion object {
        const val MAXIMUM_FRIENDS = 200
        const val MAXIMUM_REQUESTS = 200
        const val MAXIMUM_USERNAME_LENGTH = 64
        const val INCOMING = "incoming"
        const val OUTGOING = "outgoing"
    }
}
