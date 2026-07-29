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
    if request.operation_id.is_nil() {
        return Err(ApiError::bad_request("invalid_creation_operation_id"));
    }
    if request.display_name.trim().is_empty()
        || request.display_name.len() > 80
        || request.display_name.chars().any(char::is_control)
        || !(1..=16).contains(&request.human_slots)
        || request.available_civilizations.is_empty()
        || request.available_civilizations.len() > 64
        || request
            .available_civilizations
            .iter()
            .any(|name| name.is_empty() || name.len() > 128 || name.chars().any(char::is_control))
    {
        return Err(ApiError::bad_request("invalid_lobby_configuration"));
    }
    if request
        .password
        .as_ref()
        .is_some_and(|password| password.len() < 12 || password.len() > 256)
    {
        return Err(ApiError::bad_request("invalid_lobby_password"));
    }
    let setup = request.setup.validate()?;
    if request.human_slots > setup.major_civilizations {
        return Err(ApiError::bad_request(
            "human_slots_exceed_major_civilizations",
        ));
    }
    let unique_civilizations = request
        .available_civilizations
        .iter()
        .collect::<std::collections::HashSet<_>>();
    if unique_civilizations.len() != request.available_civilizations.len()
        || request.available_civilizations.len() < usize::from(request.human_slots)
        || !unique_civilizations.contains(&setup.owner_civilization_id)
    {
        return Err(ApiError::bad_request("invalid_available_civilizations"));
    }
    let password_identity = request
        .password
        .as_ref()
        .map(|password| unciv_authoritative_server::state_hash(password.as_bytes()));
    let password_hash = request
        .password
        .as_deref()
        .map(|password| PasswordService.hash(password))
        .transpose()
        .map_err(|_| ApiError::bad_request("invalid_lobby_password"))?;
    let lobby = unciv_authoritative_server::postgres::LobbyCreateConfiguration {
        display_name: request.display_name,
        human_slots: request.human_slots,
        password_hash,
        password_identity,
        available_civilizations: request.available_civilizations,
    };
    let game_id = state
        .repository
        .create_authoritative_game(
            &state.worker,
            actor.id,
            request.operation_id,
            request.ruleset_manifest_hash,
            setup,
            lobby,
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

#[utoipa::path(
    get,
    path = "/api/v3/games/{game_id}/projection/delta",
    params(("game_id" = uuid::Uuid, Path), ProjectionDeltaQuery),
    security(("bearer_auth" = [])),
    responses(
        (status = 200, body = unciv_authoritative_server::GameProjectionDelta),
        (status = 401, body = ErrorResponse),
        (status = 403, body = ErrorResponse),
        (status = 409, body = ErrorResponse),
        (status = 503, body = ErrorResponse)
    )
)]
pub(super) async fn game_projection_delta(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(game_id): Path<uuid::Uuid>,
    Query(query): Query<ProjectionDeltaQuery>,
) -> Result<Json<unciv_authoritative_server::GameProjectionDelta>, ApiError> {
    let actor = authenticated_account(&state, &headers).await?;
    let delta = state
        .repository
        .game_projection_delta(
            &state.worker,
            actor.id,
            game_id,
            query.base_revision,
            &query.base_canonical_state_hash,
            &query.base_projection_hash,
        )
        .await
        .map_err(game_error)?;
    Ok(Json(delta))
}
