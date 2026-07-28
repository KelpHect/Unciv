use super::*;

const SOCIAL_WRITE_WINDOW_SECONDS: i32 = 60;
const SOCIAL_WRITE_MAX_REQUESTS: i32 = 30;
const SOCIAL_WRITE_BLOCK_SECONDS: i32 = 60;

#[utoipa::path(
    get,
    path = "/api/v3/friends",
    security(("bearer_auth" = [])),
    responses(
        (status = 200, body = unciv_authoritative_server::postgres::SocialGraph),
        (status = 401, body = ErrorResponse), (status = 500, body = ErrorResponse)
    )
)]
pub(super) async fn list_friends(
    State(state): State<AppState>,
    headers: HeaderMap,
) -> Result<Json<unciv_authoritative_server::postgres::SocialGraph>, ApiError> {
    let actor = authenticated_account(&state, &headers).await?;
    state
        .repository
        .list_social_graph(actor.id)
        .await
        .map(Json)
        .map_err(game_error)
}

#[utoipa::path(
    post,
    path = "/api/v3/friend-requests",
    security(("bearer_auth" = [])),
    request_body = FriendRequest,
    responses(
        (status = 204), (status = 401, body = ErrorResponse),
        (status = 404, body = ErrorResponse), (status = 409, body = ErrorResponse),
        (status = 422, body = ErrorResponse), (status = 429, body = ErrorResponse)
    )
)]
pub(super) async fn request_friend(
    State(state): State<AppState>,
    headers: HeaderMap,
    Json(request): Json<FriendRequest>,
) -> Result<StatusCode, ApiError> {
    let actor = authenticated_account(&state, &headers).await?;
    enforce_social_write_limit(&state, actor.id).await?;
    state
        .repository
        .request_friendship(actor.id, request.request_id, &request.username)
        .await
        .map_err(game_error)?;
    Ok(StatusCode::NO_CONTENT)
}

#[utoipa::path(
    post,
    path = "/api/v3/friend-requests/{request_id}/accept",
    params(("request_id" = uuid::Uuid, Path)),
    security(("bearer_auth" = [])),
    responses(
        (status = 204), (status = 401, body = ErrorResponse),
        (status = 403, body = ErrorResponse), (status = 404, body = ErrorResponse),
        (status = 422, body = ErrorResponse), (status = 429, body = ErrorResponse)
    )
)]
pub(super) async fn accept_friend(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(request_id): Path<uuid::Uuid>,
) -> Result<StatusCode, ApiError> {
    let actor = authenticated_account(&state, &headers).await?;
    enforce_social_write_limit(&state, actor.id).await?;
    state
        .repository
        .accept_friendship(actor.id, request_id)
        .await
        .map_err(game_error)?;
    Ok(StatusCode::NO_CONTENT)
}

#[utoipa::path(
    delete,
    path = "/api/v3/friend-requests/{request_id}",
    params(("request_id" = uuid::Uuid, Path)),
    security(("bearer_auth" = [])),
    responses(
        (status = 204), (status = 401, body = ErrorResponse),
        (status = 404, body = ErrorResponse), (status = 429, body = ErrorResponse)
    )
)]
pub(super) async fn remove_friend_request(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(request_id): Path<uuid::Uuid>,
) -> Result<StatusCode, ApiError> {
    let actor = authenticated_account(&state, &headers).await?;
    enforce_social_write_limit(&state, actor.id).await?;
    state
        .repository
        .remove_friend_request(actor.id, request_id)
        .await
        .map_err(game_error)?;
    Ok(StatusCode::NO_CONTENT)
}

#[utoipa::path(
    delete,
    path = "/api/v3/friends/{username}",
    params(("username" = String, Path)),
    security(("bearer_auth" = [])),
    responses(
        (status = 204), (status = 401, body = ErrorResponse),
        (status = 404, body = ErrorResponse), (status = 429, body = ErrorResponse)
    )
)]
pub(super) async fn remove_friend(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(username): Path<String>,
) -> Result<StatusCode, ApiError> {
    let actor = authenticated_account(&state, &headers).await?;
    enforce_social_write_limit(&state, actor.id).await?;
    state
        .repository
        .remove_friendship(actor.id, &username)
        .await
        .map_err(game_error)?;
    Ok(StatusCode::NO_CONTENT)
}

async fn enforce_social_write_limit(state: &AppState, actor: uuid::Uuid) -> Result<(), ApiError> {
    state
        .repository
        .consume_rate_limit(
            &format!("social-write:{actor}"),
            SOCIAL_WRITE_WINDOW_SECONDS,
            SOCIAL_WRITE_MAX_REQUESTS,
            SOCIAL_WRITE_BLOCK_SECONDS,
        )
        .await
        .map_err(|error| match error {
            AuthError::RateLimited => ApiError::rate_limited(SOCIAL_WRITE_BLOCK_SECONDS as u64),
            _ => ApiError::internal(),
        })
}
