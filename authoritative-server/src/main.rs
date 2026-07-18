use std::{
    net::{IpAddr, SocketAddr},
    time::Duration,
};

use axum::{
    Json, Router,
    extract::ws::{Message, WebSocket, WebSocketUpgrade},
    extract::{ConnectInfo, DefaultBodyLimit, Path, Query, State},
    http::{HeaderMap, HeaderValue, StatusCode, header},
    response::{IntoResponse, Response},
    routing::{delete, get, post},
};
use futures_util::{SinkExt, StreamExt};
use serde::{Deserialize, Serialize};
use unciv_authoritative_server::{
    CommandEnvelope, CommitError, GameCommand, PROJECTION_VERSION, PROTOCOL_VERSION,
    auth::{Account, AuthError},
    notifications::{NotificationHub, run_outbox_dispatcher},
    postgres::{GameMetadata, PostgresGameRepository},
    worker::EngineWorkerClient,
};
use utoipa::{Modify, OpenApi, ToSchema};

#[derive(Clone)]
struct AppState {
    repository: PostgresGameRepository,
    worker: EngineWorkerClient,
    notifications: NotificationHub,
}

struct RateLimitPolicy {
    window_seconds: i32,
    max_requests: i32,
    block_seconds: i32,
    event_type: &'static str,
}

#[derive(Serialize, ToSchema)]
struct HealthResponse {
    status: &'static str,
    protocol_version: u16,
}

#[derive(Serialize, ToSchema)]
struct CapabilitiesResponse {
    protocol_version: u16,
    projection_version: u16,
    commands: [&'static str; 5],
    whole_state_upload: bool,
    websocket_notifications: bool,
}

#[derive(Deserialize, ToSchema)]
#[serde(deny_unknown_fields)]
struct CredentialsRequest {
    username: String,
    password: String,
}

#[derive(Serialize, ToSchema)]
struct AccountResponse {
    account_id: uuid::Uuid,
    username: String,
}

#[derive(Serialize, ToSchema)]
struct LoginResponse {
    account: AccountResponse,
    session_token: String,
}

#[derive(Serialize, ToSchema)]
struct SessionResponse {
    session_token: String,
}

#[derive(Deserialize, ToSchema)]
#[serde(deny_unknown_fields)]
struct ChangePasswordRequest {
    current_password: String,
    new_password: String,
}

#[derive(Deserialize, ToSchema)]
#[serde(deny_unknown_fields)]
struct ConfirmPasswordRequest {
    password: String,
}

#[derive(Deserialize, ToSchema)]
#[serde(deny_unknown_fields)]
struct CreateGameRequest {
    ruleset_manifest_hash: String,
}

#[derive(Serialize, ToSchema)]
struct GameMetadataResponse {
    game_id: uuid::Uuid,
    committed_revision: u64,
    canonical_state_hash: String,
    role: String,
    civilization_id: Option<String>,
}

#[derive(Deserialize, utoipa::IntoParams)]
#[serde(deny_unknown_fields)]
struct ListGamesQuery {
    after: Option<String>,
    limit: Option<u32>,
}

#[derive(Deserialize, ToSchema)]
#[serde(deny_unknown_fields)]
struct EndTurnRequest {
    command_id: uuid::Uuid,
    expected_revision: u64,
    client_observed_state_hash: Option<String>,
}

#[derive(Deserialize, ToSchema)]
#[serde(deny_unknown_fields)]
struct JoinGameRequest {
    command_id: uuid::Uuid,
    expected_revision: u64,
    client_observed_state_hash: Option<String>,
}

#[derive(Deserialize, ToSchema)]
#[serde(deny_unknown_fields)]
struct MoveUnitRequest {
    command_id: uuid::Uuid,
    expected_revision: u64,
    client_observed_state_hash: Option<String>,
    unit_id: i32,
    destination_x: i32,
    destination_y: i32,
}

#[derive(Deserialize, ToSchema)]
#[serde(deny_unknown_fields)]
struct QueueConstructionRequest {
    command_id: uuid::Uuid,
    expected_revision: u64,
    client_observed_state_hash: Option<String>,
    city_id: String,
    construction_name: String,
}

#[derive(Deserialize, ToSchema)]
#[serde(deny_unknown_fields)]
struct SetResearchPathRequest {
    command_id: uuid::Uuid,
    expected_revision: u64,
    client_observed_state_hash: Option<String>,
    technology_name: String,
}

#[derive(Serialize, ToSchema)]
struct ErrorResponse {
    code: &'static str,
    #[serde(skip_serializing_if = "Option::is_none")]
    current_revision: Option<u64>,
}

#[derive(Debug)]
struct ApiError {
    status: StatusCode,
    code: &'static str,
    current_revision: Option<u64>,
    retry_after_seconds: Option<u64>,
}

#[derive(OpenApi)]
#[openapi(
    paths(
        health,
        capabilities,
        openapi_document,
        register,
        login,
        refresh_session,
        logout,
        change_password,
        disable_account,
        delete_account,
        list_games,
        create_game,
        game_metadata,
        game_projection,
        websocket_notifications,
        join_game,
        end_turn,
        move_unit,
        queue_construction,
        set_research_path
    ),
    components(schemas(
        HealthResponse,
        CapabilitiesResponse,
        CredentialsRequest,
        AccountResponse,
        LoginResponse,
        SessionResponse,
        ChangePasswordRequest,
        ConfirmPasswordRequest,
        CreateGameRequest,
        GameMetadataResponse,
        EndTurnRequest,
        JoinGameRequest,
        MoveUnitRequest,
        QueueConstructionRequest,
        SetResearchPathRequest,
        ErrorResponse,
        unciv_authoritative_server::CommandAccepted,
        unciv_authoritative_server::postgres::GameSummary,
        unciv_authoritative_server::postgres::GamePage,
        unciv_authoritative_server::postgres::GameProjection,
        unciv_authoritative_server::notifications::RevisionNotification,
        unciv_authoritative_server::projection::PlayerProjection,
        unciv_authoritative_server::projection::ProjectedCity,
        unciv_authoritative_server::projection::ProjectedUnit,
        unciv_authoritative_server::projection::ProjectedTileVisibility
    )),
    modifiers(&SecurityAddon),
    tags((name = "authoritative-multiplayer-v3", description = "Server-authoritative Unciv multiplayer API v3"))
)]
struct ApiDoc;

struct SecurityAddon;

impl Modify for SecurityAddon {
    fn modify(&self, openapi: &mut utoipa::openapi::OpenApi) {
        use utoipa::openapi::security::{HttpAuthScheme, HttpBuilder, SecurityScheme};
        if let Some(components) = openapi.components.as_mut() {
            components.add_security_scheme(
                "bearer_auth",
                SecurityScheme::Http(
                    HttpBuilder::new()
                        .scheme(HttpAuthScheme::Bearer)
                        .description(Some("Opaque revocable API-v3 session token"))
                        .build(),
                ),
            );
        }
    }
}

impl ApiError {
    const fn bad_request(code: &'static str) -> Self {
        Self {
            status: StatusCode::BAD_REQUEST,
            code,
            current_revision: None,
            retry_after_seconds: None,
        }
    }

    const fn unauthorized() -> Self {
        Self {
            status: StatusCode::UNAUTHORIZED,
            code: "invalid_credentials",
            current_revision: None,
            retry_after_seconds: None,
        }
    }

    const fn conflict(code: &'static str) -> Self {
        Self {
            status: StatusCode::CONFLICT,
            code,
            current_revision: None,
            retry_after_seconds: None,
        }
    }

    const fn internal() -> Self {
        Self {
            status: StatusCode::INTERNAL_SERVER_ERROR,
            code: "internal_error",
            current_revision: None,
            retry_after_seconds: None,
        }
    }

    const fn rate_limited(retry_after_seconds: u64) -> Self {
        Self {
            status: StatusCode::TOO_MANY_REQUESTS,
            code: "rate_limited",
            current_revision: None,
            retry_after_seconds: Some(retry_after_seconds),
        }
    }
}

impl IntoResponse for ApiError {
    fn into_response(self) -> Response {
        let mut response = (
            self.status,
            Json(ErrorResponse {
                code: self.code,
                current_revision: self.current_revision,
            }),
        )
            .into_response();
        if let Some(seconds) = self.retry_after_seconds {
            response.headers_mut().insert(
                header::RETRY_AFTER,
                HeaderValue::from_str(&seconds.to_string()).expect("retry delay is a valid header"),
            );
        }
        response
    }
}

fn account_response(account: Account) -> AccountResponse {
    AccountResponse {
        account_id: account.id,
        username: account.username_normalized,
    }
}

#[utoipa::path(get, path = "/healthz", responses((status = 200, body = HealthResponse)))]
async fn health() -> Json<HealthResponse> {
    Json(HealthResponse {
        status: "ok",
        protocol_version: PROTOCOL_VERSION,
    })
}

#[utoipa::path(get, path = "/api/v3/capabilities", responses((status = 200, body = CapabilitiesResponse)))]
async fn capabilities() -> Json<CapabilitiesResponse> {
    Json(CapabilitiesResponse {
        protocol_version: PROTOCOL_VERSION,
        projection_version: PROJECTION_VERSION,
        commands: [
            "join_game",
            "move_unit",
            "queue_construction",
            "set_research_path",
            "end_turn",
        ],
        whole_state_upload: false,
        websocket_notifications: true,
    })
}

#[utoipa::path(
    get,
    path = "/api/v3/openapi.json",
    responses((status = 200, description = "Generated OpenAPI 3.1 contract", body = serde_json::Value))
)]
async fn openapi_document() -> Json<utoipa::openapi::OpenApi> {
    Json(ApiDoc::openapi())
}

#[utoipa::path(
    post,
    path = "/api/v3/auth/register",
    request_body = CredentialsRequest,
    responses(
        (status = 201, body = AccountResponse),
        (status = 400, body = ErrorResponse),
        (status = 409, body = ErrorResponse),
        (status = 429, body = ErrorResponse),
        (status = 500, body = ErrorResponse)
    )
)]
async fn register(
    State(state): State<AppState>,
    ConnectInfo(source): ConnectInfo<SocketAddr>,
    Json(credentials): Json<CredentialsRequest>,
) -> Result<(StatusCode, Json<AccountResponse>), ApiError> {
    let source_prefix = source_prefix(source.ip());
    let identity = credentials.username.trim().to_ascii_lowercase();
    enforce_rate_limit(
        &state,
        &format!("register:source:{source_prefix}"),
        RateLimitPolicy {
            window_seconds: 3_600,
            max_requests: 5,
            block_seconds: 3_600,
            event_type: "registration",
        },
        &source_prefix,
        Some(&identity),
    )
    .await?;
    let result = state
        .repository
        .register_account(&credentials.username, &credentials.password)
        .await;
    match result {
        Ok(account) => {
            audit_security(
                &state,
                Some(account.id),
                "registration",
                "success",
                &source_prefix,
                Some(&identity),
            )
            .await;
            Ok((StatusCode::CREATED, Json(account_response(account))))
        }
        Err(error) => {
            audit_security(
                &state,
                None,
                "registration",
                "rejected",
                &source_prefix,
                Some(&identity),
            )
            .await;
            Err(register_error(error))
        }
    }
}

#[utoipa::path(
    post,
    path = "/api/v3/auth/login",
    request_body = CredentialsRequest,
    responses(
        (status = 200, body = LoginResponse),
        (status = 401, body = ErrorResponse),
        (status = 429, body = ErrorResponse),
        (status = 500, body = ErrorResponse)
    )
)]
async fn login(
    State(state): State<AppState>,
    ConnectInfo(source): ConnectInfo<SocketAddr>,
    Json(credentials): Json<CredentialsRequest>,
) -> Result<Json<LoginResponse>, ApiError> {
    let source_prefix = source_prefix(source.ip());
    let identity = credentials.username.trim().to_ascii_lowercase();
    enforce_rate_limit(
        &state,
        &format!("login:source:{source_prefix}"),
        RateLimitPolicy {
            window_seconds: 60,
            max_requests: 30,
            block_seconds: 60,
            event_type: "login",
        },
        &source_prefix,
        Some(&identity),
    )
    .await?;
    let identity_bucket = format!("login:identity:{source_prefix}:{identity}");
    enforce_rate_limit(
        &state,
        &identity_bucket,
        RateLimitPolicy {
            window_seconds: 900,
            max_requests: 5,
            block_seconds: 900,
            event_type: "login",
        },
        &source_prefix,
        Some(&identity),
    )
    .await?;
    let account = match state
        .repository
        .authenticate_account(&credentials.username, &credentials.password)
        .await
    {
        Ok(account) => account,
        Err(error) => {
            audit_security(
                &state,
                None,
                "login",
                "rejected",
                &source_prefix,
                Some(&identity),
            )
            .await;
            return Err(login_error(error));
        }
    };
    state
        .repository
        .clear_rate_limit(&identity_bucket)
        .await
        .map_err(login_error)?;
    let credential = state
        .repository
        .issue_session(account.id)
        .await
        .map_err(|_| ApiError::internal())?;
    audit_security(
        &state,
        Some(account.id),
        "login",
        "success",
        &source_prefix,
        Some(&identity),
    )
    .await;
    Ok(Json(LoginResponse {
        account: account_response(account),
        session_token: credential.token,
    }))
}

async fn enforce_rate_limit(
    state: &AppState,
    bucket: &str,
    policy: RateLimitPolicy,
    source_prefix: &str,
    identity: Option<&str>,
) -> Result<(), ApiError> {
    match state
        .repository
        .consume_rate_limit(
            bucket,
            policy.window_seconds,
            policy.max_requests,
            policy.block_seconds,
        )
        .await
    {
        Ok(()) => Ok(()),
        Err(AuthError::RateLimited) => {
            audit_security(
                state,
                None,
                policy.event_type,
                "rate_limited",
                source_prefix,
                identity,
            )
            .await;
            Err(ApiError::rate_limited(policy.block_seconds as u64))
        }
        Err(_) => Err(ApiError::internal()),
    }
}

async fn audit_security(
    state: &AppState,
    account_id: Option<uuid::Uuid>,
    event_type: &str,
    outcome: &str,
    source_prefix: &str,
    identity: Option<&str>,
) {
    if let Err(error) = state
        .repository
        .record_security_audit(account_id, event_type, outcome, source_prefix, identity)
        .await
    {
        eprintln!("authoritative security audit write failed: {error}");
    }
}

fn source_prefix(address: IpAddr) -> String {
    match address {
        IpAddr::V4(address) => {
            let [a, b, c, _] = address.octets();
            format!("{a}.{b}.{c}.0/24")
        }
        IpAddr::V6(address) => {
            let segments = address.segments();
            format!(
                "{:x}:{:x}:{:x}:{:x}::/64",
                segments[0], segments[1], segments[2], segments[3]
            )
        }
    }
}

#[utoipa::path(
    post,
    path = "/api/v3/auth/logout",
    security(("bearer_auth" = [])),
    responses((status = 204), (status = 401, body = ErrorResponse), (status = 500, body = ErrorResponse))
)]
async fn logout(State(state): State<AppState>, headers: HeaderMap) -> Result<StatusCode, ApiError> {
    let bearer_token = bearer_token(&headers).ok_or_else(ApiError::unauthorized)?;
    // Resolve before revocation so malformed, expired, and already-revoked
    // credentials receive the same generic rejection.
    state
        .repository
        .authenticate_session(bearer_token)
        .await
        .map_err(login_error)?;
    state
        .repository
        .revoke_session(bearer_token)
        .await
        .map_err(|_| ApiError::internal())?;
    Ok(StatusCode::NO_CONTENT)
}

#[utoipa::path(
    post,
    path = "/api/v3/auth/refresh",
    security(("bearer_auth" = [])),
    responses((status = 200, body = SessionResponse), (status = 401, body = ErrorResponse), (status = 500, body = ErrorResponse))
)]
async fn refresh_session(
    State(state): State<AppState>,
    headers: HeaderMap,
) -> Result<Json<SessionResponse>, ApiError> {
    let bearer_token = bearer_token(&headers).ok_or_else(ApiError::unauthorized)?;
    let credential = state
        .repository
        .rotate_session(bearer_token)
        .await
        .map_err(login_error)?;
    Ok(Json(SessionResponse {
        session_token: credential.token,
    }))
}

#[utoipa::path(
    post,
    path = "/api/v3/account/password",
    security(("bearer_auth" = [])),
    request_body = ChangePasswordRequest,
    responses(
        (status = 200, body = SessionResponse),
        (status = 400, body = ErrorResponse),
        (status = 401, body = ErrorResponse),
        (status = 429, body = ErrorResponse),
        (status = 500, body = ErrorResponse)
    )
)]
async fn change_password(
    State(state): State<AppState>,
    ConnectInfo(source): ConnectInfo<SocketAddr>,
    headers: HeaderMap,
    Json(request): Json<ChangePasswordRequest>,
) -> Result<Json<SessionResponse>, ApiError> {
    let actor = authenticated_account(&state, &headers).await?;
    enforce_account_security_rate_limit(&state, actor.id, source.ip()).await?;
    let credential = match state
        .repository
        .change_password(actor.id, &request.current_password, &request.new_password)
        .await
    {
        Ok(credential) => credential,
        Err(error) => {
            audit_account_lifecycle(&state, actor.id, "password_change", "rejected", source.ip())
                .await;
            return Err(account_change_error(error));
        }
    };
    audit_account_lifecycle(&state, actor.id, "password_change", "success", source.ip()).await;
    Ok(Json(SessionResponse {
        session_token: credential.token,
    }))
}

#[utoipa::path(
    post,
    path = "/api/v3/account/disable",
    security(("bearer_auth" = [])),
    request_body = ConfirmPasswordRequest,
    responses(
        (status = 204),
        (status = 401, body = ErrorResponse),
        (status = 429, body = ErrorResponse),
        (status = 500, body = ErrorResponse)
    )
)]
async fn disable_account(
    State(state): State<AppState>,
    ConnectInfo(source): ConnectInfo<SocketAddr>,
    headers: HeaderMap,
    Json(request): Json<ConfirmPasswordRequest>,
) -> Result<StatusCode, ApiError> {
    let actor = authenticated_account(&state, &headers).await?;
    enforce_account_security_rate_limit(&state, actor.id, source.ip()).await?;
    if let Err(error) = state
        .repository
        .disable_account(actor.id, &request.password)
        .await
    {
        audit_account_lifecycle(&state, actor.id, "account_disable", "rejected", source.ip()).await;
        return Err(account_change_error(error));
    }
    audit_account_lifecycle(&state, actor.id, "account_disable", "success", source.ip()).await;
    Ok(StatusCode::NO_CONTENT)
}

#[utoipa::path(
    delete,
    path = "/api/v3/account",
    security(("bearer_auth" = [])),
    request_body = ConfirmPasswordRequest,
    responses(
        (status = 204),
        (status = 401, body = ErrorResponse),
        (status = 429, body = ErrorResponse),
        (status = 500, body = ErrorResponse)
    )
)]
async fn delete_account(
    State(state): State<AppState>,
    ConnectInfo(source): ConnectInfo<SocketAddr>,
    headers: HeaderMap,
    Json(request): Json<ConfirmPasswordRequest>,
) -> Result<StatusCode, ApiError> {
    let actor = authenticated_account(&state, &headers).await?;
    enforce_account_security_rate_limit(&state, actor.id, source.ip()).await?;
    if let Err(error) = state
        .repository
        .delete_account(actor.id, &request.password)
        .await
    {
        audit_account_lifecycle(&state, actor.id, "account_delete", "rejected", source.ip()).await;
        return Err(account_change_error(error));
    }
    audit_account_lifecycle(&state, actor.id, "account_delete", "success", source.ip()).await;
    Ok(StatusCode::NO_CONTENT)
}

async fn enforce_account_security_rate_limit(
    state: &AppState,
    account_id: uuid::Uuid,
    source: IpAddr,
) -> Result<(), ApiError> {
    let source_prefix = source_prefix(source);
    enforce_rate_limit(
        state,
        &format!("account-security:{account_id}:{source_prefix}"),
        RateLimitPolicy {
            window_seconds: 900,
            max_requests: 5,
            block_seconds: 900,
            event_type: "account_security",
        },
        &source_prefix,
        None,
    )
    .await
}

async fn audit_account_lifecycle(
    state: &AppState,
    account_id: uuid::Uuid,
    event_type: &str,
    outcome: &str,
    source: IpAddr,
) {
    audit_security(
        state,
        Some(account_id),
        event_type,
        outcome,
        &source_prefix(source),
        None,
    )
    .await;
}

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
async fn create_game(
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
async fn game_metadata(
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
async fn list_games(
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

fn game_page_cursor(requested: Option<&str>) -> Result<Option<uuid::Uuid>, ApiError> {
    requested
        .map(uuid::Uuid::parse_str)
        .transpose()
        .map_err(|_| ApiError::bad_request("invalid_page_cursor"))
}

fn game_page_limit(requested: Option<u32>) -> Result<u32, ApiError> {
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
async fn game_projection(
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
    path = "/api/v3/notifications",
    security(("bearer_auth" = [])),
    responses(
        (status = 101, description = "WebSocket revision-hint stream", body = unciv_authoritative_server::notifications::RevisionNotification),
        (status = 401, body = ErrorResponse)
    )
)]
async fn websocket_notifications(
    websocket: WebSocketUpgrade,
    State(state): State<AppState>,
    headers: HeaderMap,
) -> Result<Response, ApiError> {
    let actor = authenticated_account(&state, &headers).await?;
    let receiver = state.notifications.subscribe(actor.id).await;
    Ok(websocket.on_upgrade(move |socket| serve_websocket(socket, receiver)))
}

async fn serve_websocket(
    socket: WebSocket,
    mut receiver: tokio::sync::broadcast::Receiver<
        unciv_authoritative_server::notifications::RevisionNotification,
    >,
) {
    let (mut sender, mut incoming) = socket.split();
    loop {
        tokio::select! {
            notification = receiver.recv() => match notification {
                Ok(notification) => {
                    let payload = serde_json::to_string(&notification)
                        .expect("revision notification is serializable");
                    if sender.send(Message::Text(payload.into())).await.is_err() {
                        break;
                    }
                }
                Err(tokio::sync::broadcast::error::RecvError::Lagged(_)) => {
                    // Exact missed revisions do not matter: this explicitly
                    // instructs the client to fetch its latest HTTP projection.
                    if sender.send(Message::Text(
                        r#"{"type":"resync_required","protocol_version":3}"#.into()
                    )).await.is_err() {
                        break;
                    }
                }
                Err(tokio::sync::broadcast::error::RecvError::Closed) => break,
            },
            message = incoming.next() => match message {
                Some(Ok(Message::Close(_))) | None | Some(Err(_)) => break,
                _ => {}
            }
        }
    }
}

#[utoipa::path(
    post,
    path = "/api/v3/games/{game_id}/join",
    params(("game_id" = uuid::Uuid, Path)),
    security(("bearer_auth" = [])),
    request_body = JoinGameRequest,
    responses(
        (status = 200, body = unciv_authoritative_server::CommandAccepted),
        (status = 401, body = ErrorResponse),
        (status = 403, body = ErrorResponse),
        (status = 404, body = ErrorResponse),
        (status = 409, body = ErrorResponse),
        (status = 422, body = ErrorResponse),
        (status = 503, body = ErrorResponse)
    )
)]
async fn join_game(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(game_id): Path<uuid::Uuid>,
    Json(request): Json<JoinGameRequest>,
) -> Result<Json<unciv_authoritative_server::CommandAccepted>, ApiError> {
    let actor = authenticated_account(&state, &headers).await?;
    let accepted = state
        .repository
        .execute_join(
            &state.worker,
            actor.id,
            CommandEnvelope {
                protocol_version: PROTOCOL_VERSION,
                game_id,
                command_id: request.command_id,
                expected_revision: request.expected_revision,
                client_observed_state_hash: request.client_observed_state_hash,
                command: GameCommand::JoinGame,
            },
        )
        .await
        .map_err(game_error)?;
    Ok(Json(accepted))
}

/// The first public gameplay mutation. The payload is intentionally closed:
/// an authenticated member can request only `EndTurn`, never submit a state
/// replacement or generic object patch.
#[utoipa::path(
    post,
    path = "/api/v3/games/{game_id}/commands/end-turn",
    params(("game_id" = uuid::Uuid, Path)),
    security(("bearer_auth" = [])),
    request_body = EndTurnRequest,
    responses(
        (status = 200, body = unciv_authoritative_server::CommandAccepted),
        (status = 401, body = ErrorResponse),
        (status = 403, body = ErrorResponse),
        (status = 404, body = ErrorResponse),
        (status = 409, body = ErrorResponse),
        (status = 422, body = ErrorResponse),
        (status = 503, body = ErrorResponse)
    )
)]
async fn end_turn(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(game_id): Path<uuid::Uuid>,
    Json(request): Json<EndTurnRequest>,
) -> Result<Json<unciv_authoritative_server::CommandAccepted>, ApiError> {
    let actor = authenticated_account(&state, &headers).await?;
    let accepted = state
        .repository
        .execute_end_turn(
            &state.worker,
            actor.id,
            CommandEnvelope {
                protocol_version: PROTOCOL_VERSION,
                game_id,
                command_id: request.command_id,
                expected_revision: request.expected_revision,
                client_observed_state_hash: request.client_observed_state_hash,
                command: GameCommand::EndTurn,
            },
        )
        .await
        .map_err(game_error)?;
    Ok(Json(accepted))
}

#[utoipa::path(
    post,
    path = "/api/v3/games/{game_id}/commands/move-unit",
    params(("game_id" = uuid::Uuid, Path)),
    security(("bearer_auth" = [])),
    request_body = MoveUnitRequest,
    responses(
        (status = 200, body = unciv_authoritative_server::CommandAccepted),
        (status = 401, body = ErrorResponse),
        (status = 403, body = ErrorResponse),
        (status = 404, body = ErrorResponse),
        (status = 409, body = ErrorResponse),
        (status = 422, body = ErrorResponse),
        (status = 503, body = ErrorResponse)
    )
)]
async fn move_unit(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(game_id): Path<uuid::Uuid>,
    Json(request): Json<MoveUnitRequest>,
) -> Result<Json<unciv_authoritative_server::CommandAccepted>, ApiError> {
    let actor = authenticated_account(&state, &headers).await?;
    let accepted = state
        .repository
        .execute_move_unit(
            &state.worker,
            actor.id,
            CommandEnvelope {
                protocol_version: PROTOCOL_VERSION,
                game_id,
                command_id: request.command_id,
                expected_revision: request.expected_revision,
                client_observed_state_hash: request.client_observed_state_hash,
                command: GameCommand::MoveUnit {
                    unit_id: request.unit_id,
                    destination_x: request.destination_x,
                    destination_y: request.destination_y,
                },
            },
        )
        .await
        .map_err(game_error)?;
    Ok(Json(accepted))
}

#[utoipa::path(
    post,
    path = "/api/v3/games/{game_id}/commands/queue-construction",
    params(("game_id" = uuid::Uuid, Path)),
    security(("bearer_auth" = [])),
    request_body = QueueConstructionRequest,
    responses(
        (status = 200, body = unciv_authoritative_server::CommandAccepted),
        (status = 400, body = ErrorResponse),
        (status = 401, body = ErrorResponse),
        (status = 403, body = ErrorResponse),
        (status = 404, body = ErrorResponse),
        (status = 409, body = ErrorResponse),
        (status = 422, body = ErrorResponse),
        (status = 503, body = ErrorResponse)
    )
)]
async fn queue_construction(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(game_id): Path<uuid::Uuid>,
    Json(request): Json<QueueConstructionRequest>,
) -> Result<Json<unciv_authoritative_server::CommandAccepted>, ApiError> {
    if request.city_id.is_empty()
        || request.city_id.len() > 64
        || request.construction_name.is_empty()
        || request.construction_name.len() > 128
    {
        return Err(ApiError::bad_request("invalid_command"));
    }
    let actor = authenticated_account(&state, &headers).await?;
    let accepted = state
        .repository
        .execute_queue_construction(
            &state.worker,
            actor.id,
            CommandEnvelope {
                protocol_version: PROTOCOL_VERSION,
                game_id,
                command_id: request.command_id,
                expected_revision: request.expected_revision,
                client_observed_state_hash: request.client_observed_state_hash,
                command: GameCommand::QueueConstruction {
                    city_id: request.city_id,
                    construction_name: request.construction_name,
                },
            },
        )
        .await
        .map_err(game_error)?;
    Ok(Json(accepted))
}

#[utoipa::path(
    post,
    path = "/api/v3/games/{game_id}/commands/set-research-path",
    params(("game_id" = uuid::Uuid, Path)),
    security(("bearer_auth" = [])),
    request_body = SetResearchPathRequest,
    responses(
        (status = 200, body = unciv_authoritative_server::CommandAccepted),
        (status = 400, body = ErrorResponse),
        (status = 401, body = ErrorResponse),
        (status = 403, body = ErrorResponse),
        (status = 404, body = ErrorResponse),
        (status = 409, body = ErrorResponse),
        (status = 422, body = ErrorResponse),
        (status = 503, body = ErrorResponse)
    )
)]
async fn set_research_path(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(game_id): Path<uuid::Uuid>,
    Json(request): Json<SetResearchPathRequest>,
) -> Result<Json<unciv_authoritative_server::CommandAccepted>, ApiError> {
    if request.technology_name.is_empty() || request.technology_name.len() > 128 {
        return Err(ApiError::bad_request("invalid_command"));
    }
    let actor = authenticated_account(&state, &headers).await?;
    let accepted = state
        .repository
        .execute_set_research_path(
            &state.worker,
            actor.id,
            CommandEnvelope {
                protocol_version: PROTOCOL_VERSION,
                game_id,
                command_id: request.command_id,
                expected_revision: request.expected_revision,
                client_observed_state_hash: request.client_observed_state_hash,
                command: GameCommand::SetResearchPath {
                    technology_name: request.technology_name,
                },
            },
        )
        .await
        .map_err(game_error)?;
    Ok(Json(accepted))
}

async fn authenticated_account(state: &AppState, headers: &HeaderMap) -> Result<Account, ApiError> {
    let bearer_token = bearer_token(headers).ok_or_else(ApiError::unauthorized)?;
    state
        .repository
        .authenticate_session(bearer_token)
        .await
        .map_err(login_error)
}

fn game_metadata_response(metadata: GameMetadata) -> GameMetadataResponse {
    GameMetadataResponse {
        game_id: metadata.game_id,
        committed_revision: metadata.committed_revision,
        canonical_state_hash: metadata.canonical_state_hash,
        role: metadata.role,
        civilization_id: metadata.civilization_id,
    }
}

fn bearer_token(headers: &HeaderMap) -> Option<&str> {
    headers
        .get("authorization")?
        .to_str()
        .ok()?
        .strip_prefix("Bearer ")
}

fn register_error(error: AuthError) -> ApiError {
    match error {
        AuthError::InvalidUsername(_) | AuthError::InvalidPassword(_) => {
            ApiError::bad_request("invalid_registration")
        }
        AuthError::UsernameTaken => ApiError::conflict("username_taken"),
        AuthError::RateLimited => ApiError::rate_limited(60),
        AuthError::Storage => ApiError::internal(),
        AuthError::InvalidCredentials | AuthError::AccountDisabled => ApiError::internal(),
    }
}

fn login_error(error: AuthError) -> ApiError {
    match error {
        AuthError::InvalidCredentials
        | AuthError::AccountDisabled
        | AuthError::InvalidUsername(_) => ApiError::unauthorized(),
        AuthError::Storage => ApiError::internal(),
        AuthError::RateLimited => ApiError::rate_limited(60),
        AuthError::InvalidPassword(_) | AuthError::UsernameTaken => ApiError::internal(),
    }
}

fn account_change_error(error: AuthError) -> ApiError {
    match error {
        AuthError::InvalidPassword(_) => ApiError::bad_request("invalid_password"),
        AuthError::InvalidCredentials | AuthError::AccountDisabled => ApiError::unauthorized(),
        AuthError::RateLimited => ApiError::rate_limited(900),
        AuthError::Storage => ApiError::internal(),
        AuthError::InvalidUsername(_) | AuthError::UsernameTaken => ApiError::internal(),
    }
}

fn game_error(error: CommitError) -> ApiError {
    eprintln!("authoritative game command failed: {error}");
    match error {
        CommitError::NotFound => ApiError {
            status: StatusCode::NOT_FOUND,
            code: "not_found",
            current_revision: None,
            retry_after_seconds: None,
        },
        CommitError::Unauthorized => ApiError {
            status: StatusCode::FORBIDDEN,
            code: "forbidden",
            current_revision: None,
            retry_after_seconds: None,
        },
        CommitError::Stale { actual, .. } => ApiError {
            status: StatusCode::CONFLICT,
            code: "stale_revision",
            current_revision: Some(actual),
            retry_after_seconds: None,
        },
        CommitError::InvalidCommand => ApiError {
            status: StatusCode::UNPROCESSABLE_ENTITY,
            code: "invalid_command",
            current_revision: None,
            retry_after_seconds: None,
        },
        CommitError::UnsupportedProtocol(_) => ApiError::bad_request("unsupported_protocol"),
        CommitError::WorkerRejected(reason) => {
            // Worker errors are deliberately reduced to a stable public code.
            // The local service log retains the diagnostic without logging a
            // snapshot, credentials, or a player projection.
            eprintln!("authoritative worker rejected command: {reason}");
            ApiError {
                status: StatusCode::UNPROCESSABLE_ENTITY,
                code: "invalid_command",
                current_revision: None,
                retry_after_seconds: None,
            }
        }
        CommitError::InvalidSnapshotHash
        | CommitError::SnapshotTooLarge
        | CommitError::WorkerRevisionMismatch => ApiError {
            status: StatusCode::BAD_GATEWAY,
            code: "worker_rejected",
            current_revision: None,
            retry_after_seconds: None,
        },
        CommitError::GameUnavailable => ApiError {
            status: StatusCode::SERVICE_UNAVAILABLE,
            code: "game_unavailable",
            current_revision: None,
            retry_after_seconds: None,
        },
        CommitError::Storage => ApiError::internal(),
    }
}

#[tokio::main]
async fn main() {
    if std::env::args().any(|argument| argument == "--write-openapi") {
        let target = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("openapi")
            .join("api-v3.json");
        std::fs::create_dir_all(target.parent().expect("OpenAPI target has a parent"))
            .expect("failed to create OpenAPI output directory");
        std::fs::write(
            &target,
            format!(
                "{}\n",
                serde_json::to_string_pretty(&ApiDoc::openapi())
                    .expect("OpenAPI document is serializable")
            ),
        )
        .expect("failed to write generated OpenAPI document");
        eprintln!("wrote {}", target.display());
        return;
    }
    let address = std::env::var("UNCIV_V3_BIND")
        .unwrap_or_else(|_| "127.0.0.1:3000".to_owned())
        .parse::<SocketAddr>()
        .expect("UNCIV_V3_BIND must be a socket address");
    let database_url = std::env::var("UNCIV_V3_DATABASE_URL")
        .expect("UNCIV_V3_DATABASE_URL is required for the authoritative API");
    let repository = PostgresGameRepository::connect(&database_url)
        .await
        .expect("failed to connect to UNCIV_V3_DATABASE_URL");
    repository
        .migrate()
        .await
        .expect("failed to migrate authoritative database");
    let worker_address = std::env::var("UNCIV_ENGINE_WORKER_ADDR")
        .unwrap_or_else(|_| "127.0.0.1:43170".to_owned())
        .parse::<SocketAddr>()
        .expect("UNCIV_ENGINE_WORKER_ADDR must be a socket address");
    let worker = EngineWorkerClient::new(worker_address, Duration::from_secs(30));
    let worker_capabilities = worker
        .handshake()
        .await
        .expect("authoritative engine worker handshake failed");
    eprintln!(
        "authoritative engine worker ready: protocol={}, engine_build={}, rulesets={}",
        unciv_authoritative_server::worker::WORKER_PROTOCOL_VERSION,
        worker_capabilities.engine_build,
        worker_capabilities.installed_rulesets.len(),
    );
    let notifications = NotificationHub::default();
    tokio::spawn(run_outbox_dispatcher(
        repository.clone(),
        notifications.clone(),
    ));
    let app = Router::new()
        .route("/healthz", get(health))
        .route("/api/v3/capabilities", get(capabilities))
        .route("/api/v3/openapi.json", get(openapi_document))
        .route("/api/v3/notifications", get(websocket_notifications))
        .route("/api/v3/auth/register", post(register))
        .route("/api/v3/auth/login", post(login))
        .route("/api/v3/auth/refresh", post(refresh_session))
        .route("/api/v3/auth/logout", post(logout))
        .route("/api/v3/account/password", post(change_password))
        .route("/api/v3/account/disable", post(disable_account))
        .route("/api/v3/account", delete(delete_account))
        .route("/api/v3/games", get(list_games).post(create_game))
        .route("/api/v3/games/{game_id}", get(game_metadata))
        .route("/api/v3/games/{game_id}/projection", get(game_projection))
        .route("/api/v3/games/{game_id}/join", post(join_game))
        .route("/api/v3/games/{game_id}/commands/end-turn", post(end_turn))
        .route(
            "/api/v3/games/{game_id}/commands/move-unit",
            post(move_unit),
        )
        .route(
            "/api/v3/games/{game_id}/commands/queue-construction",
            post(queue_construction),
        )
        .route(
            "/api/v3/games/{game_id}/commands/set-research-path",
            post(set_research_path),
        )
        .layer(DefaultBodyLimit::max(8 * 1024))
        .with_state(AppState {
            repository,
            worker,
            notifications,
        });
    let listener = tokio::net::TcpListener::bind(address)
        .await
        .expect("failed to bind UNCIV_V3_BIND");
    axum::serve(
        listener,
        app.into_make_service_with_connect_info::<SocketAddr>(),
    )
    .await
    .expect("authoritative API server failed");
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn generated_openapi_matches_checked_in_contract() {
        let generated = format!(
            "{}\n",
            serde_json::to_string_pretty(&ApiDoc::openapi()).unwrap()
        );
        assert_eq!(generated, include_str!("../openapi/api-v3.json"));
    }

    #[test]
    fn openapi_covers_routes_security_and_closed_command_shapes() {
        let document = serde_json::to_value(ApiDoc::openapi()).unwrap();
        let paths = document["paths"].as_object().unwrap();
        let expected_paths = [
            "/healthz",
            "/api/v3/capabilities",
            "/api/v3/openapi.json",
            "/api/v3/notifications",
            "/api/v3/auth/register",
            "/api/v3/auth/login",
            "/api/v3/auth/refresh",
            "/api/v3/auth/logout",
            "/api/v3/account/password",
            "/api/v3/account/disable",
            "/api/v3/account",
            "/api/v3/games",
            "/api/v3/games/{game_id}",
            "/api/v3/games/{game_id}/projection",
            "/api/v3/games/{game_id}/join",
            "/api/v3/games/{game_id}/commands/end-turn",
            "/api/v3/games/{game_id}/commands/move-unit",
            "/api/v3/games/{game_id}/commands/queue-construction",
            "/api/v3/games/{game_id}/commands/set-research-path",
        ];
        assert_eq!(paths.len(), expected_paths.len());
        for path in expected_paths {
            assert!(paths.contains_key(path), "missing OpenAPI path {path}");
        }
        assert_eq!(
            document["components"]["securitySchemes"]["bearer_auth"]["scheme"],
            "bearer"
        );
        for (path, methods) in paths {
            for operation in methods.as_object().unwrap().values() {
                let public = matches!(
                    path.as_str(),
                    "/healthz"
                        | "/api/v3/capabilities"
                        | "/api/v3/openapi.json"
                        | "/api/v3/auth/register"
                        | "/api/v3/auth/login"
                );
                assert_eq!(
                    operation.get("security").is_some(),
                    !public,
                    "incorrect security declaration for {path}"
                );
            }
        }
        for schema in [
            "EndTurnRequest",
            "JoinGameRequest",
            "MoveUnitRequest",
            "QueueConstructionRequest",
        ] {
            assert_eq!(
                document["components"]["schemas"][schema]["additionalProperties"], false,
                "{schema} must remain a closed request object"
            );
        }
        assert!(
            document["components"]["schemas"]["GameProjection"]["properties"]["projection"]["$ref"]
                .as_str()
                .unwrap()
                .ends_with("/PlayerProjection")
        );
        let serialized = serde_json::to_string(&document).unwrap();
        assert!(!serialized.contains("GameInfo"));
        assert!(!serialized.contains("snapshot"));
    }

    #[test]
    fn stale_errors_expose_the_canonical_revision() {
        let error = game_error(CommitError::Stale {
            expected: 3,
            actual: 5,
        });
        assert_eq!(error.status, StatusCode::CONFLICT);
        assert_eq!(error.code, "stale_revision");
        assert_eq!(error.current_revision, Some(5));
    }

    #[tokio::test]
    async fn capabilities_forbid_whole_state_uploads() {
        let response = capabilities().await;
        assert_eq!(response.0.protocol_version, PROTOCOL_VERSION);
        assert_eq!(response.0.projection_version, PROJECTION_VERSION);
        assert!(!response.0.whole_state_upload);
        assert!(response.0.commands.contains(&"move_unit"));
        assert!(response.0.commands.contains(&"queue_construction"));
        assert!(response.0.commands.contains(&"set_research_path"));
    }

    #[test]
    fn rate_limit_response_and_network_prefixes_are_stable() {
        let response = ApiError::rate_limited(900).into_response();
        assert_eq!(response.status(), StatusCode::TOO_MANY_REQUESTS);
        assert_eq!(response.headers()[header::RETRY_AFTER], "900");
        assert_eq!(source_prefix("192.0.2.99".parse().unwrap()), "192.0.2.0/24");
        assert_eq!(
            source_prefix("2001:db8:abcd:1234:ffff::1".parse().unwrap()),
            "2001:db8:abcd:1234::/64"
        );
    }

    #[test]
    fn corrupt_games_fail_closed_with_stable_unavailable_semantics() {
        let error = game_error(CommitError::GameUnavailable);
        assert_eq!(error.status, StatusCode::SERVICE_UNAVAILABLE);
        assert_eq!(error.code, "game_unavailable");
        assert_eq!(error.current_revision, None);
    }

    #[test]
    fn game_discovery_page_limits_are_bounded_and_stable() {
        assert_eq!(game_page_limit(None).unwrap(), 50);
        assert_eq!(game_page_limit(Some(100)).unwrap(), 100);
        for invalid in [0, 101, u32::MAX] {
            let error = game_page_limit(Some(invalid)).unwrap_err();
            assert_eq!(error.status, StatusCode::BAD_REQUEST);
            assert_eq!(error.code, "invalid_page_limit");
        }
        assert_eq!(game_page_cursor(None).unwrap(), None);
        assert!(game_page_cursor(Some("00000000-0000-0000-0000-000000000001")).is_ok());
        let error = game_page_cursor(Some("not-a-uuid")).unwrap_err();
        assert_eq!(error.status, StatusCode::BAD_REQUEST);
        assert_eq!(error.code, "invalid_page_cursor");
    }
}
