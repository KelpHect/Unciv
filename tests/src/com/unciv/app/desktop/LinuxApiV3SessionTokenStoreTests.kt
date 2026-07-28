package com.unciv.app.desktop

import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test

class LinuxApiV3SessionTokenStoreTests {
    @Test
    fun secretServiceRoundTripAndClear() = runBlocking {
        assumeTrue(System.getProperty("os.name").startsWith("Linux", ignoreCase = true))
        val store = LinuxApiV3SessionTokenStore.create(scope(UUID.randomUUID().toString()))
        assumeNotNull(store)
        checkNotNull(store)
        val token = "opaque-api-v3-session-token"

        try {
            store.clear()
            store.save(token)
            assertEquals(token, store.load())
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
