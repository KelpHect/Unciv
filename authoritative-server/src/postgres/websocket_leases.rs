use super::*;

const WEBSOCKET_ADMISSION_LOCK: i64 = 0x554e_4349_5657_5333;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct WebSocketConnectionLease {
    pub lease_id: Uuid,
    pub replica_id: Uuid,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum WebSocketLeaseError {
    GlobalLimit,
    AccountLimit,
    Storage,
}

impl PostgresGameRepository {
    pub async fn acquire_websocket_lease(
        &self,
        account_id: Uuid,
        replica_id: Uuid,
        global_limit: usize,
        account_limit: usize,
        ttl_seconds: u64,
    ) -> Result<WebSocketConnectionLease, WebSocketLeaseError> {
        let mut tx = self
            .pool
            .begin()
            .await
            .map_err(|_| WebSocketLeaseError::Storage)?;
        sqlx::query("SELECT pg_advisory_xact_lock($1)")
            .bind(WEBSOCKET_ADMISSION_LOCK)
            .execute(&mut *tx)
            .await
            .map_err(|_| WebSocketLeaseError::Storage)?;
        sqlx::query(
            "DELETE FROM websocket_connection_leases WHERE expires_at <= clock_timestamp()",
        )
        .execute(&mut *tx)
        .await
        .map_err(|_| WebSocketLeaseError::Storage)?;

        let global_count: i64 =
            sqlx::query_scalar("SELECT count(*) FROM websocket_connection_leases")
                .fetch_one(&mut *tx)
                .await
                .map_err(|_| WebSocketLeaseError::Storage)?;
        if global_count >= limit_as_i64(global_limit)? {
            return Err(WebSocketLeaseError::GlobalLimit);
        }
        let account_count: i64 = sqlx::query_scalar(
            "SELECT count(*) FROM websocket_connection_leases WHERE account_id = $1",
        )
        .bind(account_id)
        .fetch_one(&mut *tx)
        .await
        .map_err(|_| WebSocketLeaseError::Storage)?;
        if account_count >= limit_as_i64(account_limit)? {
            return Err(WebSocketLeaseError::AccountLimit);
        }

        let lease = WebSocketConnectionLease {
            lease_id: Uuid::new_v4(),
            replica_id,
        };
        sqlx::query(
            "INSERT INTO websocket_connection_leases (
                lease_id, account_id, replica_id, expires_at
             ) VALUES ($1, $2, $3, clock_timestamp() + $4 * INTERVAL '1 second')",
        )
        .bind(lease.lease_id)
        .bind(account_id)
        .bind(replica_id)
        .bind(ttl_as_i64(ttl_seconds)?)
        .execute(&mut *tx)
        .await
        .map_err(|_| WebSocketLeaseError::Storage)?;
        tx.commit()
            .await
            .map_err(|_| WebSocketLeaseError::Storage)?;
        Ok(lease)
    }

    pub async fn renew_websocket_lease(
        &self,
        lease: WebSocketConnectionLease,
        ttl_seconds: u64,
    ) -> Result<bool, WebSocketLeaseError> {
        let result = sqlx::query(
            "UPDATE websocket_connection_leases
             SET renewed_at = clock_timestamp(),
                 expires_at = clock_timestamp() + $3 * INTERVAL '1 second'
             WHERE lease_id = $1 AND replica_id = $2 AND expires_at > clock_timestamp()",
        )
        .bind(lease.lease_id)
        .bind(lease.replica_id)
        .bind(ttl_as_i64(ttl_seconds)?)
        .execute(&self.pool)
        .await
        .map_err(|_| WebSocketLeaseError::Storage)?;
        Ok(result.rows_affected() == 1)
    }

    pub async fn release_websocket_lease(
        &self,
        lease: WebSocketConnectionLease,
    ) -> Result<bool, WebSocketLeaseError> {
        let result = sqlx::query(
            "DELETE FROM websocket_connection_leases
             WHERE lease_id = $1 AND replica_id = $2",
        )
        .bind(lease.lease_id)
        .bind(lease.replica_id)
        .execute(&self.pool)
        .await
        .map_err(|_| WebSocketLeaseError::Storage)?;
        Ok(result.rows_affected() == 1)
    }
}

fn limit_as_i64(limit: usize) -> Result<i64, WebSocketLeaseError> {
    i64::try_from(limit).map_err(|_| WebSocketLeaseError::Storage)
}

fn ttl_as_i64(ttl_seconds: u64) -> Result<i64, WebSocketLeaseError> {
    i64::try_from(ttl_seconds).map_err(|_| WebSocketLeaseError::Storage)
}
