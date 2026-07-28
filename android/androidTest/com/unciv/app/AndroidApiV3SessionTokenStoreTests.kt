package com.unciv.app

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.security.KeyStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidApiV3SessionTokenStoreTests {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val server = "https://credential-test.invalid"
    private val token = "opaque-api-v3-session-token"

    @Test
    fun keystoreRoundTripNeverPersistsPlaintextAndClearRemovesCredential() = runBlocking {
        val store = AndroidApiV3SessionTokenStore(context, server)
        store.clear()

        store.save(token)

        assertEquals(token, AndroidApiV3SessionTokenStore(context, server).load())
        val preferences = context.getSharedPreferences("api-v3-credentials", Context.MODE_PRIVATE)
        assertFalse(preferences.all.values.any { it.toString().contains(token) })
        val aliases = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.aliases().toList()
        assertFalse(aliases.isEmpty())

        store.clear()
        assertNull(AndroidApiV3SessionTokenStore(context, server).load())
    }
}
