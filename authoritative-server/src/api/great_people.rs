use super::*;

#[utoipa::path(
    post,
    path = "/api/v3/games/{game_id}/commands/choose-great-person",
    params(("game_id" = uuid::Uuid, Path)), security(("bearer_auth" = [])),
    request_body = ChooseGreatPersonRequest,
    responses(
        (status = 200, body = unciv_authoritative_server::CommandAccepted),
        (status = 400, body = ErrorResponse), (status = 401, body = ErrorResponse),
        (status = 403, body = ErrorResponse), (status = 404, body = ErrorResponse),
        (status = 409, body = ErrorResponse), (status = 422, body = ErrorResponse),
        (status = 503, body = ErrorResponse)
    )
)]
pub(super) async fn choose_great_person(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(game_id): Path<uuid::Uuid>,
    Json(request): Json<ChooseGreatPersonRequest>,
) -> Result<Json<unciv_authoritative_server::CommandAccepted>, ApiError> {
    if request.unit_name.is_empty() || request.unit_name.len() > 128 {
        return Err(ApiError::bad_request("invalid_command"));
    }
    let actor = authenticated_account(&state, &headers).await?;
    let accepted = state
        .repository
        .execute_choose_great_person(
            &state.worker,
            actor.id,
            CommandEnvelope {
                protocol_version: PROTOCOL_VERSION,
                game_id,
                command_id: request.command_id,
                expected_revision: request.expected_revision,
                client_observed_state_hash: request.client_observed_state_hash,
                command: GameCommand::ChooseGreatPerson {
                    unit_name: request.unit_name,
                },
            },
        )
        .await
        .map_err(game_error)?;
    Ok(Json(accepted))
}

#[utoipa::path(post, path = "/api/v3/games/{game_id}/commands/use-great-person-unit", params(("game_id" = uuid::Uuid, Path)), security(("bearer_auth" = [])), request_body = UseGreatPersonUnitRequest, responses((status = 200, body = unciv_authoritative_server::CommandAccepted), (status = 400, body = ErrorResponse), (status = 401, body = ErrorResponse), (status = 409, body = ErrorResponse), (status = 422, body = ErrorResponse)))]
pub(super) async fn use_great_person_unit(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(game_id): Path<uuid::Uuid>,
    Json(request): Json<UseGreatPersonUnitRequest>,
) -> Result<Json<unciv_authoritative_server::CommandAccepted>, ApiError> {
    if request.unit_id < 0 {
        return Err(ApiError::bad_request("invalid_command"));
    }
    let actor = authenticated_account(&state, &headers).await?;
    let accepted = state
        .repository
        .execute_use_great_person_unit(
            &state.worker,
            actor.id,
            CommandEnvelope {
                protocol_version: PROTOCOL_VERSION,
                game_id,
                command_id: request.command_id,
                expected_revision: request.expected_revision,
                client_observed_state_hash: request.client_observed_state_hash,
                command: GameCommand::UseGreatPersonUnit {
                    unit_id: request.unit_id,
                    action: request.action,
                },
            },
        )
        .await
        .map_err(game_error)?;
    Ok(Json(accepted))
}
