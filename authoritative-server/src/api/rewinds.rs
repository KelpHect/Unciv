use super::*;

#[utoipa::path(
    get,
    path = "/api/v3/games/{game_id}/rewind-checkpoints",
    params(("game_id" = uuid::Uuid, Path), RewindCheckpointQuery),
    security(("bearer_auth" = [])),
    responses(
        (status = 200, body = Vec<unciv_authoritative_server::postgres::RewindCheckpoint>),
        (status = 401, body = ErrorResponse),
        (status = 403, body = ErrorResponse),
        (status = 503, body = ErrorResponse)
    )
)]
pub(super) async fn rewind_checkpoints(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(game_id): Path<uuid::Uuid>,
    Query(query): Query<RewindCheckpointQuery>,
) -> Result<Json<Vec<unciv_authoritative_server::postgres::RewindCheckpoint>>, ApiError> {
    let actor = authenticated_account(&state, &headers).await?;
    let checkpoints = state
        .repository
        .rewind_checkpoints(&state.worker, actor.id, game_id, query.limit.unwrap_or(20))
        .await
        .map_err(game_error)?;
    Ok(Json(checkpoints))
}

#[utoipa::path(
    post,
    path = "/api/v3/games/{game_id}/rewinds",
    params(("game_id" = uuid::Uuid, Path)),
    security(("bearer_auth" = [])),
    request_body = ProposeRewindRequest,
    responses(
        (status = 200, body = unciv_authoritative_server::postgres::RewindStatus),
        (status = 400, body = ErrorResponse),
        (status = 401, body = ErrorResponse),
        (status = 403, body = ErrorResponse),
        (status = 409, body = ErrorResponse),
        (status = 422, body = ErrorResponse)
    )
)]
pub(super) async fn propose_rewind(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(game_id): Path<uuid::Uuid>,
    Json(request): Json<ProposeRewindRequest>,
) -> Result<Json<unciv_authoritative_server::postgres::RewindStatus>, ApiError> {
    let actor = authenticated_account(&state, &headers).await?;
    let status = state
        .repository
        .propose_rewind(
            &state.worker,
            actor.id,
            game_id,
            unciv_authoritative_server::postgres::RewindRequest {
                request_id: request.request_id,
                expected_head_revision: request.expected_head_revision,
                target_revision: request.target_revision,
            },
        )
        .await
        .map_err(game_error)?;
    Ok(Json(status))
}

#[utoipa::path(
    get,
    path = "/api/v3/games/{game_id}/rewinds/{request_id}",
    params(("game_id" = uuid::Uuid, Path), ("request_id" = uuid::Uuid, Path)),
    security(("bearer_auth" = [])),
    responses(
        (status = 200, body = unciv_authoritative_server::postgres::RewindStatus),
        (status = 401, body = ErrorResponse),
        (status = 403, body = ErrorResponse),
        (status = 404, body = ErrorResponse)
    )
)]
pub(super) async fn rewind_status(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path((game_id, request_id)): Path<(uuid::Uuid, uuid::Uuid)>,
) -> Result<Json<unciv_authoritative_server::postgres::RewindStatus>, ApiError> {
    let actor = authenticated_account(&state, &headers).await?;
    state
        .repository
        .rewind_status(actor.id, game_id, request_id)
        .await
        .map(Json)
        .map_err(game_error)
}

#[utoipa::path(
    get,
    path = "/api/v3/games/{game_id}/rewinds/current",
    params(("game_id" = uuid::Uuid, Path)),
    security(("bearer_auth" = [])),
    responses(
        (status = 200, body = unciv_authoritative_server::postgres::RewindStatus),
        (status = 401, body = ErrorResponse),
        (status = 403, body = ErrorResponse),
        (status = 404, body = ErrorResponse)
    )
)]
pub(super) async fn current_rewind(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(game_id): Path<uuid::Uuid>,
) -> Result<Json<unciv_authoritative_server::postgres::RewindStatus>, ApiError> {
    let actor = authenticated_account(&state, &headers).await?;
    state
        .repository
        .current_rewind(actor.id, game_id)
        .await
        .map(Json)
        .map_err(game_error)
}

#[utoipa::path(
    put,
    path = "/api/v3/games/{game_id}/rewinds/{request_id}/vote",
    params(("game_id" = uuid::Uuid, Path), ("request_id" = uuid::Uuid, Path)),
    security(("bearer_auth" = [])),
    request_body = VoteRewindRequest,
    responses(
        (status = 200, body = unciv_authoritative_server::postgres::RewindStatus),
        (status = 401, body = ErrorResponse),
        (status = 403, body = ErrorResponse),
        (status = 409, body = ErrorResponse),
        (status = 422, body = ErrorResponse)
    )
)]
pub(super) async fn vote_rewind(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path((game_id, request_id)): Path<(uuid::Uuid, uuid::Uuid)>,
    Json(request): Json<VoteRewindRequest>,
) -> Result<Json<unciv_authoritative_server::postgres::RewindStatus>, ApiError> {
    let actor = authenticated_account(&state, &headers).await?;
    state
        .repository
        .vote_rewind(
            &state.worker,
            actor.id,
            game_id,
            request_id,
            request.approved,
        )
        .await
        .map(Json)
        .map_err(game_error)
}
