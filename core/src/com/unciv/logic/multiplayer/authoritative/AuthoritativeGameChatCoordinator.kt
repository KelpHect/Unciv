package com.unciv.logic.multiplayer.authoritative

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * Bounded retry-safe client for membership-scoped game chat. Chat is an
 * account service and never changes a canonical game revision.
 */
class AuthoritativeGameChatCoordinator(
    private val loadPage: suspend (String, String?, Int) -> ApiV3GameChatPage,
    private val sendMessage: suspend (String, ApiV3PostGameChatRequest) -> Unit,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    constructor(transport: ApiV3Transport) : this(
        transport::gameChat,
        transport::postGameChat,
    )

    private val pendingIds = mutableMapOf<MessageMeaning, String>()
    private val pendingIdsMutex = Mutex()

    suspend fun refresh(gameId: String): ApiV3GameChatPage =
        validatePage(loadPage(requireUuid(gameId, "Game ID"), null, MAXIMUM_PAGE_SIZE))

    suspend fun older(gameId: String, cursor: String): ApiV3GameChatPage =
        validatePage(
            loadPage(
                requireUuid(gameId, "Game ID"),
                requireUuid(cursor, "Game chat cursor"),
                MAXIMUM_PAGE_SIZE,
            ),
        )

    suspend fun send(gameId: String, body: String) {
        val normalizedGameId = requireUuid(gameId, "Game ID")
        val normalizedBody = validateBody(body)
        val meaning = MessageMeaning(normalizedGameId, normalizedBody)
        val messageId = pendingIdsMutex.withLock {
            pendingIds.getOrPut(meaning, newId)
        }
        requireUuid(messageId, "Game chat message ID")
        sendMessage(
            normalizedGameId,
            ApiV3PostGameChatRequest(messageId, normalizedBody),
        )
        pendingIdsMutex.withLock { pendingIds.remove(meaning, messageId) }
    }

    private fun validatePage(page: ApiV3GameChatPage): ApiV3GameChatPage {
        require(page.messages.size <= MAXIMUM_PAGE_SIZE) {
            "Authoritative chat page exceeded $MAXIMUM_PAGE_SIZE messages"
        }
        require(page.messages.map { it.messageId }.toSet().size == page.messages.size) {
            "Authoritative chat page repeated a message ID"
        }
        page.messages.forEach {
            requireUuid(it.messageId, "Game chat message ID")
            require(it.senderUsername.isNotBlank()) { "Game chat sender must not be blank" }
            require(it.createdAtMillis >= 0) { "Game chat timestamp must not be negative" }
            validateBody(it.body)
        }
        page.nextCursor?.let { requireUuid(it, "Game chat cursor") }
        return page
    }

    private fun validateBody(body: String): String {
        val trimmed = body.trim()
        require(trimmed.isNotEmpty()) { "Game chat message must not be blank" }
        require(trimmed.toByteArray(Charsets.UTF_8).size <= MAXIMUM_MESSAGE_BYTES) {
            "Game chat message exceeds $MAXIMUM_MESSAGE_BYTES bytes"
        }
        require(trimmed.none { it.isISOControl() && it != '\n' && it != '\t' }) {
            "Game chat message contains unsupported control characters"
        }
        return trimmed
    }

    private fun requireUuid(value: String, label: String): String {
        require(runCatching { UUID.fromString(value) }.isSuccess) { "$label must be a UUID" }
        return value
    }

    private data class MessageMeaning(val gameId: String, val body: String)

    companion object {
        const val MAXIMUM_PAGE_SIZE = 100
        const val MAXIMUM_MESSAGE_BYTES = 1000
    }
}
