use super::*;

#[derive(Deserialize, ToSchema)]
#[serde(deny_unknown_fields)]
pub(super) struct ManageResearchQueueRequest {
    pub(super) command_id: uuid::Uuid,
    pub(super) expected_revision: u64,
    pub(super) client_observed_state_hash: Option<String>,
    pub(super) technology_name: String,
    pub(super) queue_index: u32,
    pub(super) action: ResearchQueueAction,
}

#[utoipa::path(
    post,
    path = "/api/v3/games/{game_id}/commands/manage-research-queue",
    params(("game_id" = uuid::Uuid, Path)),
    security(("bearer_auth" = [])),
    request_body = ManageResearchQueueRequest,
    responses(
        (status = 200, body = unciv_authoritative_server::CommandAccepted),
        (status = 400, body = ErrorResponse),
        (status = 401, body = ErrorResponse),
        (status = 403, body = ErrorResponse),
        (status = 404, body = ErrorResponse),
        (status = 409, body = ErrorResponse),
        (status = 422, body = ErrorResponse),
        (status = 503, body = ErrorResponse)
    )
)]
pub(super) async fn manage_research_queue(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(game_id): Path<uuid::Uuid>,
    Json(request): Json<ManageResearchQueueRequest>,
) -> Result<Json<unciv_authoritative_server::CommandAccepted>, ApiError> {
    if request.technology_name.is_empty()
        || request.technology_name.len() > 128
        || request.queue_index >= 10_000
    {
        return Err(ApiError::bad_request("invalid_command"));
    }
    let actor = authenticated_account(&state, &headers).await?;
    let accepted = state
        .repository
        .execute_manage_research_queue(
            &state.worker,
            actor.id,
            CommandEnvelope {
                protocol_version: PROTOCOL_VERSION,
                game_id,
                command_id: request.command_id,
                expected_revision: request.expected_revision,
                client_observed_state_hash: request.client_observed_state_hash,
                command: GameCommand::ManageResearchQueue {
                    technology_name: request.technology_name,
                    queue_index: request.queue_index,
                    action: request.action,
                },
            },
        )
        .await
        .map_err(game_error)?;
    Ok(Json(accepted))
}
