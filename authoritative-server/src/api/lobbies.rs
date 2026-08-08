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
    get,
    path = "/api/v3/lobbies/{game_id}/map-preview",
    params(("game_id" = uuid::Uuid, Path)),
    security(("bearer_auth" = [])),
    responses(
        (status = 200, body = unciv_authoritative_server::postgres::LobbyMapPreview),
        (status = 401, body = ErrorResponse),
        (status = 403, body = ErrorResponse),
        (status = 404, body = ErrorResponse)
    )
)]
pub(super) async fn lobby_map_preview(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(game_id): Path<uuid::Uuid>,
) -> Result<Json<unciv_authoritative_server::postgres::LobbyMapPreview>, ApiError> {
    let actor = authenticated_account(&state, &headers).await?;
    Ok(Json(
        state
            .repository
            .lobby_map_preview(&state.worker, actor.id, game_id)
            .await
            .map_err(game_error)?,
    ))
}

#[utoipa::path(
    put,
    path = "/api/v3/lobbies/{game_id}/configuration",
    params(("game_id" = uuid::Uuid, Path)),
    security(("bearer_auth" = [])),
    request_body = ReconfigureLobbyRequest,
    responses(
        (status = 200, body = unciv_authoritative_server::postgres::LobbySummary),
        (status = 400, body = ErrorResponse),
        (status = 401, body = ErrorResponse),
        (status = 409, body = ErrorResponse)
    )
)]
pub(super) async fn reconfigure_lobby(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(game_id): Path<uuid::Uuid>,
    Json(request): Json<ReconfigureLobbyRequest>,
) -> Result<Json<unciv_authoritative_server::postgres::LobbySummary>, ApiError> {
    let actor = authenticated_account(&state, &headers).await?;
    if request.operation_id.is_nil()
        || request.display_name.trim().is_empty()
        || request.display_name.len() > 80
        || request.display_name.chars().any(char::is_control)
        || !(0..=16).contains(&request.human_slots)
    {
        return Err(ApiError::bad_request("invalid_lobby_configuration"));
    }
    let setup = request.setup.validate()?;
    if request.human_slots > setup.major_civilizations {
        return Err(ApiError::bad_request(
            "human_slots_exceed_major_civilizations",
        ));
    }
    if !super::game_setup::ai_roster_matches_human_slots(&setup, request.human_slots) {
        return Err(ApiError::bad_request(
            "ai_roster_does_not_match_human_slots",
        ));
    }
    let password = match request.password {
        LobbyPasswordUpdateRequest::Keep => {
            unciv_authoritative_server::postgres::LobbyPasswordUpdate::Keep
        }
        LobbyPasswordUpdateRequest::Clear => {
            unciv_authoritative_server::postgres::LobbyPasswordUpdate::Clear
        }
        LobbyPasswordUpdateRequest::Replace { password } => {
            let identity = unciv_authoritative_server::state_hash(password.as_bytes());
            let hash = PasswordService
                .hash(&password)
                .map_err(|_| ApiError::bad_request("invalid_lobby_password"))?;
            unciv_authoritative_server::postgres::LobbyPasswordUpdate::Replace { hash, identity }
        }
    };
    let lobby = state
        .repository
        .reconfigure_lobby(
            &state.worker,
            actor.id,
            game_id,
            request.operation_id,
            request.expected_lobby_revision,
            unciv_authoritative_server::postgres::LobbyConfigurationUpdate {
                display_name: request.display_name,
                human_slots: request.human_slots,
                password,
                setup,
            },
        )
        .await
        .map_err(game_error)?;
    state.notifications.require_resync_for_all();
    Ok(Json(lobby))
}

#[utoipa::path(
    put,
    path = "/api/v3/lobbies/{game_id}/faction",
    params(("game_id" = uuid::Uuid, Path)),
    security(("bearer_auth" = [])),
    request_body = SelectLobbyFactionRequest,
    responses(
        (status = 200, body = unciv_authoritative_server::postgres::LobbySummary),
        (status = 400, body = ErrorResponse),
        (status = 401, body = ErrorResponse),
        (status = 409, body = ErrorResponse)
    )
)]
pub(super) async fn select_lobby_faction(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(game_id): Path<uuid::Uuid>,
    Json(request): Json<SelectLobbyFactionRequest>,
) -> Result<Json<unciv_authoritative_server::postgres::LobbySummary>, ApiError> {
    let actor = authenticated_account(&state, &headers).await?;
    let lobby = state
        .repository
        .reselect_lobby_faction(
            &state.worker,
            actor.id,
            game_id,
            request.operation_id,
            request.expected_lobby_revision,
            request.civilization_id,
        )
        .await
        .map_err(game_error)?;
    state.notifications.require_resync_for_all();
    Ok(Json(lobby))
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
    let lobby = state
        .repository
        .set_lobby_ready(
            actor.id,
            game_id,
            request.expected_lobby_revision,
            request.ready,
        )
        .await
        .map_err(game_error)?;
    state.notifications.require_resync_for_all();
    Ok(Json(lobby))
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
    let lobby = state
        .repository
        .start_lobby(actor.id, game_id, request.expected_lobby_revision)
        .await
        .map_err(game_error)?;
    state.notifications.require_resync_for_all();
    Ok(Json(lobby))
}
