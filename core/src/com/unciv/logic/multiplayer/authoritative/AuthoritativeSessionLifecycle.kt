package com.unciv.logic.multiplayer.authoritative

import com.unciv.logic.multiplayer.ApiVersion
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class AuthoritativeSessionStatus {
    NotStarted,
    Detecting,
    SecureStoreUnavailable,
    LoginRequired,
    Authenticated,
    Failed,
}

/**
 * Owns server detection and API-v3 session restoration without allowing an
 * unknown or failed server to fall through to whole-save multiplayer.
 */
class AuthoritativeSessionLifecycle(
    private val onTurnStarted: () -> Unit = {},
    private val detectServer: suspend (String) -> ApiVersion? = {
        ApiVersion.detect(it, suppress = false)
    },
    private val createSession: (String, ApiV3SessionTokenStore) -> AuthoritativeMultiplayerSession =
        { baseUrl, tokenStore ->
            AuthoritativeMultiplayerSession.create(
                ApiV3Client(baseUrl, tokenStore),
                closeTransport = true,
                onTurnStarted = onTurnStarted,
            )
        },
) : AutoCloseable {
    private val mutex = Mutex()

    @Volatile
    var status = AuthoritativeSessionStatus.NotStarted
        private set

    @Volatile
    var session: AuthoritativeMultiplayerSession? = null
        private set

    @Volatile
    var failureMessage: String? = null
        private set

    suspend fun restoreConfiguredServer(
        baseUrl: String,
        tokenStore: (String) -> ApiV3SessionTokenStore?,
    ): AuthoritativeSessionStatus = mutex.withLock {
        status = AuthoritativeSessionStatus.Detecting
        failureMessage = null
        val version = try {
            detectServer(baseUrl)
        } catch (exception: Exception) {
            clearSession()
            failureMessage = boundedFailure(
                "Could not reach the configured server",
                exception,
            )
            status = AuthoritativeSessionStatus.Failed
            return@withLock status
        }
        if (version == null) {
            clearSession()
            failureMessage =
                "The server does not advertise authoritative protocol " +
                    "${CommandEnvelope.CURRENT_PROTOCOL_VERSION}."
            status = AuthoritativeSessionStatus.Failed
            return@withLock status
        }
        if (version != ApiVersion.APIv3) {
            clearSession()
            failureMessage =
                "This server is not an authoritative API v3 multiplayer server."
            status = AuthoritativeSessionStatus.Failed
            return@withLock status
        }

        val store = try {
            tokenStore(baseUrl)
        } catch (_: Exception) {
            null
        }
        if (store == null) {
            clearSession()
            status = AuthoritativeSessionStatus.SecureStoreUnavailable
            return@withLock status
        }

        val restored = try {
            val replacement = createSession(baseUrl, store)
            clearSession()
            session = replacement
            replacement.restore()
        } catch (exception: Exception) {
            clearSession()
            failureMessage = boundedFailure(
                "Could not restore the authoritative account session",
                exception,
            )
            status = AuthoritativeSessionStatus.Failed
            return@withLock status
        }
        status = if (restored) {
            AuthoritativeSessionStatus.Authenticated
        } else {
            AuthoritativeSessionStatus.LoginRequired
        }
        status
    }

    suspend fun login(username: String, password: String): ApiV3Account = mutex.withLock {
        val current = session ?: error("Authoritative session is not available")
        val account = current.login(username, password)
        status = AuthoritativeSessionStatus.Authenticated
        account
    }

    suspend fun registerAndLogin(username: String, password: String): ApiV3Account =
        mutex.withLock {
            val current = session ?: error("Authoritative session is not available")
            current.register(username, password)
            val account = current.login(username, password)
            status = AuthoritativeSessionStatus.Authenticated
            account
        }

    suspend fun recoverAccount(
        username: String,
        recoveryCode: String,
        newPassword: String,
    ): ApiV3Account = mutex.withLock {
        val current = session ?: error("Authoritative session is not available")
        val account = current.recoverAccount(username, recoveryCode, newPassword)
        status = AuthoritativeSessionStatus.Authenticated
        account
    }

    suspend fun logout() = mutex.withLock {
        val current = session ?: return@withLock
        try {
            current.logout()
        } finally {
            status = AuthoritativeSessionStatus.LoginRequired
        }
    }

    suspend fun logoutAll() = mutex.withLock {
        val current = session ?: return@withLock
        current.logoutAll()
        status = AuthoritativeSessionStatus.LoginRequired
    }

    suspend fun changePassword(currentPassword: String, newPassword: String) =
        mutex.withLock {
            val current = session ?: error("Authoritative session is not available")
            current.changePassword(currentPassword, newPassword)
        }

    suspend fun replaceRecoveryCodes(password: String): ApiV3RecoveryCodes =
        mutex.withLock {
            val current = session ?: error("Authoritative session is not available")
            current.replaceRecoveryCodes(password)
        }

    suspend fun disableAccount(password: String) = mutex.withLock {
        val current = session ?: error("Authoritative session is not available")
        current.disableAccount(password)
        status = AuthoritativeSessionStatus.LoginRequired
    }

    suspend fun deleteAccount(password: String) = mutex.withLock {
        val current = session ?: error("Authoritative session is not available")
        current.deleteAccount(password)
        status = AuthoritativeSessionStatus.LoginRequired
    }

    private fun clearSession() {
        session?.close()
        session = null
    }

    private fun boundedFailure(prefix: String, exception: Exception): String {
        val detail = exception.message
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.take(160)
        return if (detail.isNullOrEmpty()) "$prefix." else "$prefix: $detail"
    }

    override fun close() {
        clearSession()
        failureMessage = null
        status = AuthoritativeSessionStatus.NotStarted
    }
}
