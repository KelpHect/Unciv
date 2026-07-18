use super::*;

impl PostgresGameRepository {
    pub async fn consume_rate_limit(
        &self,
        bucket_material: &str,
        window_seconds: i32,
        max_requests: i32,
        block_seconds: i32,
    ) -> Result<(), AuthError> {
        let bucket_hash = state_hash(bucket_material.as_bytes());
        let allowed: bool = sqlx::query_scalar(
            "WITH consumed AS (INSERT INTO api_rate_limits (bucket_hash, request_count) VALUES ($1, 1) ON CONFLICT (bucket_hash) DO UPDATE SET request_count=CASE WHEN api_rate_limits.window_started_at <= now() - $2 * interval '1 second' THEN 1 ELSE api_rate_limits.request_count + 1 END, window_started_at=CASE WHEN api_rate_limits.window_started_at <= now() - $2 * interval '1 second' THEN now() ELSE api_rate_limits.window_started_at END, blocked_until=CASE WHEN api_rate_limits.blocked_until > now() THEN api_rate_limits.blocked_until WHEN (CASE WHEN api_rate_limits.window_started_at <= now() - $2 * interval '1 second' THEN 1 ELSE api_rate_limits.request_count + 1 END) > $3 THEN now() + $4 * interval '1 second' ELSE NULL END, updated_at=now() RETURNING request_count, blocked_until) SELECT request_count <= $3 AND COALESCE(blocked_until <= now(), true) FROM consumed",
        )
        .bind(bucket_hash)
        .bind(window_seconds.max(1))
        .bind(max_requests.max(1))
        .bind(block_seconds.max(1))
        .fetch_one(&self.pool)
        .await
        .map_err(|_| AuthError::Storage)?;
        if allowed {
            Ok(())
        } else {
            Err(AuthError::RateLimited)
        }
    }

    pub async fn clear_rate_limit(&self, bucket_material: &str) -> Result<(), AuthError> {
        sqlx::query("DELETE FROM api_rate_limits WHERE bucket_hash=$1")
            .bind(state_hash(bucket_material.as_bytes()))
            .execute(&self.pool)
            .await
            .map_err(|_| AuthError::Storage)?;
        Ok(())
    }

    /// Security audit data is deliberately bounded: a network prefix and a
    /// one-way identity hash, never credentials, bearer tokens, or request bodies.
    pub async fn record_security_audit(
        &self,
        account_id: Option<Uuid>,
        event_type: &str,
        outcome: &str,
        source_ip_prefix: &str,
        identity: Option<&str>,
    ) -> Result<(), AuthError> {
        let identity_hash = identity.map(|value| state_hash(value.as_bytes()));
        sqlx::query(
            "INSERT INTO security_audit_events (account_id, event_type, outcome, source_ip_prefix, identity_hash) VALUES ($1, $2, $3, $4::inet, $5)",
        )
        .bind(account_id)
        .bind(event_type)
        .bind(outcome)
        .bind(source_ip_prefix)
        .bind(identity_hash)
        .execute(&self.pool)
        .await
        .map_err(|_| AuthError::Storage)?;
        Ok(())
    }
}
