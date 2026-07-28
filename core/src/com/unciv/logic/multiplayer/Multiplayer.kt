package com.unciv.logic.multiplayer

import com.unciv.logic.multiplayer.authoritative.ApiV3SessionTokenStore
import com.unciv.logic.multiplayer.authoritative.AuthoritativeSessionLifecycle
import com.unciv.logic.multiplayer.authoritative.AuthoritativeSessionStatus

/**
 * Separates the authoritative API-v3 lifecycle from explicit legacy
 * whole-save multiplayer.
 *
 * API-v3 callers use [authoritativeSession]. Existing API-v1/v2 callers must
 * opt into [legacy], making whole-save access visible at every call site.
 */
class Multiplayer {
    val legacy = LegacyMultiplayer()
    private val authoritative = AuthoritativeSessionLifecycle()

    val authoritativeSession get() = authoritative.session
    val authoritativeStatus get() = authoritative.status

    suspend fun restoreConfiguredAuthoritativeSession(
        baseUrl: String,
        tokenStore: (String) -> ApiV3SessionTokenStore?,
    ): AuthoritativeSessionStatus =
        authoritative.restoreConfiguredServer(baseUrl, tokenStore)

    suspend fun loginAuthoritative(username: String, password: String) =
        authoritative.login(username, password)

    suspend fun registerAuthoritative(username: String, password: String) =
        authoritative.registerAndLogin(username, password)

    suspend fun logoutAuthoritative() = authoritative.logout()

    suspend fun recoverAuthoritative(
        username: String,
        recoveryCode: String,
        newPassword: String,
    ) = authoritative.recoverAccount(username, recoveryCode, newPassword)

    suspend fun logoutAllAuthoritative() = authoritative.logoutAll()

    suspend fun changeAuthoritativePassword(currentPassword: String, newPassword: String) =
        authoritative.changePassword(currentPassword, newPassword)

    suspend fun replaceAuthoritativeRecoveryCodes(password: String) =
        authoritative.replaceRecoveryCodes(password)

    suspend fun disableAuthoritativeAccount(password: String) =
        authoritative.disableAccount(password)

    suspend fun deleteAuthoritativeAccount(password: String) =
        authoritative.deleteAccount(password)

    fun clearAuthoritativeSession() {
        authoritative.close()
    }

    fun close() {
        clearAuthoritativeSession()
        legacy.multiplayerGameUpdater.cancel()
    }
}
