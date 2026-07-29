use super::*;

#[derive(Serialize, ToSchema)]
pub(super) struct HealthResponse {
    pub(super) status: &'static str,
    pub(super) protocol_version: u16,
}

#[derive(Serialize, ToSchema)]
pub(super) struct ReadinessResponse {
    pub(super) status: &'static str,
    pub(super) protocol_version: u16,
    pub(super) postgres: &'static str,
    pub(super) engine_worker: &'static str,
}

#[derive(Serialize, ToSchema)]
pub(super) struct CapabilitiesResponse {
    pub(super) protocol_version: u16,
    pub(super) projection_version: u16,
    pub(super) commands: Vec<&'static str>,
    pub(super) whole_state_upload: bool,
    pub(super) websocket_notifications: bool,
    pub(super) projection_deltas: bool,
}

#[derive(Deserialize, ToSchema)]
#[serde(deny_unknown_fields)]
pub(super) struct CredentialsRequest {
    pub(super) username: String,
    pub(super) password: String,
}

#[derive(Serialize, ToSchema)]
pub(super) struct AccountResponse {
    pub(super) account_id: uuid::Uuid,
    pub(super) username: String,
}

#[derive(Serialize, ToSchema)]
pub(super) struct LoginResponse {
    pub(super) account: AccountResponse,
    pub(super) session_token: String,
}

#[derive(Serialize, ToSchema)]
pub(super) struct SessionResponse {
    pub(super) session_token: String,
}

#[derive(Deserialize, ToSchema)]
#[serde(deny_unknown_fields)]
pub(super) struct RecoverAccountRequest {
    pub(super) username: String,
    pub(super) recovery_code: String,
    pub(super) new_password: String,
}

#[derive(Serialize, ToSchema)]
pub(super) struct RecoveryCodesResponse {
    pub(super) recovery_codes: Vec<String>,
    pub(super) expires_in_days: u16,
}

#[derive(Deserialize, ToSchema)]
#[serde(deny_unknown_fields)]
pub(super) struct ChangePasswordRequest {
    pub(super) current_password: String,
    pub(super) new_password: String,
}

#[derive(Deserialize, ToSchema)]
#[serde(deny_unknown_fields)]
pub(super) struct ConfirmPasswordRequest {
    pub(super) password: String,
}

#[derive(Deserialize, ToSchema)]
#[serde(deny_unknown_fields)]
pub(super) struct CreateGameRequest {
    pub(super) operation_id: uuid::Uuid,
    pub(super) ruleset_manifest_hash: String,
    pub(super) display_name: String,
    pub(super) human_slots: u8,
    pub(super) password: Option<String>,
    pub(super) available_civilizations: Vec<String>,
    pub(super) setup: CreateGameSetupRequest,
}

#[derive(Serialize, ToSchema)]
pub(super) struct GameMetadataResponse {
    pub(super) game_id: uuid::Uuid,
    pub(super) committed_revision: u64,
    pub(super) canonical_state_hash: String,
    pub(super) role: String,
    pub(super) civilization_id: Option<String>,
    pub(super) lifecycle_status: String,
}

#[derive(Deserialize, utoipa::IntoParams)]
#[serde(deny_unknown_fields)]
#[into_params(parameter_in = Query)]
pub(super) struct ListGamesQuery {
    pub(super) after: Option<String>,
    pub(super) limit: Option<u32>,
}

#[derive(Deserialize, utoipa::IntoParams)]
#[serde(deny_unknown_fields)]
#[into_params(parameter_in = Query)]
pub(super) struct ProjectionDeltaQuery {
    pub(super) base_revision: u64,
    pub(super) base_canonical_state_hash: String,
    pub(super) base_projection_hash: String,
}

#[derive(Deserialize, ToSchema)]
#[serde(deny_unknown_fields)]
pub(super) struct AddSpectatorRequest {
    pub(super) username: String,
}

#[derive(Deserialize, ToSchema)]
#[serde(deny_unknown_fields)]
pub(super) struct RevokeSpectatorRequest {
    pub(super) operation_id: uuid::Uuid,
    pub(super) username: String,
}

#[derive(Deserialize, ToSchema)]
#[serde(deny_unknown_fields)]
pub(super) struct JoinGameRequest {
    pub(super) command_id: uuid::Uuid,
    pub(super) expected_revision: u64,
    pub(super) client_observed_state_hash: Option<String>,
    pub(super) civilization_id: String,
    pub(super) password: Option<String>,
}

#[derive(Deserialize, utoipa::IntoParams)]
#[serde(deny_unknown_fields)]
#[into_params(parameter_in = Query)]
pub(super) struct ListLobbiesQuery {
    pub(super) after: Option<String>,
    pub(super) limit: Option<u32>,
}

#[derive(Deserialize, ToSchema)]
#[serde(deny_unknown_fields)]
pub(super) struct SetLobbyReadyRequest {
    pub(super) expected_lobby_revision: u64,
    pub(super) ready: bool,
}

#[derive(Deserialize, ToSchema)]
#[serde(deny_unknown_fields)]
pub(super) struct StartLobbyRequest {
    pub(super) expected_lobby_revision: u64,
}

#[derive(Deserialize, ToSchema)]
#[serde(tag = "action", rename_all = "snake_case", deny_unknown_fields)]
pub(super) enum LobbyPasswordUpdateRequest {
    Keep,
    Clear,
    Replace { password: String },
}

#[derive(Deserialize, ToSchema)]
#[serde(deny_unknown_fields)]
pub(super) struct ReconfigureLobbyRequest {
    pub(super) operation_id: uuid::Uuid,
    pub(super) expected_lobby_revision: u64,
    pub(super) display_name: String,
    pub(super) human_slots: u8,
    pub(super) password: LobbyPasswordUpdateRequest,
    pub(super) setup: CreateGameSetupRequest,
}

#[derive(Deserialize, ToSchema)]
#[serde(deny_unknown_fields)]
pub(super) struct SelectLobbyFactionRequest {
    pub(super) operation_id: uuid::Uuid,
    pub(super) expected_lobby_revision: u64,
    pub(super) civilization_id: String,
}

#[derive(Deserialize, ToSchema)]
#[serde(deny_unknown_fields)]
pub(super) struct MoveUnitRequest {
    pub(super) command_id: uuid::Uuid,
    pub(super) expected_revision: u64,
    pub(super) client_observed_state_hash: Option<String>,
    pub(super) unit_id: i32,
    pub(super) destination_x: i32,
    pub(super) destination_y: i32,
    pub(super) escort_unit_id: Option<i32>,
}

#[derive(Deserialize, ToSchema)]
#[serde(deny_unknown_fields)]
pub(super) struct MoveUnitTowardRequest {
    pub(super) command_id: uuid::Uuid,
    pub(super) expected_revision: u64,
    pub(super) client_observed_state_hash: Option<String>,
    pub(super) unit_id: i32,
    pub(super) destination_x: i32,
    pub(super) destination_y: i32,
    pub(super) escort_unit_id: Option<i32>,
}

#[derive(Deserialize, ToSchema)]
#[serde(deny_unknown_fields)]
pub(super) struct CancelUnitMovementOrderRequest {
    pub(super) command_id: uuid::Uuid,
    pub(super) expected_revision: u64,
    pub(super) client_observed_state_hash: Option<String>,
    pub(super) unit_id: i32,
}

#[derive(Deserialize, ToSchema)]
#[serde(deny_unknown_fields)]
pub(super) struct SetUnitExplorationRequest {
    pub(super) command_id: uuid::Uuid,
    pub(super) expected_revision: u64,
    pub(super) client_observed_state_hash: Option<String>,
    pub(super) unit_id: i32,
    pub(super) enabled: bool,
}

#[derive(Deserialize, ToSchema)]
#[serde(deny_unknown_fields)]
pub(super) struct SetUnitAutomationRequest {
    pub(super) command_id: uuid::Uuid,
    pub(super) expected_revision: u64,
    pub(super) client_observed_state_hash: Option<String>,
    pub(super) unit_id: i32,
    pub(super) enabled: bool,
}

#[derive(Deserialize, ToSchema)]
#[serde(deny_unknown_fields)]
pub(super) struct SetUnitPostureRequest {
    pub(super) command_id: uuid::Uuid,
    pub(super) expected_revision: u64,
    pub(super) client_observed_state_hash: Option<String>,
    pub(super) unit_id: i32,
    pub(super) posture: unciv_authoritative_server::UnitPosture,
}

#[derive(Deserialize, ToSchema)]
#[serde(deny_unknown_fields)]
pub(super) struct DisbandUnitRequest {
    pub(super) command_id: uuid::Uuid,
    pub(super) expected_revision: u64,
    pub(super) client_observed_state_hash: Option<String>,
    pub(super) unit_id: i32,
}

#[derive(Deserialize, ToSchema)]
#[serde(deny_unknown_fields)]
pub(super) struct PillageTileRequest {
    pub(super) command_id: uuid::Uuid,
    pub(super) expected_revision: u64,
    pub(super) client_observed_state_hash: Option<String>,
    pub(super) unit_id: i32,
}

#[derive(Deserialize, ToSchema)]
#[serde(deny_unknown_fields)]
pub(super) struct FoundCityRequest {
    pub(super) command_id: uuid::Uuid,
    pub(super) expected_revision: u64,
    pub(super) client_observed_state_hash: Option<String>,
    pub(super) unit_id: i32,
}

#[derive(Deserialize, ToSchema)]
#[serde(deny_unknown_fields)]
pub(super) struct ParadropUnitRequest {
    pub(super) command_id: uuid::Uuid,
    pub(super) expected_revision: u64,
    pub(super) client_observed_state_hash: Option<String>,
    pub(super) unit_id: i32,
    pub(super) destination_x: i32,
    pub(super) destination_y: i32,
}

#[derive(Deserialize, ToSchema)]
#[serde(deny_unknown_fields)]
pub(super) struct AttackWithUnitRequest {
    pub(super) command_id: uuid::Uuid,
    pub(super) expected_revision: u64,
    pub(super) client_observed_state_hash: Option<String>,
    pub(super) unit_id: i32,
    pub(super) target_x: i32,
    pub(super) target_y: i32,
}

#[derive(Deserialize, ToSchema)]
#[serde(deny_unknown_fields)]
pub(super) struct BombardWithCityRequest {
    pub(super) command_id: uuid::Uuid,
    pub(super) expected_revision: u64,
    pub(super) client_observed_state_hash: Option<String>,
    pub(super) city_id: String,
    pub(super) target_x: i32,
    pub(super) target_y: i32,
}

#[derive(Deserialize, ToSchema)]
#[serde(deny_unknown_fields)]
pub(super) struct LaunchNuclearStrikeRequest {
    pub(super) command_id: uuid::Uuid,
    pub(super) expected_revision: u64,
    pub(super) client_observed_state_hash: Option<String>,
    pub(super) unit_id: i32,
    pub(super) target_x: i32,
    pub(super) target_y: i32,
}

#[derive(Deserialize, ToSchema)]
#[serde(deny_unknown_fields)]
pub(super) struct AirSweepRequest {
    pub(super) command_id: uuid::Uuid,
    pub(super) expected_revision: u64,
    pub(super) client_observed_state_hash: Option<String>,
    pub(super) unit_id: i32,
    pub(super) target_x: i32,
    pub(super) target_y: i32,
}

#[derive(Deserialize, ToSchema)]
#[serde(deny_unknown_fields)]
pub(super) struct UpgradeUnitsRequest {
    pub(super) command_id: uuid::Uuid,
    pub(super) expected_revision: u64,
    pub(super) client_observed_state_hash: Option<String>,
    pub(super) unit_ids: Vec<i32>,
    pub(super) target_unit_name: String,
}

#[derive(Deserialize, ToSchema)]
#[serde(deny_unknown_fields)]
pub(super) struct PromoteUnitRequest {
    pub(super) command_id: uuid::Uuid,
    pub(super) expected_revision: u64,
    pub(super) client_observed_state_hash: Option<String>,
    pub(super) unit_id: i32,
    pub(super) promotion_names: Vec<String>,
    pub(super) save_as_city_default: bool,
}

#[derive(Deserialize, ToSchema)]
#[serde(deny_unknown_fields)]
pub(super) struct SetCityUnitPromotionPreferenceRequest {
    pub(super) command_id: uuid::Uuid,
    pub(super) expected_revision: u64,
    pub(super) client_observed_state_hash: Option<String>,
    pub(super) city_id: String,
    pub(super) base_unit_name: String,
    pub(super) enabled: bool,
}

#[derive(Deserialize, ToSchema)]
#[serde(deny_unknown_fields)]
pub(super) struct RenameUnitRequest {
    pub(super) command_id: uuid::Uuid,
    pub(super) expected_revision: u64,
    pub(super) client_observed_state_hash: Option<String>,
    pub(super) unit_id: i32,
    pub(super) instance_name: Option<String>,
}

#[derive(Deserialize, ToSchema)]
#[serde(deny_unknown_fields)]
pub(super) struct SetTileImprovementOrderRequest {
    pub(super) command_id: uuid::Uuid,
    pub(super) expected_revision: u64,
    pub(super) client_observed_state_hash: Option<String>,
    pub(super) unit_id: i32,
    pub(super) improvement_name: Option<String>,
    pub(super) queued_improvement_name: Option<String>,
}

#[derive(Deserialize, ToSchema)]
#[serde(deny_unknown_fields)]
pub(super) struct SetRoadConnectionOrderRequest {
    pub(super) command_id: uuid::Uuid,
    pub(super) expected_revision: u64,
    pub(super) client_observed_state_hash: Option<String>,
    pub(super) unit_id: i32,
    pub(super) destination_x: Option<i32>,
    pub(super) destination_y: Option<i32>,
}

#[derive(Deserialize, ToSchema)]
#[serde(deny_unknown_fields)]
pub(super) struct SwapUnitsRequest {
    pub(super) command_id: uuid::Uuid,
    pub(super) expected_revision: u64,
    pub(super) client_observed_state_hash: Option<String>,
    pub(super) unit_id: i32,
    pub(super) destination_x: i32,
    pub(super) destination_y: i32,
}

#[derive(Deserialize, ToSchema)]
#[serde(deny_unknown_fields)]
pub(super) struct SetCityGovernanceRequest {
    pub(super) command_id: uuid::Uuid,
    pub(super) expected_revision: u64,
    pub(super) client_observed_state_hash: Option<String>,
    pub(super) city_id: String,
    pub(super) action: CityGovernanceAction,
}

#[derive(Deserialize, ToSchema)]
#[serde(deny_unknown_fields)]
pub(super) struct ResolveCityDispositionRequest {
    pub(super) command_id: uuid::Uuid,
    pub(super) expected_revision: u64,
    pub(super) client_observed_state_hash: Option<String>,
    pub(super) city_id: String,
    pub(super) action: CityDispositionAction,
}

#[derive(Deserialize, ToSchema)]
#[serde(deny_unknown_fields)]
pub(super) struct CastDiplomaticVoteRequest {
    pub(super) command_id: uuid::Uuid,
    pub(super) expected_revision: u64,
    pub(super) client_observed_state_hash: Option<String>,
    pub(super) candidate_civilization_id: Option<String>,
}

#[derive(Deserialize, ToSchema)]
#[serde(deny_unknown_fields)]
pub(super) struct ChooseGreatPersonRequest {
    pub(super) command_id: uuid::Uuid,
    pub(super) expected_revision: u64,
    pub(super) client_observed_state_hash: Option<String>,
    pub(super) unit_name: String,
}

#[derive(Deserialize, ToSchema)]
#[serde(deny_unknown_fields)]
pub(super) struct UseReligiousUnitRequest {
    pub(super) command_id: uuid::Uuid,
    pub(super) expected_revision: u64,
    pub(super) client_observed_state_hash: Option<String>,
    pub(super) unit_id: i32,
    pub(super) action: unciv_authoritative_server::ReligiousUnitAction,
}

#[derive(Deserialize, ToSchema)]
#[serde(deny_unknown_fields)]
pub(super) struct UseGreatPersonUnitRequest {
    pub(super) command_id: uuid::Uuid,
    pub(super) expected_revision: u64,
    pub(super) client_observed_state_hash: Option<String>,
    pub(super) unit_id: i32,
    pub(super) action: unciv_authoritative_server::GreatPersonUnitAction,
}

#[derive(Deserialize, ToSchema)]
#[serde(deny_unknown_fields)]
pub(super) struct GiftUnitRequest {
    pub(super) command_id: uuid::Uuid,
    pub(super) expected_revision: u64,
    pub(super) client_observed_state_hash: Option<String>,
    pub(super) unit_id: i32,
}

#[derive(Deserialize, ToSchema)]
#[serde(deny_unknown_fields)]
pub(super) struct TransformUnitRequest {
    pub(super) command_id: uuid::Uuid,
    pub(super) expected_revision: u64,
    pub(super) client_observed_state_hash: Option<String>,
    pub(super) unit_id: i32,
    pub(super) action_id: String,
}

#[derive(Deserialize, ToSchema)]
#[serde(deny_unknown_fields)]
pub(super) struct TriggerUnitUniqueRequest {
    pub(super) command_id: uuid::Uuid,
    pub(super) expected_revision: u64,
    pub(super) client_observed_state_hash: Option<String>,
    pub(super) unit_id: i32,
    pub(super) action_id: String,
}

#[derive(Deserialize, ToSchema)]
#[serde(deny_unknown_fields)]
pub(super) struct CreateInstantImprovementRequest {
    pub(super) command_id: uuid::Uuid,
    pub(super) expected_revision: u64,
    pub(super) client_observed_state_hash: Option<String>,
    pub(super) unit_id: i32,
    pub(super) action_id: String,
}

#[derive(Deserialize, ToSchema)]
#[serde(deny_unknown_fields)]
pub(super) struct ChooseReligiousBeliefsRequest {
    pub(super) command_id: uuid::Uuid,
    pub(super) expected_revision: u64,
    pub(super) client_observed_state_hash: Option<String>,
    pub(super) belief_names: Vec<String>,
    pub(super) religion_icon_name: Option<String>,
    pub(super) religion_display_name: Option<String>,
}

#[derive(Deserialize, ToSchema)]
#[serde(deny_unknown_fields)]
pub(super) struct OfferTradeRequest {
    pub(super) command_id: uuid::Uuid,
    pub(super) expected_revision: u64,
    pub(super) client_observed_state_hash: Option<String>,
    pub(super) other_civilization_id: String,
    pub(super) trade: unciv_authoritative_server::projection::ProjectedTrade,
}

#[derive(Deserialize, ToSchema)]
#[serde(deny_unknown_fields)]
pub(super) struct RetractTradeOfferRequest {
    pub(super) command_id: uuid::Uuid,
    pub(super) expected_revision: u64,
    pub(super) client_observed_state_hash: Option<String>,
    pub(super) other_civilization_id: String,
}

#[derive(Deserialize, ToSchema)]
#[serde(deny_unknown_fields)]
pub(super) struct TradeRequestDecisionRequest {
    pub(super) command_id: uuid::Uuid,
    pub(super) expected_revision: u64,
    pub(super) client_observed_state_hash: Option<String>,
    pub(super) request_id: String,
}

#[derive(Deserialize, ToSchema)]
#[serde(deny_unknown_fields)]
pub(super) struct CounterTradeRequest {
    pub(super) command_id: uuid::Uuid,
    pub(super) expected_revision: u64,
    pub(super) client_observed_state_hash: Option<String>,
    pub(super) request_id: String,
    pub(super) trade: unciv_authoritative_server::projection::ProjectedTrade,
}

#[derive(Deserialize, ToSchema)]
#[serde(deny_unknown_fields)]
pub(super) struct DiplomacyPartnerRequest {
    pub(super) command_id: uuid::Uuid,
    pub(super) expected_revision: u64,
    pub(super) client_observed_state_hash: Option<String>,
    pub(super) other_civilization_id: String,
}

#[derive(Deserialize, ToSchema)]
#[serde(deny_unknown_fields)]
pub(super) struct DiplomaticDemandRequest {
    pub(super) command_id: uuid::Uuid,
    pub(super) expected_revision: u64,
    pub(super) client_observed_state_hash: Option<String>,
    pub(super) other_civilization_id: String,
    pub(super) demand: unciv_authoritative_server::projection::DiplomaticDemand,
}

#[derive(Deserialize, ToSchema)]
#[serde(deny_unknown_fields)]
pub(super) struct DiplomaticPromptResponseRequest {
    pub(super) command_id: uuid::Uuid,
    pub(super) expected_revision: u64,
    pub(super) client_observed_state_hash: Option<String>,
    pub(super) prompt_id: String,
    pub(super) accept: bool,
}

#[derive(Deserialize, ToSchema)]
#[serde(deny_unknown_fields)]
pub(super) struct CityStateProtectionPromptResponseRequest {
    pub(super) command_id: uuid::Uuid,
    pub(super) expected_revision: u64,
    pub(super) client_observed_state_hash: Option<String>,
    pub(super) prompt_id: String,
    pub(super) response: unciv_authoritative_server::projection::CityStateProtectionResponse,
}

#[derive(Deserialize, ToSchema)]
#[serde(deny_unknown_fields)]
pub(super) struct CityStateGoldGiftRequest {
    pub(super) command_id: uuid::Uuid,
    pub(super) expected_revision: u64,
    pub(super) client_observed_state_hash: Option<String>,
    pub(super) city_state_civilization_id: String,
    pub(super) amount: u32,
}

#[derive(Deserialize, ToSchema)]
#[serde(deny_unknown_fields)]
pub(super) struct CityStateProtectionRequest {
    pub(super) command_id: uuid::Uuid,
    pub(super) expected_revision: u64,
    pub(super) client_observed_state_hash: Option<String>,
    pub(super) city_state_civilization_id: String,
    pub(super) protect: bool,
}

#[derive(Deserialize, ToSchema)]
#[serde(deny_unknown_fields)]
pub(super) struct CityStateTributeRequest {
    pub(super) command_id: uuid::Uuid,
    pub(super) expected_revision: u64,
    pub(super) client_observed_state_hash: Option<String>,
    pub(super) city_state_civilization_id: String,
    pub(super) worker: bool,
}

#[derive(Deserialize, ToSchema)]
#[serde(deny_unknown_fields)]
pub(super) struct CityStateImprovementGiftRequest {
    pub(super) command_id: uuid::Uuid,
    pub(super) expected_revision: u64,
    pub(super) client_observed_state_hash: Option<String>,
    pub(super) city_state_civilization_id: String,
    pub(super) x: i32,
    pub(super) y: i32,
    pub(super) improvement_name: String,
}

#[derive(Deserialize, ToSchema)]
#[serde(deny_unknown_fields)]
pub(super) struct CityStatePeaceRequest {
    pub(super) command_id: uuid::Uuid,
    pub(super) expected_revision: u64,
    pub(super) client_observed_state_hash: Option<String>,
    pub(super) city_state_civilization_id: String,
}

#[derive(Deserialize, ToSchema)]
#[serde(deny_unknown_fields)]
pub(super) struct CityStateMarriageRequest {
    pub(super) command_id: uuid::Uuid,
    pub(super) expected_revision: u64,
    pub(super) client_observed_state_hash: Option<String>,
    pub(super) city_state_civilization_id: String,
}

#[derive(Deserialize, ToSchema)]
#[serde(deny_unknown_fields)]
pub(super) struct MoveSpyRequest {
    pub(super) command_id: uuid::Uuid,
    pub(super) expected_revision: u64,
    pub(super) client_observed_state_hash: Option<String>,
    pub(super) spy_name: String,
    pub(super) city_id: Option<String>,
}

#[derive(Deserialize, ToSchema)]
#[serde(deny_unknown_fields)]
pub(super) struct SetSpyCoupRequest {
    pub(super) command_id: uuid::Uuid,
    pub(super) expected_revision: u64,
    pub(super) client_observed_state_hash: Option<String>,
    pub(super) spy_name: String,
    pub(super) enabled: bool,
}

#[derive(Deserialize, ToSchema)]
#[serde(deny_unknown_fields)]
pub(super) struct ResolveEventChoiceRequest {
    pub(super) command_id: uuid::Uuid,
    pub(super) expected_revision: u64,
    pub(super) client_observed_state_hash: Option<String>,
    pub(super) prompt_id: String,
    pub(super) choice_id: String,
}

#[derive(Deserialize, ToSchema)]
#[serde(deny_unknown_fields)]
pub(super) struct SetCityTileAssignmentRequest {
    pub(super) command_id: uuid::Uuid,
    pub(super) expected_revision: u64,
    pub(super) client_observed_state_hash: Option<String>,
    pub(super) city_id: String,
    pub(super) x: i32,
    pub(super) y: i32,
    pub(super) assignment: CityTileAssignment,
}

#[derive(Deserialize, ToSchema)]
#[serde(deny_unknown_fields)]
pub(super) struct SetSpecialistCountRequest {
    pub(super) command_id: uuid::Uuid,
    pub(super) expected_revision: u64,
    pub(super) client_observed_state_hash: Option<String>,
    pub(super) city_id: String,
    pub(super) specialist_name: String,
    pub(super) count: u32,
}

#[derive(Deserialize, ToSchema)]
#[serde(deny_unknown_fields)]
pub(super) struct SetManualSpecialistsRequest {
    pub(super) command_id: uuid::Uuid,
    pub(super) expected_revision: u64,
    pub(super) client_observed_state_hash: Option<String>,
    pub(super) city_id: String,
    pub(super) enabled: bool,
}

#[derive(Deserialize, ToSchema)]
#[serde(deny_unknown_fields)]
pub(super) struct ResetCitizensRequest {
    pub(super) command_id: uuid::Uuid,
    pub(super) expected_revision: u64,
    pub(super) client_observed_state_hash: Option<String>,
    pub(super) city_id: String,
}

#[derive(Deserialize, ToSchema)]
#[serde(deny_unknown_fields)]
pub(super) struct SetAvoidGrowthRequest {
    pub(super) command_id: uuid::Uuid,
    pub(super) expected_revision: u64,
    pub(super) client_observed_state_hash: Option<String>,
    pub(super) city_id: String,
    pub(super) enabled: bool,
}

#[derive(Deserialize, ToSchema)]
#[serde(deny_unknown_fields)]
pub(super) struct SetCitizenFocusRequest {
    pub(super) command_id: uuid::Uuid,
    pub(super) expected_revision: u64,
    pub(super) client_observed_state_hash: Option<String>,
    pub(super) city_id: String,
    pub(super) focus: unciv_authoritative_server::CitizenFocus,
}

#[derive(Deserialize, ToSchema)]
#[serde(deny_unknown_fields)]
pub(super) struct SetResearchPathRequest {
    pub(super) command_id: uuid::Uuid,
    pub(super) expected_revision: u64,
    pub(super) client_observed_state_hash: Option<String>,
    pub(super) technology_name: String,
    pub(super) append: bool,
}

#[derive(Deserialize, ToSchema)]
#[serde(deny_unknown_fields)]
pub(super) struct AdoptPolicyRequest {
    pub(super) command_id: uuid::Uuid,
    pub(super) expected_revision: u64,
    pub(super) client_observed_state_hash: Option<String>,
    pub(super) policy_name: String,
}

#[derive(Deserialize, ToSchema)]
#[serde(deny_unknown_fields)]
pub(super) struct ChooseFreeTechnologyRequest {
    pub(super) command_id: uuid::Uuid,
    pub(super) expected_revision: u64,
    pub(super) client_observed_state_hash: Option<String>,
    pub(super) technology_name: String,
}

#[derive(Deserialize, ToSchema)]
#[serde(deny_unknown_fields)]
pub(super) struct AcknowledgeResearchCompletionRequest {
    pub(super) command_id: uuid::Uuid,
    pub(super) expected_revision: u64,
    pub(super) client_observed_state_hash: Option<String>,
    pub(super) prompt_id: String,
}

#[derive(Serialize, ToSchema)]
pub(super) struct ErrorResponse {
    pub(super) code: &'static str,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub(super) current_revision: Option<u64>,
}
