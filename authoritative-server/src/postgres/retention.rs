use super::*;

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
    /// Removes only payload bytes selected by the retention policy. Revision,
    /// command, audit, snapshot hash, and snapshot identity metadata remain
    /// immutable. The game row lock serializes compaction with commits.
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
        let head_i64: i64 =
            sqlx::query_scalar("SELECT head_revision FROM games WHERE id=$1 FOR UPDATE")
                .bind(game_id)
                .fetch_optional(&mut *tx)
                .await
                .map_err(CommitError::storage)?
                .ok_or(CommitError::NotFound)?;
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
            if should_retain(
                revision,
                head_revision,
                &revision_kind,
                command_type.as_deref(),
                policy,
            ) {
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

fn should_retain(
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
        || revision_kind == "recovery"
        || command_type == Some("end_turn")
        || revision.is_multiple_of(policy.long_term_interval)
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
}
