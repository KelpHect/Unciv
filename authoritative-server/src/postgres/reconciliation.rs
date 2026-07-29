use futures_util::TryStreamExt;

use super::*;

const MAX_REPORTED_FINDINGS: usize = 1_000;

#[derive(Clone, Debug, PartialEq, Eq, Hash, serde::Serialize)]
#[serde(rename_all = "snake_case")]
pub enum ReconciliationKind {
    InvalidHead,
    MissingSnapshot,
    MissingSnapshotPayload,
    OrphanSnapshot,
    BrokenRevisionChain,
    MissingRevisionCommand,
    MissingCommandActor,
    MissingCommandTime,
    MissingCommandReplayOperation,
    MissingCreationReplayContext,
    OrphanCommand,
    MissingCommitOutbox,
    OrphanCommitOutbox,
    DuplicateCivilizationMembership,
    InvalidOwnerCount,
    QuarantinedGame,
    InvalidSnapshotPayload,
}

impl ReconciliationKind {
    fn from_database(value: &str) -> Self {
        match value {
            "invalid_head" => Self::InvalidHead,
            "missing_snapshot" => Self::MissingSnapshot,
            "missing_snapshot_payload" => Self::MissingSnapshotPayload,
            "orphan_snapshot" => Self::OrphanSnapshot,
            "broken_revision_chain" => Self::BrokenRevisionChain,
            "missing_revision_command" => Self::MissingRevisionCommand,
            "missing_command_actor" => Self::MissingCommandActor,
            "missing_command_time" => Self::MissingCommandTime,
            "missing_command_replay_operation" => Self::MissingCommandReplayOperation,
            "missing_creation_replay_context" => Self::MissingCreationReplayContext,
            "orphan_command" => Self::OrphanCommand,
            "missing_commit_outbox" => Self::MissingCommitOutbox,
            "orphan_commit_outbox" => Self::OrphanCommitOutbox,
            "duplicate_civilization_membership" => Self::DuplicateCivilizationMembership,
            "invalid_owner_count" => Self::InvalidOwnerCount,
            "quarantined_game" => Self::QuarantinedGame,
            _ => unreachable!("reconciliation SQL emits a closed finding set"),
        }
    }
}

#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize)]
pub struct ReconciliationFinding {
    pub kind: ReconciliationKind,
    pub game_id: Uuid,
    pub revision: Option<u64>,
    pub detail: String,
}

#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize)]
pub struct ReconciliationReport {
    pub games_scanned: u64,
    pub revisions_scanned: u64,
    pub snapshots_scanned: u64,
    pub total_findings: u64,
    pub findings_truncated: bool,
    pub findings: Vec<ReconciliationFinding>,
}

impl ReconciliationReport {
    fn record(&mut self, finding: ReconciliationFinding) {
        self.total_findings += 1;
        if self.findings.len() < MAX_REPORTED_FINDINGS {
            self.findings.push(finding);
        } else {
            self.findings_truncated = true;
        }
    }
}

impl PostgresGameRepository {
    /// Performs a read-only, bounded-memory audit. It never migrates, repairs,
    /// quarantines, or exposes canonical payload bytes in its report.
    pub async fn reconcile_authoritative_state(&self) -> Result<ReconciliationReport, CommitError> {
        let (games, revisions, snapshots): (i64, i64, i64) = sqlx::query_as(
            "SELECT (SELECT count(*) FROM games), (SELECT count(*) FROM game_revisions), (SELECT count(*) FROM game_snapshots)",
        )
        .fetch_one(&self.pool)
        .await
        .map_err(CommitError::storage)?;
        let mut report = ReconciliationReport {
            games_scanned: u64::try_from(games).expect("count is non-negative"),
            revisions_scanned: u64::try_from(revisions).expect("count is non-negative"),
            snapshots_scanned: u64::try_from(snapshots).expect("count is non-negative"),
            total_findings: 0,
            findings_truncated: false,
            findings: Vec::new(),
        };

        let structural_sql = r#"
            SELECT 'invalid_head' AS kind, g.id AS game_id, g.head_revision AS revision,
                   'head revision or its canonical snapshot is missing or inconsistent' AS detail
            FROM games g
            LEFT JOIN game_revisions r ON r.game_id=g.id AND r.revision=g.head_revision
            LEFT JOIN game_snapshots s ON s.game_id=g.id AND s.revision=r.snapshot_revision
            LEFT JOIN game_snapshot_blobs b ON b.game_id=s.game_id AND b.revision=s.revision
            WHERE r.game_id IS NULL OR s.game_id IS NULL OR b.game_id IS NULL
               OR r.snapshot_revision<>g.head_revision
            UNION ALL
            SELECT 'missing_snapshot', r.game_id, r.revision, 'revision references no snapshot'
            FROM game_revisions r LEFT JOIN game_snapshots s
              ON s.game_id=r.game_id AND s.revision=r.snapshot_revision
            WHERE s.game_id IS NULL
            UNION ALL
            SELECT 'missing_snapshot_payload', s.game_id, s.revision,
                   'retained snapshot metadata has no payload blob'
            FROM game_snapshots s LEFT JOIN game_snapshot_blobs b
              ON b.game_id=s.game_id AND b.revision=s.revision
            WHERE s.payload_retention_status='retained' AND b.game_id IS NULL
            UNION ALL
            SELECT 'orphan_snapshot', s.game_id, s.revision, 'snapshot has no matching revision'
            FROM game_snapshots s LEFT JOIN game_revisions r
              ON r.game_id=s.game_id AND r.snapshot_revision=s.revision
            WHERE r.game_id IS NULL
            UNION ALL
            SELECT 'broken_revision_chain', r.game_id, r.revision, 'revision parent or command identity is inconsistent'
            FROM game_revisions r
            WHERE (r.revision=0 AND (r.revision_kind<>'genesis' OR r.parent_revision IS NOT NULL OR r.command_id IS NOT NULL))
               OR (r.revision>0 AND r.parent_revision IS DISTINCT FROM r.revision-1)
               OR (r.revision>0 AND r.revision_kind='command' AND r.command_id IS NULL)
               OR (r.revision>0 AND r.revision_kind IN ('recovery','rewind') AND r.command_id IS NOT NULL)
               OR r.revision_kind NOT IN ('genesis','command','recovery','rewind')
               OR r.snapshot_revision<>r.revision
               OR r.revision>(SELECT g.head_revision FROM games g WHERE g.id=r.game_id)
            UNION ALL
            SELECT 'missing_revision_command', r.game_id, r.revision, 'revision has no matching accepted command'
            FROM game_revisions r LEFT JOIN game_commands c
              ON c.game_id=r.game_id AND c.revision=r.revision AND c.command_id=r.command_id
            WHERE r.revision>0 AND r.revision_kind='command' AND c.game_id IS NULL
            UNION ALL
            SELECT 'orphan_command', c.game_id, c.revision, 'accepted command has no matching revision'
            FROM game_commands c LEFT JOIN game_revisions r
              ON r.game_id=c.game_id AND r.revision=c.revision AND r.command_id=c.command_id
            WHERE r.game_id IS NULL
            UNION ALL
            SELECT 'missing_command_actor', c.game_id, c.revision, 'accepted command has no immutable actor civilization for replay'
            FROM game_commands c
            WHERE NOT c.replay_identity_available OR c.actor_civilization_id IS NULL
            UNION ALL
            SELECT 'missing_command_time', c.game_id, c.revision, 'accepted command has no server execution timestamp for replay'
            FROM game_commands c
            WHERE NOT c.replay_time_available OR c.server_time_millis IS NULL
            UNION ALL
            SELECT 'missing_command_replay_operation', c.game_id, c.revision, 'accepted command has no exact private worker operation for replay'
            FROM game_commands c
            WHERE NOT c.replay_operation_available OR c.replay_operation IS NULL
            UNION ALL
            SELECT 'missing_creation_replay_context', operation.game_id, 0, 'game creation has no retained server seed or execution timestamp for replay'
            FROM game_creation_operations operation
            WHERE NOT operation.replay_context_available
               OR operation.server_seed IS NULL
               OR operation.server_time_millis IS NULL
            UNION ALL
            SELECT 'missing_commit_outbox', r.game_id, r.revision, 'revision does not have exactly one commit outbox event'
            FROM game_revisions r
            WHERE r.revision>0 AND (
                (r.revision_kind='command' AND (
                  (SELECT count(*) FROM game_outbox o
                   WHERE o.game_id=r.game_id AND o.revision=r.revision AND o.topic='game.revision.committed')
                  +(SELECT count(*) FROM game_outbox_receipts receipt
                    WHERE receipt.game_id=r.game_id AND receipt.revision=r.revision AND receipt.topic='game.revision.committed')
                )<>1)
                OR
                (r.revision_kind='recovery' AND (
                  (SELECT count(*) FROM game_outbox o
                   WHERE o.game_id=r.game_id AND o.revision=r.revision AND o.topic='game.revision.recovered')
                  +(SELECT count(*) FROM game_outbox_receipts receipt
                    WHERE receipt.game_id=r.game_id AND receipt.revision=r.revision AND receipt.topic='game.revision.recovered')
                )<>1)
                OR
                (r.revision_kind='rewind' AND (
                  (SELECT count(*) FROM game_outbox o
                   WHERE o.game_id=r.game_id AND o.revision=r.revision AND o.topic='game.revision.rewound')
                  +(SELECT count(*) FROM game_outbox_receipts receipt
                    WHERE receipt.game_id=r.game_id AND receipt.revision=r.revision AND receipt.topic='game.revision.rewound')
                )<>1)
            )
            UNION ALL
            SELECT 'orphan_commit_outbox', o.game_id, o.revision, 'commit outbox event has no matching revision'
            FROM game_outbox o LEFT JOIN game_revisions r
              ON r.game_id=o.game_id AND r.revision=o.revision
            WHERE o.topic IN ('game.revision.committed','game.revision.recovered','game.revision.rewound') AND r.game_id IS NULL
            UNION ALL
            SELECT 'orphan_commit_outbox', receipt.game_id, receipt.revision, 'compacted commit outbox receipt has no matching revision'
            FROM game_outbox_receipts receipt LEFT JOIN game_revisions r
              ON r.game_id=receipt.game_id AND r.revision=receipt.revision
            WHERE receipt.topic IN ('game.revision.committed','game.revision.recovered','game.revision.rewound') AND r.game_id IS NULL
            UNION ALL
            SELECT 'duplicate_civilization_membership', gm.game_id, NULL::bigint, 'civilization is assigned to multiple player memberships'
            FROM game_members gm
            WHERE gm.role IN ('owner','player') AND gm.civilization_id IS NOT NULL
            GROUP BY gm.game_id, gm.civilization_id HAVING count(*)>1
            UNION ALL
            SELECT 'invalid_owner_count', g.id, NULL::bigint, 'game must have exactly one owner membership'
            FROM games g LEFT JOIN game_members gm ON gm.game_id=g.id
            GROUP BY g.id HAVING count(*) FILTER (WHERE gm.role='owner')<>1
            UNION ALL
            SELECT 'quarantined_game', g.id, g.head_revision, 'game is quarantined and requires operator review'
            FROM games g WHERE g.unavailable_at IS NOT NULL
            ORDER BY kind, game_id, revision NULLS FIRST
        "#;
        let mut structural = sqlx::query(structural_sql).fetch(&self.pool);
        while let Some(row) = structural.try_next().await.map_err(CommitError::storage)? {
            let revision: Option<i64> = row.get("revision");
            report.record(ReconciliationFinding {
                kind: ReconciliationKind::from_database(row.get("kind")),
                game_id: row.get("game_id"),
                revision: revision
                    .map(|value| u64::try_from(value).expect("stored revisions are non-negative")),
                detail: row.get("detail"),
            });
        }

        let mut stored = sqlx::query(
            "SELECT s.game_id, s.revision, b.payload, s.codec, s.compressed_size, s.uncompressed_size, s.protocol_version, s.validation_status, s.payload_hash, s.canonical_state_hash, r.canonical_state_hash AS revision_state_hash FROM game_snapshots s JOIN game_snapshot_blobs b ON b.game_id=s.game_id AND b.revision=s.revision LEFT JOIN game_revisions r ON r.game_id=s.game_id AND r.snapshot_revision=s.revision WHERE s.payload_retention_status='retained' ORDER BY s.game_id, s.revision",
        )
        .fetch(&self.pool);
        while let Some(row) = stored.try_next().await.map_err(CommitError::storage)? {
            let payload: Vec<u8> = row.get("payload");
            let decoded = decode_snapshot(
                row.get("codec"),
                &payload,
                row.get("compressed_size"),
                row.get("uncompressed_size"),
            );
            let canonical_hash = decoded.as_ref().ok().map(|bytes| state_hash(bytes));
            let revision_hash: Option<String> = row.get("revision_state_hash");
            let valid = row.get::<i32, _>("protocol_version") == i32::from(PROTOCOL_VERSION)
                && row.get::<String, _>("validation_status") == "valid"
                && row.get::<String, _>("payload_hash") == state_hash(&payload)
                && canonical_hash.as_ref() == Some(&row.get::<String, _>("canonical_state_hash"))
                && revision_hash
                    .as_ref()
                    .is_none_or(|hash| canonical_hash.as_ref() == Some(hash))
                && decoded
                    .as_ref()
                    .is_ok_and(|bytes| std::str::from_utf8(bytes).is_ok());
            if !valid {
                let revision = row.get::<i64, _>("revision");
                report.record(ReconciliationFinding {
                    kind: ReconciliationKind::InvalidSnapshotPayload,
                    game_id: row.get("game_id"),
                    revision: Some(
                        u64::try_from(revision).expect("stored revisions are non-negative"),
                    ),
                    detail: "snapshot codec, size, protocol, status, or hash validation failed"
                        .to_owned(),
                });
            }
        }
        Ok(report)
    }
}
