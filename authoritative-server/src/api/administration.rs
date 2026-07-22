use super::*;

#[utoipa::path(
    put,
    path = "/api/v3/games/{game_id}/player-invitations",
    params(("game_id" = uuid::Uuid, Path)),
    security(("bearer_auth" = [])),
    request_body = InvitePlayerRequest,
    responses(
        (status = 204), (status = 400, body = ErrorResponse),
        (status = 401, body = ErrorResponse), (status = 403, body = ErrorResponse),
        (status = 404, body = ErrorResponse), (status = 422, body = ErrorResponse)
    )
)]
pub(super) async fn invite_player(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(game_id): Path<uuid::Uuid>,
    Json(request): Json<InvitePlayerRequest>,
) -> Result<StatusCode, ApiError> {
    let actor = authenticated_account(&state, &headers).await?;
    state
        .repository
        .invite_player(actor.id, game_id, request.invitation_id, &request.username)
        .await
        .map_err(game_error)?;
    Ok(StatusCode::NO_CONTENT)
}

#[utoipa::path(
    get,
    path = "/api/v3/player-invitations",
    security(("bearer_auth" = [])),
    responses(
        (status = 200, body = [unciv_authoritative_server::postgres::PlayerInvitation]),
        (status = 401, body = ErrorResponse), (status = 500, body = ErrorResponse)
    )
)]
pub(super) async fn list_player_invitations(
    State(state): State<AppState>,
    headers: HeaderMap,
) -> Result<Json<Vec<unciv_authoritative_server::postgres::PlayerInvitation>>, ApiError> {
    let actor = authenticated_account(&state, &headers).await?;
    let invitations = state
        .repository
        .list_player_invitations(actor.id)
        .await
        .map_err(game_error)?;
    Ok(Json(invitations))
}

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
