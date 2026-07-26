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
    suspend fun listRulesetManifests(
        after: String? = null,
        limit: Int = 50,
    ): ApiV3RulesetManifestPage = error("Ruleset manifest discovery is unsupported by this transport")
    suspend fun listGames(after: String? = null, limit: Int = 50): ApiV3GamePage
    suspend fun listPlayerInvitations(): List<ApiV3PlayerInvitation>
    suspend fun invitePlayer(gameId: String, request: ApiV3InvitePlayerRequest)
    suspend fun createGame(rulesetManifestHash: String): ApiV3GameMetadata
    suspend fun joinGame(gameId: String, request: ApiV3JoinGameRequest): ApiV3CommandAccepted
    suspend fun projection(gameId: String): ApiV3GameProjection
    suspend fun spectatorProjection(gameId: String): ApiV3SpectatorGameProjection
    suspend fun addSpectator(gameId: String, username: String)
    suspend fun leaveSpectator(gameId: String)
    suspend fun transferOwnership(gameId: String, request: ApiV3TransferOwnershipRequest)
    suspend fun closeGameAdmin(gameId: String, request: ApiV3GameAdminOperationRequest)
    suspend fun archiveGame(gameId: String, request: ApiV3GameAdminOperationRequest)
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
    suspend fun manageConstructionQueues(gameId: String, request: ApiV3ManageConstructionQueuesRequest): ApiV3CommandAccepted
    suspend fun purchaseConstruction(gameId: String, request: ApiV3PurchaseConstructionRequest): ApiV3CommandAccepted
    suspend fun purchaseConstructionAtTile(gameId: String, request: ApiV3PurchaseConstructionAtTileRequest): ApiV3CommandAccepted
    suspend fun buyCityTile(gameId: String, request: ApiV3BuyCityTileRequest): ApiV3CommandAccepted
    suspend fun buyCityTileBatch(
        gameId: String,
        request: ApiV3BuyCityTileBatchRequest,
    ): ApiV3CommandAccepted = error("Batch city tile purchase is unsupported by this transport")
    suspend fun sellBuilding(gameId: String, request: ApiV3SellBuildingRequest): ApiV3CommandAccepted
    suspend fun setCityGovernance(gameId: String, request: ApiV3SetCityGovernanceRequest): ApiV3CommandAccepted
    suspend fun resolveCityDisposition(gameId: String, request: ApiV3ResolveCityDispositionRequest): ApiV3CommandAccepted
    suspend fun castDiplomaticVote(gameId: String, request: ApiV3CastDiplomaticVoteRequest): ApiV3CommandAccepted
    suspend fun chooseGreatPerson(gameId: String, request: ApiV3ChooseGreatPersonRequest): ApiV3CommandAccepted
    suspend fun useReligiousUnit(gameId: String, request: ApiV3UseReligiousUnitRequest): ApiV3CommandAccepted
    suspend fun useGreatPersonUnit(gameId: String, request: ApiV3UseGreatPersonUnitRequest): ApiV3CommandAccepted
    suspend fun giftUnit(gameId: String, request: ApiV3GiftUnitRequest): ApiV3CommandAccepted
    suspend fun addUnitToCapitalProject(
        gameId: String,
        request: ApiV3AddUnitToCapitalProjectRequest,
    ): ApiV3CommandAccepted
    suspend fun createInstantImprovement(
        gameId: String,
        request: ApiV3CreateInstantImprovementRequest,
    ): ApiV3CommandAccepted
    suspend fun transformUnit(gameId: String, request: ApiV3TransformUnitRequest): ApiV3CommandAccepted
    suspend fun triggerUnitUnique(gameId: String, request: ApiV3TriggerUnitUniqueRequest): ApiV3CommandAccepted
    suspend fun chooseReligiousBeliefs(gameId: String, request: ApiV3ChooseReligiousBeliefsRequest): ApiV3CommandAccepted
    suspend fun offerTrade(gameId: String, request: ApiV3OfferTradeRequest): ApiV3CommandAccepted
    suspend fun retractTradeOffer(gameId: String, request: ApiV3RetractTradeOfferRequest): ApiV3CommandAccepted
    suspend fun acceptTrade(gameId: String, request: ApiV3TradeRequestDecisionRequest): ApiV3CommandAccepted
    suspend fun declineTrade(gameId: String, request: ApiV3TradeRequestDecisionRequest): ApiV3CommandAccepted
    suspend fun counterTrade(gameId: String, request: ApiV3CounterTradeRequest): ApiV3CommandAccepted
    suspend fun declareWar(gameId: String, request: ApiV3DiplomacyPartnerRequest): ApiV3CommandAccepted
    suspend fun denounceCivilization(gameId: String, request: ApiV3DiplomacyPartnerRequest): ApiV3CommandAccepted
    suspend fun offerFriendship(gameId: String, request: ApiV3DiplomacyPartnerRequest): ApiV3CommandAccepted
    suspend fun makeDiplomaticDemand(gameId: String, request: ApiV3DiplomaticDemandRequest): ApiV3CommandAccepted
    suspend fun respondToDiplomaticPrompt(gameId: String, request: ApiV3DiplomaticPromptResponseRequest): ApiV3CommandAccepted
    suspend fun respondToCityStateProtectionPrompt(gameId: String, request: ApiV3CityStateProtectionPromptResponseRequest): ApiV3CommandAccepted
    suspend fun giftCityStateGold(gameId: String, request: ApiV3CityStateGoldGiftRequest): ApiV3CommandAccepted
    suspend fun setCityStateProtection(gameId: String, request: ApiV3CityStateProtectionRequest): ApiV3CommandAccepted
    suspend fun demandCityStateTribute(gameId: String, request: ApiV3CityStateTributeRequest): ApiV3CommandAccepted
    suspend fun giftCityStateImprovement(gameId: String, request: ApiV3CityStateImprovementGiftRequest): ApiV3CommandAccepted
    suspend fun negotiateCityStatePeace(gameId: String, request: ApiV3CityStatePeaceRequest): ApiV3CommandAccepted
    suspend fun marryCityState(gameId: String, request: ApiV3CityStateMarriageRequest): ApiV3CommandAccepted
    suspend fun moveSpy(gameId: String, request: ApiV3MoveSpyRequest): ApiV3CommandAccepted
    suspend fun setSpyCoup(gameId: String, request: ApiV3SetSpyCoupRequest): ApiV3CommandAccepted
    suspend fun resolveEventChoice(gameId: String, request: ApiV3ResolveEventChoiceRequest): ApiV3CommandAccepted
    suspend fun setCityTileAssignment(gameId: String, request: ApiV3SetCityTileAssignmentRequest): ApiV3CommandAccepted
    suspend fun setSpecialistCount(gameId: String, request: ApiV3SetSpecialistCountRequest): ApiV3CommandAccepted
    suspend fun setManualSpecialists(gameId: String, request: ApiV3SetManualSpecialistsRequest): ApiV3CommandAccepted
    suspend fun resetCitizens(gameId: String, request: ApiV3ResetCitizensRequest): ApiV3CommandAccepted
    suspend fun setAvoidGrowth(gameId: String, request: ApiV3SetAvoidGrowthRequest): ApiV3CommandAccepted
    suspend fun setCitizenFocus(gameId: String, request: ApiV3SetCitizenFocusRequest): ApiV3CommandAccepted
    suspend fun setResearchPath(gameId: String, request: ApiV3SetResearchPathRequest): ApiV3CommandAccepted
    suspend fun manageResearchQueue(
        gameId: String,
        request: ApiV3ManageResearchQueueRequest,
    ): ApiV3CommandAccepted = error("Research queue management is unsupported by this transport")
    suspend fun adoptPolicy(gameId: String, request: ApiV3AdoptPolicyRequest): ApiV3CommandAccepted
    suspend fun chooseFreeTechnology(gameId: String, request: ApiV3ChooseFreeTechnologyRequest): ApiV3CommandAccepted
    suspend fun acknowledgeResearchCompletion(gameId: String, request: ApiV3AcknowledgeResearchCompletionRequest): ApiV3CommandAccepted
    suspend fun endTurn(gameId: String, request: ApiV3EndTurnRequest): ApiV3CommandAccepted
    suspend fun resign(gameId: String, request: ApiV3ResignRequest): ApiV3CommandAccepted
    suspend fun forceResign(gameId: String, request: ApiV3ForceResignRequest): ApiV3CommandAccepted
    suspend fun kickMember(gameId: String, request: ApiV3KickMemberRequest): ApiV3CommandAccepted
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
