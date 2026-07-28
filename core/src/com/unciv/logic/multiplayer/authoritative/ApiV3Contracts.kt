package com.unciv.logic.multiplayer.authoritative

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class ApiV3Capabilities(
    @SerialName("protocol_version") val protocolVersion: Int,
    @SerialName("projection_version") val projectionVersion: Int,
    val commands: List<String>,
    @SerialName("whole_state_upload") val wholeStateUpload: Boolean,
    @SerialName("websocket_notifications") val websocketNotifications: Boolean,
    @SerialName("projection_deltas") val projectionDeltas: Boolean = false,
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
data class ApiV3RecoverAccountRequest(
    val username: String,
    @SerialName("recovery_code") val recoveryCode: String,
    @SerialName("new_password") val newPassword: String,
)

@Serializable
data class ApiV3RecoveryCodes(
    @SerialName("recovery_codes") val recoveryCodes: List<String>,
    @SerialName("expires_in_days") val expiresInDays: Int,
)

@Serializable
data class ApiV3CreateGameRequest(
    @SerialName("operation_id") val operationId: String,
    @SerialName("ruleset_manifest_hash") val rulesetManifestHash: String,
    val setup: ApiV3GameSetup,
)

@Serializable
data class ApiV3GameMetadata(
    @SerialName("game_id") val gameId: String,
    @SerialName("committed_revision") val committedRevision: Long,
    @SerialName("canonical_state_hash") val canonicalStateHash: String,
    val role: String,
    @SerialName("civilization_id") val civilizationId: String? = null,
    @SerialName("lifecycle_status") val lifecycleStatus: String = "active",
)

@Serializable
data class ApiV3GameSummary(
    @SerialName("game_id") val gameId: String,
    @SerialName("committed_revision") val committedRevision: Long,
    @SerialName("canonical_state_hash") val canonicalStateHash: String,
    val role: String,
    @SerialName("civilization_id") val civilizationId: String? = null,
    val available: Boolean,
    @SerialName("lifecycle_status") val lifecycleStatus: String = "active",
)

@Serializable
data class ApiV3GamePage(
    val games: List<ApiV3GameSummary>,
    @SerialName("next_cursor") val nextCursor: String? = null,
)

@Serializable
data class ApiV3PlayerInvitation(
    @SerialName("game_id") val gameId: String,
    @SerialName("invitation_id") val invitationId: String,
    @SerialName("invited_by") val invitedBy: String,
    @SerialName("committed_revision") val committedRevision: Long,
    @SerialName("canonical_state_hash") val canonicalStateHash: String,
)

@Serializable
data class ApiV3Friend(
    val username: String,
)

@Serializable
data class ApiV3FriendRequest(
    @SerialName("request_id") val requestId: String,
    val username: String,
    val direction: String,
)

@Serializable
data class ApiV3SocialGraph(
    val friends: List<ApiV3Friend>,
    val requests: List<ApiV3FriendRequest>,
)

@Serializable
data class ApiV3CreateFriendRequest(
    @SerialName("request_id") val requestId: String,
    val username: String,
)

@Serializable
data class ApiV3GameChatMessage(
    @SerialName("message_id") val messageId: String,
    @SerialName("sender_username") val senderUsername: String,
    val body: String,
    @SerialName("created_at_millis") val createdAtMillis: Long,
)

@Serializable
data class ApiV3GameChatPage(
    val messages: List<ApiV3GameChatMessage>,
    @SerialName("next_cursor") val nextCursor: String? = null,
)

@Serializable
data class ApiV3PostGameChatRequest(
    @SerialName("message_id") val messageId: String,
    val body: String,
)

@Serializable
data class ApiV3InvitePlayerRequest(
    @SerialName("invitation_id") val invitationId: String,
    val username: String,
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
data class ApiV3ProjectionDeltaOperation(
    val path: String,
    val value: JsonElement,
)

@Serializable
data class ApiV3GameProjectionDelta(
    @SerialName("game_id") val gameId: String,
    @SerialName("projection_version") val projectionVersion: Int,
    @SerialName("base_revision") val baseRevision: Long,
    @SerialName("base_canonical_state_hash") val baseCanonicalStateHash: String,
    @SerialName("base_projection_hash") val baseProjectionHash: String,
    @SerialName("committed_revision") val committedRevision: Long,
    @SerialName("canonical_state_hash") val canonicalStateHash: String,
    @SerialName("projection_hash") val projectionHash: String,
    val operations: List<ApiV3ProjectionDeltaOperation>,
)

@Serializable
data class ApiV3SpectatorGameProjection(
    @SerialName("game_id") val gameId: String,
    @SerialName("projection_version") val projectionVersion: Int,
    @SerialName("committed_revision") val committedRevision: Long,
    @SerialName("canonical_state_hash") val canonicalStateHash: String,
    @SerialName("projection_hash") val projectionHash: String,
    val projection: SpectatorProjection,
)

@Serializable
data class ApiV3AddSpectatorRequest(val username: String)

@Serializable
data class ApiV3RevokeSpectatorRequest(
    @SerialName("operation_id") val operationId: String,
    val username: String,
)

@Serializable
data class ApiV3TransferOwnershipRequest(
    @SerialName("operation_id") val operationId: String,
    val username: String,
)

@Serializable
data class ApiV3GameAdminOperationRequest(
    @SerialName("operation_id") val operationId: String,
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
    @SerialName("escort_unit_id") val escortUnitId: Int? = null,
)

@Serializable
data class ApiV3MoveUnitTowardRequest(
    @SerialName("command_id") val commandId: String,
    @SerialName("expected_revision") val expectedRevision: Long,
    @SerialName("client_observed_state_hash") val clientObservedStateHash: String,
    @SerialName("unit_id") val unitId: Int,
    @SerialName("destination_x") val destinationX: Int,
    @SerialName("destination_y") val destinationY: Int,
    @SerialName("escort_unit_id") val escortUnitId: Int? = null,
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
data class ApiV3ManageConstructionQueuesRequest(
    @SerialName("command_id") val commandId: String,
    @SerialName("expected_revision") val expectedRevision: Long,
    @SerialName("client_observed_state_hash") val clientObservedStateHash: String,
    @SerialName("city_id") val cityId: String,
    @SerialName("construction_name") val constructionName: String,
    @SerialName("queue_index") val queueIndex: Int? = null,
    val action: ConstructionQueueAction,
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
data class ApiV3BuyCityTileBatchRequest(
    @SerialName("command_id") val commandId: String,
    @SerialName("expected_revision") val expectedRevision: Long,
    @SerialName("client_observed_state_hash") val clientObservedStateHash: String,
    @SerialName("city_id") val cityId: String,
    val ring: Int,
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
data class ApiV3UseGreatPersonUnitRequest(
    @SerialName("command_id") val commandId: String,
    @SerialName("expected_revision") val expectedRevision: Long,
    @SerialName("client_observed_state_hash") val clientObservedStateHash: String,
    @SerialName("unit_id") val unitId: Int,
    val action: GreatPersonUnitAction,
)

@Serializable
data class ApiV3GiftUnitRequest(
    @SerialName("command_id") val commandId: String,
    @SerialName("expected_revision") val expectedRevision: Long,
    @SerialName("client_observed_state_hash") val clientObservedStateHash: String,
    @SerialName("unit_id") val unitId: Int,
)

@Serializable
data class ApiV3AddUnitToCapitalProjectRequest(
    @SerialName("command_id") val commandId: String,
    @SerialName("expected_revision") val expectedRevision: Long,
    @SerialName("client_observed_state_hash") val clientObservedStateHash: String,
    @SerialName("unit_id") val unitId: Int,
)

@Serializable
data class ApiV3TransformUnitRequest(
    @SerialName("command_id") val commandId: String,
    @SerialName("expected_revision") val expectedRevision: Long,
    @SerialName("client_observed_state_hash") val clientObservedStateHash: String,
    @SerialName("unit_id") val unitId: Int,
    @SerialName("action_id") val actionId: String,
)

@Serializable
data class ApiV3TriggerUnitUniqueRequest(
    @SerialName("command_id") val commandId: String,
    @SerialName("expected_revision") val expectedRevision: Long,
    @SerialName("client_observed_state_hash") val clientObservedStateHash: String,
    @SerialName("unit_id") val unitId: Int,
    @SerialName("action_id") val actionId: String,
)

@Serializable
data class ApiV3CreateInstantImprovementRequest(
    @SerialName("command_id") val commandId: String,
    @SerialName("expected_revision") val expectedRevision: Long,
    @SerialName("client_observed_state_hash") val clientObservedStateHash: String,
    @SerialName("unit_id") val unitId: Int,
    @SerialName("action_id") val actionId: String,
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
data class ApiV3CityStateProtectionPromptResponseRequest(
    @SerialName("command_id") val commandId: String,
    @SerialName("expected_revision") val expectedRevision: Long,
    @SerialName("client_observed_state_hash") val clientObservedStateHash: String,
    @SerialName("prompt_id") val promptId: String,
    val response: CityStateProtectionResponse,
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
data class ApiV3MoveSpyRequest(
    @SerialName("command_id") val commandId: String,
    @SerialName("expected_revision") val expectedRevision: Long,
    @SerialName("client_observed_state_hash") val clientObservedStateHash: String,
    @SerialName("spy_name") val spyName: String,
    @SerialName("city_id") val cityId: String?,
)

@Serializable
data class ApiV3SetSpyCoupRequest(
    @SerialName("command_id") val commandId: String,
    @SerialName("expected_revision") val expectedRevision: Long,
    @SerialName("client_observed_state_hash") val clientObservedStateHash: String,
    @SerialName("spy_name") val spyName: String,
    val enabled: Boolean,
)

@Serializable
data class ApiV3ResolveEventChoiceRequest(
    @SerialName("command_id") val commandId: String,
    @SerialName("expected_revision") val expectedRevision: Long,
    @SerialName("client_observed_state_hash") val clientObservedStateHash: String,
    @SerialName("prompt_id") val promptId: String,
    @SerialName("choice_id") val choiceId: String,
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
    val append: Boolean = false,
)

@Serializable
data class ApiV3ManageResearchQueueRequest(
    @SerialName("command_id") val commandId: String,
    @SerialName("expected_revision") val expectedRevision: Long,
    @SerialName("client_observed_state_hash") val clientObservedStateHash: String,
    @SerialName("technology_name") val technologyName: String,
    @SerialName("queue_index") val queueIndex: Int,
    val action: ResearchQueueAction,
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
data class ApiV3AcknowledgeResearchCompletionRequest(
    @SerialName("command_id") val commandId: String,
    @SerialName("expected_revision") val expectedRevision: Long,
    @SerialName("client_observed_state_hash") val clientObservedStateHash: String,
    @SerialName("prompt_id") val promptId: String,
)

@Serializable
data class ApiV3EndTurnRequest(
    @SerialName("command_id") val commandId: String,
    @SerialName("expected_revision") val expectedRevision: Long,
    @SerialName("client_observed_state_hash") val clientObservedStateHash: String,
)

@Serializable
data class ApiV3ResignRequest(
    @SerialName("command_id") val commandId: String,
    @SerialName("expected_revision") val expectedRevision: Long,
    @SerialName("client_observed_state_hash") val clientObservedStateHash: String,
)

@Serializable
data class ApiV3ForceResignRequest(
    @SerialName("command_id") val commandId: String,
    @SerialName("expected_revision") val expectedRevision: Long,
    @SerialName("client_observed_state_hash") val clientObservedStateHash: String,
)

@Serializable
data class ApiV3KickMemberRequest(
    @SerialName("command_id") val commandId: String,
    @SerialName("expected_revision") val expectedRevision: Long,
    @SerialName("client_observed_state_hash") val clientObservedStateHash: String,
    val username: String,
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
