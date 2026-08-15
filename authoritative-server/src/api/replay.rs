use super::*;

/// A replay is a full no-fog-of-war view of a game, so it is only ever served
/// for a game that can no longer be played. Serving one for a live match would
/// hand any authenticated caller the whole map and every rival's economy.
///
/// Authorization is checked before the lifecycle gate: a caller who cannot see
/// a private game at all must not learn whether it is still being played.
async fn authorize_replay(
    state: &AppState,
    actor: &Account,
    game_id: uuid::Uuid,
) -> Result<(), ApiError> {
    let access = state
        .repository
        .replay_access(game_id)
        .await
        .map_err(game_error)?;
    if !access.is_public {
        let _membership = state
            .repository
            .game_metadata(actor.id, game_id)
            .await
            .map_err(game_error)?;
    }
    if !access.is_concluded {
        return Err(ApiError::forbidden("replay_unavailable_while_active"));
    }
    Ok(())
}

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
    authorize_replay(&state, &actor, game_id).await?;
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
    authorize_replay(&state, &actor, game_id).await?;
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
    let limit = public_match_page_limit(params.limit)?;
    let offset = params.offset.unwrap_or(0);
    let matches = state
        .repository
        .list_public_matches(limit, offset)
        .await
        .map_err(game_error)?;
    Ok(Json(matches))
}

pub(super) fn public_match_page_limit(requested: Option<u32>) -> Result<u32, ApiError> {
    let limit = requested.unwrap_or(50);
    if (1..=200).contains(&limit) {
        Ok(limit)
    } else {
        Err(ApiError::bad_request("invalid_page_limit"))
    }
}

#[derive(Deserialize)]
pub(super) struct PublicMatchesQuery {
    pub limit: Option<u32>,
    pub offset: Option<u32>,
}
