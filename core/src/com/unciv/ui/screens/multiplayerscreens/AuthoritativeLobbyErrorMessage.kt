package com.unciv.ui.screens.multiplayerscreens

import com.unciv.logic.multiplayer.authoritative.ApiV3Exception

fun authoritativeLobbyErrorMessage(
    exception: Exception,
    fallback: String,
): String {
    val apiException = exception as? ApiV3Exception
        ?: return exception.message ?: fallback
    return when (apiException.error.code) {
        "stale_revision" -> "The lobby changed on the server. Refreshing the latest settings."
        "invalid_lobby_password" -> "The lobby password is incorrect."
        "civilization_taken" -> "That faction was claimed by another player. Choose another faction."
        "already_member" -> "This account has already joined the lobby."
        "lobby_full" -> "The lobby filled before this action reached the server."
        "invalid_lobby_configuration" ->
            "The server rejected the lobby configuration. Check the labeled limits."
        "human_slots_exceed_major_civilizations" ->
            "Human slots cannot exceed the number of major civilizations."
        "invalid_game_setup" -> "One or more game or world settings are outside server limits."
        "invalid_command" -> "That lobby action is no longer legal in the current state."
        "idempotency_conflict" ->
            "This retry no longer represents the same lobby operation. Try the action again."
        "forbidden" -> "This account is not allowed to perform that lobby action."
        "not_found" -> "The lobby no longer exists or is not visible to this account."
        "game_unavailable" -> "The lobby is temporarily unavailable while the server recovers it."
        "worker_rejected" ->
            "The authoritative game worker rejected these settings without creating a match."
        "rate_limited" -> "Too many requests reached the server. Wait briefly and retry."
        "invalid_server_response" ->
            "The server returned a response this client cannot decode. Update both client and server."
        "internal_error" -> "The server could not complete the lobby action."
        else -> "The server rejected the lobby action: ${apiException.error.code}."
    }
}
