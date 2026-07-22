use super::*;

#[utoipa::path(post, path = "/api/v3/games/{game_id}/commands/trigger-unit-unique", params(("game_id" = uuid::Uuid, Path)), security(("bearer_auth" = [])), request_body = TriggerUnitUniqueRequest, responses((status = 200, body = unciv_authoritative_server::CommandAccepted), (status = 400, body = ErrorResponse), (status = 401, body = ErrorResponse), (status = 409, body = ErrorResponse), (status = 422, body = ErrorResponse)))]
pub(super) async fn trigger_unit_unique(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(game_id): Path<uuid::Uuid>,
    Json(request): Json<TriggerUnitUniqueRequest>,
) -> Result<Json<unciv_authoritative_server::CommandAccepted>, ApiError> {
    if request.unit_id < 0
        || request.action_id.len() != 64
        || !request
            .action_id
            .bytes()
            .all(|byte| byte.is_ascii_hexdigit())
    {
        return Err(ApiError::bad_request("invalid_command"));
    }
    let actor = authenticated_account(&state, &headers).await?;
    Ok(Json(
        state
            .repository
            .execute_trigger_unit_unique(
                &state.worker,
                actor.id,
                CommandEnvelope {
                    protocol_version: PROTOCOL_VERSION,
                    game_id,
                    command_id: request.command_id,
                    expected_revision: request.expected_revision,
                    client_observed_state_hash: request.client_observed_state_hash,
                    command: GameCommand::TriggerUnitUnique {
                        unit_id: request.unit_id,
                        action_id: request.action_id,
                    },
                },
            )
            .await
            .map_err(game_error)?,
    ))
}
