//! Process-wide, redacted observability for the authoritative control plane.
//!
//! Labels are deliberately closed and low-cardinality. Account, game, command,
//! session, ruleset, network, and canonical-state values must never be metrics
//! labels or tracing fields.

use std::net::SocketAddr;

use metrics::{Unit, describe_counter, describe_gauge, describe_histogram};
use metrics_exporter_prometheus::PrometheusBuilder;
use thiserror::Error;
use tracing_subscriber::EnvFilter;

const DEFAULT_FILTER: &str = "unciv_authoritative_server=info,warn";
const HTTP_LATENCY_BUCKETS: &[f64] = &[
    0.005, 0.010, 0.025, 0.050, 0.100, 0.250, 0.500, 1.0, 2.5, 5.0, 10.0,
];

#[derive(Debug, Error)]
pub enum TelemetryError {
    #[error("UNCIV_V3_METRICS_BIND must be a loopback socket address")]
    InvalidMetricsBind,
    #[error("failed to install the structured tracing subscriber")]
    Tracing(#[source] Box<dyn std::error::Error + Send + Sync>),
    #[error("failed to install the Prometheus metrics recorder")]
    Metrics(#[source] metrics_exporter_prometheus::BuildError),
}

/// Installs one JSON tracing subscriber and one loopback-only Prometheus
/// exporter. The exporter is intentionally separate from the public API.
pub fn initialize() -> Result<SocketAddr, TelemetryError> {
    tracing_subscriber::fmt()
        .json()
        .flatten_event(true)
        .with_current_span(true)
        .with_span_list(false)
        .with_ansi(false)
        .with_env_filter(
            EnvFilter::try_from_env("UNCIV_V3_LOG")
                .unwrap_or_else(|_| EnvFilter::new(DEFAULT_FILTER)),
        )
        .try_init()
        .map_err(TelemetryError::Tracing)?;

    let metrics_address = std::env::var("UNCIV_V3_METRICS_BIND")
        .unwrap_or_else(|_| "127.0.0.1:9464".to_owned())
        .parse::<SocketAddr>()
        .map_err(|_| TelemetryError::InvalidMetricsBind)?;
    if !metrics_address.ip().is_loopback() {
        return Err(TelemetryError::InvalidMetricsBind);
    }

    PrometheusBuilder::new()
        .with_http_listener(metrics_address)
        .add_allowed_address("127.0.0.0/8")
        .map_err(TelemetryError::Metrics)?
        .add_allowed_address("::1/128")
        .map_err(TelemetryError::Metrics)?
        .set_buckets(HTTP_LATENCY_BUCKETS)
        .map_err(TelemetryError::Metrics)?
        .with_recommended_naming(true)
        .install()
        .map_err(TelemetryError::Metrics)?;

    describe_metrics();
    metrics::gauge!("unciv_v3_process_up").set(1.0);
    Ok(metrics_address)
}

fn describe_metrics() {
    describe_gauge!(
        "unciv_v3_process_up",
        "Whether the authoritative API process initialized successfully."
    );
    describe_gauge!(
        "unciv_v3_websocket_connections",
        "Currently admitted authoritative notification sockets."
    );
    describe_gauge!(
        "unciv_v3_projection_bytes",
        Unit::Bytes,
        "Latest serialized player or spectator projection response size."
    );
    describe_gauge!(
        "unciv_v3_revision",
        "Latest successfully returned canonical revision."
    );
    describe_gauge!(
        "unciv_v3_outbox_pending",
        "Undelivered authoritative notification outbox rows."
    );
    describe_gauge!(
        "unciv_v3_outbox_dead_letters",
        "Dead-lettered authoritative notification outbox rows."
    );
    describe_gauge!(
        "unciv_v3_outbox_oldest_pending_age",
        Unit::Seconds,
        "Age of the oldest pending authoritative notification."
    );
    describe_histogram!(
        "unciv_v3_http_request_duration",
        Unit::Seconds,
        "Public HTTP request latency by closed route class, method, and status class."
    );
    describe_histogram!(
        "unciv_v3_response_body_size",
        Unit::Bytes,
        "Response payload size by closed route class."
    );
    describe_histogram!(
        "unciv_v3_worker_request_duration",
        Unit::Seconds,
        "Private Kotlin worker request latency by bounded outcome."
    );
    describe_counter!(
        "unciv_v3_http_requests_total",
        "Public HTTP requests by closed route class, method, and status class."
    );
    describe_counter!(
        "unciv_v3_api_errors_total",
        "Stable redacted API errors by bounded code."
    );
    describe_counter!(
        "unciv_v3_stale_conflicts_total",
        "Commands rejected because the expected revision was stale."
    );
    describe_counter!(
        "unciv_v3_commands_committed_total",
        "Canonical gameplay commands committed successfully."
    );
    describe_counter!(
        "unciv_v3_worker_failures_total",
        "Private worker failures by bounded failure class."
    );
    describe_counter!(
        "unciv_v3_database_lock_conflicts_total",
        "Database lock or serialization conflicts."
    );
    describe_counter!(
        "unciv_v3_websocket_disconnects_total",
        "Notification socket disconnects by bounded reason."
    );
    describe_counter!(
        "unciv_v3_websocket_admission_rejections_total",
        "Notification socket admissions rejected by bounded scope."
    );
    describe_counter!(
        "unciv_v3_notification_runtime_failures_total",
        "Notification runtime failures by bounded component."
    );
    describe_counter!(
        "unciv_v3_outbox_dead_letters_total",
        "Notification outbox events dead-lettered after bounded retries."
    );
    describe_counter!(
        "unciv_v3_security_audit_write_failures_total",
        "Durable security audit writes that failed closed."
    );
    describe_gauge!(
        "unciv_v3_snapshot_postgres_bytes",
        Unit::Bytes,
        "Retained PostgreSQL snapshot payload bytes observed by maintenance."
    );
    describe_gauge!(
        "unciv_v3_snapshot_budget_exceeded",
        "Whether retained PostgreSQL snapshot bytes exceed the configured budget."
    );
    describe_counter!(
        "unciv_v3_snapshot_archive_runs_total",
        "Completed bounded snapshot maintenance passes."
    );
    describe_counter!(
        "unciv_v3_snapshot_archive_failures_total",
        "Snapshot archival passes or per-game archival operations that failed closed."
    );
    describe_counter!(
        "unciv_v3_snapshot_budget_exceeded_total",
        "Maintenance passes that remained above the configured PostgreSQL snapshot budget."
    );
    describe_gauge!(
        "unciv_v3_snapshot_archive_bytes",
        Unit::Bytes,
        "Verified Lockwell archive object bytes recorded by maintenance."
    );
    describe_gauge!(
        "unciv_v3_snapshot_archive_quota_exceeded",
        "Whether the aggregate Lockwell archive quota has paused archival."
    );
    describe_counter!(
        "unciv_v3_snapshot_archive_quota_exceeded_total",
        "Maintenance passes that reached the aggregate Lockwell archive quota."
    );
}

/// Closed route classes prevent game IDs and other path parameters from
/// becoming high-cardinality labels.
pub fn route_class(path: &str) -> &'static str {
    if path == "/healthz" || path == "/readyz" {
        "health"
    } else if path.starts_with("/api/v3/auth/") {
        "auth"
    } else if path.starts_with("/api/v3/account") {
        "account"
    } else if path.ends_with("/projection") || path.ends_with("/projection/delta") {
        "projection"
    } else if path.ends_with("/spectator-projection") {
        "spectator_projection"
    } else if path.contains("/commands/") || path.ends_with("/join") {
        "command"
    } else if path == "/api/v3/notifications" {
        "notifications"
    } else if path.starts_with("/api/v3/games") {
        "games"
    } else if path.starts_with("/api/v3/ruleset-manifests") {
        "rulesets"
    } else if path.ends_with(".json") || path == "/api/v3/capabilities" {
        "contract"
    } else {
        "unknown"
    }
}

pub fn status_class(status: u16) -> &'static str {
    match status {
        100..=199 => "1xx",
        200..=299 => "2xx",
        300..=399 => "3xx",
        400..=499 => "4xx",
        _ => "5xx",
    }
}

pub fn method_class(method: &str) -> &'static str {
    match method {
        "GET" => "GET",
        "POST" => "POST",
        "PUT" => "PUT",
        "DELETE" => "DELETE",
        "OPTIONS" => "OPTIONS",
        _ => "OTHER",
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn route_labels_never_include_identifiers() {
        let first =
            route_class("/api/v3/games/018f6d44-a29b-7152-9cf1-39d6a754d306/commands/end-turn");
        let second =
            route_class("/api/v3/games/aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee/commands/move-unit");
        assert_eq!(first, "command");
        assert_eq!(first, second);
    }

    #[test]
    fn attacker_controlled_methods_collapse_to_one_label() {
        assert_eq!(method_class("POST"), "POST");
        assert_eq!(method_class("ATTACKER-CONTROLLED-ONE"), "OTHER");
        assert_eq!(method_class("ATTACKER-CONTROLLED-TWO"), "OTHER");
    }

    #[test]
    fn metrics_listener_rejects_public_addresses() {
        let public = "0.0.0.0:9464".parse::<SocketAddr>().unwrap();
        assert!(!public.ip().is_loopback());
        let private = "127.0.0.1:9464".parse::<SocketAddr>().unwrap();
        assert!(private.ip().is_loopback());
    }

    #[test]
    fn prometheus_output_contains_only_bounded_dimensions() {
        let recorder = PrometheusBuilder::new()
            .set_buckets(HTTP_LATENCY_BUCKETS)
            .unwrap()
            .with_recommended_naming(true)
            .build_recorder();
        let handle = recorder.handle();
        metrics::with_local_recorder(&recorder, || {
            metrics::counter!(
                "unciv_v3_http_requests_total",
                "route" => "command",
                "method" => "POST",
                "status_class" => "2xx"
            )
            .increment(1);
            metrics::histogram!(
                "unciv_v3_http_request_duration",
                "route" => "command",
                "method" => "POST",
                "status_class" => "2xx"
            )
            .record(0.125);
        });
        let rendered = handle.render();
        assert!(rendered.contains("unciv_v3_http_requests_total"));
        assert!(rendered.contains("route=\"command\""));
        assert!(rendered.contains("status_class=\"2xx\""));
        for private in ["game_id", "account_id", "command_id", "session_id"] {
            assert!(!rendered.contains(private));
        }
    }
}
