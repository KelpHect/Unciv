use sqlx::Row;
use uuid::Uuid;

use super::PostgresGameRepository;

pub const SECURITY_AUDIT_EXPORT_PAGE_SIZE: i64 = 1_000;

#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize)]
pub struct SecurityAuditExportEvent {
    pub id: i64,
    pub account_id: Option<Uuid>,
    pub event_type: String,
    pub outcome: String,
    pub source_ip_prefix: Option<String>,
    pub identity_hash: Option<String>,
    pub created_at_utc: String,
}

impl PostgresGameRepository {
    /// Reads one stable, bounded page for the local operator export. The audit
    /// role is read-only and the monotonically increasing ID is the cursor.
    pub async fn security_audit_export_page(
        &self,
        after_id: i64,
        through_id: i64,
    ) -> Result<Vec<SecurityAuditExportEvent>, sqlx::Error> {
        let rows = sqlx::query(
            "SELECT id, account_id, event_type, outcome, source_ip_prefix::text AS source_ip_prefix,
                    identity_hash,
                    to_char(created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS.US\"Z\"')
                        AS created_at_utc
             FROM security_audit_events
             WHERE id > $1 AND id <= $2
             ORDER BY id
             LIMIT $3",
        )
        .bind(after_id)
        .bind(through_id)
        .bind(SECURITY_AUDIT_EXPORT_PAGE_SIZE)
        .fetch_all(&self.pool)
        .await?;
        rows.into_iter()
            .map(|row| {
                Ok(SecurityAuditExportEvent {
                    id: row.try_get("id")?,
                    account_id: row.try_get("account_id")?,
                    event_type: row.try_get("event_type")?,
                    outcome: row.try_get("outcome")?,
                    source_ip_prefix: row.try_get("source_ip_prefix")?,
                    identity_hash: row.try_get("identity_hash")?,
                    created_at_utc: row.try_get("created_at_utc")?,
                })
            })
            .collect()
    }

    pub async fn security_audit_export_high_watermark(&self) -> Result<i64, sqlx::Error> {
        sqlx::query_scalar("SELECT COALESCE(max(id), 0) FROM security_audit_events")
            .fetch_one(&self.pool)
            .await
    }
}
