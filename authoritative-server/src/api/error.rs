use super::*;

#[derive(Debug)]
pub(super) struct ApiError {
    pub(super) status: StatusCode,
    pub(super) code: &'static str,
    pub(super) current_revision: Option<u64>,
    pub(super) retry_after_seconds: Option<u64>,
}

impl ApiError {
    pub(super) const fn bad_request(code: &'static str) -> Self {
        Self {
            status: StatusCode::BAD_REQUEST,
            code,
            current_revision: None,
            retry_after_seconds: None,
        }
    }

    pub(super) const fn unauthorized() -> Self {
        Self {
            status: StatusCode::UNAUTHORIZED,
            code: "invalid_credentials",
            current_revision: None,
            retry_after_seconds: None,
        }
    }

    pub(super) const fn forbidden(code: &'static str) -> Self {
        Self {
            status: StatusCode::FORBIDDEN,
            code,
            current_revision: None,
            retry_after_seconds: None,
        }
    }

    pub(super) const fn conflict(code: &'static str) -> Self {
        Self {
            status: StatusCode::CONFLICT,
            code,
            current_revision: None,
            retry_after_seconds: None,
        }
    }

    pub(super) const fn internal() -> Self {
        Self {
            status: StatusCode::INTERNAL_SERVER_ERROR,
            code: "internal_error",
            current_revision: None,
            retry_after_seconds: None,
        }
    }

    pub(super) const fn rate_limited(retry_after_seconds: u64) -> Self {
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

pub(super) fn register_error(error: AuthError) -> ApiError {
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

pub(super) fn login_error(error: AuthError) -> ApiError {
    match error {
        AuthError::InvalidCredentials
        | AuthError::AccountDisabled
        | AuthError::InvalidUsername(_) => ApiError::unauthorized(),
        AuthError::Storage => ApiError::internal(),
        AuthError::RateLimited => ApiError::rate_limited(60),
        AuthError::InvalidPassword(_) | AuthError::UsernameTaken => ApiError::internal(),
    }
}

pub(super) fn account_change_error(error: AuthError) -> ApiError {
    match error {
        AuthError::InvalidPassword(_) => ApiError::bad_request("invalid_password"),
        AuthError::InvalidCredentials | AuthError::AccountDisabled => ApiError::unauthorized(),
        AuthError::RateLimited => ApiError::rate_limited(900),
        AuthError::Storage => ApiError::internal(),
        AuthError::InvalidUsername(_) | AuthError::UsernameTaken => ApiError::internal(),
    }
}

pub(super) fn game_error(error: CommitError) -> ApiError {
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
        CommitError::WorkerRejected(_) => {
            // Worker reasons are private and may contain canonical-state
            // diagnostics. Both the response and normal logs use stable codes.
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
        CommitError::ProjectionDeltaUnavailable => ApiError {
            status: StatusCode::CONFLICT,
            code: "projection_delta_unavailable",
            current_revision: None,
            retry_after_seconds: None,
        },
        CommitError::RecoveryEvidenceMissing
        | CommitError::RecoveryTailTooLong
        | CommitError::RecoveryDiverged
        | CommitError::Storage => ApiError::internal(),
    }
}
