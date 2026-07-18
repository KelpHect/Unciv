use super::*;

#[utoipa::path(
    post,
    path = "/api/v3/games",
    security(("bearer_auth" = [])),
    request_body = CreateGameRequest,
    responses(
        (status = 201, body = GameMetadataResponse),
        (status = 400, body = ErrorResponse),
        (status = 401, body = ErrorResponse),
        (status = 422, body = ErrorResponse),
        (status = 500, body = ErrorResponse),
        (status = 502, body = ErrorResponse)
    )
)]
pub(super) async fn create_game(
    State(state): State<AppState>,
    headers: HeaderMap,
    Json(request): Json<CreateGameRequest>,
) -> Result<(StatusCode, Json<GameMetadataResponse>), ApiError> {
    let actor = authenticated_account(&state, &headers).await?;
    if request.ruleset_manifest_hash.len() != 64
        || !request
            .ruleset_manifest_hash
            .bytes()
            .all(|byte| byte.is_ascii_hexdigit())
    {
        return Err(ApiError::bad_request("invalid_ruleset_manifest_hash"));
    }
    let game_id = uuid::Uuid::new_v4();
    state
        .repository
        .create_authoritative_game(
            &state.worker,
            actor.id,
            game_id,
            request.ruleset_manifest_hash,
        )
        .await
        .map_err(game_error)?;
    let metadata = state
        .repository
        .game_metadata(actor.id, game_id)
        .await
        .map_err(game_error)?;
    Ok((StatusCode::CREATED, Json(game_metadata_response(metadata))))
}

#[utoipa::path(
    get,
    path = "/api/v3/games/{game_id}",
    params(("game_id" = uuid::Uuid, Path)),
    security(("bearer_auth" = [])),
    responses(
        (status = 200, body = GameMetadataResponse),
        (status = 401, body = ErrorResponse),
        (status = 403, body = ErrorResponse),
        (status = 404, body = ErrorResponse),
        (status = 503, body = ErrorResponse)
    )
)]
pub(super) async fn game_metadata(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(game_id): Path<uuid::Uuid>,
) -> Result<Json<GameMetadataResponse>, ApiError> {
    let actor = authenticated_account(&state, &headers).await?;
    let metadata = state
        .repository
        .game_metadata(actor.id, game_id)
        .await
        .map_err(game_error)?;
    Ok(Json(game_metadata_response(metadata)))
}

#[utoipa::path(
    get,
    path = "/api/v3/games",
    params(ListGamesQuery),
    security(("bearer_auth" = [])),
    responses(
        (status = 200, body = unciv_authoritative_server::postgres::GamePage),
        (status = 400, body = ErrorResponse),
        (status = 401, body = ErrorResponse),
        (status = 500, body = ErrorResponse)
    )
)]
pub(super) async fn list_games(
    State(state): State<AppState>,
    headers: HeaderMap,
    Query(query): Query<ListGamesQuery>,
) -> Result<Json<unciv_authoritative_server::postgres::GamePage>, ApiError> {
    let actor = authenticated_account(&state, &headers).await?;
    let limit = game_page_limit(query.limit)?;
    let after = game_page_cursor(query.after.as_deref())?;
    let page = state
        .repository
        .list_games(actor.id, after, limit)
        .await
        .map_err(game_error)?;
    Ok(Json(page))
}

pub(super) fn game_page_cursor(requested: Option<&str>) -> Result<Option<uuid::Uuid>, ApiError> {
    requested
        .map(uuid::Uuid::parse_str)
        .transpose()
        .map_err(|_| ApiError::bad_request("invalid_page_cursor"))
}

pub(super) fn game_page_limit(requested: Option<u32>) -> Result<u32, ApiError> {
    let limit = requested.unwrap_or(50);
    if (1..=100).contains(&limit) {
        Ok(limit)
    } else {
        Err(ApiError::bad_request("invalid_page_limit"))
    }
}

#[utoipa::path(
    get,
    path = "/api/v3/games/{game_id}/projection",
    params(("game_id" = uuid::Uuid, Path)),
    security(("bearer_auth" = [])),
    responses(
        (status = 200, body = unciv_authoritative_server::postgres::GameProjection),
        (status = 401, body = ErrorResponse),
        (status = 403, body = ErrorResponse),
        (status = 404, body = ErrorResponse),
        (status = 503, body = ErrorResponse)
    )
)]
pub(super) async fn game_projection(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(game_id): Path<uuid::Uuid>,
) -> Result<Json<unciv_authoritative_server::postgres::GameProjection>, ApiError> {
    let actor = authenticated_account(&state, &headers).await?;
    let projection = state
        .repository
        .game_projection(&state.worker, actor.id, game_id)
        .await
        .map_err(game_error)?;
    Ok(Json(projection))
}
