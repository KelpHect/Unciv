use std::net::SocketAddr;

use axum::{Json, Router, routing::get};
use serde::Serialize;

#[derive(Serialize)]
struct HealthResponse {
    status: &'static str,
    protocol_version: u16,
}

async fn health() -> Json<HealthResponse> {
    Json(HealthResponse {
        status: "ok",
        protocol_version: unciv_authoritative_server::PROTOCOL_VERSION,
    })
}

#[tokio::main]
async fn main() {
    let address = std::env::var("UNCIV_V3_BIND")
        .unwrap_or_else(|_| "127.0.0.1:3000".to_owned())
        .parse::<SocketAddr>()
        .expect("UNCIV_V3_BIND must be a socket address");
    let app = Router::new().route("/healthz", get(health));
    let listener = tokio::net::TcpListener::bind(address)
        .await
        .expect("failed to bind UNCIV_V3_BIND");
    axum::serve(listener, app).await.expect("authoritative API server failed");
}
