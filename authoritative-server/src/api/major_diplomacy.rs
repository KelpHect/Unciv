use super::*;

#[derive(Clone, Copy)]
enum PartnerAction {
    War,
    Denounce,
    Friendship,
}

async fn partner_command(
    state: AppState,
    headers: HeaderMap,
    game_id: uuid::Uuid,
    request: DiplomacyPartnerRequest,
    action: PartnerAction,
) -> Result<Json<unciv_authoritative_server::CommandAccepted>, ApiError> {
    if request.other_civilization_id.is_empty() || request.other_civilization_id.len() > 128 {
        return Err(ApiError::bad_request("invalid_command"));
    }
    let actor = authenticated_account(&state, &headers).await?;
    let command = match action {
        PartnerAction::War => GameCommand::DeclareWar {
            other_civilization_id: request.other_civilization_id,
        },
        PartnerAction::Denounce => GameCommand::DenounceCivilization {
            other_civilization_id: request.other_civilization_id,
        },
        PartnerAction::Friendship => GameCommand::OfferFriendship {
            other_civilization_id: request.other_civilization_id,
        },
    };
    let envelope = CommandEnvelope {
        protocol_version: PROTOCOL_VERSION,
        game_id,
        command_id: request.command_id,
        expected_revision: request.expected_revision,
        client_observed_state_hash: request.client_observed_state_hash,
        command,
    };
    Ok(Json(
        state
            .repository
            .execute_diplomacy_partner_command(&state.worker, actor.id, envelope)
            .await
            .map_err(game_error)?,
    ))
}

#[utoipa::path(post, path = "/api/v3/games/{game_id}/commands/declare-war", params(("game_id" = uuid::Uuid, Path)), security(("bearer_auth" = [])), request_body = DiplomacyPartnerRequest, responses((status = 200, body = unciv_authoritative_server::CommandAccepted), (status = 400, body = ErrorResponse), (status = 401, body = ErrorResponse), (status = 409, body = ErrorResponse), (status = 422, body = ErrorResponse)))]
pub(super) async fn declare_war(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(game_id): Path<uuid::Uuid>,
    Json(request): Json<DiplomacyPartnerRequest>,
) -> Result<Json<unciv_authoritative_server::CommandAccepted>, ApiError> {
    partner_command(state, headers, game_id, request, PartnerAction::War).await
}

#[utoipa::path(post, path = "/api/v3/games/{game_id}/commands/denounce-civilization", params(("game_id" = uuid::Uuid, Path)), security(("bearer_auth" = [])), request_body = DiplomacyPartnerRequest, responses((status = 200, body = unciv_authoritative_server::CommandAccepted), (status = 400, body = ErrorResponse), (status = 401, body = ErrorResponse), (status = 409, body = ErrorResponse), (status = 422, body = ErrorResponse)))]
pub(super) async fn denounce_civilization(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(game_id): Path<uuid::Uuid>,
    Json(request): Json<DiplomacyPartnerRequest>,
) -> Result<Json<unciv_authoritative_server::CommandAccepted>, ApiError> {
    partner_command(state, headers, game_id, request, PartnerAction::Denounce).await
}

#[utoipa::path(post, path = "/api/v3/games/{game_id}/commands/offer-friendship", params(("game_id" = uuid::Uuid, Path)), security(("bearer_auth" = [])), request_body = DiplomacyPartnerRequest, responses((status = 200, body = unciv_authoritative_server::CommandAccepted), (status = 400, body = ErrorResponse), (status = 401, body = ErrorResponse), (status = 409, body = ErrorResponse), (status = 422, body = ErrorResponse)))]
pub(super) async fn offer_friendship(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(game_id): Path<uuid::Uuid>,
    Json(request): Json<DiplomacyPartnerRequest>,
) -> Result<Json<unciv_authoritative_server::CommandAccepted>, ApiError> {
    partner_command(state, headers, game_id, request, PartnerAction::Friendship).await
}

#[utoipa::path(post, path = "/api/v3/games/{game_id}/commands/make-diplomatic-demand", params(("game_id" = uuid::Uuid, Path)), security(("bearer_auth" = [])), request_body = DiplomaticDemandRequest, responses((status = 200, body = unciv_authoritative_server::CommandAccepted), (status = 400, body = ErrorResponse), (status = 401, body = ErrorResponse), (status = 409, body = ErrorResponse), (status = 422, body = ErrorResponse)))]
pub(super) async fn make_diplomatic_demand(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(game_id): Path<uuid::Uuid>,
    Json(request): Json<DiplomaticDemandRequest>,
) -> Result<Json<unciv_authoritative_server::CommandAccepted>, ApiError> {
    if request.other_civilization_id.is_empty() || request.other_civilization_id.len() > 128 {
        return Err(ApiError::bad_request("invalid_command"));
    }
    let actor = authenticated_account(&state, &headers).await?;
    let envelope = CommandEnvelope {
        protocol_version: PROTOCOL_VERSION,
        game_id,
        command_id: request.command_id,
        expected_revision: request.expected_revision,
        client_observed_state_hash: request.client_observed_state_hash,
        command: GameCommand::MakeDiplomaticDemand {
            other_civilization_id: request.other_civilization_id,
            demand: request.demand,
        },
    };
    Ok(Json(
        state
            .repository
            .execute_make_diplomatic_demand(&state.worker, actor.id, envelope)
            .await
            .map_err(game_error)?,
    ))
}

#[utoipa::path(post, path = "/api/v3/games/{game_id}/commands/respond-to-diplomatic-prompt", params(("game_id" = uuid::Uuid, Path)), security(("bearer_auth" = [])), request_body = DiplomaticPromptResponseRequest, responses((status = 200, body = unciv_authoritative_server::CommandAccepted), (status = 400, body = ErrorResponse), (status = 401, body = ErrorResponse), (status = 409, body = ErrorResponse), (status = 422, body = ErrorResponse)))]
pub(super) async fn respond_to_diplomatic_prompt(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(game_id): Path<uuid::Uuid>,
    Json(request): Json<DiplomaticPromptResponseRequest>,
) -> Result<Json<unciv_authoritative_server::CommandAccepted>, ApiError> {
    if request.prompt_id.len() != 64
        || !request
            .prompt_id
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
        command: GameCommand::RespondToDiplomaticPrompt {
            prompt_id: request.prompt_id,
            accept: request.accept,
        },
    };
    Ok(Json(
        state
            .repository
            .execute_respond_to_diplomatic_prompt(&state.worker, actor.id, envelope)
            .await
            .map_err(game_error)?,
    ))
}

#[utoipa::path(post, path = "/api/v3/games/{game_id}/commands/respond-to-city-state-protection-prompt", params(("game_id" = uuid::Uuid, Path)), security(("bearer_auth" = [])), request_body = CityStateProtectionPromptResponseRequest, responses((status = 200, body = unciv_authoritative_server::CommandAccepted), (status = 400, body = ErrorResponse), (status = 401, body = ErrorResponse), (status = 409, body = ErrorResponse), (status = 422, body = ErrorResponse)))]
pub(super) async fn respond_to_city_state_protection_prompt(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(game_id): Path<uuid::Uuid>,
    Json(request): Json<CityStateProtectionPromptResponseRequest>,
) -> Result<Json<unciv_authoritative_server::CommandAccepted>, ApiError> {
    if request.prompt_id.len() != 64
        || !request
            .prompt_id
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
        command: GameCommand::RespondToCityStateProtectionPrompt {
            prompt_id: request.prompt_id,
            response: request.response,
        },
    };
    Ok(Json(
        state
            .repository
            .execute_respond_to_city_state_protection_prompt(&state.worker, actor.id, envelope)
            .await
            .map_err(game_error)?,
    ))
}
