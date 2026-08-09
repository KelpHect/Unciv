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
    headers: HeaderMap,
    Json(credentials): Json<CredentialsRequest>,
) -> Result<(StatusCode, Json<AccountResponse>), ApiError> {
    let source_prefix = source_prefix(state.trusted_proxy.client_ip(source, &headers)?);
    let identity = credentials.username.trim().to_ascii_lowercase();
    enforce_rate_limit(
        &state,
        &format!("register:source:{source_prefix}"),
        RateLimitPolicy {
            window_seconds: 3_600,
            max_requests: 5,
            block_seconds: 3_600,
            event_type: SecurityAuditEvent::Registration,
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
                SecurityAuditEvent::Registration,
                SecurityAuditOutcome::Success,
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
                SecurityAuditEvent::Registration,
                SecurityAuditOutcome::Rejected,
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
    headers: HeaderMap,
    Json(credentials): Json<CredentialsRequest>,
) -> Result<Json<LoginResponse>, ApiError> {
    let source_prefix = source_prefix(state.trusted_proxy.client_ip(source, &headers)?);
    let identity = credentials.username.trim().to_ascii_lowercase();
    enforce_rate_limit(
        &state,
        &format!("login:source:{source_prefix}"),
        RateLimitPolicy {
            window_seconds: 60,
            max_requests: 30,
            block_seconds: 60,
            event_type: SecurityAuditEvent::Login,
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
            event_type: SecurityAuditEvent::Login,
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
                SecurityAuditEvent::Login,
                SecurityAuditOutcome::Rejected,
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
        .issue_session_with_policy(account.id, state.session_policy)
        .await
        .map_err(|_| ApiError::internal())?;
    audit_security(
        &state,
        Some(account.id),
        SecurityAuditEvent::Login,
        SecurityAuditOutcome::Success,
        &source_prefix,
        Some(&identity),
    )
    .await;
    Ok(Json(LoginResponse {
        account: account_response(account),
        session_token: credential.token,
        refresh_token: credential.refresh_token,
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
                SecurityAuditOutcome::RateLimited,
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
    event_type: SecurityAuditEvent,
    outcome: SecurityAuditOutcome,
    source_prefix: &str,
    identity: Option<&str>,
) {
    if state
        .repository
        .record_security_audit(account_id, event_type, outcome, source_prefix, identity)
        .await
        .is_err()
    {
        metrics::counter!("unciv_v3_security_audit_write_failures_total").increment(1);
        tracing::error!("security audit write failed");
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
    delete,
    path = "/api/v3/account/sessions",
    security(("bearer_auth" = [])),
    responses(
        (status = 204),
        (status = 401, body = ErrorResponse),
        (status = 429, body = ErrorResponse),
        (status = 500, body = ErrorResponse)
    )
)]
pub(super) async fn logout_all_sessions(
    State(state): State<AppState>,
    ConnectInfo(source): ConnectInfo<SocketAddr>,
    headers: HeaderMap,
) -> Result<StatusCode, ApiError> {
    let actor = authenticated_account(&state, &headers).await?;
    let source = state.trusted_proxy.client_ip(source, &headers)?;
    enforce_account_security_rate_limit(&state, actor.id, source).await?;
    state
        .repository
        .revoke_all_sessions(actor.id)
        .await
        .map_err(|_| ApiError::internal())?;
    audit_account_lifecycle(
        &state,
        actor.id,
        SecurityAuditEvent::AccountSecurity,
        SecurityAuditOutcome::Success,
        source,
    )
    .await;
    Ok(StatusCode::NO_CONTENT)
}

#[utoipa::path(
    post,
    path = "/api/v3/auth/refresh",
    request_body = RefreshSessionRequest,
    responses((status = 200, body = SessionResponse), (status = 400, body = ErrorResponse), (status = 401, body = ErrorResponse), (status = 500, body = ErrorResponse))
)]
pub(super) async fn refresh_session(
    State(state): State<AppState>,
    Json(request): Json<RefreshSessionRequest>,
) -> Result<Json<SessionResponse>, ApiError> {
    let credential = state
        .repository
        .rotate_refresh_session(&request.refresh_token)
        .await
        .map_err(login_error)?;
    Ok(Json(SessionResponse {
        session_token: credential.token,
        refresh_token: credential.refresh_token,
    }))
}

#[utoipa::path(
    post,
    path = "/api/v3/account/recovery-codes",
    security(("bearer_auth" = [])),
    request_body = ConfirmPasswordRequest,
    responses(
        (status = 200, body = RecoveryCodesResponse),
        (status = 401, body = ErrorResponse),
        (status = 429, body = ErrorResponse),
        (status = 500, body = ErrorResponse)
    )
)]
pub(super) async fn replace_recovery_codes(
    State(state): State<AppState>,
    ConnectInfo(source): ConnectInfo<SocketAddr>,
    headers: HeaderMap,
    Json(request): Json<ConfirmPasswordRequest>,
) -> Result<Json<RecoveryCodesResponse>, ApiError> {
    let actor = authenticated_account(&state, &headers).await?;
    let source = state.trusted_proxy.client_ip(source, &headers)?;
    enforce_account_security_rate_limit(&state, actor.id, source).await?;
    let batch = match state
        .repository
        .replace_recovery_codes(actor.id, &request.password)
        .await
    {
        Ok(batch) => batch,
        Err(error) => {
            audit_account_lifecycle(
                &state,
                actor.id,
                SecurityAuditEvent::RecoveryCodes,
                SecurityAuditOutcome::Rejected,
                source,
            )
            .await;
            return Err(account_change_error(error));
        }
    };
    audit_account_lifecycle(
        &state,
        actor.id,
        SecurityAuditEvent::RecoveryCodes,
        SecurityAuditOutcome::Success,
        source,
    )
    .await;
    Ok(Json(RecoveryCodesResponse {
        recovery_codes: batch.codes,
        expires_in_days: 90,
    }))
}

#[utoipa::path(
    post,
    path = "/api/v3/auth/recover",
    request_body = RecoverAccountRequest,
    responses(
        (status = 200, body = LoginResponse),
        (status = 400, body = ErrorResponse),
        (status = 401, body = ErrorResponse),
        (status = 429, body = ErrorResponse),
        (status = 500, body = ErrorResponse)
    )
)]
pub(super) async fn recover_account(
    State(state): State<AppState>,
    ConnectInfo(source): ConnectInfo<SocketAddr>,
    headers: HeaderMap,
    Json(request): Json<RecoverAccountRequest>,
) -> Result<Json<LoginResponse>, ApiError> {
    let source_prefix = source_prefix(state.trusted_proxy.client_ip(source, &headers)?);
    let identity = request.username.trim().to_ascii_lowercase();
    enforce_rate_limit(
        &state,
        &format!("recover:source:{source_prefix}"),
        RateLimitPolicy {
            window_seconds: 3_600,
            max_requests: 10,
            block_seconds: 3_600,
            event_type: SecurityAuditEvent::AccountRecovery,
        },
        &source_prefix,
        Some(&identity),
    )
    .await?;
    let identity_bucket = format!("recover:identity:{source_prefix}:{identity}");
    enforce_rate_limit(
        &state,
        &identity_bucket,
        RateLimitPolicy {
            window_seconds: 3_600,
            max_requests: 5,
            block_seconds: 3_600,
            event_type: SecurityAuditEvent::AccountRecovery,
        },
        &source_prefix,
        Some(&identity),
    )
    .await?;
    let recovered = state
        .repository
        .recover_account(
            &request.username,
            &request.recovery_code,
            &request.new_password,
        )
        .await;
    let (account, credential) = match recovered {
        Ok(recovered) => recovered,
        Err(error) => {
            audit_security(
                &state,
                None,
                SecurityAuditEvent::AccountRecovery,
                SecurityAuditOutcome::Rejected,
                &source_prefix,
                Some(&identity),
            )
            .await;
            return Err(match error {
                AuthError::InvalidPassword(_) => account_change_error(error),
                _ => login_error(error),
            });
        }
    };
    state
        .repository
        .clear_rate_limit(&identity_bucket)
        .await
        .map_err(login_error)?;
    audit_security(
        &state,
        Some(account.id),
        SecurityAuditEvent::AccountRecovery,
        SecurityAuditOutcome::Success,
        &source_prefix,
        Some(&identity),
    )
    .await;
    Ok(Json(LoginResponse {
        account: account_response(account),
        session_token: credential.token,
        refresh_token: credential.refresh_token,
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
    let source = state.trusted_proxy.client_ip(source, &headers)?;
    enforce_account_security_rate_limit(&state, actor.id, source).await?;
    let credential = match state
        .repository
        .change_password(actor.id, &request.current_password, &request.new_password)
        .await
    {
        Ok(credential) => credential,
        Err(error) => {
            audit_account_lifecycle(
                &state,
                actor.id,
                SecurityAuditEvent::PasswordChange,
                SecurityAuditOutcome::Rejected,
                source,
            )
            .await;
            return Err(account_change_error(error));
        }
    };
    audit_account_lifecycle(
        &state,
        actor.id,
        SecurityAuditEvent::PasswordChange,
        SecurityAuditOutcome::Success,
        source,
    )
    .await;
    Ok(Json(SessionResponse {
        session_token: credential.token,
        refresh_token: credential.refresh_token,
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
    let source = state.trusted_proxy.client_ip(source, &headers)?;
    enforce_account_security_rate_limit(&state, actor.id, source).await?;
    if let Err(error) = state
        .repository
        .disable_account(actor.id, &request.password)
        .await
    {
        audit_account_lifecycle(
            &state,
            actor.id,
            SecurityAuditEvent::AccountDisable,
            SecurityAuditOutcome::Rejected,
            source,
        )
        .await;
        return Err(account_change_error(error));
    }
    audit_account_lifecycle(
        &state,
        actor.id,
        SecurityAuditEvent::AccountDisable,
        SecurityAuditOutcome::Success,
        source,
    )
    .await;
    state.notifications.require_resync_for_all();
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
    let source = state.trusted_proxy.client_ip(source, &headers)?;
    enforce_account_security_rate_limit(&state, actor.id, source).await?;
    if let Err(error) = state
        .repository
        .delete_account(actor.id, &request.password)
        .await
    {
        audit_account_lifecycle(
            &state,
            actor.id,
            SecurityAuditEvent::AccountDelete,
            SecurityAuditOutcome::Rejected,
            source,
        )
        .await;
        return Err(account_change_error(error));
    }
    audit_account_lifecycle(
        &state,
        actor.id,
        SecurityAuditEvent::AccountDelete,
        SecurityAuditOutcome::Success,
        source,
    )
    .await;
    state.notifications.require_resync_for_all();
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
            event_type: SecurityAuditEvent::AccountSecurity,
        },
        &source_prefix,
        None,
    )
    .await
}

pub(super) async fn audit_account_lifecycle(
    state: &AppState,
    account_id: uuid::Uuid,
    event_type: SecurityAuditEvent,
    outcome: SecurityAuditOutcome,
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
