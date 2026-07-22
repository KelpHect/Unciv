package com.unciv.logic.multiplayer.authoritative

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ApiV3Capabilities(
    @SerialName("protocol_version") val protocolVersion: Int,
    @SerialName("projection_version") val projectionVersion: Int,
    val commands: List<String>,
    @SerialName("whole_state_upload") val wholeStateUpload: Boolean,
    @SerialName("websocket_notifications") val websocketNotifications: Boolean,
)

@Serializable
data class ApiV3Credentials(val username: String, val password: String)

@Serializable
data class ApiV3Account(
    @SerialName("account_id") val accountId: String,
    val username: String,
)

@Serializable
data class ApiV3Login(
    val account: ApiV3Account,
    @SerialName("session_token") val sessionToken: String,
)

@Serializable
data class ApiV3Session(@SerialName("session_token") val sessionToken: String)

@Serializable
data class ApiV3ChangePasswordRequest(
    @SerialName("current_password") val currentPassword: String,
    @SerialName("new_password") val newPassword: String,
)

@Serializable
data class ApiV3ConfirmPasswordRequest(val password: String)

@Serializable
data class ApiV3CreateGameRequest(
    @SerialName("ruleset_manifest_hash") val rulesetManifestHash: String,
)

@Serializable
data class ApiV3GameMetadata(
    @SerialName("game_id") val gameId: String,
    @SerialName("committed_revision") val committedRevision: Long,
    @SerialName("canonical_state_hash") val canonicalStateHash: String,
    val role: String,
    @SerialName("civilization_id") val civilizationId: String? = null,
)

@Serializable
data class ApiV3GameSummary(
    @SerialName("game_id") val gameId: String,
    @SerialName("committed_revision") val committedRevision: Long,
    @SerialName("canonical_state_hash") val canonicalStateHash: String,
    val role: String,
    @SerialName("civilization_id") val civilizationId: String? = null,
    val available: Boolean,
)

@Serializable
data class ApiV3GamePage(
    val games: List<ApiV3GameSummary>,
    @SerialName("next_cursor") val nextCursor: String? = null,
)

@Serializable
data class ApiV3JoinGameRequest(
    @SerialName("command_id") val commandId: String,
    @SerialName("expected_revision") val expectedRevision: Long,
    @SerialName("client_observed_state_hash") val clientObservedStateHash: String,
)

@Serializable
data class ApiV3GameProjection(
    @SerialName("game_id") val gameId: String,
    @SerialName("projection_version") val projectionVersion: Int,
    @SerialName("committed_revision") val committedRevision: Long,
    @SerialName("canonical_state_hash") val canonicalStateHash: String,
    @SerialName("projection_hash") val projectionHash: String,
    val projection: PlayerProjection,
)

@Serializable
data class ApiV3CommandAccepted(
    @SerialName("game_id") val gameId: String,
    @SerialName("command_id") val commandId: String,
    @SerialName("previous_revision") val previousRevision: Long,
    @SerialName("committed_revision") val committedRevision: Long,
    @SerialName("canonical_state_hash") val canonicalStateHash: String,
)

@Serializable
data class ApiV3MoveUnitRequest(
    @SerialName("command_id") val commandId: String,
    @SerialName("expected_revision") val expectedRevision: Long,
    @SerialName("client_observed_state_hash") val clientObservedStateHash: String,
    @SerialName("unit_id") val unitId: Int,
    @SerialName("destination_x") val destinationX: Int,
    @SerialName("destination_y") val destinationY: Int,
)

@Serializable
data class ApiV3MoveUnitTowardRequest(
    @SerialName("command_id") val commandId: String,
    @SerialName("expected_revision") val expectedRevision: Long,
    @SerialName("client_observed_state_hash") val clientObservedStateHash: String,
    @SerialName("unit_id") val unitId: Int,
    @SerialName("destination_x") val destinationX: Int,
    @SerialName("destination_y") val destinationY: Int,
)

@Serializable
data class ApiV3CancelUnitMovementOrderRequest(
    @SerialName("command_id") val commandId: String,
    @SerialName("expected_revision") val expectedRevision: Long,
    @SerialName("client_observed_state_hash") val clientObservedStateHash: String,
    @SerialName("unit_id") val unitId: Int,
)

@Serializable
data class ApiV3SetUnitExplorationRequest(
    @SerialName("command_id") val commandId: String,
    @SerialName("expected_revision") val expectedRevision: Long,
    @SerialName("client_observed_state_hash") val clientObservedStateHash: String,
    @SerialName("unit_id") val unitId: Int,
    val enabled: Boolean,
)

@Serializable
data class ApiV3SetUnitAutomationRequest(
    @SerialName("command_id") val commandId: String,
    @SerialName("expected_revision") val expectedRevision: Long,
    @SerialName("client_observed_state_hash") val clientObservedStateHash: String,
    @SerialName("unit_id") val unitId: Int,
    val enabled: Boolean,
)

@Serializable
data class ApiV3SetUnitPostureRequest(
    @SerialName("command_id") val commandId: String,
    @SerialName("expected_revision") val expectedRevision: Long,
    @SerialName("client_observed_state_hash") val clientObservedStateHash: String,
    @SerialName("unit_id") val unitId: Int,
    val posture: UnitPosture,
)

@Serializable
data class ApiV3DisbandUnitRequest(
    @SerialName("command_id") val commandId: String,
    @SerialName("expected_revision") val expectedRevision: Long,
    @SerialName("client_observed_state_hash") val clientObservedStateHash: String,
    @SerialName("unit_id") val unitId: Int,
)

@Serializable
data class ApiV3PillageTileRequest(
    @SerialName("command_id") val commandId: String,
    @SerialName("expected_revision") val expectedRevision: Long,
    @SerialName("client_observed_state_hash") val clientObservedStateHash: String,
    @SerialName("unit_id") val unitId: Int,
)

@Serializable
data class ApiV3FoundCityRequest(
    @SerialName("command_id") val commandId: String,
    @SerialName("expected_revision") val expectedRevision: Long,
    @SerialName("client_observed_state_hash") val clientObservedStateHash: String,
    @SerialName("unit_id") val unitId: Int,
)

@Serializable
data class ApiV3ParadropUnitRequest(
    @SerialName("command_id") val commandId: String,
    @SerialName("expected_revision") val expectedRevision: Long,
    @SerialName("client_observed_state_hash") val clientObservedStateHash: String,
    @SerialName("unit_id") val unitId: Int,
    @SerialName("destination_x") val destinationX: Int,
    @SerialName("destination_y") val destinationY: Int,
)

@Serializable
data class ApiV3AttackWithUnitRequest(
    @SerialName("command_id") val commandId: String,
    @SerialName("expected_revision") val expectedRevision: Long,
    @SerialName("client_observed_state_hash") val clientObservedStateHash: String,
    @SerialName("unit_id") val unitId: Int,
    @SerialName("target_x") val targetX: Int,
    @SerialName("target_y") val targetY: Int,
)

@Serializable
data class ApiV3BombardWithCityRequest(
    @SerialName("command_id") val commandId: String,
    @SerialName("expected_revision") val expectedRevision: Long,
    @SerialName("client_observed_state_hash") val clientObservedStateHash: String,
    @SerialName("city_id") val cityId: String,
    @SerialName("target_x") val targetX: Int,
    @SerialName("target_y") val targetY: Int,
)

@Serializable
data class ApiV3LaunchNuclearStrikeRequest(
    @SerialName("command_id") val commandId: String,
    @SerialName("expected_revision") val expectedRevision: Long,
    @SerialName("client_observed_state_hash") val clientObservedStateHash: String,
    @SerialName("unit_id") val unitId: Int,
    @SerialName("target_x") val targetX: Int,
    @SerialName("target_y") val targetY: Int,
)

@Serializable
data class ApiV3AirSweepRequest(
    @SerialName("command_id") val commandId: String,
    @SerialName("expected_revision") val expectedRevision: Long,
    @SerialName("client_observed_state_hash") val clientObservedStateHash: String,
    @SerialName("unit_id") val unitId: Int,
    @SerialName("target_x") val targetX: Int,
    @SerialName("target_y") val targetY: Int,
)

@Serializable
data class ApiV3UpgradeUnitsRequest(
    @SerialName("command_id") val commandId: String,
    @SerialName("expected_revision") val expectedRevision: Long,
    @SerialName("client_observed_state_hash") val clientObservedStateHash: String,
    @SerialName("unit_ids") val unitIds: List<Int>,
    @SerialName("target_unit_name") val targetUnitName: String,
)

@Serializable
data class ApiV3PromoteUnitRequest(
    @SerialName("command_id") val commandId: String,
    @SerialName("expected_revision") val expectedRevision: Long,
    @SerialName("client_observed_state_hash") val clientObservedStateHash: String,
    @SerialName("unit_id") val unitId: Int,
    @SerialName("promotion_names") val promotionNames: List<String>,
    @SerialName("save_as_city_default") val saveAsCityDefault: Boolean,
)

@Serializable
data class ApiV3SetCityUnitPromotionPreferenceRequest(
    @SerialName("command_id") val commandId: String,
    @SerialName("expected_revision") val expectedRevision: Long,
    @SerialName("client_observed_state_hash") val clientObservedStateHash: String,
    @SerialName("city_id") val cityId: String,
    @SerialName("base_unit_name") val baseUnitName: String,
    val enabled: Boolean,
)

@Serializable
data class ApiV3RenameUnitRequest(
    @SerialName("command_id") val commandId: String,
    @SerialName("expected_revision") val expectedRevision: Long,
    @SerialName("client_observed_state_hash") val clientObservedStateHash: String,
    @SerialName("unit_id") val unitId: Int,
    @SerialName("instance_name") val instanceName: String?,
)

@Serializable
data class ApiV3SetTileImprovementOrderRequest(
    @SerialName("command_id") val commandId: String,
    @SerialName("expected_revision") val expectedRevision: Long,
    @SerialName("client_observed_state_hash") val clientObservedStateHash: String,
    @SerialName("unit_id") val unitId: Int,
    @SerialName("improvement_name") val improvementName: String?,
    @SerialName("queued_improvement_name") val queuedImprovementName: String?,
)

@Serializable
data class ApiV3SetRoadConnectionOrderRequest(
    @SerialName("command_id") val commandId: String,
    @SerialName("expected_revision") val expectedRevision: Long,
    @SerialName("client_observed_state_hash") val clientObservedStateHash: String,
    @SerialName("unit_id") val unitId: Int,
    @SerialName("destination_x") val destinationX: Int?,
    @SerialName("destination_y") val destinationY: Int?,
)

@Serializable
data class ApiV3SwapUnitsRequest(
    @SerialName("command_id") val commandId: String,
    @SerialName("expected_revision") val expectedRevision: Long,
    @SerialName("client_observed_state_hash") val clientObservedStateHash: String,
    @SerialName("unit_id") val unitId: Int,
    @SerialName("destination_x") val destinationX: Int,
    @SerialName("destination_y") val destinationY: Int,
)

@Serializable
data class ApiV3QueueConstructionRequest(
    @SerialName("command_id") val commandId: String,
    @SerialName("expected_revision") val expectedRevision: Long,
    @SerialName("client_observed_state_hash") val clientObservedStateHash: String,
    @SerialName("city_id") val cityId: String,
    @SerialName("construction_name") val constructionName: String,
)

@Serializable
data class ApiV3QueueConstructionAtTileRequest(
    @SerialName("command_id") val commandId: String,
    @SerialName("expected_revision") val expectedRevision: Long,
    @SerialName("client_observed_state_hash") val clientObservedStateHash: String,
    @SerialName("city_id") val cityId: String,
    @SerialName("construction_name") val constructionName: String,
    val x: Int,
    val y: Int,
)

@Serializable
data class ApiV3SetPerpetualConstructionRequest(
    @SerialName("command_id") val commandId: String,
    @SerialName("expected_revision") val expectedRevision: Long,
    @SerialName("client_observed_state_hash") val clientObservedStateHash: String,
    @SerialName("city_id") val cityId: String,
    @SerialName("construction_name") val constructionName: String,
)

@Serializable
data class ApiV3RemoveConstructionRequest(
    @SerialName("command_id") val commandId: String,
    @SerialName("expected_revision") val expectedRevision: Long,
    @SerialName("client_observed_state_hash") val clientObservedStateHash: String,
    @SerialName("city_id") val cityId: String,
    @SerialName("queue_index") val queueIndex: Int,
    @SerialName("expected_construction_name") val expectedConstructionName: String,
)

@Serializable
data class ApiV3MoveConstructionRequest(
    @SerialName("command_id") val commandId: String,
    @SerialName("expected_revision") val expectedRevision: Long,
    @SerialName("client_observed_state_hash") val clientObservedStateHash: String,
    @SerialName("city_id") val cityId: String,
    @SerialName("from_index") val fromIndex: Int,
    @SerialName("to_index") val toIndex: Int,
    @SerialName("expected_construction_name") val expectedConstructionName: String,
)

@Serializable
data class ApiV3PurchaseConstructionRequest(
    @SerialName("command_id") val commandId: String,
    @SerialName("expected_revision") val expectedRevision: Long,
    @SerialName("client_observed_state_hash") val clientObservedStateHash: String,
    @SerialName("city_id") val cityId: String,
    @SerialName("construction_name") val constructionName: String,
    @SerialName("currency_name") val currencyName: String,
    @SerialName("queue_index") val queueIndex: Int? = null,
)

@Serializable
data class ApiV3PurchaseConstructionAtTileRequest(
    @SerialName("command_id") val commandId: String,
    @SerialName("expected_revision") val expectedRevision: Long,
    @SerialName("client_observed_state_hash") val clientObservedStateHash: String,
    @SerialName("city_id") val cityId: String,
    @SerialName("construction_name") val constructionName: String,
    @SerialName("currency_name") val currencyName: String,
    val x: Int,
    val y: Int,
    @SerialName("queue_index") val queueIndex: Int? = null,
)

@Serializable
data class ApiV3BuyCityTileRequest(
    @SerialName("command_id") val commandId: String,
    @SerialName("expected_revision") val expectedRevision: Long,
    @SerialName("client_observed_state_hash") val clientObservedStateHash: String,
    @SerialName("city_id") val cityId: String,
    val x: Int,
    val y: Int,
)

@Serializable
data class ApiV3SellBuildingRequest(
    @SerialName("command_id") val commandId: String,
    @SerialName("expected_revision") val expectedRevision: Long,
    @SerialName("client_observed_state_hash") val clientObservedStateHash: String,
    @SerialName("city_id") val cityId: String,
    @SerialName("building_name") val buildingName: String,
)

@Serializable
data class ApiV3SetCityGovernanceRequest(
    @SerialName("command_id") val commandId: String,
    @SerialName("expected_revision") val expectedRevision: Long,
    @SerialName("client_observed_state_hash") val clientObservedStateHash: String,
    @SerialName("city_id") val cityId: String,
    val action: CityGovernanceAction,
)

@Serializable
data class ApiV3ResolveCityDispositionRequest(
    @SerialName("command_id") val commandId: String,
    @SerialName("expected_revision") val expectedRevision: Long,
    @SerialName("client_observed_state_hash") val clientObservedStateHash: String,
    @SerialName("city_id") val cityId: String,
    val action: CityDispositionAction,
)

@Serializable
data class ApiV3CastDiplomaticVoteRequest(
    @SerialName("command_id") val commandId: String,
    @SerialName("expected_revision") val expectedRevision: Long,
    @SerialName("client_observed_state_hash") val clientObservedStateHash: String,
    @SerialName("candidate_civilization_id") val candidateCivilizationId: String?,
)

@Serializable
data class ApiV3ChooseGreatPersonRequest(
    @SerialName("command_id") val commandId: String,
    @SerialName("expected_revision") val expectedRevision: Long,
    @SerialName("client_observed_state_hash") val clientObservedStateHash: String,
    @SerialName("unit_name") val unitName: String,
)

@Serializable
data class ApiV3UseReligiousUnitRequest(
    @SerialName("command_id") val commandId: String,
    @SerialName("expected_revision") val expectedRevision: Long,
    @SerialName("client_observed_state_hash") val clientObservedStateHash: String,
    @SerialName("unit_id") val unitId: Int,
    val action: ReligiousUnitAction,
)

@Serializable
data class ApiV3ChooseReligiousBeliefsRequest(
    @SerialName("command_id") val commandId: String,
    @SerialName("expected_revision") val expectedRevision: Long,
    @SerialName("client_observed_state_hash") val clientObservedStateHash: String,
    @SerialName("belief_names") val beliefNames: List<String>,
    @SerialName("religion_icon_name") val religionIconName: String? = null,
    @SerialName("religion_display_name") val religionDisplayName: String? = null,
)

@Serializable
data class ApiV3OfferTradeRequest(
    @SerialName("command_id") val commandId: String,
    @SerialName("expected_revision") val expectedRevision: Long,
    @SerialName("client_observed_state_hash") val clientObservedStateHash: String,
    @SerialName("other_civilization_id") val otherCivilizationId: String,
    val trade: ProjectedTrade,
)

@Serializable
data class ApiV3RetractTradeOfferRequest(
    @SerialName("command_id") val commandId: String,
    @SerialName("expected_revision") val expectedRevision: Long,
    @SerialName("client_observed_state_hash") val clientObservedStateHash: String,
    @SerialName("other_civilization_id") val otherCivilizationId: String,
)

@Serializable
data class ApiV3TradeRequestDecisionRequest(
    @SerialName("command_id") val commandId: String,
    @SerialName("expected_revision") val expectedRevision: Long,
    @SerialName("client_observed_state_hash") val clientObservedStateHash: String,
    @SerialName("request_id") val requestId: String,
)

@Serializable
data class ApiV3CounterTradeRequest(
    @SerialName("command_id") val commandId: String,
    @SerialName("expected_revision") val expectedRevision: Long,
    @SerialName("client_observed_state_hash") val clientObservedStateHash: String,
    @SerialName("request_id") val requestId: String,
    val trade: ProjectedTrade,
)

@Serializable
data class ApiV3DiplomacyPartnerRequest(
    @SerialName("command_id") val commandId: String,
    @SerialName("expected_revision") val expectedRevision: Long,
    @SerialName("client_observed_state_hash") val clientObservedStateHash: String,
    @SerialName("other_civilization_id") val otherCivilizationId: String,
)

@Serializable
data class ApiV3DiplomaticDemandRequest(
    @SerialName("command_id") val commandId: String,
    @SerialName("expected_revision") val expectedRevision: Long,
    @SerialName("client_observed_state_hash") val clientObservedStateHash: String,
    @SerialName("other_civilization_id") val otherCivilizationId: String,
    val demand: DiplomaticDemand,
)

@Serializable
data class ApiV3DiplomaticPromptResponseRequest(
    @SerialName("command_id") val commandId: String,
    @SerialName("expected_revision") val expectedRevision: Long,
    @SerialName("client_observed_state_hash") val clientObservedStateHash: String,
    @SerialName("prompt_id") val promptId: String,
    val accept: Boolean,
)

@Serializable
data class ApiV3CityStateGoldGiftRequest(
    @SerialName("command_id") val commandId: String,
    @SerialName("expected_revision") val expectedRevision: Long,
    @SerialName("client_observed_state_hash") val clientObservedStateHash: String,
    @SerialName("city_state_civilization_id") val cityStateCivilizationId: String,
    val amount: Int,
)

@Serializable
data class ApiV3CityStateProtectionRequest(
    @SerialName("command_id") val commandId: String,
    @SerialName("expected_revision") val expectedRevision: Long,
    @SerialName("client_observed_state_hash") val clientObservedStateHash: String,
    @SerialName("city_state_civilization_id") val cityStateCivilizationId: String,
    val protect: Boolean,
)

@Serializable
data class ApiV3CityStateTributeRequest(
    @SerialName("command_id") val commandId: String,
    @SerialName("expected_revision") val expectedRevision: Long,
    @SerialName("client_observed_state_hash") val clientObservedStateHash: String,
    @SerialName("city_state_civilization_id") val cityStateCivilizationId: String,
    val worker: Boolean,
)

@Serializable
data class ApiV3CityStateImprovementGiftRequest(
    @SerialName("command_id") val commandId: String,
    @SerialName("expected_revision") val expectedRevision: Long,
    @SerialName("client_observed_state_hash") val clientObservedStateHash: String,
    @SerialName("city_state_civilization_id") val cityStateCivilizationId: String,
    val x: Int,
    val y: Int,
    @SerialName("improvement_name") val improvementName: String,
)

@Serializable
data class ApiV3CityStatePeaceRequest(
    @SerialName("command_id") val commandId: String,
    @SerialName("expected_revision") val expectedRevision: Long,
    @SerialName("client_observed_state_hash") val clientObservedStateHash: String,
    @SerialName("city_state_civilization_id") val cityStateCivilizationId: String,
)

@Serializable
data class ApiV3CityStateMarriageRequest(
    @SerialName("command_id") val commandId: String,
    @SerialName("expected_revision") val expectedRevision: Long,
    @SerialName("client_observed_state_hash") val clientObservedStateHash: String,
    @SerialName("city_state_civilization_id") val cityStateCivilizationId: String,
)

@Serializable
data class ApiV3SetCityTileAssignmentRequest(
    @SerialName("command_id") val commandId: String,
    @SerialName("expected_revision") val expectedRevision: Long,
    @SerialName("client_observed_state_hash") val clientObservedStateHash: String,
    @SerialName("city_id") val cityId: String,
    val x: Int,
    val y: Int,
    val assignment: CityTileAssignment,
)

@Serializable
data class ApiV3SetSpecialistCountRequest(
    @SerialName("command_id") val commandId: String,
    @SerialName("expected_revision") val expectedRevision: Long,
    @SerialName("client_observed_state_hash") val clientObservedStateHash: String,
    @SerialName("city_id") val cityId: String,
    @SerialName("specialist_name") val specialistName: String,
    val count: Int,
)

@Serializable
data class ApiV3SetManualSpecialistsRequest(
    @SerialName("command_id") val commandId: String,
    @SerialName("expected_revision") val expectedRevision: Long,
    @SerialName("client_observed_state_hash") val clientObservedStateHash: String,
    @SerialName("city_id") val cityId: String,
    val enabled: Boolean,
)

@Serializable
data class ApiV3ResetCitizensRequest(
    @SerialName("command_id") val commandId: String,
    @SerialName("expected_revision") val expectedRevision: Long,
    @SerialName("client_observed_state_hash") val clientObservedStateHash: String,
    @SerialName("city_id") val cityId: String,
)

@Serializable
data class ApiV3SetAvoidGrowthRequest(
    @SerialName("command_id") val commandId: String,
    @SerialName("expected_revision") val expectedRevision: Long,
    @SerialName("client_observed_state_hash") val clientObservedStateHash: String,
    @SerialName("city_id") val cityId: String,
    val enabled: Boolean,
)

@Serializable
data class ApiV3SetCitizenFocusRequest(
    @SerialName("command_id") val commandId: String,
    @SerialName("expected_revision") val expectedRevision: Long,
    @SerialName("client_observed_state_hash") val clientObservedStateHash: String,
    @SerialName("city_id") val cityId: String,
    val focus: CitizenFocus,
)

@Serializable
data class ApiV3SetResearchPathRequest(
    @SerialName("command_id") val commandId: String,
    @SerialName("expected_revision") val expectedRevision: Long,
    @SerialName("client_observed_state_hash") val clientObservedStateHash: String,
    @SerialName("technology_name") val technologyName: String,
)

@Serializable
data class ApiV3AdoptPolicyRequest(
    @SerialName("command_id") val commandId: String,
    @SerialName("expected_revision") val expectedRevision: Long,
    @SerialName("client_observed_state_hash") val clientObservedStateHash: String,
    @SerialName("policy_name") val policyName: String,
)

@Serializable
data class ApiV3ChooseFreeTechnologyRequest(
    @SerialName("command_id") val commandId: String,
    @SerialName("expected_revision") val expectedRevision: Long,
    @SerialName("client_observed_state_hash") val clientObservedStateHash: String,
    @SerialName("technology_name") val technologyName: String,
)

@Serializable
data class ApiV3EndTurnRequest(
    @SerialName("command_id") val commandId: String,
    @SerialName("expected_revision") val expectedRevision: Long,
    @SerialName("client_observed_state_hash") val clientObservedStateHash: String,
)

@Serializable
data class ApiV3ErrorResponse(
    val code: String,
    @SerialName("current_revision") val currentRevision: Long? = null,
)

@Serializable
data class ApiV3RevisionNotification(
    val type: String,
    @SerialName("protocol_version") val protocolVersion: Int,
    @SerialName("game_id") val gameId: String? = null,
    @SerialName("committed_revision") val committedRevision: Long? = null,
    @SerialName("canonical_state_hash") val canonicalStateHash: String? = null,
)

class ApiV3Exception(
    val httpStatus: Int,
    val error: ApiV3ErrorResponse,
) : Exception(error.code)
