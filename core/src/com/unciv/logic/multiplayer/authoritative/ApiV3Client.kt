package com.unciv.logic.multiplayer.authoritative

import com.unciv.UncivGame
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

class ApiV3Client(
    baseUrl: String,
    private val tokenStore: ApiV3SessionTokenStore,
    private val client: HttpClient = defaultClient(baseUrl),
) : ApiV3Transport, AutoCloseable {
    private var sessionToken: String? = null

    suspend fun restoreSession(): Boolean {
        sessionToken = tokenStore.load()
        return sessionToken != null
    }

    override suspend fun capabilities(): ApiV3Capabilities =
        decode(client.get("api/v3/capabilities"))

    override suspend fun register(username: String, password: String): ApiV3Account =
        decode(client.post("api/v3/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(ApiV3Credentials(username, password))
        })

    override suspend fun login(username: String, password: String): ApiV3Account {
        val response: ApiV3Login = decode(client.post("api/v3/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(ApiV3Credentials(username, password))
        })
        sessionToken = response.sessionToken
        tokenStore.save(response.sessionToken)
        return response.account
    }

    override suspend fun refreshSession() {
        val response: ApiV3Session = decode(client.post("api/v3/auth/refresh") { authenticate() })
        sessionToken = response.sessionToken
        tokenStore.save(response.sessionToken)
    }

    override suspend fun logout() {
        val response = client.post("api/v3/auth/logout") { authenticate() }
        if (!response.status.isSuccess()) throw response.toApiException()
        sessionToken = null
        tokenStore.clear()
    }

    override suspend fun createGame(rulesetManifestHash: String): ApiV3GameMetadata =
        decode(client.post("api/v3/games") {
            authenticate()
            contentType(ContentType.Application.Json)
            setBody(ApiV3CreateGameRequest(rulesetManifestHash))
        })

    override suspend fun joinGame(
        gameId: String,
        request: ApiV3JoinGameRequest,
    ): ApiV3CommandAccepted = decode(client.post("api/v3/games/$gameId/join") {
        authenticate()
        contentType(ContentType.Application.Json)
        setBody(request)
    })

    override suspend fun projection(gameId: String): ApiV3GameProjection =
        decode(client.get("api/v3/games/$gameId/projection") { authenticate() })

    override suspend fun moveUnit(
        gameId: String,
        request: ApiV3MoveUnitRequest,
    ): ApiV3CommandAccepted = decode(client.post("api/v3/games/$gameId/commands/move-unit") {
        authenticate()
        contentType(ContentType.Application.Json)
        setBody(request)
    })

    override suspend fun endTurn(
        gameId: String,
        request: ApiV3EndTurnRequest,
    ): ApiV3CommandAccepted = decode(client.post("api/v3/games/$gameId/commands/end-turn") {
        authenticate()
        contentType(ContentType.Application.Json)
        setBody(request)
    })

    private fun io.ktor.client.request.HttpRequestBuilder.authenticate() {
        bearerAuth(sessionToken ?: error("API v3 session is not available"))
    }

    private suspend inline fun <reified T> decode(response: HttpResponse): T {
        if (!response.status.isSuccess()) throw response.toApiException()
        return response.body()
    }

    private suspend fun HttpResponse.toApiException(): ApiV3Exception {
        val error = runCatching { body<ApiV3ErrorResponse>() }
            .getOrElse { ApiV3ErrorResponse("invalid_server_response") }
        return ApiV3Exception(status.value, error)
    }

    override fun close() = client.close()

    companion object {
        private fun defaultClient(baseUrl: String) = HttpClient(CIO) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = false; encodeDefaults = true })
            }
            install(HttpTimeout) {
                requestTimeoutMillis = 30_000
                connectTimeoutMillis = 10_000
            }
            defaultRequest {
                url(if (baseUrl.endsWith('/')) baseUrl else "$baseUrl/")
                header(HttpHeaders.UserAgent, UncivGame.getUserAgent("Multiplayer-v3"))
            }
        }
    }
}
