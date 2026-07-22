use super::*;

#[utoipa::path(
    put,
    path = "/api/v3/games/{game_id}/owner",
    params(("game_id" = uuid::Uuid, Path)),
    security(("bearer_auth" = [])),
    request_body = TransferOwnershipRequest,
    responses(
        (status = 204), (status = 400, body = ErrorResponse),
        (status = 401, body = ErrorResponse), (status = 403, body = ErrorResponse),
        (status = 404, body = ErrorResponse), (status = 422, body = ErrorResponse)
    )
)]
pub(super) async fn transfer_ownership(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(game_id): Path<uuid::Uuid>,
    Json(request): Json<TransferOwnershipRequest>,
) -> Result<StatusCode, ApiError> {
    let actor = authenticated_account(&state, &headers).await?;
    state
        .repository
        .transfer_ownership(actor.id, game_id, request.operation_id, &request.username)
        .await
        .map_err(game_error)?;
    Ok(StatusCode::NO_CONTENT)
}

#[utoipa::path(
    post,
    path = "/api/v3/games/{game_id}/close",
    params(("game_id" = uuid::Uuid, Path)),
    security(("bearer_auth" = [])),
    request_body = GameAdminOperationRequest,
    responses(
        (status = 204), (status = 400, body = ErrorResponse),
        (status = 401, body = ErrorResponse), (status = 403, body = ErrorResponse),
        (status = 404, body = ErrorResponse), (status = 422, body = ErrorResponse)
    )
)]
pub(super) async fn close_game_admin(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(game_id): Path<uuid::Uuid>,
    Json(request): Json<GameAdminOperationRequest>,
) -> Result<StatusCode, ApiError> {
    let actor = authenticated_account(&state, &headers).await?;
    state
        .repository
        .close_game(actor.id, game_id, request.operation_id)
        .await
        .map_err(game_error)?;
    Ok(StatusCode::NO_CONTENT)
}

#[utoipa::path(
    post,
    path = "/api/v3/games/{game_id}/archive",
    params(("game_id" = uuid::Uuid, Path)),
    security(("bearer_auth" = [])),
    request_body = GameAdminOperationRequest,
    responses(
        (status = 204), (status = 400, body = ErrorResponse),
        (status = 401, body = ErrorResponse), (status = 403, body = ErrorResponse),
        (status = 404, body = ErrorResponse), (status = 422, body = ErrorResponse)
    )
)]
pub(super) async fn archive_game_admin(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(game_id): Path<uuid::Uuid>,
    Json(request): Json<GameAdminOperationRequest>,
) -> Result<StatusCode, ApiError> {
    let actor = authenticated_account(&state, &headers).await?;
    state
        .repository
        .archive_game(actor.id, game_id, request.operation_id)
        .await
        .map_err(game_error)?;
    Ok(StatusCode::NO_CONTENT)
}
