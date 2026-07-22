use super::*;

#[utoipa::path(
    post,
    path = "/api/v3/games/{game_id}/commands/resolve-city-disposition",
    params(("game_id" = uuid::Uuid, Path)), security(("bearer_auth" = [])),
    request_body = ResolveCityDispositionRequest,
    responses(
        (status = 200, body = unciv_authoritative_server::CommandAccepted),
        (status = 400, body = ErrorResponse), (status = 401, body = ErrorResponse),
        (status = 403, body = ErrorResponse), (status = 404, body = ErrorResponse),
        (status = 409, body = ErrorResponse), (status = 422, body = ErrorResponse),
        (status = 503, body = ErrorResponse)
    )
)]
pub(super) async fn resolve_city_disposition(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(game_id): Path<uuid::Uuid>,
    Json(request): Json<ResolveCityDispositionRequest>,
) -> Result<Json<unciv_authoritative_server::CommandAccepted>, ApiError> {
    if request.city_id.is_empty() || request.city_id.len() > 128 {
        return Err(ApiError::bad_request("invalid_command"));
    }
    let actor = authenticated_account(&state, &headers).await?;
    let accepted = state
        .repository
        .execute_resolve_city_disposition(
            &state.worker,
            actor.id,
            CommandEnvelope {
                protocol_version: PROTOCOL_VERSION,
                game_id,
                command_id: request.command_id,
                expected_revision: request.expected_revision,
                client_observed_state_hash: request.client_observed_state_hash,
                command: GameCommand::ResolveCityDisposition {
                    city_id: request.city_id,
                    action: request.action,
                },
            },
        )
        .await
        .map_err(game_error)?;
    Ok(Json(accepted))
}
