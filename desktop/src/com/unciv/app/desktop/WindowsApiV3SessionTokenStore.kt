package com.unciv.app.desktop

import com.sun.jna.platform.win32.Crypt32Util
import com.unciv.logic.multiplayer.authoritative.ApiV3SessionTokenStore
import com.unciv.logic.multiplayer.authoritative.MAX_API_V3_SESSION_TOKEN_BYTES
import com.unciv.logic.multiplayer.authoritative.requireValidApiV3SessionToken
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Persists only a Windows-DPAPI ciphertext bound to the current OS user.
 */
class WindowsApiV3SessionTokenStore(
    private val tokenFile: Path,
) : ApiV3SessionTokenStore {
    private val mutex = Mutex()

    override suspend fun load(): String? = mutex.withLock {
        if (!Files.isRegularFile(tokenFile)) return@withLock null
        try {
            require(Files.size(tokenFile) <= MAX_ENCRYPTED_BYTES) {
                "Encrypted API-v3 session token is too large"
            }
            val encrypted = Files.readAllBytes(tokenFile)
            val token = Crypt32Util.cryptUnprotectData(encrypted)
                .toString(Charsets.UTF_8)
            requireValidApiV3SessionToken(token)
            token
        } catch (_: Exception) {
            Files.deleteIfExists(tokenFile)
            null
        }
    }

    override suspend fun save(token: String) = mutex.withLock {
        requireValidApiV3SessionToken(token)
        val encrypted = Crypt32Util.cryptProtectData(token.toByteArray(Charsets.UTF_8))
        require(encrypted.size <= MAX_ENCRYPTED_BYTES) {
            "Encrypted API-v3 session token is too large"
        }
        Files.createDirectories(tokenFile.parent)
        val temporary = Files.createTempFile(tokenFile.parent, ".api-v3-token-", ".tmp")
        try {
            Files.write(temporary, encrypted)
            try {
                Files.move(
                    temporary,
                    tokenFile,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, tokenFile, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
            encrypted.fill(0)
        }
        Unit
    }

    override suspend fun clear() = mutex.withLock {
        Files.deleteIfExists(tokenFile)
        Unit
    }

    companion object {
        private const val MAX_ENCRYPTED_BYTES = MAX_API_V3_SESSION_TOKEN_BYTES + 1024
    }
}
