use super::*;

fn valid_name(value: &str) -> bool {
    !value.is_empty() && value.len() <= 128 && !value.chars().any(char::is_control)
}

#[utoipa::path(post, path = "/api/v3/games/{game_id}/commands/move-spy", params(("game_id" = uuid::Uuid, Path)), security(("bearer_auth" = [])), request_body = MoveSpyRequest, responses((status = 200, body = unciv_authoritative_server::CommandAccepted), (status = 400, body = ErrorResponse), (status = 401, body = ErrorResponse), (status = 409, body = ErrorResponse), (status = 422, body = ErrorResponse)))]
pub(super) async fn move_spy(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(game_id): Path<uuid::Uuid>,
    Json(request): Json<MoveSpyRequest>,
) -> Result<Json<unciv_authoritative_server::CommandAccepted>, ApiError> {
    if !valid_name(&request.spy_name) || request.city_id.as_ref().is_some_and(|id| !valid_name(id))
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
        command: GameCommand::MoveSpy {
            spy_name: request.spy_name,
            city_id: request.city_id,
        },
    };
    Ok(Json(
        state
            .repository
            .execute_move_spy(&state.worker, actor.id, envelope)
            .await
            .map_err(game_error)?,
    ))
}

#[utoipa::path(post, path = "/api/v3/games/{game_id}/commands/set-spy-coup", params(("game_id" = uuid::Uuid, Path)), security(("bearer_auth" = [])), request_body = SetSpyCoupRequest, responses((status = 200, body = unciv_authoritative_server::CommandAccepted), (status = 400, body = ErrorResponse), (status = 401, body = ErrorResponse), (status = 409, body = ErrorResponse), (status = 422, body = ErrorResponse)))]
pub(super) async fn set_spy_coup(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(game_id): Path<uuid::Uuid>,
    Json(request): Json<SetSpyCoupRequest>,
) -> Result<Json<unciv_authoritative_server::CommandAccepted>, ApiError> {
    if !valid_name(&request.spy_name) {
        return Err(ApiError::bad_request("invalid_command"));
    }
    let actor = authenticated_account(&state, &headers).await?;
    let envelope = CommandEnvelope {
        protocol_version: PROTOCOL_VERSION,
        game_id,
        command_id: request.command_id,
        expected_revision: request.expected_revision,
        client_observed_state_hash: request.client_observed_state_hash,
        command: GameCommand::SetSpyCoup {
            spy_name: request.spy_name,
            enabled: request.enabled,
        },
    };
    Ok(Json(
        state
            .repository
            .execute_set_spy_coup(&state.worker, actor.id, envelope)
            .await
            .map_err(game_error)?,
    ))
}
