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
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ApiV3ClientErrorTests {
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
