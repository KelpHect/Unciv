use std::sync::Arc;
use std::time::{Duration, Instant};

use reqwest::{Client, StatusCode};
use serde::Deserialize;
use sha2::{Digest, Sha256};
use tokio::{sync::Mutex, time::sleep};

#[derive(Debug, thiserror::Error)]
pub enum ObjectStoreError {
    #[error("Lockwell object storage is not configured")]
    NotConfigured,
    #[error(
        "Lockwell configuration is incomplete; set endpoint, access key, secret, and bucket together"
    )]
    IncompleteConfiguration,
    #[error("Lockwell request failed: {0}")]
    Request(#[from] reqwest::Error),
    #[error("Lockwell returned HTTP {status}: {message}")]
    Http { status: StatusCode, message: String },
    #[error("Lockwell returned an invalid token response")]
    InvalidToken,
    #[error("invalid Lockwell object key")]
    InvalidKey,
}

#[derive(Clone)]
pub struct LockwellObjectStore {
    inner: Arc<LockwellInner>,
}

struct LockwellInner {
    client: Client,
    endpoint: String,
    bucket: String,
    access_key_id: String,
    secret_key: String,
    token: Mutex<Option<CachedToken>>,
}

struct CachedToken {
    value: String,
    refresh_at: Instant,
}

#[derive(Deserialize)]
struct TokenResponse {
    #[serde(rename = "accessToken", alias = "access_token", alias = "token")]
    access_token: String,
}

impl LockwellObjectStore {
    pub fn from_env() -> Result<Option<Self>, ObjectStoreError> {
        let endpoint = std::env::var("UNCIV_LOCKWELL_ENDPOINT").ok();
        let access_key_id = std::env::var("UNCIV_LOCKWELL_ACCESS_KEY_ID").ok();
        let secret_key = std::env::var("UNCIV_LOCKWELL_SECRET_KEY").ok();
        let bucket = std::env::var("UNCIV_LOCKWELL_BUCKET").ok();
        let configured = endpoint.is_some()
            || access_key_id.is_some()
            || secret_key.is_some()
            || bucket.is_some();
        if !configured {
            return Ok(None);
        }
        let Some(endpoint) = endpoint.filter(|value| !value.trim().is_empty()) else {
            return Err(ObjectStoreError::IncompleteConfiguration);
        };
        let Some(access_key_id) = access_key_id.filter(|value| !value.trim().is_empty()) else {
            return Err(ObjectStoreError::IncompleteConfiguration);
        };
        let Some(secret_key) = secret_key.filter(|value| !value.trim().is_empty()) else {
            return Err(ObjectStoreError::IncompleteConfiguration);
        };
        let Some(bucket) = bucket.filter(|value| !value.trim().is_empty()) else {
            return Err(ObjectStoreError::IncompleteConfiguration);
        };
        Ok(Some(Self::new(
            endpoint,
            bucket,
            access_key_id,
            secret_key,
        )?))
    }

    pub fn new(
        endpoint: impl Into<String>,
        bucket: impl Into<String>,
        access_key_id: impl Into<String>,
        secret_key: impl Into<String>,
    ) -> Result<Self, ObjectStoreError> {
        let endpoint = endpoint.into().trim_end_matches('/').to_owned();
        let bucket = bucket.into();
        if endpoint.is_empty()
            || bucket.is_empty()
            || bucket.contains('/')
            || bucket.contains('?')
            || bucket.contains('#')
        {
            return Err(ObjectStoreError::IncompleteConfiguration);
        }
        Ok(Self {
            inner: Arc::new(LockwellInner {
                client: Client::builder()
                    .user_agent("unciv-authoritative-server/lockwell-archive")
                    .build()?,
                endpoint,
                bucket,
                access_key_id: access_key_id.into(),
                secret_key: secret_key.into(),
                token: Mutex::new(None),
            }),
        })
    }

    pub fn bucket(&self) -> &str {
        &self.inner.bucket
    }

    /// Creates an object without overwriting an existing object. A caller that
    /// receives `AlreadyExists` must GET and verify the object before treating
    /// the archive operation as idempotently complete.
    pub async fn put_if_absent(
        &self,
        key: &str,
        payload: Vec<u8>,
    ) -> Result<PutObjectResult, ObjectStoreError> {
        let url = self.object_url(key)?;
        let payload_hash = crate::state_hash(&payload);
        let checksum = base64_sha256(&payload);
        let idempotency_key = format!(
            "unciv-archive-{}",
            crate::state_hash(format!("{key}\0{payload_hash}").as_bytes())
        );
        for attempt in 0..3 {
            let mut refreshed = false;
            loop {
                let token = self.token(refreshed).await?;
                let response = match self
                    .inner
                    .client
                    .put(&url)
                    .bearer_auth(token)
                    .header("If-None-Match", "*")
                    .header("Idempotency-Key", &idempotency_key)
                    .header("X-Lockwell-Checksum-SHA256", &checksum)
                    .header("Content-Type", "application/octet-stream")
                    .body(payload.clone())
                    .send()
                    .await
                {
                    Ok(response) => response,
                    Err(_error) if attempt < 2 => {
                        sleep(Duration::from_millis(50 * (1 << attempt))).await;
                        break;
                    }
                    Err(error) => return Err(error.into()),
                };
                if response.status() == StatusCode::UNAUTHORIZED && !refreshed {
                    refreshed = true;
                    continue;
                }
                if is_retryable(response.status()) && attempt < 2 {
                    sleep(Duration::from_millis(50 * (1 << attempt))).await;
                    break;
                }
                if response.status() == StatusCode::PRECONDITION_FAILED
                    || response.status() == StatusCode::CONFLICT
                {
                    return Ok(PutObjectResult::AlreadyExists);
                }
                if !response.status().is_success() {
                    return Err(http_error(response).await);
                }
                return Ok(PutObjectResult::Created);
            }
        }
        unreachable!("token refresh loop returns or continues")
    }

    pub async fn get(&self, key: &str) -> Result<Vec<u8>, ObjectStoreError> {
        let url = self.object_url(key)?;
        for refresh in [false, true] {
            let token = self.token(refresh).await?;
            let response = self
                .inner
                .client
                .get(&url)
                .bearer_auth(token)
                .send()
                .await?;
            if response.status() == StatusCode::UNAUTHORIZED && !refresh {
                continue;
            }
            if !response.status().is_success() {
                return Err(http_error(response).await);
            }
            return Ok(response.bytes().await?.to_vec());
        }
        unreachable!("token refresh loop returns or continues")
    }

    pub async fn delete(&self, key: &str) -> Result<(), ObjectStoreError> {
        let url = self.object_url(key)?;
        for refresh in [false, true] {
            let token = self.token(refresh).await?;
            let response = self
                .inner
                .client
                .delete(&url)
                .bearer_auth(token)
                .send()
                .await?;
            if response.status() == StatusCode::UNAUTHORIZED && !refresh {
                continue;
            }
            if response.status() == StatusCode::NOT_FOUND {
                return Ok(());
            }
            if !response.status().is_success() {
                return Err(http_error(response).await);
            }
            return Ok(());
        }
        unreachable!("token refresh loop returns or continues")
    }

    async fn token(&self, force_refresh: bool) -> Result<String, ObjectStoreError> {
        let mut cached = self.inner.token.lock().await;
        if !force_refresh
            && let Some(token) = cached
                .as_ref()
                .filter(|token| token.refresh_at > Instant::now())
        {
            return Ok(token.value.clone());
        }
        let response = self
            .inner
            .client
            .post(format!("{}/api/v1/auth/token", self.inner.endpoint))
            .json(&serde_json::json!({
                "accessKeyId": self.inner.access_key_id,
                "secretKey": self.inner.secret_key,
            }))
            .send()
            .await?;
        if !response.status().is_success() {
            return Err(http_error(response).await);
        }
        let body: TokenResponse = response
            .json()
            .await
            .map_err(|_| ObjectStoreError::InvalidToken)?;
        if body.access_token.trim().is_empty() {
            return Err(ObjectStoreError::InvalidToken);
        }
        // Lockwell's default native token TTL is one hour. Refresh early; if a
        // deployment uses a shorter TTL, a 401 causes one forced re-mint.
        let token = CachedToken {
            value: body.access_token.clone(),
            refresh_at: Instant::now() + Duration::from_secs(45 * 60),
        };
        *cached = Some(token);
        Ok(body.access_token)
    }

    fn object_url(&self, key: &str) -> Result<String, ObjectStoreError> {
        // Archive keys are generated from UUIDs, decimal revisions, and '/'.
        // Rejecting arbitrary path bytes keeps this adapter from becoming a
        // path traversal surface and avoids depending on a URL-encoding crate.
        if key.is_empty()
            || !key.bytes().all(|byte| {
                byte.is_ascii_alphanumeric() || matches!(byte, b'-' | b'_' | b'.' | b'/')
            })
            || key
                .split('/')
                .any(|segment| segment.is_empty() || segment == "." || segment == "..")
        {
            return Err(ObjectStoreError::InvalidKey);
        }
        Ok(format!(
            "{}/api/v1/buckets/{}/objects/{}",
            self.inner.endpoint, self.inner.bucket, key
        ))
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PutObjectResult {
    Created,
    AlreadyExists,
}

fn is_retryable(status: StatusCode) -> bool {
    status == StatusCode::REQUEST_TIMEOUT
        || status == StatusCode::TOO_EARLY
        || status == StatusCode::TOO_MANY_REQUESTS
        || status.is_server_error()
}

fn base64_sha256(payload: &[u8]) -> String {
    const TABLE: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    let digest = Sha256::digest(payload);
    let mut encoded = String::with_capacity(44);
    for chunk in digest.chunks(3) {
        let first = chunk[0];
        let second = chunk.get(1).copied().unwrap_or(0);
        let third = chunk.get(2).copied().unwrap_or(0);
        encoded.push(TABLE[(first >> 2) as usize] as char);
        encoded.push(TABLE[((first & 0x03) << 4 | second >> 4) as usize] as char);
        encoded.push(if chunk.len() > 1 {
            TABLE[((second & 0x0f) << 2 | third >> 6) as usize] as char
        } else {
            '='
        });
        encoded.push(if chunk.len() > 2 {
            TABLE[(third & 0x3f) as usize] as char
        } else {
            '='
        });
    }
    encoded
}

async fn http_error(response: reqwest::Response) -> ObjectStoreError {
    let status = response.status();
    let message = response
        .text()
        .await
        .unwrap_or_default()
        .chars()
        .take(512)
        .collect();
    ObjectStoreError::Http { status, message }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn empty_constructor_values_fail_closed() {
        assert!(matches!(
            LockwellObjectStore::new("", "snapshots", "id", "secret"),
            Err(ObjectStoreError::IncompleteConfiguration)
        ));
        assert!(matches!(
            LockwellObjectStore::new("http://localhost:9000", "", "id", "secret"),
            Err(ObjectStoreError::IncompleteConfiguration)
        ));
    }

    #[test]
    fn lockwell_checksum_uses_standard_base64_sha256() {
        assert_eq!(
            base64_sha256(b"hello"),
            "LPJNul+wow4m6DsqxbninhsWHlwfp0JecwQzYpOLmCQ="
        );
    }

    #[test]
    fn generated_archive_paths_are_stable_and_safe() {
        let store =
            LockwellObjectStore::new("http://localhost:9000", "snapshots", "id", "secret").unwrap();
        assert_eq!(
            store
                .object_url("games/00000000-0000-0000-0000-000000000000/revisions/42.snap")
                .unwrap(),
            "http://localhost:9000/api/v1/buckets/snapshots/objects/games/00000000-0000-0000-0000-000000000000/revisions/42.snap"
        );
        assert!(matches!(
            store.object_url("../escape"),
            Err(ObjectStoreError::InvalidKey)
        ));
    }
}
