package com.unciv.logic.multiplayer.authoritative

/**
 * Guards the Ktor WebSocket handshake against Android 6.0/7.x.
 *
 * Ktor's nonce generator (`io.ktor.util.NonceKt`) lazily resolves a
 * `SecureRandom` provider. When none of the JVM-oriented providers it tries
 * exists, it falls back to `SecureRandom.getInstanceStrong()`, which was only
 * added in Android API 26. On API 21-25 that call throws `NoSuchMethodError`
 * and crashes the app as soon as the V3 notification WebSocket opens (right
 * after login).
 *
 * Ktor consults the `io.ktor.random.secure.random.provider` system property
 * before its provider list. Pinning it to `SHA1PRNG` - available on every
 * Android release - keeps the standard notification path working on old
 * devices instead of degrading to polling. Devices on Android 8+ are left on
 * Ktor's defaults.
 */
object KtorNonceProviderGuard {
    const val KTOR_SECURE_RANDOM_PROVIDER_PROPERTY = "io.ktor.random.secure.random.provider"
    const val ANDROID_SHA1PRNG_PROVIDER = "SHA1PRNG"
    const val ANDROID_O_API_LEVEL = 26

    /** Pin a resolvable provider on pre-O Android; leave newer platforms untouched. */
    fun configureFor(sdkInt: Int) {
        if (sdkInt < ANDROID_O_API_LEVEL) {
            System.setProperty(KTOR_SECURE_RANDOM_PROVIDER_PROPERTY, ANDROID_SHA1PRNG_PROVIDER)
        }
    }
}
