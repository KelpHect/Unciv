package com.unciv.logic.multiplayer.authoritative

import kotlinx.coroutines.flow.Flow

interface ApiV3Transport {
    suspend fun restoreSession(): Boolean
    suspend fun capabilities(): ApiV3Capabilities
    suspend fun register(username: String, password: String): ApiV3Account
    suspend fun login(username: String, password: String): ApiV3Account
    suspend fun refreshSession()
    suspend fun logout()
    suspend fun listGames(after: String? = null, limit: Int = 50): ApiV3GamePage
    suspend fun createGame(rulesetManifestHash: String): ApiV3GameMetadata
    suspend fun joinGame(gameId: String, request: ApiV3JoinGameRequest): ApiV3CommandAccepted
    suspend fun projection(gameId: String): ApiV3GameProjection
    suspend fun moveUnit(gameId: String, request: ApiV3MoveUnitRequest): ApiV3CommandAccepted
    suspend fun queueConstruction(gameId: String, request: ApiV3QueueConstructionRequest): ApiV3CommandAccepted
    suspend fun endTurn(gameId: String, request: ApiV3EndTurnRequest): ApiV3CommandAccepted
    fun notifications(): Flow<ApiV3RevisionNotification>
}

/** Platform implementations must protect this value with the OS credential
 * store. The core client never persists usernames/passwords. */
interface ApiV3SessionTokenStore {
    suspend fun load(): String?
    suspend fun save(token: String)
    suspend fun clear()
}

class InMemoryApiV3SessionTokenStore : ApiV3SessionTokenStore {
    private var token: String? = null
    override suspend fun load() = token
    override suspend fun save(token: String) { this.token = token }
    override suspend fun clear() { token = null }
}
