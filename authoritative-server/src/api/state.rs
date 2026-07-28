use super::*;

#[derive(Clone)]
pub(super) struct AppState {
    pub(super) repository: PostgresGameRepository,
    pub(super) worker: EngineWorkerClient,
    pub(super) notifications: NotificationHub,
    pub(super) websocket_policy: WebSocketRuntimePolicy,
    pub(super) trusted_proxy: TrustedProxyPolicy,
}

pub(super) struct RateLimitPolicy {
    pub(super) window_seconds: i32,
    pub(super) max_requests: i32,
    pub(super) block_seconds: i32,
    pub(super) event_type: SecurityAuditEvent,
}
