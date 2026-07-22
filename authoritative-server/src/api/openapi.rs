use super::*;

#[derive(OpenApi)]
#[openapi(
    paths(
        health,
        capabilities,
        openapi_document,
        register,
        login,
        refresh_session,
        logout,
        change_password,
        disable_account,
        delete_account,
        list_games,
        create_game,
        game_metadata,
        game_projection,
        websocket_notifications,
        join_game,
        end_turn,
        move_unit,
        move_unit_toward,
        cancel_unit_movement_order,
        set_unit_exploration,
        set_unit_automation,
        set_unit_posture,
        disband_unit,
        pillage_tile,
        found_city,
        paradrop_unit,
        attack_with_unit,
        bombard_with_city,
        launch_nuclear_strike,
        upgrade_units,
        promote_unit,
        set_city_unit_promotion_preference,
        rename_unit,
        set_tile_improvement_order,
        set_road_connection_order,
        swap_units,
        queue_construction,
        queue_construction_at_tile,
        set_perpetual_construction,
        remove_construction,
        move_construction,
        purchase_construction,
        purchase_construction_at_tile,
        buy_city_tile,
        set_city_tile_assignment,
        set_specialist_count,
        set_manual_specialists,
        reset_citizens,
        set_avoid_growth,
        set_citizen_focus,
        set_research_path,
        adopt_policy,
        choose_free_technology
    ),
    components(schemas(
        HealthResponse,
        CapabilitiesResponse,
        CredentialsRequest,
        AccountResponse,
        LoginResponse,
        SessionResponse,
        ChangePasswordRequest,
        ConfirmPasswordRequest,
        CreateGameRequest,
        GameMetadataResponse,
        EndTurnRequest,
        JoinGameRequest,
        MoveUnitRequest,
        MoveUnitTowardRequest,
        CancelUnitMovementOrderRequest,
        SetUnitExplorationRequest,
        SetUnitAutomationRequest,
        SetUnitPostureRequest,
        DisbandUnitRequest,
        PillageTileRequest,
        FoundCityRequest,
        ParadropUnitRequest,
        AttackWithUnitRequest,
        BombardWithCityRequest,
        LaunchNuclearStrikeRequest,
        UpgradeUnitsRequest,
        PromoteUnitRequest,
        SetCityUnitPromotionPreferenceRequest,
        RenameUnitRequest,
        SetTileImprovementOrderRequest,
        SetRoadConnectionOrderRequest,
        unciv_authoritative_server::UnitPosture,
        SwapUnitsRequest,
        QueueConstructionRequest,
        QueueConstructionAtTileRequest,
        SetPerpetualConstructionRequest,
        RemoveConstructionRequest,
        MoveConstructionRequest,
        PurchaseConstructionRequest,
        PurchaseConstructionAtTileRequest,
        BuyCityTileRequest,
        SetCityTileAssignmentRequest,
        SetSpecialistCountRequest,
        SetManualSpecialistsRequest,
        ResetCitizensRequest,
        SetAvoidGrowthRequest,
        SetCitizenFocusRequest,
        unciv_authoritative_server::CityTileAssignment,
        SetResearchPathRequest,
        AdoptPolicyRequest,
        ChooseFreeTechnologyRequest,
        ErrorResponse,
        unciv_authoritative_server::CommandAccepted,
        unciv_authoritative_server::postgres::GameSummary,
        unciv_authoritative_server::postgres::GamePage,
        unciv_authoritative_server::postgres::GameProjection,
        unciv_authoritative_server::notifications::RevisionNotification,
        unciv_authoritative_server::projection::PlayerProjection,
        unciv_authoritative_server::projection::ProjectedCity,
        unciv_authoritative_server::projection::ProjectedCityTile,
        unciv_authoritative_server::projection::ProjectedSpecialist,
        unciv_authoritative_server::projection::ProjectedUnitPromotionPreference,
        unciv_authoritative_server::projection::CitizenFocus,
        unciv_authoritative_server::projection::ProjectedUnit,
        unciv_authoritative_server::projection::ProjectedTileVisibility
    )),
    modifiers(&SecurityAddon),
    tags((name = "authoritative-multiplayer-v3", description = "Server-authoritative Unciv multiplayer API v3"))
)]
pub(super) struct ApiDoc;

pub(super) struct SecurityAddon;

impl Modify for SecurityAddon {
    fn modify(&self, openapi: &mut utoipa::openapi::OpenApi) {
        use utoipa::openapi::security::{HttpAuthScheme, HttpBuilder, SecurityScheme};
        if let Some(components) = openapi.components.as_mut() {
            components.add_security_scheme(
                "bearer_auth",
                SecurityScheme::Http(
                    HttpBuilder::new()
                        .scheme(HttpAuthScheme::Bearer)
                        .description(Some("Opaque revocable API-v3 session token"))
                        .build(),
                ),
            );
        }
    }
}

#[utoipa::path(get, path = "/healthz", responses((status = 200, body = HealthResponse)))]
pub(super) async fn health() -> Json<HealthResponse> {
    Json(HealthResponse {
        status: "ok",
        protocol_version: PROTOCOL_VERSION,
    })
}

#[utoipa::path(get, path = "/api/v3/capabilities", responses((status = 200, body = CapabilitiesResponse)))]
pub(super) async fn capabilities() -> Json<CapabilitiesResponse> {
    Json(CapabilitiesResponse {
        protocol_version: PROTOCOL_VERSION,
        projection_version: PROJECTION_VERSION,
        commands: vec![
            "join_game",
            "move_unit",
            "move_unit_toward",
            "cancel_unit_movement_order",
            "set_unit_exploration",
            "set_unit_automation",
            "set_unit_posture",
            "disband_unit",
            "pillage_tile",
            "found_city",
            "paradrop_unit",
            "attack_with_unit",
            "bombard_with_city",
            "launch_nuclear_strike",
            "upgrade_units",
            "promote_unit",
            "set_city_unit_promotion_preference",
            "rename_unit",
            "set_tile_improvement_order",
            "set_road_connection_order",
            "swap_units",
            "queue_construction",
            "queue_construction_at_tile",
            "set_perpetual_construction",
            "remove_construction",
            "move_construction",
            "purchase_construction",
            "purchase_construction_at_tile",
            "buy_city_tile",
            "set_city_tile_assignment",
            "set_specialist_count",
            "set_manual_specialists",
            "reset_citizens",
            "set_avoid_growth",
            "set_citizen_focus",
            "set_research_path",
            "adopt_policy",
            "choose_free_technology",
            "end_turn",
        ],
        whole_state_upload: false,
        websocket_notifications: true,
    })
}

#[utoipa::path(
    get,
    path = "/api/v3/openapi.json",
    responses((status = 200, description = "Generated OpenAPI 3.1 contract", body = serde_json::Value))
)]
pub(super) async fn openapi_document() -> Json<utoipa::openapi::OpenApi> {
    Json(ApiDoc::openapi())
}
