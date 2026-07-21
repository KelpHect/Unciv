use super::*;

#[utoipa::path(
    post,
    path = "/api/v3/games/{game_id}/commands/set-avoid-growth",
    params(("game_id" = uuid::Uuid, Path)), security(("bearer_auth" = [])),
    request_body = SetAvoidGrowthRequest,
    responses(
        (status = 200, body = unciv_authoritative_server::CommandAccepted),
        (status = 400, body = ErrorResponse), (status = 401, body = ErrorResponse),
        (status = 403, body = ErrorResponse), (status = 404, body = ErrorResponse),
        (status = 409, body = ErrorResponse), (status = 422, body = ErrorResponse),
        (status = 503, body = ErrorResponse)
    )
)]
pub(super) async fn set_avoid_growth(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(game_id): Path<uuid::Uuid>,
    Json(request): Json<SetAvoidGrowthRequest>,
) -> Result<Json<unciv_authoritative_server::CommandAccepted>, ApiError> {
    validate_city_id(&request.city_id)?;
    let actor = authenticated_account(&state, &headers).await?;
    let accepted = state
        .repository
        .execute_set_avoid_growth(
            &state.worker,
            actor.id,
            CommandEnvelope {
                protocol_version: PROTOCOL_VERSION,
                game_id,
                command_id: request.command_id,
                expected_revision: request.expected_revision,
                client_observed_state_hash: request.client_observed_state_hash,
                command: GameCommand::SetAvoidGrowth {
                    city_id: request.city_id,
                    enabled: request.enabled,
                },
            },
        )
        .await
        .map_err(game_error)?;
    Ok(Json(accepted))
}

#[utoipa::path(
    post,
    path = "/api/v3/games/{game_id}/commands/set-citizen-focus",
    params(("game_id" = uuid::Uuid, Path)), security(("bearer_auth" = [])),
    request_body = SetCitizenFocusRequest,
    responses(
        (status = 200, body = unciv_authoritative_server::CommandAccepted),
        (status = 400, body = ErrorResponse), (status = 401, body = ErrorResponse),
        (status = 403, body = ErrorResponse), (status = 404, body = ErrorResponse),
        (status = 409, body = ErrorResponse), (status = 422, body = ErrorResponse),
        (status = 503, body = ErrorResponse)
    )
)]
pub(super) async fn set_citizen_focus(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(game_id): Path<uuid::Uuid>,
    Json(request): Json<SetCitizenFocusRequest>,
) -> Result<Json<unciv_authoritative_server::CommandAccepted>, ApiError> {
    validate_city_id(&request.city_id)?;
    let actor = authenticated_account(&state, &headers).await?;
    let accepted = state
        .repository
        .execute_set_citizen_focus(
            &state.worker,
            actor.id,
            CommandEnvelope {
                protocol_version: PROTOCOL_VERSION,
                game_id,
                command_id: request.command_id,
                expected_revision: request.expected_revision,
                client_observed_state_hash: request.client_observed_state_hash,
                command: GameCommand::SetCitizenFocus {
                    city_id: request.city_id,
                    focus: request.focus,
                },
            },
        )
        .await
        .map_err(game_error)?;
    Ok(Json(accepted))
}

fn validate_city_id(city_id: &str) -> Result<(), ApiError> {
    if city_id.is_empty() || city_id.len() > 128 {
        return Err(ApiError::bad_request("invalid_command"));
    }
    Ok(())
}

#[utoipa::path(
    post,
    path = "/api/v3/games/{game_id}/commands/reset-citizens",
    params(("game_id" = uuid::Uuid, Path)), security(("bearer_auth" = [])),
    request_body = ResetCitizensRequest,
    responses(
        (status = 200, body = unciv_authoritative_server::CommandAccepted),
        (status = 400, body = ErrorResponse), (status = 401, body = ErrorResponse),
        (status = 403, body = ErrorResponse), (status = 404, body = ErrorResponse),
        (status = 409, body = ErrorResponse), (status = 422, body = ErrorResponse),
        (status = 503, body = ErrorResponse)
    )
)]
pub(super) async fn reset_citizens(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(game_id): Path<uuid::Uuid>,
    Json(request): Json<ResetCitizensRequest>,
) -> Result<Json<unciv_authoritative_server::CommandAccepted>, ApiError> {
    if request.city_id.is_empty() || request.city_id.len() > 128 {
        return Err(ApiError::bad_request("invalid_command"));
    }
    let actor = authenticated_account(&state, &headers).await?;
    let accepted = state
        .repository
        .execute_reset_citizens(
            &state.worker,
            actor.id,
            CommandEnvelope {
                protocol_version: PROTOCOL_VERSION,
                game_id,
                command_id: request.command_id,
                expected_revision: request.expected_revision,
                client_observed_state_hash: request.client_observed_state_hash,
                command: GameCommand::ResetCitizens {
                    city_id: request.city_id,
                },
            },
        )
        .await
        .map_err(game_error)?;
    Ok(Json(accepted))
}

#[utoipa::path(
    post,
    path = "/api/v3/games/{game_id}/commands/set-manual-specialists",
    params(("game_id" = uuid::Uuid, Path)), security(("bearer_auth" = [])),
    request_body = SetManualSpecialistsRequest,
    responses(
        (status = 200, body = unciv_authoritative_server::CommandAccepted),
        (status = 400, body = ErrorResponse), (status = 401, body = ErrorResponse),
        (status = 403, body = ErrorResponse), (status = 404, body = ErrorResponse),
        (status = 409, body = ErrorResponse), (status = 422, body = ErrorResponse),
        (status = 503, body = ErrorResponse)
    )
)]
pub(super) async fn set_manual_specialists(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(game_id): Path<uuid::Uuid>,
    Json(request): Json<SetManualSpecialistsRequest>,
) -> Result<Json<unciv_authoritative_server::CommandAccepted>, ApiError> {
    if request.city_id.is_empty() || request.city_id.len() > 128 {
        return Err(ApiError::bad_request("invalid_command"));
    }
    let actor = authenticated_account(&state, &headers).await?;
    let accepted = state
        .repository
        .execute_set_manual_specialists(
            &state.worker,
            actor.id,
            CommandEnvelope {
                protocol_version: PROTOCOL_VERSION,
                game_id,
                command_id: request.command_id,
                expected_revision: request.expected_revision,
                client_observed_state_hash: request.client_observed_state_hash,
                command: GameCommand::SetManualSpecialists {
                    city_id: request.city_id,
                    enabled: request.enabled,
                },
            },
        )
        .await
        .map_err(game_error)?;
    Ok(Json(accepted))
}

#[utoipa::path(
    post,
    path = "/api/v3/games/{game_id}/commands/set-specialist-count",
    params(("game_id" = uuid::Uuid, Path)), security(("bearer_auth" = [])),
    request_body = SetSpecialistCountRequest,
    responses(
        (status = 200, body = unciv_authoritative_server::CommandAccepted),
        (status = 400, body = ErrorResponse), (status = 401, body = ErrorResponse),
        (status = 403, body = ErrorResponse), (status = 404, body = ErrorResponse),
        (status = 409, body = ErrorResponse), (status = 422, body = ErrorResponse),
        (status = 503, body = ErrorResponse)
    )
)]
pub(super) async fn set_specialist_count(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(game_id): Path<uuid::Uuid>,
    Json(request): Json<SetSpecialistCountRequest>,
) -> Result<Json<unciv_authoritative_server::CommandAccepted>, ApiError> {
    if request.city_id.is_empty()
        || request.city_id.len() > 128
        || request.specialist_name.is_empty()
        || request.specialist_name.len() > 128
    {
        return Err(ApiError::bad_request("invalid_command"));
    }
    let actor = authenticated_account(&state, &headers).await?;
    let accepted = state
        .repository
        .execute_set_specialist_count(
            &state.worker,
            actor.id,
            CommandEnvelope {
                protocol_version: PROTOCOL_VERSION,
                game_id,
                command_id: request.command_id,
                expected_revision: request.expected_revision,
                client_observed_state_hash: request.client_observed_state_hash,
                command: GameCommand::SetSpecialistCount {
                    city_id: request.city_id,
                    specialist_name: request.specialist_name,
                    count: request.count,
                },
            },
        )
        .await
        .map_err(game_error)?;
    Ok(Json(accepted))
}

#[utoipa::path(
    post,
    path = "/api/v3/games/{game_id}/commands/set-city-tile-assignment",
    params(("game_id" = uuid::Uuid, Path)), security(("bearer_auth" = [])),
    request_body = SetCityTileAssignmentRequest,
    responses(
        (status = 200, body = unciv_authoritative_server::CommandAccepted),
        (status = 400, body = ErrorResponse), (status = 401, body = ErrorResponse),
        (status = 403, body = ErrorResponse), (status = 404, body = ErrorResponse),
        (status = 409, body = ErrorResponse), (status = 422, body = ErrorResponse),
        (status = 503, body = ErrorResponse)
    )
)]
pub(super) async fn set_city_tile_assignment(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(game_id): Path<uuid::Uuid>,
    Json(request): Json<SetCityTileAssignmentRequest>,
) -> Result<Json<unciv_authoritative_server::CommandAccepted>, ApiError> {
    if request.city_id.is_empty() || request.city_id.len() > 128 {
        return Err(ApiError::bad_request("invalid_command"));
    }
    let actor = authenticated_account(&state, &headers).await?;
    let accepted = state
        .repository
        .execute_set_city_tile_assignment(
            &state.worker,
            actor.id,
            CommandEnvelope {
                protocol_version: PROTOCOL_VERSION,
                game_id,
                command_id: request.command_id,
                expected_revision: request.expected_revision,
                client_observed_state_hash: request.client_observed_state_hash,
                command: GameCommand::SetCityTileAssignment {
                    city_id: request.city_id,
                    x: request.x,
                    y: request.y,
                    assignment: request.assignment,
                },
            },
        )
        .await
        .map_err(game_error)?;
    Ok(Json(accepted))
}
