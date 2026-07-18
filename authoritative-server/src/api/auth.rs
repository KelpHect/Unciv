use super::*;

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
pub(super) async fn register(
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
pub(super) async fn login(
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

pub(super) async fn enforce_rate_limit(
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

pub(super) async fn audit_security(
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

pub(super) fn source_prefix(address: IpAddr) -> String {
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
pub(super) async fn logout(
    State(state): State<AppState>,
    headers: HeaderMap,
) -> Result<StatusCode, ApiError> {
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
pub(super) async fn refresh_session(
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
pub(super) async fn change_password(
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
pub(super) async fn disable_account(
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
pub(super) async fn delete_account(
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

pub(super) async fn enforce_account_security_rate_limit(
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

pub(super) async fn audit_account_lifecycle(
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
