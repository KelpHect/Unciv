package com.unciv.logic.multiplayer.authoritative

import com.unciv.UncivGame
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.http.appendPathSegments
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import java.util.UUID

class ApiV3Client(
    baseUrl: String,
    private val tokenStore: ApiV3SessionTokenStore,
    private val client: HttpClient = defaultClient(baseUrl),
) : ApiV3Transport, AutoCloseable {
    private var sessionToken: String? = null
    private val json = Json { ignoreUnknownKeys = false; encodeDefaults = true }

    override suspend fun restoreSession(): Boolean {
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

    override suspend fun changePassword(currentPassword: String, newPassword: String) {
        val response: ApiV3Session = decode(client.post("api/v3/account/password") {
            authenticate()
            contentType(ContentType.Application.Json)
            setBody(ApiV3ChangePasswordRequest(currentPassword, newPassword))
        })
        sessionToken = response.sessionToken
        tokenStore.save(response.sessionToken)
    }

    override suspend fun disableAccount(password: String) {
        val response = client.post("api/v3/account/disable") {
            authenticate()
            contentType(ContentType.Application.Json)
            setBody(ApiV3ConfirmPasswordRequest(password))
        }
        if (!response.status.isSuccess()) throw response.toApiException()
        sessionToken = null
        tokenStore.clear()
    }

    override suspend fun deleteAccount(password: String) {
        val response = client.delete("api/v3/account") {
            authenticate()
            contentType(ContentType.Application.Json)
            setBody(ApiV3ConfirmPasswordRequest(password))
        }
        if (!response.status.isSuccess()) throw response.toApiException()
        sessionToken = null
        tokenStore.clear()
    }

    override suspend fun listGames(after: String?, limit: Int): ApiV3GamePage {
        require(limit in 1..100) { "API v3 game page limit must be between 1 and 100" }
        require(after == null || runCatching { UUID.fromString(after) }.isSuccess) {
            "API v3 game page cursor must be a UUID"
        }
        return decode(client.get("api/v3/games") {
            authenticate()
            parameter("limit", limit)
            after?.let { parameter("after", it) }
        })
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

    override suspend fun queueConstruction(
        gameId: String,
        request: ApiV3QueueConstructionRequest,
    ): ApiV3CommandAccepted = decode(client.post("api/v3/games/$gameId/commands/queue-construction") {
        authenticate()
        contentType(ContentType.Application.Json)
        setBody(request)
    })

    override suspend fun queueConstructionAtTile(
        gameId: String,
        request: ApiV3QueueConstructionAtTileRequest,
    ): ApiV3CommandAccepted = decode(client.post("api/v3/games/$gameId/commands/queue-construction-at-tile") {
        authenticate()
        contentType(ContentType.Application.Json)
        setBody(request)
    })

    override suspend fun setPerpetualConstruction(
        gameId: String,
        request: ApiV3SetPerpetualConstructionRequest,
    ): ApiV3CommandAccepted = decode(client.post("api/v3/games/$gameId/commands/set-perpetual-construction") {
        authenticate()
        contentType(ContentType.Application.Json)
        setBody(request)
    })

    override suspend fun removeConstruction(
        gameId: String,
        request: ApiV3RemoveConstructionRequest,
    ): ApiV3CommandAccepted = decode(client.post("api/v3/games/$gameId/commands/remove-construction") {
        authenticate()
        contentType(ContentType.Application.Json)
        setBody(request)
    })

    override suspend fun moveConstruction(
        gameId: String,
        request: ApiV3MoveConstructionRequest,
    ): ApiV3CommandAccepted = decode(client.post("api/v3/games/$gameId/commands/move-construction") {
        authenticate()
        contentType(ContentType.Application.Json)
        setBody(request)
    })

    override suspend fun purchaseConstruction(
        gameId: String,
        request: ApiV3PurchaseConstructionRequest,
    ): ApiV3CommandAccepted = decode(client.post("api/v3/games/$gameId/commands/purchase-construction") {
        authenticate()
        contentType(ContentType.Application.Json)
        setBody(request)
    })

    override suspend fun purchaseConstructionAtTile(
        gameId: String,
        request: ApiV3PurchaseConstructionAtTileRequest,
    ): ApiV3CommandAccepted = decode(client.post("api/v3/games/$gameId/commands/purchase-construction-at-tile") {
        authenticate()
        contentType(ContentType.Application.Json)
        setBody(request)
    })

    override suspend fun buyCityTile(
        gameId: String,
        request: ApiV3BuyCityTileRequest,
    ): ApiV3CommandAccepted = decode(client.post("api/v3/games/$gameId/commands/buy-city-tile") {
        authenticate()
        contentType(ContentType.Application.Json)
        setBody(request)
    })

    override suspend fun setCityTileAssignment(
        gameId: String,
        request: ApiV3SetCityTileAssignmentRequest,
    ): ApiV3CommandAccepted = decode(client.post("api/v3/games/$gameId/commands/set-city-tile-assignment") {
        authenticate()
        contentType(ContentType.Application.Json)
        setBody(request)
    })

    override suspend fun setSpecialistCount(
        gameId: String,
        request: ApiV3SetSpecialistCountRequest,
    ): ApiV3CommandAccepted = decode(client.post("api/v3/games/$gameId/commands/set-specialist-count") {
        authenticate()
        contentType(ContentType.Application.Json)
        setBody(request)
    })

    override suspend fun setManualSpecialists(
        gameId: String,
        request: ApiV3SetManualSpecialistsRequest,
    ): ApiV3CommandAccepted = decode(client.post("api/v3/games/$gameId/commands/set-manual-specialists") {
        authenticate()
        contentType(ContentType.Application.Json)
        setBody(request)
    })

    override suspend fun setResearchPath(
        gameId: String,
        request: ApiV3SetResearchPathRequest,
    ): ApiV3CommandAccepted = decode(client.post("api/v3/games/$gameId/commands/set-research-path") {
        authenticate()
        contentType(ContentType.Application.Json)
        setBody(request)
    })

    override suspend fun adoptPolicy(
        gameId: String,
        request: ApiV3AdoptPolicyRequest,
    ): ApiV3CommandAccepted = decode(client.post("api/v3/games/$gameId/commands/adopt-policy") {
        authenticate()
        contentType(ContentType.Application.Json)
        setBody(request)
    })

    override suspend fun chooseFreeTechnology(
        gameId: String,
        request: ApiV3ChooseFreeTechnologyRequest,
    ): ApiV3CommandAccepted = decode(client.post("api/v3/games/$gameId/commands/choose-free-technology") {
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

    override fun notifications(): Flow<ApiV3RevisionNotification> = flow {
        var retryDelayMillis = 250L
        while (currentCoroutineContext().isActive) {
            val session = try {
                client.webSocketSession {
                    url { appendPathSegments("api", "v3", "notifications") }
                    authenticate()
                }
            } catch (_: Throwable) {
                delay(retryDelayMillis)
                retryDelayMillis = (retryDelayMillis * 2).coerceAtMost(10_000L)
                continue
            }
            try {
                retryDelayMillis = 250L
                for (frame in session.incoming) {
                    if (frame !is Frame.Text) continue
                    val notification = json.decodeFromString(
                        ApiV3RevisionNotification.serializer(),
                        frame.readText(),
                    )
                    if (notification.protocolVersion == CommandEnvelope.CURRENT_PROTOCOL_VERSION) {
                        emit(notification)
                    }
                }
            } finally {
                session.close()
            }
            if (currentCoroutineContext().isActive) delay(retryDelayMillis)
        }
    }

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
            install(WebSockets)
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
