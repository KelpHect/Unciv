package com.unciv.logic.multiplayer

import com.unciv.logic.multiplayer.authoritative.ApiV3Client
import com.unciv.logic.multiplayer.authoritative.ApiV3SessionTokenStore
import com.unciv.logic.multiplayer.authoritative.AuthoritativeMultiplayerSession

/**
 * Separates the authoritative API-v3 lifecycle from explicit legacy
 * whole-save multiplayer.
 *
 * API-v3 callers use [authoritativeSession]. Existing API-v1/v2 callers must
 * opt into [legacy], making whole-save access visible at every call site.
 */
class Multiplayer {
    val legacy = LegacyMultiplayer()

    var authoritativeSession: AuthoritativeMultiplayerSession? = null
        private set

    fun installAuthoritativeSession(
        baseUrl: String,
        tokenStore: ApiV3SessionTokenStore,
    ): AuthoritativeMultiplayerSession {
        authoritativeSession?.close()
        return AuthoritativeMultiplayerSession.create(
            ApiV3Client(baseUrl, tokenStore),
            closeTransport = true,
        ).also { authoritativeSession = it }
    }

    fun clearAuthoritativeSession() {
        authoritativeSession?.close()
        authoritativeSession = null
    }

    fun close() {
        clearAuthoritativeSession()
        legacy.multiplayerGameUpdater.cancel()
    }
}
