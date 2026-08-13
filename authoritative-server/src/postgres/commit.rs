use super::*;

impl PostgresGameRepository {
    /// Creates revision zero atomically. The caller must have already stored a
    /// content-addressed ruleset manifest and account; the public API will do
    /// that through authenticated setup rather than accepting a save upload.
    pub async fn create_game(&self, game: NewGame) -> Result<(), CommitError> {
        let mut tx = self.pool.begin().await.map_err(CommitError::storage)?;
        self.create_game_in_transaction(&mut tx, game).await?;
        tx.commit().await.map_err(CommitError::storage)
    }

    pub(super) async fn create_game_in_transaction(
        &self,
        tx: &mut sqlx::Transaction<'_, sqlx::Postgres>,
        game: NewGame,
    ) -> Result<(), CommitError> {
        let stored = stored_snapshot(&game.snapshot)?;
        let engine_build: String =
            sqlx::query_scalar("SELECT engine_build FROM ruleset_manifests WHERE hash = $1")
                .bind(&game.ruleset_manifest_hash)
                .fetch_one(&mut **tx)
                .await
                .map_err(CommitError::storage)?;
        sqlx::query("INSERT INTO games (id, ruleset_manifest_hash) VALUES ($1, $2)")
            .bind(game.game_id)
            .bind(&game.ruleset_manifest_hash)
            .execute(&mut **tx)
            .await
            .map_err(CommitError::storage)?;
        if game.owner_civilization_id.is_empty() {
            sqlx::query(
                "INSERT INTO game_members (game_id, account_id, role, civilization_id) VALUES ($1, $2, 'spectator', NULL)",
            )
            .bind(game.game_id)
            .bind(game.owner_account_id)
            .execute(&mut **tx)
            .await
            .map_err(CommitError::storage)?;
        } else {
            sqlx::query(
                "INSERT INTO game_members (game_id, account_id, role, civilization_id) VALUES ($1, $2, 'owner', $3)",
            )
            .bind(game.game_id)
            .bind(game.owner_account_id)
            .bind(game.owner_civilization_id)
            .execute(&mut **tx)
            .await
            .map_err(CommitError::storage)?;
        }
        snapshot_storage::insert_snapshot(
            tx,
            game.game_id,
            0,
            &engine_build,
            &game.ruleset_manifest_hash,
            &stored,
        )
        .await?;
        sqlx::query(
            "INSERT INTO game_revisions (game_id, revision, parent_revision, command_id, snapshot_revision, canonical_state_hash, revision_kind) VALUES ($1, 0, NULL, NULL, 0, $2, 'genesis')",
        )
        .bind(game.game_id)
        .bind(&stored.canonical_state_hash)
        .execute(&mut **tx)
        .await
        .map_err(CommitError::storage)?;
        Ok(())
    }

    /// Commits only a server-worker result. `FOR UPDATE` makes the database
    /// authoritative across processes; all journal, snapshot, head, and outbox
    /// writes share one transaction.
    pub async fn commit(
        &self,
        actor_account_id: Uuid,
        envelope: CommandEnvelope,
        proposal: CommitProposal,
    ) -> Result<CommandAccepted, CommitError> {
        self.commit_internal(actor_account_id, envelope, proposal, None, None)
            .await
    }

    pub(super) async fn commit_resignation(
        &self,
        actor_account_id: Uuid,
        envelope: CommandEnvelope,
        proposal: CommitProposal,
    ) -> Result<CommandAccepted, CommitError> {
        self.commit_internal(
            actor_account_id,
            envelope,
            proposal,
            None,
            Some(MembershipRemoval::Actor),
        )
        .await
    }

    pub(super) async fn commit_civilization_removal(
        &self,
        actor_account_id: Uuid,
        envelope: CommandEnvelope,
        proposal: CommitProposal,
        civilization_id: String,
    ) -> Result<CommandAccepted, CommitError> {
        self.commit_internal(
            actor_account_id,
            envelope,
            proposal,
            None,
            Some(MembershipRemoval::Civilization(civilization_id)),
        )
        .await
    }

    pub(super) async fn commit_internal(
        &self,
        actor_account_id: Uuid,
        envelope: CommandEnvelope,
        proposal: CommitProposal,
        new_member: Option<NewMemberAssignment>,
        membership_removal: Option<MembershipRemoval>,
    ) -> Result<CommandAccepted, CommitError> {
        if envelope.protocol_version != PROTOCOL_VERSION {
            return Err(CommitError::UnsupportedProtocol(envelope.protocol_version));
        }
        if proposal.snapshot.is_empty() || proposal.snapshot.len() > MAX_SNAPSHOT_BYTES {
            return Err(CommitError::SnapshotTooLarge);
        }
        if state_hash(&proposal.snapshot) != proposal.canonical_state_hash {
            return Err(CommitError::InvalidSnapshotHash);
        }
        let stored = stored_snapshot(&proposal.snapshot)?;
        let mut tx = self.pool.begin().await.map_err(CommitError::storage)?;

        let duplicate = sqlx::query(
            "SELECT c.revision, c.account_id, c.payload, r.canonical_state_hash FROM game_commands c JOIN game_revisions r ON r.game_id = c.game_id AND r.revision = c.revision WHERE c.game_id = $1 AND c.command_id = $2",
        )
        .bind(envelope.game_id)
        .bind(envelope.command_id)
        .fetch_optional(&mut *tx)
        .await
        .map_err(CommitError::storage)?;
        if let Some(row) = duplicate {
            if row.get::<Uuid, _>("account_id") != actor_account_id {
                return Err(CommitError::Unauthorized);
            }
            let original = serde_json::from_value::<CommandEnvelope>(row.get("payload"))
                .map_err(|_| CommitError::IdempotencyConflict)?;
            if !crate::repository::same_idempotency_identity(&original, &envelope) {
                return Err(CommitError::IdempotencyConflict);
            }
            let committed_revision: i64 = row.get("revision");
            let canonical_state_hash: String = row.get("canonical_state_hash");
            return Ok(CommandAccepted {
                game_id: envelope.game_id,
                command_id: envelope.command_id,
                previous_revision: u64::try_from(committed_revision - 1)
                    .expect("command revisions are positive"),
                committed_revision: u64::try_from(committed_revision)
                    .expect("revision is non-negative"),
                canonical_state_hash,
            });
        }

        let head = sqlx::query(
            "SELECT g.unavailable_at IS NOT NULL AS is_unavailable, g.lifecycle_status, g.head_revision, g.ruleset_manifest_hash, m.engine_build FROM games g JOIN ruleset_manifests m ON m.hash = g.ruleset_manifest_hash WHERE g.id = $1 FOR UPDATE",
        )
        .bind(envelope.game_id)
        .fetch_optional(&mut *tx)
        .await
        .map_err(CommitError::storage)?
        .ok_or(CommitError::NotFound)?;
        if head.get::<bool, _>("is_unavailable") {
            return Err(CommitError::GameUnavailable);
        }
        if head.get::<String, _>("lifecycle_status") != "active" {
            return Err(CommitError::InvalidCommand);
        }
        let current_revision = u64::try_from(head.get::<i64, _>("head_revision"))
            .expect("head revision is non-negative");
        if envelope.expected_revision != current_revision {
            return Err(CommitError::Stale {
                expected: envelope.expected_revision,
                actual: current_revision,
            });
        }
        if proposal.previous_revision != current_revision {
            return Err(CommitError::WorkerRevisionMismatch);
        }

        let mut consumed_invitation_id: Option<Uuid> = None;
        if let Some(assignment) = new_member {
            if !matches!(&envelope.command, crate::GameCommand::JoinGame { .. }) {
                return Err(CommitError::InvalidCommand);
            }
            if assignment.lobby_join {
                let lobby = sqlx::query(
                    "SELECT human_slots FROM game_lobbies
                     WHERE game_id=$1 AND started_at IS NULL AND closed_at IS NULL
                     FOR UPDATE",
                )
                .bind(envelope.game_id)
                .fetch_optional(&mut *tx)
                .await
                .map_err(CommitError::storage)?
                .ok_or(CommitError::InvalidCommand)?;
                let occupied: i64 = sqlx::query_scalar(
                    "SELECT count(*) FROM game_members
                     WHERE game_id=$1 AND role IN ('owner', 'player')",
                )
                .bind(envelope.game_id)
                .fetch_one(&mut *tx)
                .await
                .map_err(CommitError::storage)?;
                if occupied >= i64::from(lobby.get::<i16, _>("human_slots")) {
                    return Err(CommitError::InvalidCommand);
                }
                let civilization_taken: bool = sqlx::query_scalar(
                    "SELECT EXISTS(SELECT 1 FROM game_members
                     WHERE game_id=$1 AND civilization_id=$2
                       AND role IN ('owner', 'player'))",
                )
                .bind(envelope.game_id)
                .bind(&assignment.civilization_id)
                .fetch_one(&mut *tx)
                .await
                .map_err(CommitError::storage)?;
                if civilization_taken {
                    return Err(CommitError::InvalidCommand);
                }
            } else {
                consumed_invitation_id = sqlx::query_scalar(
                "SELECT invitation_id FROM game_player_invitations WHERE game_id=$1 AND invited_account_id=$2 AND consumed_at IS NULL FOR UPDATE",
            )
            .bind(envelope.game_id)
            .bind(actor_account_id)
            .fetch_optional(&mut *tx)
            .await
            .map_err(CommitError::storage)?;
                if consumed_invitation_id.is_none() {
                    return Err(CommitError::Unauthorized);
                }
            }
            let membership_exists: bool = sqlx::query_scalar(
                "SELECT EXISTS(SELECT 1 FROM game_members WHERE game_id = $1 AND account_id = $2)",
            )
            .bind(envelope.game_id)
            .bind(actor_account_id)
            .fetch_one(&mut *tx)
            .await
            .map_err(CommitError::storage)?;
            if membership_exists {
                return Err(CommitError::InvalidCommand);
            }
            sqlx::query(
                "INSERT INTO game_members (game_id, account_id, role, civilization_id) VALUES ($1, $2, 'player', $3)",
            )
            .bind(envelope.game_id)
            .bind(actor_account_id)
            .bind(&assignment.civilization_id)
            .execute(&mut *tx)
            .await
            .map_err(CommitError::storage)?;
            if assignment.lobby_join {
                sqlx::query(
                    "INSERT INTO game_lobby_readiness (game_id, account_id, ready)
                     VALUES ($1, $2, FALSE)",
                )
                .bind(envelope.game_id)
                .bind(actor_account_id)
                .execute(&mut *tx)
                .await
                .map_err(CommitError::storage)?;
                sqlx::query(
                    "UPDATE game_lobbies
                     SET lobby_revision=lobby_revision+1 WHERE game_id=$1",
                )
                .bind(envelope.game_id)
                .execute(&mut *tx)
                .await
                .map_err(CommitError::storage)?;
            }
        } else {
            let role: Option<String> = sqlx::query_scalar(
                "SELECT role FROM game_members WHERE game_id = $1 AND account_id = $2",
            )
            .bind(envelope.game_id)
            .bind(actor_account_id)
            .fetch_optional(&mut *tx)
            .await
            .map_err(CommitError::storage)?;
            // A zero-human match's owner is a spectator with no civilization.
            // The advance-ai-turn authorization gate already proved the lobby
            // is started, has no human slots, and the actor is its spectator,
            // so the generic owner/player/admin commit role check accepts this
            // one spectator-driven command.
            let advance_ai = matches!(&envelope.command, crate::GameCommand::AdvanceAiTurn {});
            if !matches!(role.as_deref(), Some("owner" | "player" | "admin"))
                && !(advance_ai && role.as_deref() == Some("spectator"))
            {
                return Err(CommitError::Unauthorized);
            }
        }

        let next_revision = current_revision
            .checked_add(1)
            .expect("revision overflow is impossible in practice");
        let next_revision_i64 = i64::try_from(next_revision).expect("revision fits BIGINT");
        if let Some(invitation_id) = consumed_invitation_id {
            let consumed = sqlx::query(
                "UPDATE game_player_invitations SET consumed_at=now(), consumed_revision=$3 WHERE game_id=$1 AND invitation_id=$2 AND consumed_at IS NULL",
            )
            .bind(envelope.game_id)
            .bind(invitation_id)
            .bind(next_revision_i64)
            .execute(&mut *tx)
            .await
            .map_err(CommitError::storage)?;
            if consumed.rows_affected() != 1 {
                return Err(CommitError::Unauthorized);
            }
        }
        let actor_civilization_id: Option<String> = sqlx::query_scalar(
            "SELECT civilization_id FROM game_members WHERE game_id=$1 AND account_id=$2",
        )
        .bind(envelope.game_id)
        .bind(actor_account_id)
        .fetch_optional(&mut *tx)
        .await
        .map_err(CommitError::storage)?
        .flatten();
        let actor_civilization_id = match actor_civilization_id {
            Some(value) if !value.is_empty() => value,
            _ if matches!(&envelope.command, crate::GameCommand::AdvanceAiTurn {}) => {
                ADVANCE_AI_ACTOR_CIVILIZATION.to_owned()
            }
            _ => return Err(CommitError::Unauthorized),
        };
        let manifest_hash: String = head.get("ruleset_manifest_hash");
        let engine_build: String = head.get("engine_build");
        let command_json =
            serde_json::to_value(&envelope).expect("command envelope is serializable");

        snapshot_storage::insert_snapshot(
            &mut tx,
            envelope.game_id,
            next_revision_i64,
            &engine_build,
            &manifest_hash,
            &stored,
        )
        .await?;
        sqlx::query(
            "INSERT INTO game_commands (game_id, command_id, revision, account_id, actor_civilization_id, server_time_millis, replay_operation, payload) VALUES ($1, $2, $3, $4, $5, $6, $7, $8)",
        )
        .bind(envelope.game_id)
        .bind(envelope.command_id)
        .bind(next_revision_i64)
        .bind(actor_account_id)
        .bind(actor_civilization_id)
        .bind(proposal.server_time_millis)
        .bind(&proposal.replay_operation)
        .bind(command_json)
        .execute(&mut *tx)
        .await
        .map_err(CommitError::storage)?;
        sqlx::query(
            "INSERT INTO game_revisions (game_id, revision, parent_revision, command_id, snapshot_revision, canonical_state_hash) VALUES ($1, $2, $3, $4, $2, $5)",
        )
        .bind(envelope.game_id)
        .bind(next_revision_i64)
        .bind(i64::try_from(current_revision).expect("revision fits BIGINT"))
        .bind(envelope.command_id)
        .bind(&proposal.canonical_state_hash)
        .execute(&mut *tx)
        .await
        .map_err(CommitError::storage)?;
        sqlx::query("UPDATE games SET head_revision = $2 WHERE id = $1 AND head_revision = $3")
            .bind(envelope.game_id)
            .bind(next_revision_i64)
            .bind(i64::try_from(current_revision).expect("revision fits BIGINT"))
            .execute(&mut *tx)
            .await
            .map_err(CommitError::storage)?;
        sqlx::query("INSERT INTO game_outbox (game_id, revision, topic, payload) VALUES ($1, $2, 'game.revision.committed', $3)")
            .bind(envelope.game_id)
            .bind(next_revision_i64)
            .bind(serde_json::json!({"game_id": envelope.game_id, "revision": next_revision, "state_hash": proposal.canonical_state_hash}))
            .execute(&mut *tx)
            .await
            .map_err(CommitError::storage)?;
        if let Some(removal) = membership_removal {
            // Readiness is only meaningful before play. Once a lobby has
            // started, remove its dependent row before removing the durable
            // game membership so the lobby FK cannot turn a valid resignation
            // or owner kick into a storage failure.
            sqlx::query(
                "DELETE FROM game_lobby_readiness
                 WHERE game_id=$1
                   AND account_id IN (
                       SELECT account_id FROM game_members
                       WHERE game_id=$1
                         AND (
                             ($2::uuid IS NOT NULL AND account_id=$2)
                             OR ($3::text IS NOT NULL AND civilization_id=$3)
                         )
                   )
                   AND EXISTS (
                       SELECT 1 FROM game_lobbies
                       WHERE game_id=$1 AND started_at IS NOT NULL
                   )",
            )
            .bind(envelope.game_id)
            .bind(match &removal {
                MembershipRemoval::Actor => Some(actor_account_id),
                MembershipRemoval::Civilization(_) => None,
            })
            .bind(match &removal {
                MembershipRemoval::Actor => None,
                MembershipRemoval::Civilization(civilization_id) => Some(civilization_id.as_str()),
            })
            .execute(&mut *tx)
            .await
            .map_err(CommitError::storage)?;
            let result = match removal {
                MembershipRemoval::Actor
                    if matches!(&envelope.command, crate::GameCommand::Resign {}) =>
                {
                    sqlx::query("DELETE FROM game_members WHERE game_id = $1 AND account_id = $2")
                        .bind(envelope.game_id)
                        .bind(actor_account_id)
                        .execute(&mut *tx)
                        .await
                        .map_err(CommitError::storage)?
                }
                MembershipRemoval::Civilization(civilization_id)
                    if matches!(
                        &envelope.command,
                        crate::GameCommand::ForceResign {} | crate::GameCommand::KickMember {}
                    ) =>
                {
                    sqlx::query(
                        "DELETE FROM game_members WHERE game_id = $1 AND civilization_id = $2",
                    )
                    .bind(envelope.game_id)
                    .bind(civilization_id)
                    .execute(&mut *tx)
                    .await
                    .map_err(CommitError::storage)?
                }
                _ => return Err(CommitError::InvalidCommand),
            };
            if result.rows_affected() != 1 {
                return Err(CommitError::WorkerRevisionMismatch);
            }
        }
        tx.commit().await.map_err(CommitError::storage)?;
        metrics::gauge!("unciv_v3_revision").set(next_revision as f64);
        metrics::counter!("unciv_v3_commands_committed_total").increment(1);

        Ok(CommandAccepted {
            game_id: envelope.game_id,
            command_id: envelope.command_id,
            previous_revision: current_revision,
            committed_revision: next_revision,
            canonical_state_hash: proposal.canonical_state_hash,
        })
    }
}
