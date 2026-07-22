use super::*;

#[utoipa::path(
    post,
    path = "/api/v3/games/{game_id}/commands/cast-diplomatic-vote",
    params(("game_id" = uuid::Uuid, Path)), security(("bearer_auth" = [])),
    request_body = CastDiplomaticVoteRequest,
    responses(
        (status = 200, body = unciv_authoritative_server::CommandAccepted),
        (status = 400, body = ErrorResponse), (status = 401, body = ErrorResponse),
        (status = 403, body = ErrorResponse), (status = 404, body = ErrorResponse),
        (status = 409, body = ErrorResponse), (status = 422, body = ErrorResponse),
        (status = 503, body = ErrorResponse)
    )
)]
pub(super) async fn cast_diplomatic_vote(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(game_id): Path<uuid::Uuid>,
    Json(request): Json<CastDiplomaticVoteRequest>,
) -> Result<Json<unciv_authoritative_server::CommandAccepted>, ApiError> {
    if request
        .candidate_civilization_id
        .as_ref()
        .is_some_and(|candidate| candidate.is_empty() || candidate.len() > 128)
    {
        return Err(ApiError::bad_request("invalid_command"));
    }
    let actor = authenticated_account(&state, &headers).await?;
    let accepted = state
        .repository
        .execute_cast_diplomatic_vote(
            &state.worker,
            actor.id,
            CommandEnvelope {
                protocol_version: PROTOCOL_VERSION,
                game_id,
                command_id: request.command_id,
                expected_revision: request.expected_revision,
                client_observed_state_hash: request.client_observed_state_hash,
                command: GameCommand::CastDiplomaticVote {
                    candidate_civilization_id: request.candidate_civilization_id,
                },
            },
        )
        .await
        .map_err(game_error)?;
    Ok(Json(accepted))
}
