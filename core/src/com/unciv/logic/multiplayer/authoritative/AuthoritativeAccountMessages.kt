package com.unciv.logic.multiplayer.authoritative

object AuthoritativeAccountMessages {
    fun forException(exception: Exception): String {
        val api = exception as? ApiV3Exception
            ?: return exception.message ?: "The server account request failed."
        return when (api.error.code) {
            "invalid_credentials" ->
                "The username, password, or recovery code was not accepted."
            "invalid_password", "invalid_registration" ->
                "Use a password with at least 12 characters."
            "username_taken" ->
                "That username is already registered."
            "rate_limited" ->
                "Too many attempts. Wait before trying this account action again."
            "invalid_server_response" ->
                "The server returned an invalid account response."
            else ->
                "The server rejected this account action (${api.error.code})."
        }
    }
}
