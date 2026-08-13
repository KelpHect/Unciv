use super::*;
use std::time::Duration;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct SnapshotRetentionPolicy {
    pub recent_revisions: u64,
    pub long_term_interval: u64,
}

impl Default for SnapshotRetentionPolicy {
    fn default() -> Self {
        Self {
            recent_revisions: 64,
            long_term_interval: 100,
        }
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct SnapshotMaintenanceConfig {
    pub enabled: bool,
    pub interval: Duration,
    pub policy: SnapshotRetentionPolicy,
    pub use_deltas: bool,
    pub max_games_per_tick: u64,
    pub max_revisions_per_game: u64,
    pub postgres_budget_bytes: Option<u64>,
    /// Maximum verified Lockwell object bytes retained for all games. Zero
    /// disables the external archive quota; PostgreSQL retention remains
    /// bounded independently by `postgres_budget_bytes` and the policy.
    pub archive_budget_bytes: Option<u64>,
    /// Per-match storage ceiling over a game's retained PostgreSQL payloads
    /// plus its verified archive objects. A match that crosses this threshold
    /// is flagged and its further archival is paused so one runaway game cannot
    /// consume the whole archive. `None` disables the per-match guard.
    pub game_storage_budget_bytes: Option<u64>,
}

impl SnapshotMaintenanceConfig {
    pub fn from_environment(object_store_configured: bool) -> Result<Self, String> {
        let enabled = match std::env::var("UNCIV_V3_SNAPSHOT_ARCHIVE_ENABLED") {
            Ok(value) => parse_bool("UNCIV_V3_SNAPSHOT_ARCHIVE_ENABLED", &value)?,
            Err(_) => object_store_configured,
        };
        if enabled && !object_store_configured {
            return Err(
                "UNCIV_V3_SNAPSHOT_ARCHIVE_ENABLED requires a complete Lockwell configuration"
                    .to_owned(),
            );
        }
        let recent_revisions = parse_u64(
            "UNCIV_V3_SNAPSHOT_ARCHIVE_RECENT_REVISIONS",
            64,
            2,
            1_000_000,
        )?;
        let long_term_interval = parse_u64(
            "UNCIV_V3_SNAPSHOT_ARCHIVE_LONG_TERM_INTERVAL",
            100,
            1,
            1_000_000,
        )?;
        let interval_seconds =
            parse_u64("UNCIV_V3_SNAPSHOT_ARCHIVE_INTERVAL_SECONDS", 60, 10, 86_400)?;
        let max_games_per_tick = parse_u64("UNCIV_V3_SNAPSHOT_ARCHIVE_MAX_GAMES", 4, 1, 1_000)?;
        let max_revisions_per_game =
            parse_u64("UNCIV_V3_SNAPSHOT_ARCHIVE_MAX_REVISIONS", 128, 1, 100_000)?;
        let budget = parse_u64("UNCIV_V3_SNAPSHOT_POSTGRES_BUDGET_BYTES", 0, 0, 1 << 40)?;
        let archive_budget = parse_u64("UNCIV_V3_SNAPSHOT_ARCHIVE_BUDGET_BYTES", 0, 0, 1 << 50)?;
        let game_budget = parse_u64("UNCIV_V3_SNAPSHOT_GAME_BUDGET_BYTES", 0, 0, 1 << 40)?;
        let use_deltas = match std::env::var("UNCIV_V3_SNAPSHOT_ARCHIVE_USE_DELTAS") {
            Ok(value) => parse_bool("UNCIV_V3_SNAPSHOT_ARCHIVE_USE_DELTAS", &value)?,
            Err(_) => true,
        };
        Ok(Self {
            enabled,
            interval: Duration::from_secs(interval_seconds),
            policy: SnapshotRetentionPolicy {
                recent_revisions,
                long_term_interval,
            },
            use_deltas,
            max_games_per_tick,
            max_revisions_per_game,
            postgres_budget_bytes: (budget != 0).then_some(budget),
            archive_budget_bytes: (archive_budget != 0).then_some(archive_budget),
            game_storage_budget_bytes: (game_budget != 0).then_some(game_budget),
        })
    }
}

#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize)]
pub struct SnapshotMaintenanceReport {
    pub games_scanned: u64,
    pub games_over_budget: u64,
    pub games_over_storage_budget: u64,
    pub archived_payloads: u64,
    pub delta_payloads: u64,
    pub bytes_archived: u64,
    pub failures: u64,
    pub postgres_bytes: u64,
    pub archive_bytes: u64,
    pub budget_exceeded: bool,
    pub archive_quota_exceeded: bool,
    pub game_storage_budget_exceeded: bool,
}

#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize)]
pub struct SnapshotCompactionReport {
    pub game_id: Uuid,
    pub head_revision: u64,
    pub retained_payloads: u64,
    pub compacted_payloads: u64,
    pub bytes_reclaimed: u64,
    pub dry_run: bool,
}

impl PostgresGameRepository {
    pub fn snapshot_archival_configured(&self) -> bool {
        self.object_store.is_some()
    }

    /// Runs one bounded maintenance pass. The automatic path only archives to
    /// verified Lockwell objects; it never uses the destructive PostgreSQL-only
    /// compactor, so every removed payload remains recoverable from a retained
    /// checkpoint plus a bounded archive delta.
    pub async fn run_snapshot_maintenance_once(
        &self,
        config: SnapshotMaintenanceConfig,
    ) -> Result<SnapshotMaintenanceReport, CommitError> {
        if !config.enabled || !self.snapshot_archival_configured() {
            return Err(CommitError::Storage);
        }
        let game_ids: Vec<Uuid> = sqlx::query_scalar(
            "SELECT g.id
             FROM games g
             WHERE g.lifecycle_status <> 'archived'
               AND EXISTS (
                   SELECT 1 FROM game_snapshot_blobs b
                   JOIN game_snapshots s
                     ON s.game_id=b.game_id AND s.revision=b.revision
                   WHERE b.game_id=g.id AND s.payload_retention_status='retained'
               )
             ORDER BY g.created_at, g.id
             LIMIT $1",
        )
        .bind(i64::try_from(config.max_games_per_tick).map_err(|_| CommitError::Storage)?)
        .fetch_all(&self.pool)
        .await
        .map_err(CommitError::storage)?;
        let mut report = SnapshotMaintenanceReport {
            games_scanned: 0,
            games_over_budget: 0,
            games_over_storage_budget: 0,
            archived_payloads: 0,
            delta_payloads: 0,
            bytes_archived: 0,
            failures: 0,
            postgres_bytes: 0,
            archive_bytes: self.archive_object_bytes().await?,
            budget_exceeded: false,
            archive_quota_exceeded: false,
            game_storage_budget_exceeded: false,
        };
        for game_id in game_ids {
            report.games_scanned += 1;
            let remaining_archive_bytes = config
                .archive_budget_bytes
                .map(|budget| budget.saturating_sub(report.archive_bytes));
            if remaining_archive_bytes == Some(0) {
                report.archive_quota_exceeded = true;
                break;
            }
            let game_bytes = self.snapshot_blob_bytes(Some(game_id)).await?;
            let game_archive_bytes = self.game_archive_object_bytes(game_id).await?;
            let game_total_bytes = game_bytes.saturating_add(game_archive_bytes);
            if game_storage_budget_exceeded(game_total_bytes, config.game_storage_budget_bytes) {
                report.games_over_storage_budget += 1;
                tracing::warn!(
                    postgres_bytes = game_bytes,
                    archive_bytes = game_archive_bytes,
                    total_bytes = game_total_bytes,
                    "game exceeds its storage budget; archival paused for this game"
                );
                continue;
            }
            let over_budget = config
                .postgres_budget_bytes
                .is_some_and(|budget| game_bytes > budget);
            if over_budget {
                report.games_over_budget += 1;
            }
            // With no budget, the pass keeps the cold backlog bounded on every
            // tick. With a budget, avoid external writes until that game's
            // retained PostgreSQL bytes actually cross the configured limit.
            if config.postgres_budget_bytes.is_some() && !over_budget {
                continue;
            }
            match self
                .archive_snapshot_payloads_bounded(
                    game_id,
                    config.policy,
                    false,
                    config.use_deltas,
                    config.max_revisions_per_game,
                    remaining_archive_bytes.unwrap_or(u64::MAX),
                )
                .await
            {
                Ok(archived) => {
                    report.archived_payloads += archived.archived_payloads;
                    report.delta_payloads += archived.delta_payloads;
                    report.bytes_archived += archived.bytes_archived;
                    report.archive_bytes += archived.bytes_archived;
                    if archive_quota_would_pause(
                        remaining_archive_bytes,
                        archived.bytes_archived,
                        archived.candidates,
                        archived.archived_payloads,
                    ) {
                        report.archive_quota_exceeded = true;
                    }
                }
                Err(error) => {
                    report.failures += 1;
                    tracing::warn!(error = %error, "snapshot maintenance failed closed for one game");
                }
            }
        }
        report.postgres_bytes = self.snapshot_blob_bytes(None).await?;
        report.budget_exceeded = config
            .postgres_budget_bytes
            .is_some_and(|budget| report.postgres_bytes > budget);
        report.archive_quota_exceeded |= config
            .archive_budget_bytes
            .is_some_and(|budget| report.archive_bytes >= budget);
        report.game_storage_budget_exceeded = report.games_over_storage_budget > 0;
        metrics::gauge!("unciv_v3_snapshot_postgres_bytes").set(report.postgres_bytes as f64);
        metrics::gauge!("unciv_v3_snapshot_budget_exceeded").set(if report.budget_exceeded {
            1.0
        } else {
            0.0
        });
        metrics::gauge!("unciv_v3_snapshot_archive_bytes").set(report.archive_bytes as f64);
        metrics::gauge!("unciv_v3_snapshot_archive_quota_exceeded").set(
            if report.archive_quota_exceeded {
                1.0
            } else {
                0.0
            },
        );
        metrics::counter!("unciv_v3_snapshot_archive_runs_total").increment(1);
        if report.failures > 0 {
            metrics::counter!("unciv_v3_snapshot_archive_failures_total")
                .increment(report.failures);
        }
        if report.budget_exceeded {
            metrics::counter!("unciv_v3_snapshot_budget_exceeded_total").increment(1);
        }
        if report.archive_quota_exceeded {
            metrics::counter!("unciv_v3_snapshot_archive_quota_exceeded_total").increment(1);
        }
        metrics::gauge!("unciv_v3_snapshot_game_storage_budget_exceeded").set(
            if report.game_storage_budget_exceeded {
                1.0
            } else {
                0.0
            },
        );
        if report.games_over_storage_budget > 0 {
            metrics::counter!("unciv_v3_snapshot_game_storage_budget_exceeded_total")
                .increment(report.games_over_storage_budget);
        }
        Ok(report)
    }

    async fn archive_object_bytes(&self) -> Result<u64, CommitError> {
        let bytes: i64 = sqlx::query_scalar(
            "SELECT COALESCE(SUM(object_size), 0)::bigint FROM game_snapshot_archives",
        )
        .fetch_one(&self.pool)
        .await
        .map_err(CommitError::storage)?;
        u64::try_from(bytes).map_err(|_| CommitError::Storage)
    }

    async fn game_archive_object_bytes(&self, game_id: Uuid) -> Result<u64, CommitError> {
        let bytes: i64 = sqlx::query_scalar(
            "SELECT COALESCE(SUM(object_size), 0)::bigint
             FROM game_snapshot_archives WHERE game_id=$1",
        )
        .bind(game_id)
        .fetch_one(&self.pool)
        .await
        .map_err(CommitError::storage)?;
        u64::try_from(bytes).map_err(|_| CommitError::Storage)
    }

    async fn snapshot_blob_bytes(&self, game_id: Option<Uuid>) -> Result<u64, CommitError> {
        let bytes: i64 = match game_id {
            Some(game_id) => sqlx::query_scalar(
                "SELECT COALESCE(SUM(octet_length(b.payload)::bigint), 0)::bigint
                 FROM game_snapshot_blobs b
                 JOIN game_snapshots s
                   ON s.game_id=b.game_id AND s.revision=b.revision
                 WHERE b.game_id=$1 AND s.payload_retention_status='retained'",
            )
            .bind(game_id)
            .fetch_one(&self.pool)
            .await
            .map_err(CommitError::storage)?,
            None => sqlx::query_scalar(
                "SELECT COALESCE(SUM(octet_length(b.payload)::bigint), 0)::bigint
                 FROM game_snapshot_blobs b
                 JOIN game_snapshots s
                   ON s.game_id=b.game_id AND s.revision=b.revision
                 WHERE s.payload_retention_status='retained'",
            )
            .fetch_one(&self.pool)
            .await
            .map_err(CommitError::storage)?,
        };
        u64::try_from(bytes).map_err(|_| CommitError::Storage)
    }

    /// Removes only payload bytes selected by the retention policy. Revision,
    /// command, audit, snapshot hash, and snapshot identity metadata remain
    /// immutable. This explicit operator tool is intentionally separate from
    /// automatic Lockwell archival.
    pub async fn compact_snapshot_payloads(
        &self,
        game_id: Uuid,
        policy: SnapshotRetentionPolicy,
        dry_run: bool,
    ) -> Result<SnapshotCompactionReport, CommitError> {
        if policy.recent_revisions < 2 || policy.long_term_interval == 0 {
            return Err(CommitError::InvalidCommand);
        }
        let mut tx = self.pool.begin().await.map_err(CommitError::storage)?;
        let game_row =
            sqlx::query("SELECT head_revision, visibility FROM games WHERE id=$1 FOR UPDATE")
                .bind(game_id)
                .fetch_optional(&mut *tx)
                .await
                .map_err(CommitError::storage)?
                .ok_or(CommitError::NotFound)?;
        let head_i64: i64 = game_row.get("head_revision");
        let is_public = game_row.get::<String, _>("visibility") == "public";
        let head_revision =
            u64::try_from(head_i64).map_err(|_| CommitError::RecoveryEvidenceMissing)?;
        let rows = sqlx::query(
            "SELECT s.revision, r.revision_kind,
                    c.payload->'command'->>'type' AS command_type,
                    octet_length(b.payload)::bigint AS payload_size
             FROM game_snapshots s
             JOIN game_snapshot_blobs b
               ON b.game_id=s.game_id AND b.revision=s.revision
             JOIN game_revisions r
               ON r.game_id=s.game_id AND r.snapshot_revision=s.revision
             LEFT JOIN game_commands c
               ON c.game_id=r.game_id AND c.revision=r.revision
             WHERE s.game_id=$1 AND s.payload_retention_status='retained'
             ORDER BY s.revision",
        )
        .bind(game_id)
        .fetch_all(&mut *tx)
        .await
        .map_err(CommitError::storage)?;

        let mut compact = Vec::new();
        let mut retained_payloads = 0_u64;
        let mut bytes_reclaimed = 0_u64;
        for row in rows {
            let revision_i64: i64 = row.get("revision");
            let revision =
                u64::try_from(revision_i64).map_err(|_| CommitError::RecoveryEvidenceMissing)?;
            let revision_kind: String = row.get("revision_kind");
            let command_type: Option<String> = row.get("command_type");
            if is_public
                || should_retain(
                    revision,
                    head_revision,
                    &revision_kind,
                    command_type.as_deref(),
                    policy,
                )
            {
                retained_payloads += 1;
            } else {
                compact.push(revision_i64);
                bytes_reclaimed += u64::try_from(row.get::<i64, _>("payload_size"))
                    .map_err(|_| CommitError::RecoveryEvidenceMissing)?;
            }
        }

        if !dry_run && !compact.is_empty() {
            sqlx::query(
                "UPDATE game_snapshots
                 SET payload_retention_status='compacted', compacted_at=now()
                 WHERE game_id=$1 AND revision=ANY($2)
                   AND payload_retention_status='retained'",
            )
            .bind(game_id)
            .bind(&compact)
            .execute(&mut *tx)
            .await
            .map_err(CommitError::storage)?;
            let deleted = sqlx::query(
                "DELETE FROM game_snapshot_blobs
                 WHERE game_id=$1 AND revision=ANY($2)",
            )
            .bind(game_id)
            .bind(&compact)
            .execute(&mut *tx)
            .await
            .map_err(CommitError::storage)?;
            if deleted.rows_affected() != compact.len() as u64 {
                return Err(CommitError::Storage);
            }
        }
        if dry_run {
            tx.rollback().await.map_err(CommitError::storage)?;
        } else {
            tx.commit().await.map_err(CommitError::storage)?;
        }
        Ok(SnapshotCompactionReport {
            game_id,
            head_revision,
            retained_payloads,
            compacted_payloads: compact.len() as u64,
            bytes_reclaimed,
            dry_run,
        })
    }
}

fn archive_quota_would_pause(
    remaining_bytes: Option<u64>,
    archived_bytes: u64,
    candidates: u64,
    archived_payloads: u64,
) -> bool {
    remaining_bytes
        .is_some_and(|remaining| archived_bytes >= remaining || candidates > archived_payloads)
}

/// True when a match's retained PostgreSQL payloads plus its verified archive
/// objects cross the configured per-match storage ceiling. `None` keeps the
/// guard disabled so existing unbounded deployments retain their behaviour.
fn game_storage_budget_exceeded(game_total_bytes: u64, budget: Option<u64>) -> bool {
    budget.is_some_and(|budget| game_total_bytes > budget)
}

pub(super) fn should_retain(
    revision: u64,
    head_revision: u64,
    revision_kind: &str,
    command_type: Option<&str>,
    policy: SnapshotRetentionPolicy,
) -> bool {
    let recent_start = head_revision.saturating_sub(policy.recent_revisions.saturating_sub(1));
    revision == 0
        || revision == head_revision
        || revision >= recent_start
        || matches!(
            revision_kind,
            "recovery" | "rewind" | "lobby_reconfiguration"
        )
        || command_type == Some("end_turn")
        || revision.is_multiple_of(policy.long_term_interval)
}

fn parse_bool(name: &str, value: &str) -> Result<bool, String> {
    match value.trim().to_ascii_lowercase().as_str() {
        "1" | "true" | "yes" | "on" => Ok(true),
        "0" | "false" | "no" | "off" => Ok(false),
        _ => Err(format!("{name} must be true or false")),
    }
}

fn parse_u64(name: &str, default: u64, minimum: u64, maximum: u64) -> Result<u64, String> {
    let value = match std::env::var(name) {
        Ok(value) => value
            .parse::<u64>()
            .map_err(|_| format!("{name} must be an integer"))?,
        Err(_) => default,
    };
    if !(minimum..=maximum).contains(&value) {
        return Err(format!("{name} must be between {minimum} and {maximum}"));
    }
    Ok(value)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn policy_protects_recovery_turn_recent_and_long_term_milestones() {
        let policy = SnapshotRetentionPolicy {
            recent_revisions: 3,
            long_term_interval: 10,
        };
        assert!(should_retain(0, 25, "genesis", None, policy));
        assert!(should_retain(5, 25, "recovery", None, policy));
        assert!(should_retain(7, 25, "command", Some("end_turn"), policy));
        assert!(should_retain(10, 25, "command", Some("move_unit"), policy));
        assert!(should_retain(23, 25, "command", Some("move_unit"), policy));
        assert!(should_retain(25, 25, "command", Some("move_unit"), policy));
        assert!(!should_retain(11, 25, "command", Some("move_unit"), policy));
    }

    #[test]
    fn invalid_boolean_is_rejected() {
        assert!(parse_bool("TEST", "maybe").is_err());
        assert!(parse_bool("TEST", "true").unwrap());
        assert!(!parse_bool("TEST", "off").unwrap());
    }

    #[test]
    fn quota_pauses_when_the_next_object_cannot_fit() {
        assert!(archive_quota_would_pause(Some(100), 0, 1, 0));
        assert!(archive_quota_would_pause(Some(100), 100, 1, 1));
        assert!(!archive_quota_would_pause(Some(100), 50, 1, 1));
        assert!(!archive_quota_would_pause(None, 100, 1, 0));
    }

    #[test]
    fn per_game_storage_budget_flags_only_matches_above_the_ceiling() {
        // Disabled guard never pauses.
        assert!(!game_storage_budget_exceeded(u64::MAX, None));
        // A match exactly at the ceiling is still allowed; above it is not.
        assert!(!game_storage_budget_exceeded(1_000, Some(1_000)));
        assert!(game_storage_budget_exceeded(1_001, Some(1_000)));
        assert!(!game_storage_budget_exceeded(0, Some(1_000)));
    }
}
