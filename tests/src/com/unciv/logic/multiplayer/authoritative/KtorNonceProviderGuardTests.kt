package com.unciv.logic.multiplayer.authoritative

import java.security.SecureRandom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KtorNonceProviderGuardTests {

    @Test
    fun pinsSha1PrngOnPreOAndroid() {
        val previous = System.getProperty(KtorNonceProviderGuard.KTOR_SECURE_RANDOM_PROVIDER_PROPERTY)
        try {
            KtorNonceProviderGuard.configureFor(23) // Android 6.0
            assertEquals(
                KtorNonceProviderGuard.ANDROID_SHA1PRNG_PROVIDER,
                System.getProperty(KtorNonceProviderGuard.KTOR_SECURE_RANDOM_PROVIDER_PROPERTY),
            )
        } finally {
            restoreProperty(previous)
        }
    }

    @Test
    fun leavesAndroidOAndNewerUntouched() {
        val previous = System.getProperty(KtorNonceProviderGuard.KTOR_SECURE_RANDOM_PROVIDER_PROPERTY)
        try {
            System.clearProperty(KtorNonceProviderGuard.KTOR_SECURE_RANDOM_PROVIDER_PROPERTY)
            KtorNonceProviderGuard.configureFor(26) // Android 8.0
            assertNull(System.getProperty(KtorNonceProviderGuard.KTOR_SECURE_RANDOM_PROVIDER_PROPERTY))
        } finally {
            restoreProperty(previous)
        }
    }

    @Test
    fun pinnedProviderResolvesHere() {
        // Android always provides SHA1PRNG (it is the historical default on
        // API 21-25). If the name resolves on this JVM too, Ktor's fatal
        // getInstanceStrong() fallback is unreachable once the pin is applied.
        assertTrue(
            runCatching { SecureRandom.getInstance(KtorNonceProviderGuard.ANDROID_SHA1PRNG_PROVIDER) }
                .isSuccess,
        )
    }

    private fun restoreProperty(previous: String?) {
        if (previous == null) {
            System.clearProperty(KtorNonceProviderGuard.KTOR_SECURE_RANDOM_PROVIDER_PROPERTY)
        } else {
            System.setProperty(KtorNonceProviderGuard.KTOR_SECURE_RANDOM_PROVIDER_PROPERTY, previous)
        }
    }
}
