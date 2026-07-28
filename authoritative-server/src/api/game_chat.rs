use super::*;

const CHAT_WRITE_WINDOW_SECONDS: i32 = 60;
const CHAT_WRITE_MAX_REQUESTS: i32 = 10;
const CHAT_WRITE_BLOCK_SECONDS: i32 = 60;
const DEFAULT_CHAT_PAGE: u32 = 50;

#[utoipa::path(
    get,
    path = "/api/v3/games/{game_id}/chat",
    params(("game_id" = uuid::Uuid, Path), GameChatQuery),
    security(("bearer_auth" = [])),
    responses(
        (status = 200, body = unciv_authoritative_server::postgres::GameChatPage),
        (status = 400, body = ErrorResponse), (status = 401, body = ErrorResponse),
        (status = 403, body = ErrorResponse), (status = 404, body = ErrorResponse)
    )
)]
pub(super) async fn list_game_chat(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(game_id): Path<uuid::Uuid>,
    Query(query): Query<GameChatQuery>,
) -> Result<Json<unciv_authoritative_server::postgres::GameChatPage>, ApiError> {
    let actor = authenticated_account(&state, &headers).await?;
    state
        .repository
        .list_game_chat(
            actor.id,
            game_id,
            query.before,
            query.limit.unwrap_or(DEFAULT_CHAT_PAGE),
        )
        .await
        .map(Json)
        .map_err(game_error)
}

#[utoipa::path(
    post,
    path = "/api/v3/games/{game_id}/chat",
    params(("game_id" = uuid::Uuid, Path)),
    security(("bearer_auth" = [])),
    request_body = PostGameChatRequest,
    responses(
        (status = 204), (status = 400, body = ErrorResponse),
        (status = 401, body = ErrorResponse), (status = 403, body = ErrorResponse),
        (status = 404, body = ErrorResponse), (status = 409, body = ErrorResponse),
        (status = 422, body = ErrorResponse), (status = 429, body = ErrorResponse)
    )
)]
pub(super) async fn post_game_chat(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(game_id): Path<uuid::Uuid>,
    Json(request): Json<PostGameChatRequest>,
) -> Result<StatusCode, ApiError> {
    let actor = authenticated_account(&state, &headers).await?;
    state
        .repository
        .consume_rate_limit(
            &format!("game-chat-write:{}:{game_id}", actor.id),
            CHAT_WRITE_WINDOW_SECONDS,
            CHAT_WRITE_MAX_REQUESTS,
            CHAT_WRITE_BLOCK_SECONDS,
        )
        .await
        .map_err(|error| match error {
            AuthError::RateLimited => ApiError::rate_limited(CHAT_WRITE_BLOCK_SECONDS as u64),
            _ => ApiError::internal(),
        })?;
    state
        .repository
        .post_game_chat(actor.id, game_id, request.message_id, &request.body)
        .await
        .map_err(game_error)?;
    Ok(StatusCode::NO_CONTENT)
}
