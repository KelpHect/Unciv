use super::*;
use crate::worker::{WorkerGameSetup, WorkerLobbyParticipant, WorkerLobbyReconfiguration};

#[derive(Clone)]
pub enum LobbyPasswordUpdate {
    Keep,
    Clear,
    Replace { hash: String, identity: String },
}

#[derive(Clone)]
pub struct LobbyConfigurationUpdate {
    pub display_name: String,
    pub human_slots: u8,
    pub password: LobbyPasswordUpdate,
    pub setup: WorkerGameSetup,
}

enum ReconfigurationIntent {
    OwnerConfiguration(Box<LobbyConfigurationUpdate>),
    MemberFaction(String),
}

struct CurrentLobby {
    owner_account_id: Uuid,
    lobby_revision: u64,
    head_revision: u64,
    display_name: String,
    human_slots: u8,
    password_hash: Option<String>,
    password_identity: Option<String>,
    setup: WorkerGameSetup,
    manifest_hash: String,
    manifest: WorkerManifest,
    engine_build: String,
    creation_seed: i64,
}

/// Re-checks the pinned AI roster after a member edit is folded in, so a member
/// faction change can never leave the room claiming a civilization the setup
/// reserved for the AI.
fn ai_roster_is_consistent(current: &CurrentLobby) -> bool {
    match current.setup.ai_civilizations.as_deref() {
        None => true,
        Some(roster) => {
            roster.len() + usize::from(current.human_slots)
                == usize::from(current.setup.major_civilizations)
                && !roster
                    .iter()
                    .any(|slot| slot.civilization_id == current.setup.owner_civilization_id)
        }
    }
}

impl PostgresGameRepository {
    pub async fn reconfigure_lobby(
        &self,
        worker: &EngineWorkerClient,
        actor_account_id: Uuid,
        game_id: Uuid,
        operation_id: Uuid,
        expected_lobby_revision: u64,
        update: LobbyConfigurationUpdate,
    ) -> Result<LobbySummary, CommitError> {
        let password_meaning = match &update.password {
            LobbyPasswordUpdate::Keep => json!({"action": "keep"}),
            LobbyPasswordUpdate::Clear => json!({"action": "clear"}),
            LobbyPasswordUpdate::Replace { identity, .. } => {
                json!({"action": "replace", "identity": identity})
            }
        };
        let request = json!({
            "kind": "owner_configuration",
            "expected_lobby_revision": expected_lobby_revision,
            "display_name": update.display_name,
            "human_slots": update.human_slots,
            "password": password_meaning,
            "setup": update.setup,
        });
        self.commit_lobby_reconfiguration(
            worker,
            actor_account_id,
            game_id,
            operation_id,
            expected_lobby_revision,
            request,
            ReconfigurationIntent::OwnerConfiguration(Box::new(update)),
        )
        .await
    }

    pub async fn reselect_lobby_faction(
        &self,
        worker: &EngineWorkerClient,
        actor_account_id: Uuid,
        game_id: Uuid,
        operation_id: Uuid,
        expected_lobby_revision: u64,
        civilization_id: String,
    ) -> Result<LobbySummary, CommitError> {
        let request = json!({
            "kind": "member_faction",
            "expected_lobby_revision": expected_lobby_revision,
            "civilization_id": civilization_id,
        });
        self.commit_lobby_reconfiguration(
            worker,
            actor_account_id,
            game_id,
            operation_id,
            expected_lobby_revision,
            request,
            ReconfigurationIntent::MemberFaction(civilization_id),
        )
        .await
    }

    #[allow(clippy::too_many_arguments)]
    async fn commit_lobby_reconfiguration(
        &self,
        worker: &EngineWorkerClient,
        actor_account_id: Uuid,
        game_id: Uuid,
        operation_id: Uuid,
        expected_lobby_revision: u64,
        request: serde_json::Value,
        intent: ReconfigurationIntent,
    ) -> Result<LobbySummary, CommitError> {
        if operation_id.is_nil() {
            return Err(CommitError::InvalidCommand);
        }
        let mut tx = self.pool.begin().await.map_err(CommitError::storage)?;
        sqlx::query("SELECT pg_advisory_xact_lock(hashtextextended($1, 0))")
            .bind(format!("lobby-reconfiguration:{game_id}:{operation_id}"))
            .execute(&mut *tx)
            .await
            .map_err(CommitError::storage)?;

        if let Some(existing) = sqlx::query(
            "SELECT actor_account_id, request
             FROM game_lobby_reconfiguration_operations
             WHERE game_id=$1 AND operation_id=$2",
        )
        .bind(game_id)
        .bind(operation_id)
        .fetch_optional(&mut *tx)
        .await
        .map_err(CommitError::storage)?
        {
            if existing.get::<Uuid, _>("actor_account_id") != actor_account_id
                || existing.get::<serde_json::Value, _>("request") != request
            {
                return Err(CommitError::InvalidCommand);
            }
            tx.commit().await.map_err(CommitError::storage)?;
            return self.lobby_summary(actor_account_id, game_id).await;
        }

        let row = sqlx::query(
            "SELECT l.owner_account_id, l.lobby_revision, l.human_slots, l.setup,
                    l.password_hash, l.password_identity, g.display_name,
                    g.head_revision, g.ruleset_manifest_hash, m.manifest,
                    s.engine_build, creation.server_seed
             FROM game_lobbies l
             JOIN games g ON g.id=l.game_id
             JOIN ruleset_manifests m ON m.hash=g.ruleset_manifest_hash
             JOIN game_snapshots s ON s.game_id=g.id AND s.revision=g.head_revision
             JOIN game_creation_operations creation ON creation.game_id=g.id
             WHERE l.game_id=$1 AND l.started_at IS NULL AND l.closed_at IS NULL
             FOR UPDATE OF l, g",
        )
        .bind(game_id)
        .fetch_optional(&mut *tx)
        .await
        .map_err(CommitError::storage)?
        .ok_or(CommitError::InvalidCommand)?;
        let mut current = CurrentLobby {
            owner_account_id: row.get("owner_account_id"),
            lobby_revision: u64::try_from(row.get::<i64, _>("lobby_revision"))
                .map_err(|_| CommitError::InvalidCommand)?,
            head_revision: u64::try_from(row.get::<i64, _>("head_revision"))
                .map_err(|_| CommitError::InvalidCommand)?,
            display_name: row.get("display_name"),
            human_slots: u8::try_from(row.get::<i16, _>("human_slots"))
                .map_err(|_| CommitError::InvalidCommand)?,
            password_hash: row.get("password_hash"),
            password_identity: row.get("password_identity"),
            setup: serde_json::from_value(row.get("setup"))
                .map_err(|_| CommitError::WorkerRevisionMismatch)?,
            manifest_hash: row.get("ruleset_manifest_hash"),
            manifest: serde_json::from_value(row.get("manifest"))
                .map_err(|_| CommitError::WorkerRevisionMismatch)?,
            engine_build: row.get("engine_build"),
            creation_seed: row
                .get::<Option<i64>, _>("server_seed")
                .ok_or(CommitError::InvalidCommand)?,
        };
        if current.lobby_revision != expected_lobby_revision {
            return Err(CommitError::Stale {
                expected: expected_lobby_revision,
                actual: current.lobby_revision,
            });
        }

        let member_rows = sqlx::query(
            "SELECT account_id, role, civilization_id
             FROM game_members
             WHERE game_id=$1 AND role IN ('owner','player')
             ORDER BY CASE role WHEN 'owner' THEN 0 ELSE 1 END, account_id
             FOR UPDATE",
        )
        .bind(game_id)
        .fetch_all(&mut *tx)
        .await
        .map_err(CommitError::storage)?;
        let mut participants = member_rows
            .iter()
            .map(|member| {
                Ok(WorkerLobbyParticipant {
                    account_id: member.get::<Uuid, _>("account_id").to_string(),
                    civilization_id: member
                        .get::<Option<String>, _>("civilization_id")
                        .ok_or(CommitError::InvalidCommand)?,
                })
            })
            .collect::<Result<Vec<_>, CommitError>>()?;
        if participants.is_empty() && current.human_slots > 0 {
            return Err(CommitError::InvalidCommand);
        }
        if !participants.is_empty()
            && participants[0].account_id != current.owner_account_id.to_string()
        {
            return Err(CommitError::InvalidCommand);
        }

        match intent {
            ReconfigurationIntent::OwnerConfiguration(update) => {
                if current.owner_account_id != actor_account_id {
                    return Err(CommitError::Unauthorized);
                }
                current.display_name = update.display_name;
                current.human_slots = update.human_slots;
                current.setup = update.setup;
                match update.password {
                    LobbyPasswordUpdate::Keep => {}
                    LobbyPasswordUpdate::Clear => {
                        current.password_hash = None;
                        current.password_identity = None;
                    }
                    LobbyPasswordUpdate::Replace { hash, identity } => {
                        current.password_hash = Some(hash);
                        current.password_identity = Some(identity);
                    }
                }
                if !participants.is_empty() {
                    participants[0].civilization_id = current.setup.owner_civilization_id.clone();
                }
            }
            ReconfigurationIntent::MemberFaction(civilization_id) => {
                if civilization_id.is_empty()
                    || civilization_id.len() > 128
                    || civilization_id.chars().any(char::is_control)
                {
                    return Err(CommitError::InvalidCommand);
                }
                let participant = participants
                    .iter_mut()
                    .find(|participant| participant.account_id == actor_account_id.to_string())
                    .ok_or(CommitError::Unauthorized)?;
                participant.civilization_id = civilization_id.clone();
                if actor_account_id == current.owner_account_id {
                    current.setup.owner_civilization_id = civilization_id;
                }
            }
        }

        if current.display_name.trim().is_empty()
            || current.display_name.len() > 80
            || current.display_name.chars().any(char::is_control)
            || !(0..=16).contains(&current.human_slots)
            || current.human_slots < u8::try_from(participants.len()).unwrap_or(u8::MAX)
            || current.human_slots > current.setup.major_civilizations
            || participants
                .iter()
                .map(|participant| &participant.civilization_id)
                .collect::<std::collections::HashSet<_>>()
                .len()
                != participants.len()
            || !ai_roster_is_consistent(&current)
        {
            return Err(CommitError::InvalidCommand);
        }

        let server_seed = derived_reconfiguration_seed(current.creation_seed, operation_id)?;
        let game_id_text = game_id.to_string();
        let created = worker
            .reconfigure_lobby(
                &current.owner_account_id.to_string(),
                &current.manifest,
                current.head_revision,
                WorkerLobbyReconfiguration {
                    game_id: &game_id_text,
                    server_seed,
                    setup: &current.setup,
                    participants: &participants,
                },
            )
            .await
            .map_err(|_| CommitError::WorkerRevisionMismatch)?;
        let proposal = created.proposal;
        if proposal.previous_revision != current.head_revision
            || proposal.snapshot.is_empty()
            || proposal.snapshot.len() > MAX_SNAPSHOT_BYTES
            || state_hash(&proposal.snapshot) != proposal.canonical_state_hash
            || created.owner_civilization_id != participants[0].civilization_id
            || participants.iter().any(|participant| {
                !created
                    .available_civilization_ids
                    .contains(&participant.civilization_id)
            })
        {
            return Err(CommitError::WorkerRevisionMismatch);
        }
        let revision = current
            .head_revision
            .checked_add(1)
            .ok_or(CommitError::InvalidCommand)?;
        let revision_i64 = i64::try_from(revision).map_err(|_| CommitError::InvalidCommand)?;
        let head_i64 =
            i64::try_from(current.head_revision).map_err(|_| CommitError::InvalidCommand)?;
        let lobby_revision = current
            .lobby_revision
            .checked_add(1)
            .ok_or(CommitError::InvalidCommand)?;
        let lobby_revision_i64 =
            i64::try_from(lobby_revision).map_err(|_| CommitError::InvalidCommand)?;
        let stored = stored_snapshot(&proposal.snapshot)?;
        snapshot_storage::insert_snapshot(
            &mut tx,
            game_id,
            revision_i64,
            &current.engine_build,
            &current.manifest_hash,
            &stored,
        )
        .await?;
        sqlx::query(
            "INSERT INTO game_revisions
             (game_id, revision, parent_revision, command_id, snapshot_revision,
              canonical_state_hash, revision_kind)
             VALUES ($1,$2,$3,NULL,$2,$4,'lobby_reconfiguration')",
        )
        .bind(game_id)
        .bind(revision_i64)
        .bind(head_i64)
        .bind(&proposal.canonical_state_hash)
        .execute(&mut *tx)
        .await
        .map_err(CommitError::storage)?;
        for participant in &participants {
            sqlx::query(
                "UPDATE game_members SET civilization_id=$3
                 WHERE game_id=$1 AND account_id=$2 AND role IN ('owner','player')",
            )
            .bind(game_id)
            .bind(
                Uuid::parse_str(&participant.account_id)
                    .map_err(|_| CommitError::InvalidCommand)?,
            )
            .bind(&participant.civilization_id)
            .execute(&mut *tx)
            .await
            .map_err(CommitError::storage)?;
        }
        sqlx::query(
            "UPDATE game_lobbies
             SET human_slots=$2, setup=$3, password_hash=$4, password_identity=$5,
                 available_civilizations=$6, lobby_revision=$7
             WHERE game_id=$1",
        )
        .bind(game_id)
        .bind(i16::from(current.human_slots))
        .bind(serde_json::to_value(&current.setup).map_err(|_| CommitError::Storage)?)
        .bind(&current.password_hash)
        .bind(&current.password_identity)
        .bind(&created.available_civilization_ids)
        .bind(lobby_revision_i64)
        .execute(&mut *tx)
        .await
        .map_err(CommitError::storage)?;
        sqlx::query(
            "UPDATE game_lobby_readiness SET ready=FALSE, updated_at=now() WHERE game_id=$1",
        )
        .bind(game_id)
        .execute(&mut *tx)
        .await
        .map_err(CommitError::storage)?;
        let visibility = if current.password_hash.is_some() {
            "private"
        } else {
            "public"
        };
        let updated = sqlx::query(
            "UPDATE games SET head_revision=$2, display_name=$3, visibility=$5
             WHERE id=$1 AND head_revision=$4",
        )
        .bind(game_id)
        .bind(revision_i64)
        .bind(&current.display_name)
        .bind(head_i64)
        .bind(visibility)
        .execute(&mut *tx)
        .await
        .map_err(CommitError::storage)?;
        if updated.rows_affected() != 1 {
            return Err(CommitError::Stale {
                expected: current.head_revision,
                actual: current.head_revision.saturating_add(1),
            });
        }
        sqlx::query(
            "INSERT INTO game_lobby_reconfiguration_operations
             (game_id, operation_id, actor_account_id, request, committed_revision,
              lobby_revision, canonical_state_hash, server_seed, server_time_millis)
             VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9)",
        )
        .bind(game_id)
        .bind(operation_id)
        .bind(actor_account_id)
        .bind(&request)
        .bind(revision_i64)
        .bind(lobby_revision_i64)
        .bind(&proposal.canonical_state_hash)
        .bind(server_seed)
        .bind(proposal.server_time_millis)
        .execute(&mut *tx)
        .await
        .map_err(CommitError::storage)?;
        sqlx::query(
            "INSERT INTO game_outbox (game_id, revision, topic, payload)
             VALUES ($1,$2,'game.lobby.reconfigured',$3)",
        )
        .bind(game_id)
        .bind(revision_i64)
        .bind(json!({
            "game_id": game_id,
            "revision": revision,
            "lobby_revision": lobby_revision,
            "state_hash": proposal.canonical_state_hash,
        }))
        .execute(&mut *tx)
        .await
        .map_err(CommitError::storage)?;
        tx.commit().await.map_err(CommitError::storage)?;
        self.lobby_summary(actor_account_id, game_id).await
    }
}

fn derived_reconfiguration_seed(base_seed: i64, operation_id: Uuid) -> Result<i64, CommitError> {
    let digest = state_hash(format!("{base_seed}:{operation_id}").as_bytes());
    let value = u64::from_str_radix(&digest[..16], 16).map_err(|_| CommitError::Storage)?;
    Ok(value as i64)
}
