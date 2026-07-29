use super::*;

#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize)]
pub struct RepairReport {
    pub game_id: Uuid,
    pub findings: u64,
    pub outbox_events_backfilled: u64,
    pub quarantine_required: bool,
    pub quarantined: bool,
    pub dry_run: bool,
}

impl PostgresGameRepository {
    /// Repairs only deterministic derived outbox hints. Any other finding is
    /// contained by quarantine and must use verified recovery or backup restore.
    pub async fn repair_authoritative_game(
        &self,
        game_id: Uuid,
        dry_run: bool,
    ) -> Result<RepairReport, CommitError> {
        let mut tx = self.pool.begin().await.map_err(CommitError::storage)?;
        sqlx::query_scalar::<_, i64>("SELECT head_revision FROM games WHERE id=$1 FOR UPDATE")
            .bind(game_id)
            .fetch_optional(&mut *tx)
            .await
            .map_err(CommitError::storage)?
            .ok_or(CommitError::NotFound)?;
        let reconciliation = self.reconcile_authoritative_state().await?;
        if reconciliation.findings_truncated {
            return Err(CommitError::Storage);
        }
        let findings = reconciliation
            .findings
            .iter()
            .filter(|finding| finding.game_id == game_id)
            .collect::<Vec<_>>();
        let quarantine_required = findings.iter().any(|finding| {
            !matches!(
                finding.kind,
                ReconciliationKind::MissingCommitOutbox | ReconciliationKind::QuarantinedGame
            )
        });
        let missing_outbox = findings
            .iter()
            .filter(|finding| finding.kind == ReconciliationKind::MissingCommitOutbox)
            .count() as u64;
        if dry_run {
            tx.rollback().await.map_err(CommitError::storage)?;
            return Ok(RepairReport {
                game_id,
                findings: findings.len() as u64,
                outbox_events_backfilled: missing_outbox,
                quarantine_required,
                quarantined: false,
                dry_run: true,
            });
        }

        let backfilled = sqlx::query(
            "INSERT INTO game_outbox (game_id, revision, topic, payload)
             SELECT r.game_id, r.revision,
                    CASE r.revision_kind
                      WHEN 'command' THEN 'game.revision.committed'
                      WHEN 'recovery' THEN 'game.revision.recovered'
                      WHEN 'rewind' THEN 'game.revision.rewound'
                      ELSE 'game.lobby.reconfigured'
                    END,
                    jsonb_build_object(
                      'game_id', r.game_id,
                      'revision', r.revision,
                      'state_hash', r.canonical_state_hash
                    )
             FROM game_revisions r
             WHERE r.game_id=$1 AND r.revision>0
               AND NOT EXISTS (
                 SELECT 1 FROM game_outbox o
                 WHERE o.game_id=r.game_id AND o.revision=r.revision
                   AND o.topic=CASE r.revision_kind
                     WHEN 'command' THEN 'game.revision.committed'
                     WHEN 'recovery' THEN 'game.revision.recovered'
                     WHEN 'rewind' THEN 'game.revision.rewound'
                     ELSE 'game.lobby.reconfigured'
                   END
               )
               AND NOT EXISTS (
                 SELECT 1 FROM game_outbox_receipts receipt
                 WHERE receipt.game_id=r.game_id AND receipt.revision=r.revision
                   AND receipt.topic=CASE r.revision_kind
                     WHEN 'command' THEN 'game.revision.committed'
                     WHEN 'recovery' THEN 'game.revision.recovered'
                     WHEN 'rewind' THEN 'game.revision.rewound'
                     ELSE 'game.lobby.reconfigured'
                   END
               )
             ON CONFLICT DO NOTHING
             RETURNING revision",
        )
        .bind(game_id)
        .fetch_all(&mut *tx)
        .await
        .map_err(CommitError::storage)?;
        for row in &backfilled {
            sqlx::query(
                "INSERT INTO game_repair_events (game_id, action, revision)
                 VALUES ($1,'outbox_backfill',$2)
                 ON CONFLICT DO NOTHING",
            )
            .bind(game_id)
            .bind(row.get::<i64, _>("revision"))
            .execute(&mut *tx)
            .await
            .map_err(CommitError::storage)?;
        }
        let mut quarantined = false;
        if quarantine_required {
            let updated = sqlx::query(
                "UPDATE games
                 SET unavailable_at=COALESCE(unavailable_at, now()),
                     unavailable_reason=COALESCE(unavailable_reason, 'reconciliation_required')
                 WHERE id=$1",
            )
            .bind(game_id)
            .execute(&mut *tx)
            .await
            .map_err(CommitError::storage)?;
            quarantined = updated.rows_affected() == 1;
            sqlx::query(
                "INSERT INTO game_repair_events (game_id, action, revision)
                 VALUES ($1,'quarantine',NULL)
                 ON CONFLICT DO NOTHING",
            )
            .bind(game_id)
            .execute(&mut *tx)
            .await
            .map_err(CommitError::storage)?;
        }
        tx.commit().await.map_err(CommitError::storage)?;
        Ok(RepairReport {
            game_id,
            findings: findings.len() as u64,
            outbox_events_backfilled: backfilled.len() as u64,
            quarantine_required,
            quarantined,
            dry_run: false,
        })
    }
}
