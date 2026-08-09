package com.unciv.logic.multiplayer.authoritative

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.util.ArrayDeque
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ApiV3ClientErrorTests {
    @Test
    fun gameMetadataReadsTheDedicatedServerRoute() = runBlocking {
        val gameId = "00000000-0000-4000-8000-000000000001"
        var requestedPath = ""
        val engine = MockEngine { request ->
            requestedPath = request.url.encodedPath
            respond(
                content = """{"game_id":"$gameId","committed_revision":3,"canonical_state_hash":"hash-3","role":"owner","civilization_id":"Rome","lifecycle_status":"active"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(
                    HttpHeaders.ContentType,
                    ContentType.Application.Json.toString(),
                ),
            )
        }
        val httpClient = HttpClient(engine) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = false; encodeDefaults = true })
            }
            defaultRequest { url("https://example.test/") }
        }
        val tokenStore = InMemoryApiV3SessionTokenStore()
        tokenStore.save("test-session-token")
        val client = ApiV3Client("https://example.test", tokenStore, httpClient)
        client.restoreSession()

        val metadata = client.gameMetadata(gameId)

        assertEquals("/api/v3/games/$gameId", requestedPath)
        assertEquals(gameId, metadata.gameId)
        assertEquals(3L, metadata.committedRevision)
        assertEquals("owner", metadata.role)
        assertEquals("Rome", metadata.civilizationId)
        client.close()
    }

    @Test
    fun loginAndRefreshPersistAndRotateBothCredentials() = runBlocking {
        val accessTokens = ArrayDeque(listOf("access-a", "access-b"))
        val refreshTokens = ArrayDeque(listOf("refresh-a", "refresh-b"))
        val paths = mutableListOf<String>()
        val engine = MockEngine { request ->
            paths += request.url.encodedPath
            val access = accessTokens.removeFirst()
            val refresh = refreshTokens.removeFirst()
            val body = if (paths.size == 1) {
                """{"account":{"account_id":"account-id","username":"player"},"session_token":"$access","refresh_token":"$refresh"}"""
            } else {
                """{"session_token":"$access","refresh_token":"$refresh"}"""
            }
            respond(
                content = body,
                status = HttpStatusCode.OK,
                headers = headersOf(
                    HttpHeaders.ContentType,
                    ContentType.Application.Json.toString(),
                ),
            )
        }
        val httpClient = HttpClient(engine) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = false; encodeDefaults = true })
            }
            defaultRequest { url("https://example.test/") }
        }
        val tokenStore = InMemoryApiV3SessionTokenStore()
        val client = ApiV3Client("https://example.test", tokenStore, httpClient)

        client.login("player", "password")
        client.refreshSession()

        assertEquals(
            listOf("/api/v3/auth/login", "/api/v3/auth/refresh"),
            paths,
        )
        assertEquals("unciv-v3-session:access-b:refresh-b", tokenStore.load())
        client.close()
    }

    @Test
    fun nonSuccessJsonPreservesStableServerError() = runBlocking {
        val engine = MockEngine {
            respond(
                content = """{"code":"stale_revision","current_revision":7}""",
                status = HttpStatusCode.Conflict,
                headers = headersOf(
                    HttpHeaders.ContentType,
                    ContentType.Application.Json.toString(),
                ),
            )
        }
        val httpClient = HttpClient(engine) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = false; encodeDefaults = true })
            }
            defaultRequest { url("https://example.test/") }
        }
        val tokenStore = InMemoryApiV3SessionTokenStore()
        tokenStore.save("test-session-token")
        val client = ApiV3Client("https://example.test", tokenStore, httpClient)
        client.restoreSession()

        val exception = assertThrows(ApiV3Exception::class.java) {
            runBlocking {
                client.lobby("00000000-0000-4000-8000-000000000001")
            }
        }

        assertEquals(HttpStatusCode.Conflict.value, exception.httpStatus)
        assertEquals("stale_revision", exception.error.code)
        assertEquals(7L, exception.error.currentRevision)
        client.close()
    }
}
