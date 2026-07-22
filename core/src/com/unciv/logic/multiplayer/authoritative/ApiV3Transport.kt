package com.unciv.logic.multiplayer.authoritative

import kotlinx.coroutines.flow.Flow

interface ApiV3Transport {
    suspend fun restoreSession(): Boolean
    suspend fun capabilities(): ApiV3Capabilities
    suspend fun register(username: String, password: String): ApiV3Account
    suspend fun login(username: String, password: String): ApiV3Account
    suspend fun refreshSession()
    suspend fun logout()
    suspend fun changePassword(currentPassword: String, newPassword: String)
    suspend fun disableAccount(password: String)
    suspend fun deleteAccount(password: String)
    suspend fun listGames(after: String? = null, limit: Int = 50): ApiV3GamePage
    suspend fun createGame(rulesetManifestHash: String): ApiV3GameMetadata
    suspend fun joinGame(gameId: String, request: ApiV3JoinGameRequest): ApiV3CommandAccepted
    suspend fun projection(gameId: String): ApiV3GameProjection
    suspend fun moveUnit(gameId: String, request: ApiV3MoveUnitRequest): ApiV3CommandAccepted
    suspend fun moveUnitToward(gameId: String, request: ApiV3MoveUnitTowardRequest): ApiV3CommandAccepted
    suspend fun cancelUnitMovementOrder(gameId: String, request: ApiV3CancelUnitMovementOrderRequest): ApiV3CommandAccepted
    suspend fun setUnitExploration(gameId: String, request: ApiV3SetUnitExplorationRequest): ApiV3CommandAccepted
    suspend fun setUnitAutomation(gameId: String, request: ApiV3SetUnitAutomationRequest): ApiV3CommandAccepted
    suspend fun setUnitPosture(gameId: String, request: ApiV3SetUnitPostureRequest): ApiV3CommandAccepted
    suspend fun disbandUnit(gameId: String, request: ApiV3DisbandUnitRequest): ApiV3CommandAccepted
    suspend fun pillageTile(gameId: String, request: ApiV3PillageTileRequest): ApiV3CommandAccepted
    suspend fun foundCity(gameId: String, request: ApiV3FoundCityRequest): ApiV3CommandAccepted
    suspend fun paradropUnit(gameId: String, request: ApiV3ParadropUnitRequest): ApiV3CommandAccepted
    suspend fun attackWithUnit(gameId: String, request: ApiV3AttackWithUnitRequest): ApiV3CommandAccepted
    suspend fun bombardWithCity(gameId: String, request: ApiV3BombardWithCityRequest): ApiV3CommandAccepted
    suspend fun launchNuclearStrike(gameId: String, request: ApiV3LaunchNuclearStrikeRequest): ApiV3CommandAccepted
    suspend fun airSweep(gameId: String, request: ApiV3AirSweepRequest): ApiV3CommandAccepted
    suspend fun upgradeUnits(gameId: String, request: ApiV3UpgradeUnitsRequest): ApiV3CommandAccepted
    suspend fun promoteUnit(gameId: String, request: ApiV3PromoteUnitRequest): ApiV3CommandAccepted
    suspend fun setCityUnitPromotionPreference(gameId: String, request: ApiV3SetCityUnitPromotionPreferenceRequest): ApiV3CommandAccepted
    suspend fun renameUnit(gameId: String, request: ApiV3RenameUnitRequest): ApiV3CommandAccepted
    suspend fun setTileImprovementOrder(gameId: String, request: ApiV3SetTileImprovementOrderRequest): ApiV3CommandAccepted
    suspend fun setRoadConnectionOrder(gameId: String, request: ApiV3SetRoadConnectionOrderRequest): ApiV3CommandAccepted
    suspend fun swapUnits(gameId: String, request: ApiV3SwapUnitsRequest): ApiV3CommandAccepted
    suspend fun queueConstruction(gameId: String, request: ApiV3QueueConstructionRequest): ApiV3CommandAccepted
    suspend fun queueConstructionAtTile(gameId: String, request: ApiV3QueueConstructionAtTileRequest): ApiV3CommandAccepted
    suspend fun setPerpetualConstruction(gameId: String, request: ApiV3SetPerpetualConstructionRequest): ApiV3CommandAccepted
    suspend fun removeConstruction(gameId: String, request: ApiV3RemoveConstructionRequest): ApiV3CommandAccepted
    suspend fun moveConstruction(gameId: String, request: ApiV3MoveConstructionRequest): ApiV3CommandAccepted
    suspend fun purchaseConstruction(gameId: String, request: ApiV3PurchaseConstructionRequest): ApiV3CommandAccepted
    suspend fun purchaseConstructionAtTile(gameId: String, request: ApiV3PurchaseConstructionAtTileRequest): ApiV3CommandAccepted
    suspend fun buyCityTile(gameId: String, request: ApiV3BuyCityTileRequest): ApiV3CommandAccepted
    suspend fun sellBuilding(gameId: String, request: ApiV3SellBuildingRequest): ApiV3CommandAccepted
    suspend fun setCityGovernance(gameId: String, request: ApiV3SetCityGovernanceRequest): ApiV3CommandAccepted
    suspend fun setCityTileAssignment(gameId: String, request: ApiV3SetCityTileAssignmentRequest): ApiV3CommandAccepted
    suspend fun setSpecialistCount(gameId: String, request: ApiV3SetSpecialistCountRequest): ApiV3CommandAccepted
    suspend fun setManualSpecialists(gameId: String, request: ApiV3SetManualSpecialistsRequest): ApiV3CommandAccepted
    suspend fun resetCitizens(gameId: String, request: ApiV3ResetCitizensRequest): ApiV3CommandAccepted
    suspend fun setAvoidGrowth(gameId: String, request: ApiV3SetAvoidGrowthRequest): ApiV3CommandAccepted
    suspend fun setCitizenFocus(gameId: String, request: ApiV3SetCitizenFocusRequest): ApiV3CommandAccepted
    suspend fun setResearchPath(gameId: String, request: ApiV3SetResearchPathRequest): ApiV3CommandAccepted
    suspend fun adoptPolicy(gameId: String, request: ApiV3AdoptPolicyRequest): ApiV3CommandAccepted
    suspend fun chooseFreeTechnology(gameId: String, request: ApiV3ChooseFreeTechnologyRequest): ApiV3CommandAccepted
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
