use std::{collections::BTreeSet, sync::Arc, time::Duration};

use axum::{
    extract::{Request, State},
    http::{HeaderValue, Method, Uri, header},
    middleware::Next,
    response::{IntoResponse, Response},
};
use tower_http::cors::CorsLayer;

use super::ApiError;

const MAX_ALLOWED_ORIGINS: usize = 16;
const MAX_ORIGIN_BYTES: usize = 256;

#[derive(Clone, Debug)]
pub(super) struct HttpSecurityConfig {
    allowed_origins: Arc<BTreeSet<HeaderValue>>,
}

impl HttpSecurityConfig {
    pub(super) fn from_environment() -> Result<Self, &'static str> {
        Self::parse(std::env::var("UNCIV_V3_ALLOWED_ORIGINS").ok().as_deref())
    }

    fn parse(raw: Option<&str>) -> Result<Self, &'static str> {
        let mut allowed_origins = BTreeSet::new();
        for origin in raw.into_iter().flat_map(|value| value.split(',')) {
            let origin = origin.trim();
            if origin.is_empty() {
                return Err("UNCIV_V3_ALLOWED_ORIGINS contains an empty origin");
            }
            if origin.len() > MAX_ORIGIN_BYTES {
                return Err("UNCIV_V3_ALLOWED_ORIGINS contains an oversized origin");
            }
            validate_origin(origin)?;
            let value = HeaderValue::from_str(origin)
                .map_err(|_| "UNCIV_V3_ALLOWED_ORIGINS contains an invalid header value")?;
            allowed_origins.insert(value);
            if allowed_origins.len() > MAX_ALLOWED_ORIGINS {
                return Err("UNCIV_V3_ALLOWED_ORIGINS exceeds its origin limit");
            }
        }
        Ok(Self {
            allowed_origins: Arc::new(allowed_origins),
        })
    }

    pub(super) fn origin_policy(&self) -> OriginPolicy {
        OriginPolicy(self.allowed_origins.clone())
    }

    pub(super) fn cors_layer(&self) -> CorsLayer {
        let layer = CorsLayer::new()
            .allow_methods([
                Method::GET,
                Method::POST,
                Method::PUT,
                Method::DELETE,
                Method::OPTIONS,
            ])
            .allow_headers([header::AUTHORIZATION, header::CONTENT_TYPE])
            .max_age(Duration::from_secs(600));
        if self.allowed_origins.is_empty() {
            layer
        } else {
            layer.allow_origin(self.allowed_origins.iter().cloned().collect::<Vec<_>>())
        }
    }
}

#[derive(Clone, Debug)]
pub(super) struct OriginPolicy(Arc<BTreeSet<HeaderValue>>);

pub(super) async fn enforce_origin(
    State(policy): State<OriginPolicy>,
    request: Request,
    next: Next,
) -> Response {
    if let Some(origin) = request.headers().get(header::ORIGIN)
        && !policy.0.contains(origin)
    {
        return ApiError::forbidden("origin_not_allowed").into_response();
    }
    next.run(request).await
}

pub(super) async fn set_security_headers(request: Request, next: Next) -> Response {
    let mut response = next.run(request).await;
    let headers = response.headers_mut();
    headers.insert(
        header::CACHE_CONTROL,
        HeaderValue::from_static("no-store, max-age=0"),
    );
    headers.insert(header::PRAGMA, HeaderValue::from_static("no-cache"));
    headers.insert(
        header::X_CONTENT_TYPE_OPTIONS,
        HeaderValue::from_static("nosniff"),
    );
    headers.insert(
        header::REFERRER_POLICY,
        HeaderValue::from_static("no-referrer"),
    );
    headers.insert(
        header::HeaderName::from_static("permissions-policy"),
        HeaderValue::from_static("camera=(), microphone=(), geolocation=()"),
    );
    response
}

fn validate_origin(origin: &str) -> Result<(), &'static str> {
    let uri = origin
        .parse::<Uri>()
        .map_err(|_| "UNCIV_V3_ALLOWED_ORIGINS contains an invalid URI")?;
    if uri.scheme_str() != Some("https") {
        return Err("UNCIV_V3_ALLOWED_ORIGINS requires HTTPS origins");
    }
    let authority = uri
        .authority()
        .ok_or("UNCIV_V3_ALLOWED_ORIGINS requires an authority")?;
    if authority.as_str().contains('@') {
        return Err("UNCIV_V3_ALLOWED_ORIGINS forbids user information");
    }
    if uri.path() != "/" || uri.query().is_some() {
        return Err("UNCIV_V3_ALLOWED_ORIGINS requires origin-only URIs");
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use std::sync::{
        Arc,
        atomic::{AtomicUsize, Ordering},
    };

    use axum::{
        Router,
        body::Body,
        http::{Request, StatusCode},
        middleware,
        routing::get,
    };
    use tower::ServiceExt;

    use super::*;

    fn secured_app(config: HttpSecurityConfig, calls: Arc<AtomicUsize>) -> Router {
        Router::new()
            .route(
                "/",
                get(|| async { "ok" }).post(move || {
                    let calls = calls.clone();
                    async move {
                        calls.fetch_add(1, Ordering::SeqCst);
                        "changed"
                    }
                }),
            )
            .layer(config.cors_layer())
            .layer(middleware::from_fn_with_state(
                config.origin_policy(),
                enforce_origin,
            ))
            .layer(middleware::from_fn(set_security_headers))
    }

    #[test]
    fn origin_allowlist_is_exact_bounded_and_https_only() {
        let config =
            HttpSecurityConfig::parse(Some("https://game.example, https://admin.example:8443"))
                .unwrap();
        assert_eq!(config.allowed_origins.len(), 2);
        assert!(
            HttpSecurityConfig::parse(None)
                .unwrap()
                .allowed_origins
                .is_empty()
        );
        for invalid in [
            "",
            "http://game.example",
            "https://user@game.example",
            "https://game.example/path",
            "https://game.example?query",
            "https://game.example,",
        ] {
            assert!(
                HttpSecurityConfig::parse(Some(invalid)).is_err(),
                "{invalid}"
            );
        }
        let excessive = (0..=MAX_ALLOWED_ORIGINS)
            .map(|index| format!("https://{index}.example"))
            .collect::<Vec<_>>()
            .join(",");
        assert!(HttpSecurityConfig::parse(Some(&excessive)).is_err());
    }

    #[tokio::test]
    async fn disallowed_origins_are_rejected_before_mutating_handlers() {
        let calls = Arc::new(AtomicUsize::new(0));
        let app = secured_app(
            HttpSecurityConfig::parse(Some("https://game.example")).unwrap(),
            calls.clone(),
        );
        let response = app
            .oneshot(
                Request::post("/")
                    .header(header::ORIGIN, "https://evil.example")
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::FORBIDDEN);
        assert_eq!(calls.load(Ordering::SeqCst), 0);
        assert_eq!(
            response.headers()[header::CACHE_CONTROL],
            "no-store, max-age=0"
        );
    }

    #[tokio::test]
    async fn allowed_and_native_requests_receive_hardened_responses() {
        let calls = Arc::new(AtomicUsize::new(0));
        let app = secured_app(
            HttpSecurityConfig::parse(Some("https://game.example")).unwrap(),
            calls,
        );
        let allowed = app
            .clone()
            .oneshot(
                Request::get("/")
                    .header(header::ORIGIN, "https://game.example")
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(allowed.status(), StatusCode::OK);
        assert_eq!(
            allowed.headers()[header::ACCESS_CONTROL_ALLOW_ORIGIN],
            "https://game.example"
        );
        for (name, expected) in [
            (header::CACHE_CONTROL, "no-store, max-age=0"),
            (header::PRAGMA, "no-cache"),
            (header::X_CONTENT_TYPE_OPTIONS, "nosniff"),
            (header::REFERRER_POLICY, "no-referrer"),
        ] {
            assert_eq!(allowed.headers()[name], expected);
        }

        let native = app
            .oneshot(Request::get("/").body(Body::empty()).unwrap())
            .await
            .unwrap();
        assert_eq!(native.status(), StatusCode::OK);
        assert!(
            !native
                .headers()
                .contains_key(header::ACCESS_CONTROL_ALLOW_ORIGIN)
        );
    }

    #[tokio::test]
    async fn preflight_is_limited_to_the_configured_origin_method_and_headers() {
        let app = secured_app(
            HttpSecurityConfig::parse(Some("https://game.example")).unwrap(),
            Arc::new(AtomicUsize::new(0)),
        );
        let response = app
            .oneshot(
                Request::builder()
                    .method(Method::OPTIONS)
                    .uri("/")
                    .header(header::ORIGIN, "https://game.example")
                    .header(header::ACCESS_CONTROL_REQUEST_METHOD, "POST")
                    .header(
                        header::ACCESS_CONTROL_REQUEST_HEADERS,
                        "authorization,content-type",
                    )
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::OK);
        assert_eq!(
            response.headers()[header::ACCESS_CONTROL_ALLOW_ORIGIN],
            "https://game.example"
        );
        assert!(
            response.headers()[header::ACCESS_CONTROL_ALLOW_METHODS]
                .to_str()
                .unwrap()
                .contains("POST")
        );
    }
}
