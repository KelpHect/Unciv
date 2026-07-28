use std::time::Instant;

use axum::{
    body::{Body, HttpBody},
    http::{Request, header},
    middleware::Next,
};
use tracing::Instrument;

use super::*;

pub(super) async fn observe_request(request: Request<Body>, next: Next) -> Response {
    let started = Instant::now();
    let method = unciv_authoritative_server::telemetry::method_class(request.method().as_str());
    let route = unciv_authoritative_server::telemetry::route_class(request.uri().path());
    let request_id = uuid::Uuid::new_v4();
    let span = tracing::info_span!(
        "http_request",
        request_id = %request_id,
        method,
        route,
        status = tracing::field::Empty,
        elapsed_ms = tracing::field::Empty,
    );

    async move {
        let response = next.run(request).await;
        let status = response.status();
        let status_class = unciv_authoritative_server::telemetry::status_class(status.as_u16());
        let elapsed = started.elapsed();

        metrics::counter!(
            "unciv_v3_http_requests_total",
            "route" => route,
            "method" => method,
            "status_class" => status_class,
        )
        .increment(1);
        metrics::histogram!(
            "unciv_v3_http_request_duration",
            "route" => route,
            "method" => method,
            "status_class" => status_class,
        )
        .record(elapsed.as_secs_f64());
        let response_size = response.body().size_hint().exact().or_else(|| {
            response
                .headers()
                .get(header::CONTENT_LENGTH)
                .and_then(|value| value.to_str().ok())
                .and_then(|value| value.parse::<u64>().ok())
        });
        if let Some(length) = response_size {
            metrics::histogram!("unciv_v3_response_body_size", "route" => route)
                .record(length as f64);
            if matches!(route, "projection" | "spectator_projection") {
                metrics::gauge!("unciv_v3_projection_bytes", "kind" => route).set(length as f64);
            }
        }

        tracing::Span::current().record("status", status.as_u16());
        tracing::Span::current().record("elapsed_ms", elapsed.as_millis() as u64);
        if status.is_server_error() {
            tracing::error!("request_completed");
        } else if status.is_client_error() {
            tracing::warn!("request_completed");
        } else {
            tracing::info!("request_completed");
        }
        response
    }
    .instrument(span)
    .await
}
