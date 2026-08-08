use super::*;

#[utoipa::path(
    get,
    path = "/api/v3/games/{game_id}/revisions",
    params(("game_id" = uuid::Uuid, Path)),
    security(("bearer_auth" = [])),
    responses(
        (status = 200, body = unciv_authoritative_server::postgres::RevisionList),
        (status = 401, body = ErrorResponse),
        (status = 403, body = ErrorResponse),
        (status = 404, body = ErrorResponse)
    )
)]
pub(super) async fn list_revisions(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(game_id): Path<uuid::Uuid>,
) -> Result<Json<unciv_authoritative_server::postgres::RevisionList>, ApiError> {
    let actor = authenticated_account(&state, &headers).await?;
    let is_public = state
        .repository
        .is_public_game(game_id)
        .await
        .map_err(game_error)?;
    if !is_public {
        // For private games, require membership
        let _meta = state
            .repository
            .game_metadata(actor.id, game_id)
            .await
            .map_err(game_error)?;
    }
    let list = state
        .repository
        .list_revisions(game_id)
        .await
        .map_err(game_error)?;
    Ok(Json(list))
}

#[utoipa::path(
    get,
    path = "/api/v3/games/{game_id}/revisions/{revision}/replay",
    params(
        ("game_id" = uuid::Uuid, Path),
        ("revision" = u64, Path),
    ),
    security(("bearer_auth" = [])),
    responses(
        (status = 200, body = unciv_authoritative_server::postgres::ReplayGameProjection),
        (status = 401, body = ErrorResponse),
        (status = 403, body = ErrorResponse),
        (status = 404, body = ErrorResponse),
        (status = 503, body = ErrorResponse)
    )
)]
pub(super) async fn replay_projection(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path((game_id, revision)): Path<(uuid::Uuid, u64)>,
) -> Result<Json<unciv_authoritative_server::postgres::ReplayGameProjection>, ApiError> {
    let actor = authenticated_account(&state, &headers).await?;
    let is_public = state
        .repository
        .is_public_game(game_id)
        .await
        .map_err(game_error)?;
    if !is_public {
        // For private games, require membership
        let _meta = state
            .repository
            .game_metadata(actor.id, game_id)
            .await
            .map_err(game_error)?;
    }
    let projection = state
        .repository
        .replay_projection(&state.worker, &actor.id.to_string(), game_id, revision)
        .await
        .map_err(game_error)?;
    Ok(Json(projection))
}

#[utoipa::path(
    get,
    path = "/api/v3/public-matches",
    params(("limit" = Option<u32>, Query), ("offset" = Option<u32>, Query)),
    security(("bearer_auth" = [])),
    responses(
        (status = 200, body = Vec<unciv_authoritative_server::postgres::PublicMatchSummary>),
        (status = 401, body = ErrorResponse)
    )
)]
pub(super) async fn list_public_matches(
    State(state): State<AppState>,
    headers: HeaderMap,
    Query(params): Query<PublicMatchesQuery>,
) -> Result<Json<Vec<unciv_authoritative_server::postgres::PublicMatchSummary>>, ApiError> {
    let _actor = authenticated_account(&state, &headers).await?;
    let limit = params.limit.unwrap_or(50).min(200);
    let offset = params.offset.unwrap_or(0);
    let matches = state
        .repository
        .list_public_matches(limit, offset)
        .await
        .map_err(game_error)?;
    Ok(Json(matches))
}

#[derive(Deserialize)]
pub(super) struct PublicMatchesQuery {
    pub limit: Option<u32>,
    pub offset: Option<u32>,
}
