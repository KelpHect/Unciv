use super::*;

impl PostgresGameRepository {
    /// Claims a bounded batch with a renewable lease. `SKIP LOCKED` permits
    /// multiple dispatchers without double-claiming; expired claims recover
    /// automatically after a process crash.
    pub async fn claim_outbox_batch(
        &self,
        limit: i64,
    ) -> Result<Vec<ClaimedOutboxEvent>, CommitError> {
        let claim_token = Uuid::new_v4();
        let rows = sqlx::query(
            "WITH candidates AS (SELECT id FROM game_outbox WHERE delivered_at IS NULL AND available_at <= now() AND (claimed_at IS NULL OR claimed_at < now() - interval '30 seconds') ORDER BY id FOR UPDATE SKIP LOCKED LIMIT $1) UPDATE game_outbox o SET claimed_at=now(), claim_token=$2, attempt_count=attempt_count+1, last_error=NULL FROM candidates c WHERE o.id=c.id RETURNING o.id, o.game_id, o.revision, o.payload",
        )
        .bind(limit.clamp(1, 1_000))
        .bind(claim_token)
        .fetch_all(&self.pool)
        .await
        .map_err(CommitError::storage)?;
        rows.into_iter()
            .map(|row| {
                let revision = u64::try_from(row.get::<i64, _>("revision"))
                    .map_err(|_| CommitError::Storage)?;
                Ok(ClaimedOutboxEvent {
                    id: row.get("id"),
                    claim_token,
                    game_id: row.get("game_id"),
                    revision,
                    payload: row.get("payload"),
                })
            })
            .collect()
    }

    pub async fn outbox_recipients(&self, game_id: Uuid) -> Result<Vec<Uuid>, CommitError> {
        sqlx::query_scalar("SELECT account_id FROM game_members WHERE game_id=$1")
            .bind(game_id)
            .fetch_all(&self.pool)
            .await
            .map_err(CommitError::storage)
    }

    pub async fn acknowledge_outbox(
        &self,
        event_id: i64,
        claim_token: Uuid,
    ) -> Result<(), CommitError> {
        let result = sqlx::query(
            "UPDATE game_outbox SET delivered_at=now(), claimed_at=NULL, claim_token=NULL WHERE id=$1 AND claim_token=$2 AND delivered_at IS NULL",
        )
        .bind(event_id)
        .bind(claim_token)
        .execute(&self.pool)
        .await
        .map_err(CommitError::storage)?;
        if result.rows_affected() != 1 {
            return Err(CommitError::Storage);
        }
        Ok(())
    }

    pub async fn retry_outbox(
        &self,
        event_id: i64,
        claim_token: Uuid,
        error: &str,
    ) -> Result<(), CommitError> {
        sqlx::query(
            "UPDATE game_outbox SET available_at=now() + interval '1 second' * LEAST(attempt_count, 60), claimed_at=NULL, claim_token=NULL, last_error=left($3, 500) WHERE id=$1 AND claim_token=$2 AND delivered_at IS NULL",
        )
        .bind(event_id)
        .bind(claim_token)
        .bind(error)
        .execute(&self.pool)
        .await
        .map_err(CommitError::storage)?;
        Ok(())
    }
}
