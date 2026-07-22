use super::*;

#[utoipa::path(
    post,
    path = "/api/v3/games/{game_id}/commands/choose-religious-beliefs",
    params(("game_id" = uuid::Uuid, Path)), security(("bearer_auth" = [])),
    request_body = ChooseReligiousBeliefsRequest,
    responses(
        (status = 200, body = unciv_authoritative_server::CommandAccepted),
        (status = 400, body = ErrorResponse), (status = 401, body = ErrorResponse),
        (status = 403, body = ErrorResponse), (status = 404, body = ErrorResponse),
        (status = 409, body = ErrorResponse), (status = 422, body = ErrorResponse),
        (status = 503, body = ErrorResponse)
    )
)]
pub(super) async fn choose_religious_beliefs(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(game_id): Path<uuid::Uuid>,
    Json(request): Json<ChooseReligiousBeliefsRequest>,
) -> Result<Json<unciv_authoritative_server::CommandAccepted>, ApiError> {
    if request.belief_names.is_empty()
        || request.belief_names.len() > 16
        || request
            .belief_names
            .iter()
            .any(|name| name.is_empty() || name.len() > 128)
        || request
            .religion_icon_name
            .as_ref()
            .is_some_and(|name| name.is_empty() || name.len() > 128)
        || request
            .religion_display_name
            .as_ref()
            .is_some_and(|name| name.is_empty() || name.len() > 128)
    {
        return Err(ApiError::bad_request("invalid_command"));
    }
    let actor = authenticated_account(&state, &headers).await?;
    let accepted = state
        .repository
        .execute_choose_religious_beliefs(
            &state.worker,
            actor.id,
            CommandEnvelope {
                protocol_version: PROTOCOL_VERSION,
                game_id,
                command_id: request.command_id,
                expected_revision: request.expected_revision,
                client_observed_state_hash: request.client_observed_state_hash,
                command: GameCommand::ChooseReligiousBeliefs {
                    belief_names: request.belief_names,
                    religion_icon_name: request.religion_icon_name,
                    religion_display_name: request.religion_display_name,
                },
            },
        )
        .await
        .map_err(game_error)?;
    Ok(Json(accepted))
}

#[utoipa::path(
    post,
    path = "/api/v3/games/{game_id}/commands/use-religious-unit",
    params(("game_id" = uuid::Uuid, Path)), security(("bearer_auth" = [])),
    request_body = UseReligiousUnitRequest,
    responses(
        (status = 200, body = unciv_authoritative_server::CommandAccepted),
        (status = 400, body = ErrorResponse), (status = 401, body = ErrorResponse),
        (status = 403, body = ErrorResponse), (status = 404, body = ErrorResponse),
        (status = 409, body = ErrorResponse), (status = 422, body = ErrorResponse),
        (status = 503, body = ErrorResponse)
    )
)]
pub(super) async fn use_religious_unit(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(game_id): Path<uuid::Uuid>,
    Json(request): Json<UseReligiousUnitRequest>,
) -> Result<Json<unciv_authoritative_server::CommandAccepted>, ApiError> {
    if request.unit_id < 0 {
        return Err(ApiError::bad_request("invalid_command"));
    }
    let actor = authenticated_account(&state, &headers).await?;
    let accepted = state
        .repository
        .execute_use_religious_unit(
            &state.worker,
            actor.id,
            CommandEnvelope {
                protocol_version: PROTOCOL_VERSION,
                game_id,
                command_id: request.command_id,
                expected_revision: request.expected_revision,
                client_observed_state_hash: request.client_observed_state_hash,
                command: GameCommand::UseReligiousUnit {
                    unit_id: request.unit_id,
                    action: request.action,
                },
            },
        )
        .await
        .map_err(game_error)?;
    Ok(Json(accepted))
}
