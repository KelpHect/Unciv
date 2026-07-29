use super::*;

#[utoipa::path(
    get,
    path = "/api/v3/lobbies",
    params(ListLobbiesQuery),
    security(("bearer_auth" = [])),
    responses(
        (status = 200, body = unciv_authoritative_server::postgres::LobbyPage),
        (status = 400, body = ErrorResponse),
        (status = 401, body = ErrorResponse)
    )
)]
pub(super) async fn list_lobbies(
    State(state): State<AppState>,
    headers: HeaderMap,
    Query(query): Query<ListLobbiesQuery>,
) -> Result<Json<unciv_authoritative_server::postgres::LobbyPage>, ApiError> {
    let actor = authenticated_account(&state, &headers).await?;
    let after = game_page_cursor(query.after.as_deref())?;
    let limit = game_page_limit(query.limit)?;
    Ok(Json(
        state
            .repository
            .list_open_lobbies(actor.id, after, limit)
            .await
            .map_err(game_error)?,
    ))
}

#[utoipa::path(
    get,
    path = "/api/v3/lobbies/{game_id}",
    params(("game_id" = uuid::Uuid, Path)),
    security(("bearer_auth" = [])),
    responses(
        (status = 200, body = unciv_authoritative_server::postgres::LobbySummary),
        (status = 401, body = ErrorResponse),
        (status = 404, body = ErrorResponse)
    )
)]
pub(super) async fn lobby(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(game_id): Path<uuid::Uuid>,
) -> Result<Json<unciv_authoritative_server::postgres::LobbySummary>, ApiError> {
    let actor = authenticated_account(&state, &headers).await?;
    Ok(Json(
        state
            .repository
            .lobby_summary(actor.id, game_id)
            .await
            .map_err(game_error)?,
    ))
}

#[utoipa::path(
    put,
    path = "/api/v3/lobbies/{game_id}/ready",
    params(("game_id" = uuid::Uuid, Path)),
    security(("bearer_auth" = [])),
    request_body = SetLobbyReadyRequest,
    responses((status = 200, body = unciv_authoritative_server::postgres::LobbySummary))
)]
pub(super) async fn set_lobby_ready(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(game_id): Path<uuid::Uuid>,
    Json(request): Json<SetLobbyReadyRequest>,
) -> Result<Json<unciv_authoritative_server::postgres::LobbySummary>, ApiError> {
    let actor = authenticated_account(&state, &headers).await?;
    Ok(Json(
        state
            .repository
            .set_lobby_ready(
                actor.id,
                game_id,
                request.expected_lobby_revision,
                request.ready,
            )
            .await
            .map_err(game_error)?,
    ))
}

#[utoipa::path(
    post,
    path = "/api/v3/lobbies/{game_id}/start",
    params(("game_id" = uuid::Uuid, Path)),
    security(("bearer_auth" = [])),
    request_body = StartLobbyRequest,
    responses((status = 200, body = unciv_authoritative_server::postgres::LobbySummary))
)]
pub(super) async fn start_lobby(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(game_id): Path<uuid::Uuid>,
    Json(request): Json<StartLobbyRequest>,
) -> Result<Json<unciv_authoritative_server::postgres::LobbySummary>, ApiError> {
    let actor = authenticated_account(&state, &headers).await?;
    Ok(Json(
        state
            .repository
            .start_lobby(actor.id, game_id, request.expected_lobby_revision)
            .await
            .map_err(game_error)?,
    ))
}
