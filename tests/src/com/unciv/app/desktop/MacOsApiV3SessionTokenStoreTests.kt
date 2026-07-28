package com.unciv.app.desktop

import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test

class MacOsApiV3SessionTokenStoreTests {
    @Test
    fun keychainRoundTripAndClear() = runBlocking {
        assumeTrue(System.getProperty("os.name").startsWith("Mac", ignoreCase = true))
        val account = scope(UUID.randomUUID().toString())
        val token = "opaque-api-v3-session-token"
        val store = MacOsApiV3SessionTokenStore(account)

        try {
            store.clear()
            store.save(token)
            assertEquals(token, MacOsApiV3SessionTokenStore(account).load())
        } finally {
            store.clear()
        }
        assertNull(store.load())
    }

    private fun scope(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
