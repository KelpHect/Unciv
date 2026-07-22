use super::*;

fn valid_identifier(value: &str) -> bool {
    !value.is_empty() && value.len() <= 128
}

#[utoipa::path(post, path = "/api/v3/games/{game_id}/commands/offer-trade", params(("game_id" = uuid::Uuid, Path)), security(("bearer_auth" = [])), request_body = OfferTradeRequest, responses((status = 200, body = unciv_authoritative_server::CommandAccepted), (status = 400, body = ErrorResponse), (status = 401, body = ErrorResponse), (status = 409, body = ErrorResponse), (status = 422, body = ErrorResponse)))]
pub(super) async fn offer_trade(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(game_id): Path<uuid::Uuid>,
    Json(request): Json<OfferTradeRequest>,
) -> Result<Json<unciv_authoritative_server::CommandAccepted>, ApiError> {
    if !valid_identifier(&request.other_civilization_id) {
        return Err(ApiError::bad_request("invalid_command"));
    }
    let actor = authenticated_account(&state, &headers).await?;
    let envelope = CommandEnvelope {
        protocol_version: PROTOCOL_VERSION,
        game_id,
        command_id: request.command_id,
        expected_revision: request.expected_revision,
        client_observed_state_hash: request.client_observed_state_hash,
        command: GameCommand::OfferTrade {
            other_civilization_id: request.other_civilization_id,
            trade: request.trade,
        },
    };
    Ok(Json(
        state
            .repository
            .execute_offer_trade(&state.worker, actor.id, envelope)
            .await
            .map_err(game_error)?,
    ))
}

#[utoipa::path(post, path = "/api/v3/games/{game_id}/commands/retract-trade-offer", params(("game_id" = uuid::Uuid, Path)), security(("bearer_auth" = [])), request_body = RetractTradeOfferRequest, responses((status = 200, body = unciv_authoritative_server::CommandAccepted), (status = 400, body = ErrorResponse), (status = 401, body = ErrorResponse), (status = 409, body = ErrorResponse), (status = 422, body = ErrorResponse)))]
pub(super) async fn retract_trade_offer(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(game_id): Path<uuid::Uuid>,
    Json(request): Json<RetractTradeOfferRequest>,
) -> Result<Json<unciv_authoritative_server::CommandAccepted>, ApiError> {
    if !valid_identifier(&request.other_civilization_id) {
        return Err(ApiError::bad_request("invalid_command"));
    }
    let actor = authenticated_account(&state, &headers).await?;
    let envelope = CommandEnvelope {
        protocol_version: PROTOCOL_VERSION,
        game_id,
        command_id: request.command_id,
        expected_revision: request.expected_revision,
        client_observed_state_hash: request.client_observed_state_hash,
        command: GameCommand::RetractTradeOffer {
            other_civilization_id: request.other_civilization_id,
        },
    };
    Ok(Json(
        state
            .repository
            .execute_retract_trade_offer(&state.worker, actor.id, envelope)
            .await
            .map_err(game_error)?,
    ))
}

async fn decide_trade(
    state: AppState,
    headers: HeaderMap,
    game_id: uuid::Uuid,
    request: TradeRequestDecisionRequest,
    accept: bool,
) -> Result<Json<unciv_authoritative_server::CommandAccepted>, ApiError> {
    if request.request_id.len() != 64
        || !request
            .request_id
            .bytes()
            .all(|byte| byte.is_ascii_hexdigit())
    {
        return Err(ApiError::bad_request("invalid_command"));
    }
    let actor = authenticated_account(&state, &headers).await?;
    let command = if accept {
        GameCommand::AcceptTrade {
            request_id: request.request_id,
        }
    } else {
        GameCommand::DeclineTrade {
            request_id: request.request_id,
        }
    };
    let envelope = CommandEnvelope {
        protocol_version: PROTOCOL_VERSION,
        game_id,
        command_id: request.command_id,
        expected_revision: request.expected_revision,
        client_observed_state_hash: request.client_observed_state_hash,
        command,
    };
    let accepted = if accept {
        state
            .repository
            .execute_accept_trade(&state.worker, actor.id, envelope)
            .await
    } else {
        state
            .repository
            .execute_decline_trade(&state.worker, actor.id, envelope)
            .await
    };
    Ok(Json(accepted.map_err(game_error)?))
}

#[utoipa::path(post, path = "/api/v3/games/{game_id}/commands/accept-trade", params(("game_id" = uuid::Uuid, Path)), security(("bearer_auth" = [])), request_body = TradeRequestDecisionRequest, responses((status = 200, body = unciv_authoritative_server::CommandAccepted), (status = 400, body = ErrorResponse), (status = 401, body = ErrorResponse), (status = 409, body = ErrorResponse), (status = 422, body = ErrorResponse)))]
pub(super) async fn accept_trade(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(game_id): Path<uuid::Uuid>,
    Json(request): Json<TradeRequestDecisionRequest>,
) -> Result<Json<unciv_authoritative_server::CommandAccepted>, ApiError> {
    decide_trade(state, headers, game_id, request, true).await
}

#[utoipa::path(post, path = "/api/v3/games/{game_id}/commands/decline-trade", params(("game_id" = uuid::Uuid, Path)), security(("bearer_auth" = [])), request_body = TradeRequestDecisionRequest, responses((status = 200, body = unciv_authoritative_server::CommandAccepted), (status = 400, body = ErrorResponse), (status = 401, body = ErrorResponse), (status = 409, body = ErrorResponse), (status = 422, body = ErrorResponse)))]
pub(super) async fn decline_trade(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(game_id): Path<uuid::Uuid>,
    Json(request): Json<TradeRequestDecisionRequest>,
) -> Result<Json<unciv_authoritative_server::CommandAccepted>, ApiError> {
    decide_trade(state, headers, game_id, request, false).await
}

#[utoipa::path(post, path = "/api/v3/games/{game_id}/commands/counter-trade", params(("game_id" = uuid::Uuid, Path)), security(("bearer_auth" = [])), request_body = CounterTradeRequest, responses((status = 200, body = unciv_authoritative_server::CommandAccepted), (status = 400, body = ErrorResponse), (status = 401, body = ErrorResponse), (status = 409, body = ErrorResponse), (status = 422, body = ErrorResponse)))]
pub(super) async fn counter_trade(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(game_id): Path<uuid::Uuid>,
    Json(request): Json<CounterTradeRequest>,
) -> Result<Json<unciv_authoritative_server::CommandAccepted>, ApiError> {
    if request.request_id.len() != 64
        || !request
            .request_id
            .bytes()
            .all(|byte| byte.is_ascii_hexdigit())
    {
        return Err(ApiError::bad_request("invalid_command"));
    }
    let actor = authenticated_account(&state, &headers).await?;
    let envelope = CommandEnvelope {
        protocol_version: PROTOCOL_VERSION,
        game_id,
        command_id: request.command_id,
        expected_revision: request.expected_revision,
        client_observed_state_hash: request.client_observed_state_hash,
        command: GameCommand::CounterTrade {
            request_id: request.request_id,
            trade: request.trade,
        },
    };
    Ok(Json(
        state
            .repository
            .execute_counter_trade(&state.worker, actor.id, envelope)
            .await
            .map_err(game_error)?,
    ))
}
