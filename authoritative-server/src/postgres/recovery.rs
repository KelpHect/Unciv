use super::*;

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct RecoveredHead {
    pub game_id: Uuid,
    pub source_revision: u64,
    pub head_revision: u64,
    pub commands_replayed: u64,
    pub canonical_state_hash: String,
    pub snapshot: Vec<u8>,
}

struct RecoveryCommand {
    revision: u64,
    account_id: Uuid,
    actor_civilization_id: String,
    server_time_millis: i64,
    replay_operation: serde_json::Value,
    canonical_state_hash: String,
}

enum RecoveryStep {
    Command(RecoveryCommand),
    RecoveryMarker {
        revision: u64,
        canonical_state_hash: String,
    },
}

impl PostgresGameRepository {
    /// Reconstructs the current head from a strictly older valid immutable
    /// snapshot and a bounded, replay-complete journal tail. This method is
    /// read-only: publication is a separate atomic operator action.
    pub async fn reconstruct_head(
        &self,
        worker: &EngineWorkerClient,
        game_id: Uuid,
        max_tail_commands: u64,
    ) -> Result<RecoveredHead, CommitError> {
        if max_tail_commands == 0 {
            return Err(CommitError::RecoveryTailTooLong);
        }
        let row = sqlx::query(
            "SELECT g.head_revision, m.manifest
             FROM games g
             JOIN ruleset_manifests m ON m.hash=g.ruleset_manifest_hash
             WHERE g.id=$1",
        )
        .bind(game_id)
        .fetch_optional(&self.pool)
        .await
        .map_err(CommitError::storage)?
        .ok_or(CommitError::NotFound)?;
        let head_revision_i64: i64 = row.get("head_revision");
        let head_revision =
            u64::try_from(head_revision_i64).map_err(|_| CommitError::RecoveryEvidenceMissing)?;
        if head_revision == 0 {
            return Err(CommitError::RecoveryEvidenceMissing);
        }
        let manifest = serde_json::from_value::<WorkerManifest>(row.get("manifest"))
            .map_err(|_| CommitError::RecoveryEvidenceMissing)?;

        let candidates = sqlx::query(
            "SELECT s.revision
             FROM game_snapshots s
             JOIN game_revisions r
               ON r.game_id=s.game_id AND r.revision=s.revision
             WHERE s.game_id=$1 AND s.revision<$2
               AND s.codec <> 'zstd_delta'
             ORDER BY s.revision DESC",
        )
        .bind(game_id)
        .bind(head_revision_i64)
        .fetch_all(&self.pool)
        .await
        .map_err(CommitError::storage)?;

        let mut source = None;
        for candidate in candidates {
            let candidate_revision: i64 = candidate.get("revision");
            let Ok(snapshot) = self
                .canonical_snapshot_at_revision(game_id, candidate_revision)
                .await
            else {
                continue;
            };
            if std::str::from_utf8(&snapshot).is_err() {
                continue;
            }
            let hash = state_hash(&snapshot);
            let revision = u64::try_from(candidate_revision)
                .map_err(|_| CommitError::RecoveryEvidenceMissing)?;
            source = Some((revision, snapshot, hash));
            break;
        }
        let (source_revision, mut snapshot, mut canonical_state_hash) =
            source.ok_or(CommitError::RecoveryEvidenceMissing)?;
        let tail_length = head_revision
            .checked_sub(source_revision)
            .ok_or(CommitError::RecoveryEvidenceMissing)?;
        if tail_length > max_tail_commands {
            return Err(CommitError::RecoveryTailTooLong);
        }

        let rows = sqlx::query(
            "SELECT r.revision, r.revision_kind, r.canonical_state_hash,
                    c.account_id, c.actor_civilization_id,
                    c.server_time_millis, c.replay_operation,
                    c.replay_identity_available, c.replay_time_available,
                    c.replay_operation_available
             FROM game_revisions r
             LEFT JOIN game_commands c
               ON c.game_id=r.game_id AND c.revision=r.revision
                  AND c.command_id=r.command_id
             WHERE r.game_id=$1 AND r.revision>$2 AND r.revision<=$3
             ORDER BY r.revision",
        )
        .bind(game_id)
        .bind(i64::try_from(source_revision).expect("revision fits BIGINT"))
        .bind(head_revision_i64)
        .fetch_all(&self.pool)
        .await
        .map_err(CommitError::storage)?;
        if rows.len() != usize::try_from(tail_length).expect("bounded tail fits usize") {
            return Err(CommitError::RecoveryEvidenceMissing);
        }

        let mut steps = Vec::with_capacity(rows.len());
        for (index, row) in rows.into_iter().enumerate() {
            let revision = u64::try_from(row.get::<i64, _>("revision"))
                .map_err(|_| CommitError::RecoveryEvidenceMissing)?;
            if revision != source_revision + u64::try_from(index).expect("index fits u64") + 1 {
                return Err(CommitError::RecoveryEvidenceMissing);
            }
            let canonical_state_hash = row.get("canonical_state_hash");
            match row.get::<String, _>("revision_kind").as_str() {
                "command"
                    if row.get::<Option<bool>, _>("replay_identity_available") == Some(true)
                        && row.get::<Option<bool>, _>("replay_time_available") == Some(true)
                        && row.get::<Option<bool>, _>("replay_operation_available")
                            == Some(true) =>
                {
                    steps.push(RecoveryStep::Command(RecoveryCommand {
                        revision,
                        account_id: row
                            .try_get("account_id")
                            .map_err(|_| CommitError::RecoveryEvidenceMissing)?,
                        actor_civilization_id: row
                            .try_get("actor_civilization_id")
                            .map_err(|_| CommitError::RecoveryEvidenceMissing)?,
                        server_time_millis: row
                            .try_get("server_time_millis")
                            .map_err(|_| CommitError::RecoveryEvidenceMissing)?,
                        replay_operation: row
                            .try_get("replay_operation")
                            .map_err(|_| CommitError::RecoveryEvidenceMissing)?,
                        canonical_state_hash,
                    }));
                }
                "recovery" => steps.push(RecoveryStep::RecoveryMarker {
                    revision,
                    canonical_state_hash,
                }),
                _ => return Err(CommitError::RecoveryEvidenceMissing),
            }
        }

        let mut commands_replayed = 0_u64;
        for step in steps {
            match step {
                RecoveryStep::Command(command) => {
                    let snapshot_text = std::str::from_utf8(&snapshot)
                        .map_err(|_| CommitError::RecoveryEvidenceMissing)?;
                    let actor = command.account_id.to_string();
                    let proposal = worker
                        .replay_operation(
                            command.revision - 1,
                            &actor,
                            &manifest,
                            command.server_time_millis,
                            snapshot_text,
                            command.replay_operation,
                        )
                        .await
                        .map_err(|error| CommitError::WorkerRejected(error.to_string()))?;
                    if proposal.previous_revision != command.revision - 1
                        || proposal.canonical_state_hash != command.canonical_state_hash
                        || state_hash(&proposal.snapshot) != command.canonical_state_hash
                        || proposal.server_time_millis != command.server_time_millis
                        || command.actor_civilization_id.is_empty()
                    {
                        return Err(CommitError::RecoveryDiverged);
                    }
                    snapshot = proposal.snapshot;
                    canonical_state_hash = proposal.canonical_state_hash;
                    commands_replayed += 1;
                }
                RecoveryStep::RecoveryMarker {
                    revision,
                    canonical_state_hash: expected_hash,
                } => {
                    if revision == 0
                        || canonical_state_hash != expected_hash
                        || state_hash(&snapshot) != expected_hash
                    {
                        return Err(CommitError::RecoveryDiverged);
                    }
                }
            }
        }

        Ok(RecoveredHead {
            game_id,
            source_revision,
            head_revision,
            commands_replayed,
            canonical_state_hash,
            snapshot,
        })
    }

    /// Atomically publishes a previously reconstructed head as a new immutable
    /// recovery revision. Damaged rows are retained for audit; only the
    /// canonical head pointer and quarantine state advance.
    pub async fn publish_recovered_head(
        &self,
        recovered: &RecoveredHead,
    ) -> Result<u64, CommitError> {
        if recovered.source_revision >= recovered.head_revision
            || recovered.snapshot.is_empty()
            || state_hash(&recovered.snapshot) != recovered.canonical_state_hash
        {
            return Err(CommitError::RecoveryDiverged);
        }
        let stored = stored_snapshot(&recovered.snapshot)?;
        if stored.canonical_state_hash != recovered.canonical_state_hash {
            return Err(CommitError::RecoveryDiverged);
        }

        let mut tx = self.pool.begin().await.map_err(CommitError::storage)?;
        let game = sqlx::query(
            "SELECT g.head_revision, g.unavailable_at IS NOT NULL AS is_unavailable,
                    g.ruleset_manifest_hash, m.engine_build,
                    r.canonical_state_hash AS head_state_hash
             FROM games g
             JOIN ruleset_manifests m ON m.hash=g.ruleset_manifest_hash
             JOIN game_revisions r ON r.game_id=g.id AND r.revision=g.head_revision
             WHERE g.id=$1
             FOR UPDATE",
        )
        .bind(recovered.game_id)
        .fetch_optional(&mut *tx)
        .await
        .map_err(CommitError::storage)?
        .ok_or(CommitError::NotFound)?;
        let current_head = u64::try_from(game.get::<i64, _>("head_revision"))
            .map_err(|_| CommitError::RecoveryEvidenceMissing)?;
        if current_head != recovered.head_revision {
            return Err(CommitError::Stale {
                expected: recovered.head_revision,
                actual: current_head,
            });
        }
        if !game.get::<bool, _>("is_unavailable")
            || game.get::<String, _>("head_state_hash") != recovered.canonical_state_hash
        {
            return Err(CommitError::RecoveryDiverged);
        }

        let revision = current_head
            .checked_add(1)
            .ok_or(CommitError::RecoveryEvidenceMissing)?;
        let revision_i64 =
            i64::try_from(revision).map_err(|_| CommitError::RecoveryEvidenceMissing)?;
        let current_head_i64 =
            i64::try_from(current_head).map_err(|_| CommitError::RecoveryEvidenceMissing)?;
        let source_revision_i64 = i64::try_from(recovered.source_revision)
            .map_err(|_| CommitError::RecoveryEvidenceMissing)?;
        let commands_replayed_i64 = i64::try_from(recovered.commands_replayed)
            .map_err(|_| CommitError::RecoveryEvidenceMissing)?;
        let manifest_hash: String = game.get("ruleset_manifest_hash");
        let engine_build: String = game.get("engine_build");

        snapshot_storage::insert_snapshot(
            &mut tx,
            recovered.game_id,
            revision_i64,
            &engine_build,
            &manifest_hash,
            &stored,
        )
        .await?;
        sqlx::query(
            "INSERT INTO game_revisions
             (game_id, revision, parent_revision, command_id, snapshot_revision,
              canonical_state_hash, revision_kind)
             VALUES ($1,$2,$3,NULL,$2,$4,'recovery')",
        )
        .bind(recovered.game_id)
        .bind(revision_i64)
        .bind(current_head_i64)
        .bind(&recovered.canonical_state_hash)
        .execute(&mut *tx)
        .await
        .map_err(CommitError::storage)?;
        sqlx::query(
            "INSERT INTO game_recovery_events
             (game_id, revision, source_revision, recovered_head_revision,
              commands_replayed, canonical_state_hash)
             VALUES ($1,$2,$3,$4,$5,$6)",
        )
        .bind(recovered.game_id)
        .bind(revision_i64)
        .bind(source_revision_i64)
        .bind(current_head_i64)
        .bind(commands_replayed_i64)
        .bind(&recovered.canonical_state_hash)
        .execute(&mut *tx)
        .await
        .map_err(CommitError::storage)?;
        sqlx::query(
            "INSERT INTO game_outbox (game_id, revision, topic, payload)
             VALUES ($1,$2,'game.revision.recovered',$3)",
        )
        .bind(recovered.game_id)
        .bind(revision_i64)
        .bind(json!({
            "game_id": recovered.game_id,
            "revision": revision,
            "recovered_head_revision": current_head,
            "state_hash": recovered.canonical_state_hash,
        }))
        .execute(&mut *tx)
        .await
        .map_err(CommitError::storage)?;
        let updated = sqlx::query(
            "UPDATE games
             SET head_revision=$2, unavailable_at=NULL, unavailable_reason=NULL
             WHERE id=$1 AND head_revision=$3",
        )
        .bind(recovered.game_id)
        .bind(revision_i64)
        .bind(current_head_i64)
        .execute(&mut *tx)
        .await
        .map_err(CommitError::storage)?;
        if updated.rows_affected() != 1 {
            return Err(CommitError::Stale {
                expected: recovered.head_revision,
                actual: current_head,
            });
        }
        tx.commit().await.map_err(CommitError::storage)?;
        Ok(revision)
    }
}
