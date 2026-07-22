use super::*;

fn valid_opaque_id(value: &str) -> bool {
    value.len() == 64 && value.bytes().all(|byte| byte.is_ascii_hexdigit())
}

#[utoipa::path(post, path = "/api/v3/games/{game_id}/commands/resolve-event-choice", params(("game_id" = uuid::Uuid, Path)), security(("bearer_auth" = [])), request_body = ResolveEventChoiceRequest, responses((status = 200, body = unciv_authoritative_server::CommandAccepted), (status = 400, body = ErrorResponse), (status = 401, body = ErrorResponse), (status = 409, body = ErrorResponse), (status = 422, body = ErrorResponse)))]
pub(super) async fn resolve_event_choice(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(game_id): Path<uuid::Uuid>,
    Json(request): Json<ResolveEventChoiceRequest>,
) -> Result<Json<unciv_authoritative_server::CommandAccepted>, ApiError> {
    if !valid_opaque_id(&request.prompt_id) || !valid_opaque_id(&request.choice_id) {
        return Err(ApiError::bad_request("invalid_command"));
    }
    let actor = authenticated_account(&state, &headers).await?;
    let envelope = CommandEnvelope {
        protocol_version: PROTOCOL_VERSION,
        game_id,
        command_id: request.command_id,
        expected_revision: request.expected_revision,
        client_observed_state_hash: request.client_observed_state_hash,
        command: GameCommand::ResolveEventChoice {
            prompt_id: request.prompt_id,
            choice_id: request.choice_id,
        },
    };
    Ok(Json(
        state
            .repository
            .execute_resolve_event_choice(&state.worker, actor.id, envelope)
            .await
            .map_err(game_error)?,
    ))
}
