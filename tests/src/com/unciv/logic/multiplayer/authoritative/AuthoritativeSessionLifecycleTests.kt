package com.unciv.logic.multiplayer.authoritative

import com.unciv.logic.multiplayer.ApiVersion
import java.lang.reflect.Proxy
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class AuthoritativeSessionLifecycleTests {
    @Test
    fun nonV3ServerFailsClosedWithoutLegacyFallback() = runBlocking {
        var storeRequested = false
        val lifecycle = lifecycle(ApiVersion.APIv2, restored = false)

        val status = lifecycle.restoreConfiguredServer("https://legacy.example") {
            storeRequested = true
            InMemoryApiV3SessionTokenStore()
        }

        assertEquals(AuthoritativeSessionStatus.Failed, status)
        assertNull(lifecycle.session)
        assertEquals(false, storeRequested)
        assertTrue(lifecycle.failureMessage?.contains("not an authoritative API v3") == true)
    }

    @Test
    fun authoritativeServerFailsClosedWithoutSecureStorage() = runBlocking {
        val lifecycle = lifecycle(ApiVersion.APIv3, restored = false)

        val status = lifecycle.restoreConfiguredServer("https://v3.example") { null }

        assertEquals(AuthoritativeSessionStatus.SecureStoreUnavailable, status)
        assertNull(lifecycle.session)
    }

    @Test
    fun authoritativeServerRestoresAuthenticationOrRequiresLogin() = runBlocking {
        val authenticated = lifecycle(ApiVersion.APIv3, restored = true)
        assertEquals(
            AuthoritativeSessionStatus.Authenticated,
            authenticated.restoreConfiguredServer("https://v3.example") {
                InMemoryApiV3SessionTokenStore()
            },
        )
        assertNotNull(authenticated.session)

        val loggedOut = lifecycle(ApiVersion.APIv3, restored = false)
        assertEquals(
            AuthoritativeSessionStatus.LoginRequired,
            loggedOut.restoreConfiguredServer("https://v3.example") {
                InMemoryApiV3SessionTokenStore()
            },
        )
        assertNotNull(loggedOut.session)
    }

    /**
     * A stored token is not proof of a live session. If the server has revoked
     * it the client must return to the login screen and drop the credential,
     * rather than report "Authenticated" while every request fails as
     * unauthenticated with no way for the player to sign back in.
     */
    @Test
    fun aRevokedStoredCredentialRequiresLoginInsteadOfLookingAuthenticated() = runBlocking {
        val calls = mutableListOf<String>()
        val revoked = lifecycle(
            ApiVersion.APIv3,
            restored = true,
            verifyFailure = ApiV3Exception(401, ApiV3ErrorResponse("invalid_credentials")),
            calls = calls,
        )

        val status = revoked.restoreConfiguredServer("https://v3.example") {
            InMemoryApiV3SessionTokenStore()
        }

        assertEquals(AuthoritativeSessionStatus.LoginRequired, status)
        assertTrue("the dead credential must be discarded", calls.contains("discard"))
    }

    /**
     * An unreachable server is not a revoked credential: a transport failure
     * must never silently sign the player out of a still-valid session.
     */
    @Test
    fun aTransportFailureDuringVerificationKeepsTheStoredCredential() = runBlocking {
        val calls = mutableListOf<String>()
        val offline = lifecycle(
            ApiVersion.APIv3,
            restored = true,
            verifyFailure = ApiV3Exception(503, ApiV3ErrorResponse("service_unavailable")),
            calls = calls,
        )

        val status = offline.restoreConfiguredServer("https://v3.example") {
            InMemoryApiV3SessionTokenStore()
        }

        assertEquals(AuthoritativeSessionStatus.Failed, status)
        assertFalse("a transient failure must not discard credentials", calls.contains("discard"))
    }

    @Test
    fun unknownOrFailedDetectionNeverFallsBackToLegacy() = runBlocking {
        val unknown = lifecycle(null, restored = false)
        assertEquals(
            AuthoritativeSessionStatus.Failed,
            unknown.restoreConfiguredServer("https://unknown.example") {
                InMemoryApiV3SessionTokenStore()
            },
        )
        assertTrue(unknown.failureMessage!!.contains("protocol"))

        val failed = AuthoritativeSessionLifecycle(
            detectServer = { error("offline") },
            createSession = { _, _ -> error("must not create session") },
        )
        assertEquals(
            AuthoritativeSessionStatus.Failed,
            failed.restoreConfiguredServer("https://offline.example") {
                InMemoryApiV3SessionTokenStore()
            },
        )
        assertNull(failed.session)
        assertTrue(failed.failureMessage!!.contains("offline"))
    }

    @Test
    fun capabilityDetectionTracksTheTypedCommandProtocol() {
        val current = ApiV3Capabilities(
            protocolVersion = CommandEnvelope.CURRENT_PROTOCOL_VERSION,
            projectionVersion = PlayerProjection.CURRENT_PROJECTION_VERSION,
            commands = emptyList(),
            wholeStateUpload = false,
            websocketNotifications = true,
        )

        assertTrue(current.supportsCurrentClient())
        assertFalse(
            current.copy(
                protocolVersion = CommandEnvelope.CURRENT_PROTOCOL_VERSION - 1,
            ).supportsCurrentClient(),
        )
        assertFalse(current.copy(wholeStateUpload = true).supportsCurrentClient())
        assertFalse(
            current.copy(
                projectionVersion = PlayerProjection.CURRENT_PROJECTION_VERSION - 1,
            ).supportsCurrentClient(),
        )
    }

    @Test
    fun loginRegistrationAndLogoutUpdateLifecycleState() = runBlocking {
        val calls = mutableListOf<String>()
        val lifecycle = AuthoritativeSessionLifecycle(
            detectServer = { ApiVersion.APIv3 },
            createSession = { _, _ -> fakeSession(restored = false, calls) },
        )
        lifecycle.restoreConfiguredServer("https://v3.example") {
            InMemoryApiV3SessionTokenStore()
        }

        lifecycle.login("player", "password")
        assertEquals(AuthoritativeSessionStatus.Authenticated, lifecycle.status)
        lifecycle.logout()
        assertEquals(AuthoritativeSessionStatus.LoginRequired, lifecycle.status)
        lifecycle.registerAndLogin("new-player", "password")
        assertEquals(AuthoritativeSessionStatus.Authenticated, lifecycle.status)
        assertEquals(listOf("login", "logout", "register", "login"), calls)
    }

    @Test
    fun recoveryAndLogoutAllUpdateLifecycleState() = runBlocking {
        val calls = mutableListOf<String>()
        val lifecycle = AuthoritativeSessionLifecycle(
            detectServer = { ApiVersion.APIv3 },
            createSession = { _, _ -> fakeSession(restored = false, calls) },
        )
        lifecycle.restoreConfiguredServer("https://v3.example") {
            InMemoryApiV3SessionTokenStore()
        }

        val account = lifecycle.recoverAccount("player", "recovery-code", "new-password")
        assertEquals("player", account.username)
        assertEquals(AuthoritativeSessionStatus.Authenticated, lifecycle.status)
        lifecycle.logoutAll()
        assertEquals(AuthoritativeSessionStatus.LoginRequired, lifecycle.status)
        assertEquals(listOf("recover", "logout-all"), calls)
    }

    @Test
    fun serverIdentityRequiresTlsAndScopesCredentialsByOrigin() {
        assertEquals("https://example.com/", normalizeApiV3BaseUrl("HTTPS://Example.COM"))
        assertEquals("http://127.0.0.1:8080/", normalizeApiV3BaseUrl("http://127.0.0.1:8080"))
        assertEquals("http://[::1]:8080/", normalizeApiV3BaseUrl("http://[::1]:8080"))
        assertEquals("http://203.0.113.7:3000/", normalizeApiV3BaseUrl("http://203.0.113.7:3000"))
        assertThrows(IllegalArgumentException::class.java) {
            normalizeApiV3BaseUrl("http://example.com")
        }
        assertThrows(IllegalArgumentException::class.java) {
            normalizeApiV3BaseUrl("https://user@example.com")
        }
        assertEquals(
            apiV3CredentialScope("https://example.com"),
            apiV3CredentialScope("HTTPS://EXAMPLE.COM:443/"),
        )
        assertNotEquals(
            apiV3CredentialScope("https://example.com"),
            apiV3CredentialScope("https://other.example.com"),
        )
    }

    @Test
    fun tokenStoresRejectBlankControlAndOversizedTokens() = runBlocking {
        val store = InMemoryApiV3SessionTokenStore()
        for (token in listOf(
            "",
            "token\nvalue",
            "x".repeat(MAX_API_V3_SESSION_TOKEN_BYTES + 1),
        )) {
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { store.save(token) }
            }
        }
        store.save("opaque-session-token")
        assertEquals("opaque-session-token", store.load())
        store.clear()
        assertNull(store.load())
    }

    private fun lifecycle(
        version: ApiVersion?,
        restored: Boolean,
        verifyFailure: Exception? = null,
        calls: MutableList<String> = mutableListOf(),
    ) = AuthoritativeSessionLifecycle(
        detectServer = { version },
        createSession = { _, _ -> fakeSession(restored, calls, verifyFailure) },
    )

    private fun fakeSession(
        restored: Boolean,
        calls: MutableList<String> = mutableListOf(),
        /** Non-null makes the server reject the restored credential. */
        verifyFailure: Exception? = null,
    ): AuthoritativeMultiplayerSession {
        val transport = Proxy.newProxyInstance(
            ApiV3Transport::class.java.classLoader,
            arrayOf(ApiV3Transport::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "capabilities" -> ApiV3Capabilities(
                    protocolVersion = CommandEnvelope.CURRENT_PROTOCOL_VERSION,
                    projectionVersion = PlayerProjection.CURRENT_PROJECTION_VERSION,
                    commands = emptyList(),
                    wholeStateUpload = false,
                    websocketNotifications = true,
                )
                "restoreSession" -> restored
                "verifySession" -> {
                    calls += "verify"
                    if (verifyFailure != null) throw verifyFailure
                    Unit
                }
                "discardStoredSession" -> {
                    calls += "discard"
                    Unit
                }
                "notifications" -> emptyFlow<ApiV3RevisionNotification>()
                "login" -> {
                    calls += "login"
                    ApiV3Account("account-id", "player")
                }
                "register" -> {
                    calls += "register"
                    ApiV3Account("account-id", "player")
                }
                "logout" -> {
                    calls += "logout"
                    Unit
                }
                "recoverAccount" -> {
                    calls += "recover"
                    ApiV3Account("account-id", "player")
                }
                "logoutAll" -> {
                    calls += "logout-all"
                    Unit
                }
                else -> error("Unexpected transport call ${method.name}")
            }
        } as ApiV3Transport
        return AuthoritativeMultiplayerSession.create(transport)
    }
}
