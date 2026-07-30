package com.unciv.logic.multiplayer.authoritative

import com.unciv.ui.screens.multiplayerscreens.authoritativeLobbyErrorMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AuthoritativeLobbyErrorMessageTests {
    @Test
    fun stableLobbyErrorsRemainActionableInsteadOfBecomingInvalidResponses() {
        val cases = mapOf(
            "stale_revision" to "The lobby changed on the server. Refreshing the latest settings.",
            "invalid_lobby_password" to "The lobby password is incorrect.",
            "civilization_taken" to
                "That faction was claimed by another player. Choose another faction.",
            "invalid_game_setup" to
                "One or more game or world settings are outside server limits.",
        )

        for ((code, expected) in cases) {
            val message = authoritativeLobbyErrorMessage(
                ApiV3Exception(409, ApiV3ErrorResponse(code)),
                "fallback",
            )
            assertEquals(expected, message)
            assertFalse(message.contains("invalid server response", ignoreCase = true))
        }
    }
}
