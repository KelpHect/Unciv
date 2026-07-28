package com.unciv.app

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.security.KeyPairGeneratorSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.annotation.RequiresApi
import com.unciv.logic.multiplayer.authoritative.ApiV3SessionTokenStore
import com.unciv.logic.multiplayer.authoritative.MAX_API_V3_SESSION_TOKEN_BYTES
import com.unciv.logic.multiplayer.authoritative.apiV3CredentialScope
import com.unciv.logic.multiplayer.authoritative.requireValidApiV3SessionToken
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Calendar
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.security.auth.x500.X500Principal
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Stores ciphertext in app-private preferences and keeps encryption keys
 * non-exportable in Android Keystore.
 *
 * API 23+ uses a Keystore AES-GCM key directly. API 21-22 wrap a random AES
 * key with a Keystore RSA key pair so supported older devices never persist
 * the session token in plaintext.
 */
@SuppressLint("ApplySharedPref", "UseKtx")
class AndroidApiV3SessionTokenStore(
    context: Context,
    serverBaseUrl: String,
) : ApiV3SessionTokenStore {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val scope = apiV3CredentialScope(serverBaseUrl)
    private val mutex = Mutex()

    override suspend fun load(): String? = mutex.withLock {
        val encrypted = preferences.getString(scoped(CIPHERTEXT), null) ?: return@withLock null
        val iv = preferences.getString(scoped(IV), null) ?: return@withLock clearCorrupt()
        try {
            val encryptedBytes = decode(encrypted, MAX_ENCRYPTED_BYTES)
            val ivBytes = decode(iv, MAX_GCM_IV_BYTES)
            val tokenBytes = try {
                decrypt(encryptedBytes, ivBytes)
            } finally {
                encryptedBytes.fill(0)
                ivBytes.fill(0)
            }
            require(tokenBytes.size <= MAX_API_V3_SESSION_TOKEN_BYTES)
            val token = tokenBytes.toString(Charsets.UTF_8)
            tokenBytes.fill(0)
            requireValidApiV3SessionToken(token)
            token
        } catch (exception: Exception) {
            Log.w(TAG, "Discarding unreadable encrypted API-v3 session credential", exception)
            clearCorrupt()
        }
    }

    override suspend fun save(token: String) = mutex.withLock {
        requireValidApiV3SessionToken(token)
        val cipher = Cipher.getInstance(AES_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey())
        val tokenBytes = token.toByteArray(Charsets.UTF_8)
        try {
            val encrypted = cipher.doFinal(tokenBytes)
            try {
                check(preferences.edit()
                    .putString(scoped(CIPHERTEXT), encode(encrypted))
                    .putString(scoped(IV), encode(cipher.iv))
                    .commit()) {
                    "Could not persist the encrypted API-v3 session token"
                }
            } finally {
                encrypted.fill(0)
            }
        } finally {
            tokenBytes.fill(0)
        }
    }

    override suspend fun clear() = mutex.withLock {
        clearCorrupt()
        Unit
    }

    private fun decrypt(encrypted: ByteArray, iv: ByteArray): ByteArray {
        require(encrypted.size <= MAX_ENCRYPTED_BYTES)
        require(iv.size in MIN_GCM_IV_BYTES..MAX_GCM_IV_BYTES)
        val cipher = Cipher.getInstance(AES_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, encryptionKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(encrypted)
    }

    private fun encryptionKey(): SecretKey =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) modernAesKey()
        else legacyWrappedAesKey()

    @RequiresApi(Build.VERSION_CODES.M)
    private fun modernAesKey(): SecretKey {
        val keyStore = androidKeyStore()
        (keyStore.getKey(scoped(AES_ALIAS), null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEY_STORE,
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                scoped(AES_ALIAS),
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    @Suppress("DEPRECATION")
    private fun legacyWrappedAesKey(): SecretKey {
        val keyStore = androidKeyStore()
        if (!keyStore.containsAlias(scoped(RSA_ALIAS))) {
            val start = Calendar.getInstance()
            val end = Calendar.getInstance().apply { add(Calendar.YEAR, 30) }
            val spec = KeyPairGeneratorSpec.Builder(appContext)
                .setAlias(scoped(RSA_ALIAS))
                .setSubject(X500Principal("CN=Unciv API v3 session"))
                .setSerialNumber(BigInteger.ONE)
                .setStartDate(start.time)
                .setEndDate(end.time)
                .build()
            KeyPairGenerator.getInstance("RSA", ANDROID_KEY_STORE).apply {
                initialize(spec)
                generateKeyPair()
            }
        }

        val wrapped = preferences.getString(scoped(WRAPPED_AES_KEY), null)
        if (wrapped != null) {
            val cipher = Cipher.getInstance(RSA_TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, keyStore.getKey(scoped(RSA_ALIAS), null))
            val encryptedKey = decode(wrapped, MAX_WRAPPED_KEY_BYTES)
            val raw = try {
                cipher.doFinal(encryptedKey)
            } finally {
                encryptedKey.fill(0)
            }
            require(raw.size == AES_KEY_BYTES)
            return SecretKeySpec(raw, AES_ALGORITHM).also { raw.fill(0) }
        }

        val raw = ByteArray(AES_KEY_BYTES).also(SecureRandom()::nextBytes)
        val key = SecretKeySpec(raw, AES_ALGORITHM)
        val cipher = Cipher.getInstance(RSA_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keyStore.getCertificate(scoped(RSA_ALIAS)).publicKey)
        val encryptedKey = cipher.doFinal(raw)
        val encoded = encode(encryptedKey)
        encryptedKey.fill(0)
        raw.fill(0)
        check(preferences.edit().putString(scoped(WRAPPED_AES_KEY), encoded).commit()) {
            "Could not persist the wrapped API-v3 encryption key"
        }
        return key
    }

    private fun androidKeyStore() = KeyStore.getInstance(ANDROID_KEY_STORE).apply {
        load(null)
    }

    private fun clearCorrupt(): String? {
        preferences.edit().remove(scoped(CIPHERTEXT)).remove(scoped(IV)).commit()
        return null
    }

    private fun scoped(name: String) = "$name-$scope"
    private fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun decode(value: String, maxBytes: Int): ByteArray {
        require(value.length <= ((maxBytes + 2) / 3) * 4)
        return Base64.decode(value, Base64.NO_WRAP).also {
            require(it.size <= maxBytes)
        }
    }

    companion object {
        private const val PREFERENCES = "api-v3-credentials"
        private const val TAG = "ApiV3CredentialStore"
        private const val CIPHERTEXT = "session-token"
        private const val IV = "session-token-iv"
        private const val WRAPPED_AES_KEY = "wrapped-aes-key"
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val AES_ALGORITHM = "AES"
        private const val AES_ALIAS = "unciv-api-v3-session-aes"
        private const val RSA_ALIAS = "unciv-api-v3-session-rsa"
        private const val AES_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val RSA_TRANSFORMATION = "RSA/ECB/PKCS1Padding"
        private const val AES_KEY_BYTES = 32
        private const val MIN_GCM_IV_BYTES = 12
        private const val MAX_GCM_IV_BYTES = 32
        private const val GCM_TAG_BITS = 128
        private const val MAX_ENCRYPTED_BYTES = MAX_API_V3_SESSION_TOKEN_BYTES + 64
        private const val MAX_WRAPPED_KEY_BYTES = 1024
    }
}
