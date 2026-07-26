package com.unciv.app.desktop

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WindowsApiV3SessionTokenStoreTests {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun dpapiRoundTripNeverPersistsPlaintextAndClearsCorruption() = runBlocking {
        assumeTrue(System.getProperty("os.name").startsWith("Windows", ignoreCase = true))
        val tokenFile = temporaryFolder.root.toPath().resolve("session.dpapi")
        val store = WindowsApiV3SessionTokenStore(tokenFile)
        val token = "opaque-session-token-that-must-not-appear-on-disk"

        store.save(token)

        assertEquals(token, WindowsApiV3SessionTokenStore(tokenFile).load())
        assertFalse(Files.readAllBytes(tokenFile).toString(Charsets.UTF_8).contains(token))

        Files.write(tokenFile, byteArrayOf(1, 2, 3, 4))
        assertNull(store.load())
        assertFalse(Files.exists(tokenFile))

        store.save(token)
        store.clear()
        assertFalse(Files.exists(tokenFile))
    }
}
