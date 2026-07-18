use std::{net::SocketAddr, time::Duration};

use axum::{
    Json, Router,
    extract::{DefaultBodyLimit, Path, State},
    http::{HeaderMap, StatusCode},
    response::{IntoResponse, Response},
    routing::{get, post},
};
use serde::{Deserialize, Serialize};
use unciv_authoritative_server::{
    CommandEnvelope, CommitError, GameCommand, PROTOCOL_VERSION,
    auth::{Account, AuthError},
    postgres::{GameMetadata, PostgresGameRepository},
    worker::EngineWorkerClient,
};

#[derive(Clone)]
struct AppState {
    repository: PostgresGameRepository,
    worker: EngineWorkerClient,
}

#[derive(Serialize)]
struct HealthResponse {
    status: &'static str,
    protocol_version: u16,
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
}

struct ApiError {
    status: StatusCode,
    code: &'static str,
}

impl ApiError {
    const fn bad_request(code: &'static str) -> Self {
        Self {
            status: StatusCode::BAD_REQUEST,
            code,
        }
    }

    const fn unauthorized() -> Self {
        Self {
            status: StatusCode::UNAUTHORIZED,
            code: "invalid_credentials",
        }
    }

    const fn conflict(code: &'static str) -> Self {
        Self {
            status: StatusCode::CONFLICT,
            code,
        }
    }

    const fn internal() -> Self {
        Self {
            status: StatusCode::INTERNAL_SERVER_ERROR,
            code: "internal_error",
        }
    }
}

impl IntoResponse for ApiError {
    fn into_response(self) -> Response {
        (self.status, Json(ErrorResponse { code: self.code })).into_response()
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

async fn register(
    State(state): State<AppState>,
    Json(credentials): Json<CredentialsRequest>,
) -> Result<(StatusCode, Json<AccountResponse>), ApiError> {
    let account = state
        .repository
        .register_account(&credentials.username, &credentials.password)
        .await
        .map_err(register_error)?;
    Ok((StatusCode::CREATED, Json(account_response(account))))
}

async fn login(
    State(state): State<AppState>,
    Json(credentials): Json<CredentialsRequest>,
) -> Result<Json<LoginResponse>, ApiError> {
    let account = state
        .repository
        .authenticate_account(&credentials.username, &credentials.password)
        .await
        .map_err(login_error)?;
    let credential = state
        .repository
        .issue_session(account.id)
        .await
        .map_err(|_| ApiError::internal())?;
    Ok(Json(LoginResponse {
        account: account_response(account),
        session_token: credential.token,
    }))
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
        AuthError::InvalidPassword(_) | AuthError::UsernameTaken => ApiError::internal(),
    }
}

fn game_error(error: CommitError) -> ApiError {
    eprintln!("authoritative game command failed: {error}");
    match error {
        CommitError::NotFound => ApiError {
            status: StatusCode::NOT_FOUND,
            code: "not_found",
        },
        CommitError::Unauthorized => ApiError {
            status: StatusCode::FORBIDDEN,
            code: "forbidden",
        },
        CommitError::Stale { .. } => ApiError::conflict("stale_revision"),
        CommitError::InvalidCommand => ApiError {
            status: StatusCode::UNPROCESSABLE_ENTITY,
            code: "invalid_command",
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
            }
        }
        CommitError::InvalidSnapshotHash | CommitError::WorkerRevisionMismatch => ApiError {
            status: StatusCode::BAD_GATEWAY,
            code: "worker_rejected",
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
    let app = Router::new()
        .route("/healthz", get(health))
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
        });
    let listener = tokio::net::TcpListener::bind(address)
        .await
        .expect("failed to bind UNCIV_V3_BIND");
    axum::serve(listener, app)
        .await
        .expect("authoritative API server failed");
}
