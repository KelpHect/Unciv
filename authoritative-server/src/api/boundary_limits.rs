use std::time::Duration;

use axum::{
    Json,
    body::{Body, to_bytes},
    extract::Request,
    http::{StatusCode, header},
    middleware::Next,
    response::{IntoResponse, Response},
};
use serde_json::Value;
use tokio::time::timeout;

use super::ErrorResponse;

pub(super) const MAX_REQUEST_BODY_BYTES: usize = 8 * 1024;
const MAX_RESPONSE_BODY_BYTES: usize = 16 * 1024 * 1024;
const MAX_JSON_DEPTH: usize = 16;
const MAX_JSON_STRING_BYTES: usize = 1_024;
const MAX_JSON_COLLECTION_ITEMS: usize = 128;
const MAX_JSON_NODES: usize = 512;
const MAX_URI_BYTES: usize = 2_048;
const MAX_HEADER_COUNT: usize = 64;
const MAX_HEADER_VALUE_BYTES: usize = 8 * 1024;
const REQUEST_DEADLINE: Duration = Duration::from_secs(40);

pub(super) async fn request_deadline(request: Request, next: Next) -> Response {
    run_with_deadline(request, next, REQUEST_DEADLINE).await
}

async fn run_with_deadline(request: Request, next: Next, deadline: Duration) -> Response {
    match timeout(deadline, next.run(request)).await {
        Ok(response) => response,
        Err(_) => boundary_error(StatusCode::REQUEST_TIMEOUT, "request_timeout"),
    }
}

pub(super) async fn enforce_request_limits(request: Request, next: Next) -> Response {
    if request.uri().to_string().len() > MAX_URI_BYTES
        || request.headers().len() > MAX_HEADER_COUNT
        || request
            .headers()
            .values()
            .any(|value| value.as_bytes().len() > MAX_HEADER_VALUE_BYTES)
    {
        return boundary_error(StatusCode::PAYLOAD_TOO_LARGE, "request_too_large");
    }
    if request
        .headers()
        .get(header::CONTENT_LENGTH)
        .and_then(|value| value.to_str().ok())
        .and_then(|value| value.parse::<usize>().ok())
        .is_some_and(|length| length > MAX_REQUEST_BODY_BYTES)
    {
        return boundary_error(StatusCode::PAYLOAD_TOO_LARGE, "request_too_large");
    }

    let validate_as_json = has_json_content_type(&request);
    let (parts, body) = request.into_parts();
    let bytes = match to_bytes(body, MAX_REQUEST_BODY_BYTES).await {
        Ok(bytes) => bytes,
        Err(_) => {
            return boundary_error(StatusCode::PAYLOAD_TOO_LARGE, "request_too_large");
        }
    };
    if validate_as_json && validate_json(&bytes).is_err() {
        return boundary_error(StatusCode::BAD_REQUEST, "invalid_json");
    }
    next.run(Request::from_parts(parts, Body::from(bytes)))
        .await
}

pub(super) async fn enforce_response_limits(request: Request, next: Next) -> Response {
    let response = next.run(request).await;
    let (parts, body) = response.into_parts();
    match to_bytes(body, MAX_RESPONSE_BODY_BYTES).await {
        Ok(bytes) => Response::from_parts(parts, Body::from(bytes)),
        Err(_) => boundary_error(StatusCode::INTERNAL_SERVER_ERROR, "internal_error"),
    }
}

fn has_json_content_type(request: &Request) -> bool {
    request
        .headers()
        .get(header::CONTENT_TYPE)
        .and_then(|value| value.to_str().ok())
        .and_then(|value| value.split(';').next())
        .is_some_and(|media_type| media_type.trim().eq_ignore_ascii_case("application/json"))
}

fn validate_json(bytes: &[u8]) -> Result<(), ()> {
    let value: Value = serde_json::from_slice(bytes).map_err(|_| ())?;
    let mut stack = vec![(&value, 1_usize)];
    let mut nodes = 0_usize;
    while let Some((value, depth)) = stack.pop() {
        nodes += 1;
        if nodes > MAX_JSON_NODES || depth > MAX_JSON_DEPTH {
            return Err(());
        }
        match value {
            Value::String(value) if value.len() > MAX_JSON_STRING_BYTES => return Err(()),
            Value::Array(values) => {
                if values.len() > MAX_JSON_COLLECTION_ITEMS {
                    return Err(());
                }
                stack.extend(values.iter().map(|value| (value, depth + 1)));
            }
            Value::Object(values) => {
                if values.len() > MAX_JSON_COLLECTION_ITEMS
                    || values.keys().any(|key| key.len() > MAX_JSON_STRING_BYTES)
                {
                    return Err(());
                }
                stack.extend(values.values().map(|value| (value, depth + 1)));
            }
            _ => {}
        }
    }
    Ok(())
}

fn boundary_error(status: StatusCode, code: &'static str) -> Response {
    (
        status,
        Json(ErrorResponse {
            code,
            current_revision: None,
        }),
    )
        .into_response()
}

#[cfg(test)]
mod tests {
    use std::sync::{
        Arc,
        atomic::{AtomicBool, Ordering},
    };

    use axum::{Router, body::Bytes, http::Request, middleware, routing::post};
    use tower::ServiceExt;

    use super::*;

    async fn echo(body: Bytes) -> Bytes {
        body
    }

    #[tokio::test]
    async fn request_json_depth_strings_collections_and_bytes_are_bounded() {
        let app = Router::new()
            .route("/", post(echo))
            .layer(middleware::from_fn(enforce_request_limits));
        for body in [
            format!(
                "{{\"value\":\"{}\"}}",
                "x".repeat(MAX_JSON_STRING_BYTES + 1)
            ),
            format!("[{}]", vec!["0"; MAX_JSON_COLLECTION_ITEMS + 1].join(",")),
            format!(
                "{}0{}",
                "[".repeat(MAX_JSON_DEPTH),
                "]".repeat(MAX_JSON_DEPTH)
            ),
        ] {
            let response = app
                .clone()
                .oneshot(
                    Request::post("/")
                        .header(header::CONTENT_TYPE, "application/json")
                        .body(Body::from(body))
                        .unwrap(),
                )
                .await
                .unwrap();
            assert_eq!(response.status(), StatusCode::BAD_REQUEST);
        }
        let response = app
            .oneshot(
                Request::post("/")
                    .header(header::CONTENT_TYPE, "application/json")
                    .body(Body::from(vec![b' '; MAX_REQUEST_BODY_BYTES + 1]))
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::PAYLOAD_TOO_LARGE);
    }

    #[tokio::test]
    async fn request_uri_and_header_metadata_are_bounded() {
        let app = Router::new()
            .route("/{*path}", post(echo))
            .layer(middleware::from_fn(enforce_request_limits));
        let response = app
            .clone()
            .oneshot(
                Request::post(format!("/{}", "x".repeat(MAX_URI_BYTES)))
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::PAYLOAD_TOO_LARGE);

        let mut request = Request::post("/").body(Body::empty()).unwrap();
        for index in 0..=MAX_HEADER_COUNT {
            request.headers_mut().insert(
                format!("x-limit-{index}")
                    .parse::<axum::http::HeaderName>()
                    .unwrap(),
                "1".parse().unwrap(),
            );
        }
        let response = app.oneshot(request).await.unwrap();
        assert_eq!(response.status(), StatusCode::PAYLOAD_TOO_LARGE);
    }

    #[tokio::test]
    async fn oversized_responses_are_replaced_before_reaching_the_client() {
        let app = Router::new()
            .route(
                "/",
                post(|| async { vec![b'x'; MAX_RESPONSE_BODY_BYTES + 1] }),
            )
            .layer(middleware::from_fn(enforce_response_limits));
        let response = app
            .oneshot(Request::post("/").body(Body::empty()).unwrap())
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::INTERNAL_SERVER_ERROR);
        let body = to_bytes(response.into_body(), 256).await.unwrap();
        assert_eq!(body, r#"{"code":"internal_error"}"#);
    }

    struct DropSignal(Arc<AtomicBool>);

    impl Drop for DropSignal {
        fn drop(&mut self) {
            self.0.store(true, Ordering::SeqCst);
        }
    }

    #[tokio::test]
    async fn deadline_drops_the_in_flight_handler_future() {
        let dropped = Arc::new(AtomicBool::new(false));
        let handler_signal = dropped.clone();
        let app = Router::new()
            .route(
                "/",
                post(move || {
                    let guard = DropSignal(handler_signal.clone());
                    async move {
                        let _guard = guard;
                        std::future::pending::<()>().await;
                    }
                }),
            )
            .layer(middleware::from_fn(|request, next| {
                run_with_deadline(request, next, Duration::from_millis(10))
            }));
        let response = app
            .oneshot(Request::post("/").body(Body::empty()).unwrap())
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::REQUEST_TIMEOUT);
        assert!(dropped.load(Ordering::SeqCst));
    }
}
