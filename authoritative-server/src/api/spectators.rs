use super::*;

#[utoipa::path(
    put,
    path = "/api/v3/games/{game_id}/spectators",
    params(("game_id" = uuid::Uuid, Path)),
    security(("bearer_auth" = [])),
    request_body = AddSpectatorRequest,
    responses(
        (status = 204),
        (status = 400, body = ErrorResponse),
        (status = 401, body = ErrorResponse),
        (status = 403, body = ErrorResponse),
        (status = 404, body = ErrorResponse)
    )
)]
pub(super) async fn add_spectator(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(game_id): Path<uuid::Uuid>,
    Json(request): Json<AddSpectatorRequest>,
) -> Result<StatusCode, ApiError> {
    let actor = authenticated_account(&state, &headers).await?;
    state
        .repository
        .add_spectator(actor.id, game_id, &request.username)
        .await
        .map_err(game_error)?;
    Ok(StatusCode::NO_CONTENT)
}

#[utoipa::path(
    delete,
    path = "/api/v3/games/{game_id}/spectators",
    params(("game_id" = uuid::Uuid, Path)),
    security(("bearer_auth" = [])),
    responses(
        (status = 204),
        (status = 401, body = ErrorResponse),
        (status = 403, body = ErrorResponse),
        (status = 404, body = ErrorResponse)
    )
)]
pub(super) async fn leave_spectator(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(game_id): Path<uuid::Uuid>,
) -> Result<StatusCode, ApiError> {
    let actor = authenticated_account(&state, &headers).await?;
    state
        .repository
        .leave_spectator(actor.id, game_id)
        .await
        .map_err(game_error)?;
    Ok(StatusCode::NO_CONTENT)
}

#[utoipa::path(
    post,
    path = "/api/v3/games/{game_id}/spectator-revocations",
    params(("game_id" = uuid::Uuid, Path)),
    security(("bearer_auth" = [])),
    request_body = RevokeSpectatorRequest,
    responses(
        (status = 204),
        (status = 400, body = ErrorResponse),
        (status = 401, body = ErrorResponse),
        (status = 403, body = ErrorResponse),
        (status = 404, body = ErrorResponse),
        (status = 422, body = ErrorResponse)
    )
)]
pub(super) async fn revoke_spectator(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(game_id): Path<uuid::Uuid>,
    Json(request): Json<RevokeSpectatorRequest>,
) -> Result<StatusCode, ApiError> {
    let actor = authenticated_account(&state, &headers).await?;
    state
        .repository
        .revoke_spectator(actor.id, game_id, request.operation_id, &request.username)
        .await
        .map_err(game_error)?;
    Ok(StatusCode::NO_CONTENT)
}

#[utoipa::path(
    get,
    path = "/api/v3/games/{game_id}/spectator-projection",
    params(("game_id" = uuid::Uuid, Path)),
    security(("bearer_auth" = [])),
    responses(
        (status = 200, body = unciv_authoritative_server::postgres::SpectatorGameProjection),
        (status = 401, body = ErrorResponse),
        (status = 403, body = ErrorResponse),
        (status = 404, body = ErrorResponse),
        (status = 503, body = ErrorResponse)
    )
)]
pub(super) async fn spectator_projection(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(game_id): Path<uuid::Uuid>,
) -> Result<Json<unciv_authoritative_server::postgres::SpectatorGameProjection>, ApiError> {
    let actor = authenticated_account(&state, &headers).await?;
    let projection = state
        .repository
        .spectator_projection(&state.worker, actor.id, game_id)
        .await
        .map_err(game_error)?;
    Ok(Json(projection))
}
