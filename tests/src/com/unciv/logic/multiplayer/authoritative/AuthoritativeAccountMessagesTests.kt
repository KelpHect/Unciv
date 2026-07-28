package com.unciv.logic.multiplayer.authoritative

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AuthoritativeAccountMessagesTests {
    @Test
    fun accountErrorsAreUnderstandableAndNeverEchoSecrets() {
        val secret = "recovery-secret-that-must-not-appear"
        val cases = mapOf(
            "invalid_credentials" to
                "The username, password, or recovery code was not accepted.",
            "invalid_password" to
                "Use a password with at least 12 characters.",
            "rate_limited" to
                "Too many attempts. Wait before trying this account action again.",
        )
        for ((code, expected) in cases) {
            val message = AuthoritativeAccountMessages.forException(
                ApiV3Exception(400, ApiV3ErrorResponse(code)),
            )
            assertEquals(expected, message)
            assertFalse(message.contains(secret))
        }
    }
}
