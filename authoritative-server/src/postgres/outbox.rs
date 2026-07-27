use super::*;

pub(crate) const SHARED_NOTIFICATION_CHANNEL: &str = "unciv_v3_revision_hints";
const MIN_MAX_DELIVERY_ATTEMPTS: i32 = 3;
const MAX_MAX_DELIVERY_ATTEMPTS: i32 = 100;
const MIN_LAG_ALERT_SECONDS: u64 = 10;
const MAX_LAG_ALERT_SECONDS: u64 = 86_400;
const MAX_COMPACTION_LIMIT: i64 = 10_000;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct OutboxRuntimePolicy {
    pub max_delivery_attempts: i32,
    pub lag_alert_after: std::time::Duration,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum OutboxRetryDisposition {
    RetryScheduled,
    DeadLettered,
}

#[derive(Clone, Debug, serde::Serialize, PartialEq, Eq)]
pub struct OutboxHealthReport {
    pub pending_events: u64,
    pub dead_letter_events: u64,
    pub oldest_pending_age_seconds: u64,
    pub oldest_dead_letter_age_seconds: u64,
    pub maximum_attempt_count: u64,
    pub alert: bool,
}

#[derive(Clone, Debug, serde::Serialize, PartialEq, Eq)]
pub struct OutboxCompactionReport {
    pub dry_run: bool,
    pub older_than_days: u32,
    pub selected_events: u64,
    pub compacted_events: u64,
}

#[derive(Clone, Debug, serde::Serialize, PartialEq, Eq)]
pub struct OutboxRequeueReport {
    pub dry_run: bool,
    pub outbox_id: i64,
    pub requeued: bool,
}

impl Default for OutboxRuntimePolicy {
    fn default() -> Self {
        Self {
            max_delivery_attempts: 12,
            lag_alert_after: std::time::Duration::from_secs(60),
        }
    }
}

impl OutboxRuntimePolicy {
    pub fn from_environment() -> Result<Self, &'static str> {
        let max_delivery_attempts = environment_number(
            "UNCIV_V3_OUTBOX_MAX_DELIVERY_ATTEMPTS",
            Self::default().max_delivery_attempts,
        )?;
        let lag_alert_seconds = environment_number(
            "UNCIV_V3_OUTBOX_LAG_ALERT_SECONDS",
            Self::default().lag_alert_after.as_secs(),
        )?;
        Self::from_values(max_delivery_attempts, lag_alert_seconds)
    }

    fn from_values(
        max_delivery_attempts: i32,
        lag_alert_seconds: u64,
    ) -> Result<Self, &'static str> {
        if !(MIN_MAX_DELIVERY_ATTEMPTS..=MAX_MAX_DELIVERY_ATTEMPTS).contains(&max_delivery_attempts)
        {
            return Err("outbox maximum delivery attempts is outside bounds");
        }
        if !(MIN_LAG_ALERT_SECONDS..=MAX_LAG_ALERT_SECONDS).contains(&lag_alert_seconds) {
            return Err("outbox lag alert threshold is outside bounds");
        }
        Ok(Self {
            max_delivery_attempts,
            lag_alert_after: std::time::Duration::from_secs(lag_alert_seconds),
        })
    }
}

impl PostgresGameRepository {
    pub async fn shared_notification_listener(
        &self,
    ) -> Result<sqlx::postgres::PgListener, sqlx::Error> {
        let mut listener = sqlx::postgres::PgListener::connect_with(&self.pool).await?;
        listener.listen(SHARED_NOTIFICATION_CHANNEL).await?;
        Ok(listener)
    }

    pub async fn publish_shared_notification(&self, payload: &str) -> Result<(), CommitError> {
        sqlx::query("SELECT pg_notify($1, $2)")
            .bind(SHARED_NOTIFICATION_CHANNEL)
            .bind(payload)
            .execute(&self.pool)
            .await
            .map_err(CommitError::storage)?;
        Ok(())
    }

    /// Claims a bounded batch with a renewable lease. `SKIP LOCKED` permits
    /// multiple dispatchers without double-claiming; expired claims recover
    /// automatically after a process crash.
    pub async fn claim_outbox_batch(
        &self,
        limit: i64,
    ) -> Result<Vec<ClaimedOutboxEvent>, CommitError> {
        let claim_token = Uuid::new_v4();
        let rows = sqlx::query(
            "WITH candidates AS (SELECT id FROM game_outbox WHERE delivered_at IS NULL AND dead_lettered_at IS NULL AND available_at <= now() AND (claimed_at IS NULL OR claimed_at < now() - interval '30 seconds') ORDER BY id FOR UPDATE SKIP LOCKED LIMIT $1) UPDATE game_outbox o SET claimed_at=now(), claim_token=$2, attempt_count=attempt_count+1, last_error=NULL FROM candidates c WHERE o.id=c.id RETURNING o.id, o.game_id, o.revision, o.topic, o.payload",
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
                    topic: row.get("topic"),
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

    pub async fn outbox_state_hash(
        &self,
        game_id: Uuid,
        revision: u64,
    ) -> Result<String, CommitError> {
        sqlx::query_scalar(
            "SELECT canonical_state_hash FROM game_revisions WHERE game_id=$1 AND revision=$2",
        )
        .bind(game_id)
        .bind(i64::try_from(revision).map_err(|_| CommitError::Storage)?)
        .fetch_optional(&self.pool)
        .await
        .map_err(CommitError::storage)?
        .ok_or(CommitError::Storage)
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
        max_delivery_attempts: i32,
    ) -> Result<OutboxRetryDisposition, CommitError> {
        let dead_lettered: bool = sqlx::query_scalar(
            "UPDATE game_outbox SET available_at=CASE WHEN attempt_count >= $4 THEN available_at ELSE now() + interval '1 second' * LEAST(attempt_count, 60) END, claimed_at=NULL, claim_token=NULL, last_error=left($3, 500), dead_lettered_at=CASE WHEN attempt_count >= $4 THEN now() ELSE NULL END, dead_letter_reason=CASE WHEN attempt_count >= $4 THEN 'delivery attempts exhausted' ELSE NULL END WHERE id=$1 AND claim_token=$2 AND delivered_at IS NULL AND dead_lettered_at IS NULL RETURNING dead_lettered_at IS NOT NULL",
        )
        .bind(event_id)
        .bind(claim_token)
        .bind(error)
        .bind(max_delivery_attempts.clamp(
            MIN_MAX_DELIVERY_ATTEMPTS,
            MAX_MAX_DELIVERY_ATTEMPTS,
        ))
        .fetch_optional(&self.pool)
        .await
        .map_err(CommitError::storage)?
        .ok_or(CommitError::Storage)?;
        Ok(if dead_lettered {
            OutboxRetryDisposition::DeadLettered
        } else {
            OutboxRetryDisposition::RetryScheduled
        })
    }

    pub async fn outbox_health(
        &self,
        lag_alert_after: std::time::Duration,
    ) -> Result<OutboxHealthReport, CommitError> {
        let row = sqlx::query(
            "SELECT count(*) FILTER (WHERE delivered_at IS NULL AND dead_lettered_at IS NULL) AS pending_events, count(*) FILTER (WHERE dead_lettered_at IS NOT NULL) AS dead_letter_events, COALESCE(EXTRACT(EPOCH FROM (now()-min(created_at) FILTER (WHERE delivered_at IS NULL AND dead_lettered_at IS NULL)))::bigint, 0) AS oldest_pending_age_seconds, COALESCE(EXTRACT(EPOCH FROM (now()-min(dead_lettered_at) FILTER (WHERE dead_lettered_at IS NOT NULL)))::bigint, 0) AS oldest_dead_letter_age_seconds, COALESCE(max(attempt_count), 0)::bigint AS maximum_attempt_count FROM game_outbox",
        )
        .fetch_one(&self.pool)
        .await
        .map_err(CommitError::storage)?;
        let pending_events = nonnegative_u64(row.get("pending_events"))?;
        let dead_letter_events = nonnegative_u64(row.get("dead_letter_events"))?;
        let oldest_pending_age_seconds = nonnegative_u64(row.get("oldest_pending_age_seconds"))?;
        let oldest_dead_letter_age_seconds =
            nonnegative_u64(row.get("oldest_dead_letter_age_seconds"))?;
        let maximum_attempt_count = nonnegative_u64(row.get("maximum_attempt_count"))?;
        Ok(OutboxHealthReport {
            pending_events,
            dead_letter_events,
            oldest_pending_age_seconds,
            oldest_dead_letter_age_seconds,
            maximum_attempt_count,
            alert: dead_letter_events > 0
                || oldest_pending_age_seconds >= lag_alert_after.as_secs(),
        })
    }

    pub async fn compact_delivered_outbox(
        &self,
        older_than_days: u32,
        limit: i64,
        dry_run: bool,
    ) -> Result<OutboxCompactionReport, CommitError> {
        let older_than_days = older_than_days.clamp(1, 3_650);
        let limit = limit.clamp(1, MAX_COMPACTION_LIMIT);
        let selected_events: i64 = sqlx::query_scalar(
            "SELECT count(*) FROM (SELECT id FROM game_outbox WHERE delivered_at IS NOT NULL AND created_at < now() - make_interval(days => $1) ORDER BY id LIMIT $2) selected",
        )
        .bind(i32::try_from(older_than_days).expect("bounded days fit i32"))
        .bind(limit)
        .fetch_one(&self.pool)
        .await
        .map_err(CommitError::storage)?;
        if dry_run || selected_events == 0 {
            return Ok(OutboxCompactionReport {
                dry_run,
                older_than_days,
                selected_events: nonnegative_u64(selected_events)?,
                compacted_events: 0,
            });
        }
        let mut tx = self.pool.begin().await.map_err(CommitError::storage)?;
        let compacted_events: i64 = sqlx::query_scalar(
            "WITH selected AS (SELECT id, game_id, revision, topic, created_at, delivered_at, attempt_count FROM game_outbox WHERE delivered_at IS NOT NULL AND created_at < now() - make_interval(days => $1) ORDER BY id FOR UPDATE SKIP LOCKED LIMIT $2), receipts AS (INSERT INTO game_outbox_receipts (outbox_id, game_id, revision, topic, created_at, delivered_at, attempt_count) SELECT id, game_id, revision, topic, created_at, delivered_at, attempt_count FROM selected ON CONFLICT DO NOTHING RETURNING outbox_id), deleted AS (DELETE FROM game_outbox o USING receipts r WHERE o.id=r.outbox_id RETURNING o.id) SELECT count(*) FROM deleted",
        )
        .bind(i32::try_from(older_than_days).expect("bounded days fit i32"))
        .bind(limit)
        .fetch_one(&mut *tx)
        .await
        .map_err(CommitError::storage)?;
        sqlx::query(
            "INSERT INTO outbox_operator_audit (action, affected_count) VALUES ('compact_delivered', $1)",
        )
        .bind(i32::try_from(compacted_events).map_err(|_| CommitError::Storage)?)
        .execute(&mut *tx)
        .await
        .map_err(CommitError::storage)?;
        tx.commit().await.map_err(CommitError::storage)?;
        Ok(OutboxCompactionReport {
            dry_run: false,
            older_than_days,
            selected_events: nonnegative_u64(selected_events)?,
            compacted_events: nonnegative_u64(compacted_events)?,
        })
    }

    pub async fn requeue_dead_letter(
        &self,
        outbox_id: i64,
        dry_run: bool,
    ) -> Result<OutboxRequeueReport, CommitError> {
        let exists: bool = sqlx::query_scalar(
            "SELECT EXISTS(SELECT 1 FROM game_outbox WHERE id=$1 AND dead_lettered_at IS NOT NULL)",
        )
        .bind(outbox_id)
        .fetch_one(&self.pool)
        .await
        .map_err(CommitError::storage)?;
        if dry_run || !exists {
            return Ok(OutboxRequeueReport {
                dry_run,
                outbox_id,
                requeued: false,
            });
        }
        let mut tx = self.pool.begin().await.map_err(CommitError::storage)?;
        let result = sqlx::query(
            "UPDATE game_outbox SET available_at=now(), attempt_count=0, claimed_at=NULL, claim_token=NULL, last_error=NULL, dead_lettered_at=NULL, dead_letter_reason=NULL WHERE id=$1 AND dead_lettered_at IS NOT NULL",
        )
        .bind(outbox_id)
        .execute(&mut *tx)
        .await
        .map_err(CommitError::storage)?;
        if result.rows_affected() != 1 {
            return Err(CommitError::Storage);
        }
        sqlx::query(
            "INSERT INTO outbox_operator_audit (action, outbox_id, affected_count) VALUES ('requeue_dead_letter', $1, 1)",
        )
        .bind(outbox_id)
        .execute(&mut *tx)
        .await
        .map_err(CommitError::storage)?;
        tx.commit().await.map_err(CommitError::storage)?;
        Ok(OutboxRequeueReport {
            dry_run: false,
            outbox_id,
            requeued: true,
        })
    }
}

fn environment_number<T>(name: &str, default: T) -> Result<T, &'static str>
where
    T: std::str::FromStr,
{
    match std::env::var(name) {
        Ok(value) => value
            .parse()
            .map_err(|_| "outbox runtime environment contains an invalid integer"),
        Err(std::env::VarError::NotPresent) => Ok(default),
        Err(std::env::VarError::NotUnicode(_)) => {
            Err("outbox runtime environment contains non-Unicode data")
        }
    }
}

fn nonnegative_u64(value: i64) -> Result<u64, CommitError> {
    u64::try_from(value).map_err(|_| CommitError::Storage)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn outbox_runtime_policy_defaults_and_bounds_are_stable() {
        let default = OutboxRuntimePolicy::default();
        assert_eq!(default.max_delivery_attempts, 12);
        assert_eq!(default.lag_alert_after, std::time::Duration::from_secs(60));
        for invalid in [0, 2, 101, i32::MAX] {
            assert!(OutboxRuntimePolicy::from_values(invalid, 60).is_err());
        }
        for invalid in [0, 9, 86_401, u64::MAX] {
            assert!(OutboxRuntimePolicy::from_values(12, invalid).is_err());
        }
        assert_eq!(
            OutboxRuntimePolicy::from_values(3, 10)
                .unwrap()
                .max_delivery_attempts,
            3
        );
        assert_eq!(
            OutboxRuntimePolicy::from_values(100, 86_400)
                .unwrap()
                .lag_alert_after,
            std::time::Duration::from_secs(86_400)
        );
    }
}
