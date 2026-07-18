use std::{
    net::{IpAddr, SocketAddr},
    time::Duration,
};

use axum::{
    Json, Router,
    extract::ws::{Message, WebSocket, WebSocketUpgrade},
    extract::{ConnectInfo, DefaultBodyLimit, Path, State},
    http::{HeaderMap, HeaderValue, StatusCode, header},
    response::{IntoResponse, Response},
    routing::{get, post},
};
use futures_util::{SinkExt, StreamExt};
use serde::{Deserialize, Serialize};
use unciv_authoritative_server::{
    CommandEnvelope, CommitError, GameCommand, PROTOCOL_VERSION,
    auth::{Account, AuthError},
    notifications::{NotificationHub, run_outbox_dispatcher},
    postgres::{GameMetadata, PostgresGameRepository},
    worker::EngineWorkerClient,
};

#[derive(Clone)]
struct AppState {
    repository: PostgresGameRepository,
    worker: EngineWorkerClient,
    notifications: NotificationHub,
}

#[derive(Serialize)]
struct HealthResponse {
    status: &'static str,
    protocol_version: u16,
}

#[derive(Serialize)]
struct CapabilitiesResponse {
    protocol_version: u16,
    projection_version: u16,
    commands: [&'static str; 3],
    whole_state_upload: bool,
    websocket_notifications: bool,
}

#[derive(Deserialize)]
struct CredentialsRequest {
    username: String,
    password: String,
}

#[derive(Serialize)]
struct AccountResponse {
    account_id: uuid::Uuid,
    username: String,
}

#[derive(Serialize)]
struct LoginResponse {
    account: AccountResponse,
    session_token: String,
}

#[derive(Serialize)]
struct SessionResponse {
    session_token: String,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct CreateGameRequest {
    ruleset_manifest_hash: String,
}

#[derive(Serialize)]
struct GameMetadataResponse {
    game_id: uuid::Uuid,
    committed_revision: u64,
    canonical_state_hash: String,
    role: String,
    civilization_id: Option<String>,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct EndTurnRequest {
    command_id: uuid::Uuid,
    expected_revision: u64,
    client_observed_state_hash: Option<String>,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct JoinGameRequest {
    command_id: uuid::Uuid,
    expected_revision: u64,
    client_observed_state_hash: Option<String>,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct MoveUnitRequest {
    command_id: uuid::Uuid,
    expected_revision: u64,
    client_observed_state_hash: Option<String>,
    unit_id: i32,
    destination_x: i32,
    destination_y: i32,
}

#[derive(Serialize)]
struct ErrorResponse {
    code: &'static str,
    #[serde(skip_serializing_if = "Option::is_none")]
    current_revision: Option<u64>,
}

struct ApiError {
    status: StatusCode,
    code: &'static str,
    current_revision: Option<u64>,
    retry_after_seconds: Option<u64>,
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

async fn health() -> Json<HealthResponse> {
    Json(HealthResponse {
        status: "ok",
        protocol_version: PROTOCOL_VERSION,
    })
}

async fn capabilities() -> Json<CapabilitiesResponse> {
    Json(CapabilitiesResponse {
        protocol_version: PROTOCOL_VERSION,
        projection_version: 1,
        commands: ["join_game", "move_unit", "end_turn"],
        whole_state_upload: false,
        websocket_notifications: true,
    })
}

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
        3_600,
        5,
        3_600,
        "registration",
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
        60,
        30,
        60,
        "login",
        &source_prefix,
        Some(&identity),
    )
    .await?;
    let identity_bucket = format!("login:identity:{source_prefix}:{identity}");
    enforce_rate_limit(
        &state,
        &identity_bucket,
        900,
        5,
        900,
        "login",
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
    window_seconds: i32,
    max_requests: i32,
    block_seconds: i32,
    event_type: &str,
    source_prefix: &str,
    identity: Option<&str>,
) -> Result<(), ApiError> {
    match state
        .repository
        .consume_rate_limit(bucket, window_seconds, max_requests, block_seconds)
        .await
    {
        Ok(()) => Ok(()),
        Err(AuthError::RateLimited) => {
            audit_security(
                state,
                None,
                event_type,
                "rate_limited",
                source_prefix,
                identity,
            )
            .await;
            Err(ApiError::rate_limited(block_seconds as u64))
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
        CommitError::InvalidSnapshotHash | CommitError::WorkerRevisionMismatch => ApiError {
            status: StatusCode::BAD_GATEWAY,
            code: "worker_rejected",
            current_revision: None,
            retry_after_seconds: None,
        },
        CommitError::Storage => ApiError::internal(),
    }
}

#[tokio::main]
async fn main() {
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
    let notifications = NotificationHub::default();
    tokio::spawn(run_outbox_dispatcher(
        repository.clone(),
        notifications.clone(),
    ));
    let app = Router::new()
        .route("/healthz", get(health))
        .route("/api/v3/capabilities", get(capabilities))
        .route("/api/v3/notifications", get(websocket_notifications))
        .route("/api/v3/auth/register", post(register))
        .route("/api/v3/auth/login", post(login))
        .route("/api/v3/auth/refresh", post(refresh_session))
        .route("/api/v3/auth/logout", post(logout))
        .route("/api/v3/games", post(create_game))
        .route("/api/v3/games/{game_id}", get(game_metadata))
        .route("/api/v3/games/{game_id}/projection", get(game_projection))
        .route("/api/v3/games/{game_id}/join", post(join_game))
        .route("/api/v3/games/{game_id}/commands/end-turn", post(end_turn))
        .route(
            "/api/v3/games/{game_id}/commands/move-unit",
            post(move_unit),
        )
        .layer(DefaultBodyLimit::max(8 * 1024))
        .with_state(AppState {
            repository,
            worker: EngineWorkerClient::new(worker_address, Duration::from_secs(30)),
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
        assert!(!response.0.whole_state_upload);
        assert!(response.0.commands.contains(&"move_unit"));
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
}
