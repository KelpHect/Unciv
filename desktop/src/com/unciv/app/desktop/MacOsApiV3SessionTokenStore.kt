package com.unciv.app.desktop

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import com.unciv.logic.multiplayer.authoritative.ApiV3SessionTokenStore
import com.unciv.logic.multiplayer.authoritative.MAX_API_V3_SESSION_TOKEN_BYTES
import com.unciv.logic.multiplayer.authoritative.requireValidApiV3SessionToken
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Stores one server-scoped session token in the current user's macOS Keychain.
 *
 * Password bytes are passed directly to Security.framework and are never
 * placed in process arguments, environment variables, preferences, or files.
 */
class MacOsApiV3SessionTokenStore(
    private val account: String,
) : ApiV3SessionTokenStore {
    private val mutex = Mutex()
    private val serviceBytes = SERVICE.toByteArray(Charsets.UTF_8)
    private val accountBytes = account.toByteArray(Charsets.UTF_8)

    init {
        require(account.matches(ACCOUNT_PATTERN))
    }

    override suspend fun load(): String? = mutex.withLock {
        val found = find() ?: return@withLock null
        try {
            require(found.length in 1..MAX_API_V3_SESSION_TOKEN_BYTES)
            val bytes = found.password.getByteArray(0, found.length)
            try {
                val token = bytes.toString(Charsets.UTF_8)
                requireValidApiV3SessionToken(token)
                token
            } finally {
                bytes.fill(0)
            }
        } catch (_: Exception) {
            delete(found.item)
            null
        } finally {
            release(found)
        }
    }

    override suspend fun save(token: String) = mutex.withLock {
        requireValidApiV3SessionToken(token)
        val bytes = token.toByteArray(Charsets.UTF_8)
        try {
            val found = find()
            if (found == null) {
                val item = PointerByReference()
                checkStatus(
                    security.SecKeychainAddGenericPassword(
                        null,
                        serviceBytes.size,
                        serviceBytes,
                        accountBytes.size,
                        accountBytes,
                        bytes.size,
                        bytes,
                        item,
                    ),
                )
                item.value?.let(coreFoundation::CFRelease)
            } else {
                try {
                    checkStatus(
                        security.SecKeychainItemModifyAttributesAndData(
                            found.item,
                            null,
                            bytes.size,
                            bytes,
                        ),
                    )
                } finally {
                    release(found)
                }
            }
        } finally {
            bytes.fill(0)
        }
        Unit
    }

    override suspend fun clear() = mutex.withLock {
        val found = find() ?: return@withLock
        try {
            delete(found.item)
        } finally {
            release(found)
        }
    }

    private fun find(): FoundPassword? {
        val length = IntByReference()
        val password = PointerByReference()
        val item = PointerByReference()
        val status = security.SecKeychainFindGenericPassword(
            null,
            serviceBytes.size,
            serviceBytes,
            accountBytes.size,
            accountBytes,
            length,
            password,
            item,
        )
        if (status == ERR_SEC_ITEM_NOT_FOUND) return null
        checkStatus(status)
        val passwordPointer = checkNotNull(password.value)
        val itemPointer = checkNotNull(item.value)
        return FoundPassword(length.value, passwordPointer, itemPointer)
    }

    private fun delete(item: Pointer) {
        val status = security.SecKeychainItemDelete(item)
        if (status != ERR_SEC_ITEM_NOT_FOUND) checkStatus(status)
    }

    private fun release(found: FoundPassword) {
        security.SecKeychainItemFreeContent(null, found.password)
        coreFoundation.CFRelease(found.item)
    }

    private fun checkStatus(status: Int) {
        check(status == ERR_SEC_SUCCESS) { "macOS Keychain rejected the API-v3 credential operation" }
    }

    private data class FoundPassword(
        val length: Int,
        val password: Pointer,
        val item: Pointer,
    )

    private interface SecurityLibrary : Library {
        fun SecKeychainFindGenericPassword(
            keychainOrArray: Pointer?,
            serviceNameLength: Int,
            serviceName: ByteArray,
            accountNameLength: Int,
            accountName: ByteArray,
            passwordLength: IntByReference,
            passwordData: PointerByReference,
            itemRef: PointerByReference,
        ): Int

        fun SecKeychainAddGenericPassword(
            keychain: Pointer?,
            serviceNameLength: Int,
            serviceName: ByteArray,
            accountNameLength: Int,
            accountName: ByteArray,
            passwordLength: Int,
            passwordData: ByteArray,
            itemRef: PointerByReference,
        ): Int

        fun SecKeychainItemModifyAttributesAndData(
            itemRef: Pointer,
            attributes: Pointer?,
            dataLength: Int,
            data: ByteArray,
        ): Int

        fun SecKeychainItemDelete(itemRef: Pointer): Int
        fun SecKeychainItemFreeContent(attributes: Pointer?, data: Pointer): Int
    }

    private interface CoreFoundationLibrary : Library {
        fun CFRelease(value: Pointer)
    }

    companion object {
        private const val SERVICE = "com.unciv.api-v3-session"
        private const val ERR_SEC_SUCCESS = 0
        private const val ERR_SEC_ITEM_NOT_FOUND = -25300
        private val ACCOUNT_PATTERN = Regex("[0-9a-f]{64}")
        private val security by lazy {
            Native.load(
                "/System/Library/Frameworks/Security.framework/Security",
                SecurityLibrary::class.java,
            )
        }
        private val coreFoundation by lazy {
            Native.load(
                "/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation",
                CoreFoundationLibrary::class.java,
            )
        }
    }
}
