use super::*;

enum CityStateAction {
    Gift(u32),
    Protection(bool),
    Tribute(bool),
}

struct CityStateCommandRequest {
    command_id: uuid::Uuid,
    expected_revision: u64,
    observed_state_hash: Option<String>,
    city_state_id: String,
}

async fn execute(
    state: AppState,
    headers: HeaderMap,
    game_id: uuid::Uuid,
    request: CityStateCommandRequest,
    action: CityStateAction,
) -> Result<Json<unciv_authoritative_server::CommandAccepted>, ApiError> {
    if request.city_state_id.is_empty() || request.city_state_id.len() > 128 {
        return Err(ApiError::bad_request("invalid_command"));
    }
    let actor = authenticated_account(&state, &headers).await?;
    let command = match action {
        CityStateAction::Gift(amount) if matches!(amount, 250 | 500 | 1000) => {
            GameCommand::GiftCityStateGold {
                city_state_civilization_id: request.city_state_id,
                amount,
            }
        }
        CityStateAction::Gift(_) => return Err(ApiError::bad_request("invalid_command")),
        CityStateAction::Protection(protect) => GameCommand::SetCityStateProtection {
            city_state_civilization_id: request.city_state_id,
            protect,
        },
        CityStateAction::Tribute(worker) => GameCommand::DemandCityStateTribute {
            city_state_civilization_id: request.city_state_id,
            worker,
        },
    };
    let envelope = CommandEnvelope {
        protocol_version: PROTOCOL_VERSION,
        game_id,
        command_id: request.command_id,
        expected_revision: request.expected_revision,
        client_observed_state_hash: request.observed_state_hash,
        command,
    };
    Ok(Json(
        state
            .repository
            .execute_city_state_command(&state.worker, actor.id, envelope)
            .await
            .map_err(game_error)?,
    ))
}

#[utoipa::path(post, path = "/api/v3/games/{game_id}/commands/gift-city-state-gold", params(("game_id" = uuid::Uuid, Path)), security(("bearer_auth" = [])), request_body = CityStateGoldGiftRequest, responses((status = 200, body = unciv_authoritative_server::CommandAccepted), (status = 400, body = ErrorResponse), (status = 401, body = ErrorResponse), (status = 409, body = ErrorResponse), (status = 422, body = ErrorResponse)))]
pub(super) async fn gift_city_state_gold(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(game_id): Path<uuid::Uuid>,
    Json(request): Json<CityStateGoldGiftRequest>,
) -> Result<Json<unciv_authoritative_server::CommandAccepted>, ApiError> {
    execute(
        state,
        headers,
        game_id,
        CityStateCommandRequest {
            command_id: request.command_id,
            expected_revision: request.expected_revision,
            observed_state_hash: request.client_observed_state_hash,
            city_state_id: request.city_state_civilization_id,
        },
        CityStateAction::Gift(request.amount),
    )
    .await
}

#[utoipa::path(post, path = "/api/v3/games/{game_id}/commands/set-city-state-protection", params(("game_id" = uuid::Uuid, Path)), security(("bearer_auth" = [])), request_body = CityStateProtectionRequest, responses((status = 200, body = unciv_authoritative_server::CommandAccepted), (status = 400, body = ErrorResponse), (status = 401, body = ErrorResponse), (status = 409, body = ErrorResponse), (status = 422, body = ErrorResponse)))]
pub(super) async fn set_city_state_protection(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(game_id): Path<uuid::Uuid>,
    Json(request): Json<CityStateProtectionRequest>,
) -> Result<Json<unciv_authoritative_server::CommandAccepted>, ApiError> {
    execute(
        state,
        headers,
        game_id,
        CityStateCommandRequest {
            command_id: request.command_id,
            expected_revision: request.expected_revision,
            observed_state_hash: request.client_observed_state_hash,
            city_state_id: request.city_state_civilization_id,
        },
        CityStateAction::Protection(request.protect),
    )
    .await
}

#[utoipa::path(post, path = "/api/v3/games/{game_id}/commands/demand-city-state-tribute", params(("game_id" = uuid::Uuid, Path)), security(("bearer_auth" = [])), request_body = CityStateTributeRequest, responses((status = 200, body = unciv_authoritative_server::CommandAccepted), (status = 400, body = ErrorResponse), (status = 401, body = ErrorResponse), (status = 409, body = ErrorResponse), (status = 422, body = ErrorResponse)))]
pub(super) async fn demand_city_state_tribute(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(game_id): Path<uuid::Uuid>,
    Json(request): Json<CityStateTributeRequest>,
) -> Result<Json<unciv_authoritative_server::CommandAccepted>, ApiError> {
    execute(
        state,
        headers,
        game_id,
        CityStateCommandRequest {
            command_id: request.command_id,
            expected_revision: request.expected_revision,
            observed_state_hash: request.client_observed_state_hash,
            city_state_id: request.city_state_civilization_id,
        },
        CityStateAction::Tribute(request.worker),
    )
    .await
}
