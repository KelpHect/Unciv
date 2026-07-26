use super::*;

#[utoipa::path(post, path = "/api/v3/games/{game_id}/commands/create-instant-improvement", params(("game_id" = uuid::Uuid, Path)), security(("bearer_auth" = [])), request_body = CreateInstantImprovementRequest, responses((status = 200, body = unciv_authoritative_server::CommandAccepted), (status = 400, body = ErrorResponse), (status = 401, body = ErrorResponse), (status = 409, body = ErrorResponse), (status = 422, body = ErrorResponse)))]
pub(super) async fn create_instant_improvement(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(game_id): Path<uuid::Uuid>,
    Json(request): Json<CreateInstantImprovementRequest>,
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
            .execute_create_instant_improvement(
                &state.worker,
                actor.id,
                CommandEnvelope {
                    protocol_version: PROTOCOL_VERSION,
                    game_id,
                    command_id: request.command_id,
                    expected_revision: request.expected_revision,
                    client_observed_state_hash: request.client_observed_state_hash,
                    command: GameCommand::CreateInstantImprovement {
                        unit_id: request.unit_id,
                        action_id: request.action_id,
                    },
                },
            )
            .await
            .map_err(game_error)?,
    ))
}
