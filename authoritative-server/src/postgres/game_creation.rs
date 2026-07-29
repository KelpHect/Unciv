use super::*;
use rand_core::{OsRng, RngCore};

impl PostgresGameRepository {
    /// Creates revision zero exactly once for one authenticated creation ID and
    /// exact request meaning. The transaction-scoped advisory lock serializes
    /// concurrent replicas before either invokes the private worker.
    pub async fn create_authoritative_game(
        &self,
        worker: &EngineWorkerClient,
        owner_account_id: Uuid,
        operation_id: Uuid,
        ruleset_manifest_hash: String,
        setup: crate::worker::WorkerGameSetup,
        lobby: LobbyCreateConfiguration,
    ) -> Result<Uuid, CommitError> {
        let request = json!({
            "ruleset_manifest_hash": ruleset_manifest_hash,
            "setup": &setup,
            "display_name": &lobby.display_name,
            "human_slots": lobby.human_slots,
            "password_identity": &lobby.password_identity,
            "available_civilizations": &lobby.available_civilizations,
        });
        let mut tx = self.pool.begin().await.map_err(CommitError::storage)?;
        sqlx::query("SELECT pg_advisory_xact_lock(hashtextextended($1, 0))")
            .bind(operation_id.to_string())
            .execute(&mut *tx)
            .await
            .map_err(CommitError::storage)?;

        if let Some(row) = sqlx::query(
            "SELECT actor_account_id, request, game_id FROM game_creation_operations WHERE operation_id=$1",
        )
        .bind(operation_id)
        .fetch_optional(&mut *tx)
        .await
        .map_err(CommitError::storage)?
        {
            if row.get::<Uuid, _>("actor_account_id") != owner_account_id
                || row.get::<serde_json::Value, _>("request") != request
            {
                return Err(CommitError::InvalidCommand);
            }
            let game_id = row.get("game_id");
            tx.commit().await.map_err(CommitError::storage)?;
            return Ok(game_id);
        }

        sqlx::query("SELECT pg_advisory_xact_lock(hashtextextended('ruleset-asset:' || $1, 0))")
            .bind(&ruleset_manifest_hash)
            .execute(&mut *tx)
            .await
            .map_err(CommitError::storage)?;
        let manifest: serde_json::Value = sqlx::query_scalar(
            "SELECT m.manifest FROM ruleset_manifests m
                 WHERE m.hash = $1
                   AND EXISTS (
                     SELECT 1 FROM ruleset_asset_versions v
                     WHERE v.manifest_hash=m.hash
                   )",
        )
        .bind(&ruleset_manifest_hash)
        .fetch_optional(&mut *tx)
        .await
        .map_err(CommitError::storage)?
        .ok_or(CommitError::NotFound)?;
        let manifest: WorkerManifest =
            serde_json::from_value(manifest).map_err(|_| CommitError::WorkerRevisionMismatch)?;

        // Keep the seed independent from both public UUIDs. Entropy or worker
        // failure aborts the transaction, leaving no game or operation record.
        let mut seed_bytes = [0_u8; 8];
        OsRng
            .try_fill_bytes(&mut seed_bytes)
            .map_err(|_| CommitError::Storage)?;
        let server_seed = i64::from_be_bytes(seed_bytes);
        let game_id = Uuid::new_v4();
        let mut canonical_setup = setup.clone();
        if canonical_setup.map_seed.is_none() {
            canonical_setup.map_seed = Some(server_seed);
        }
        let created = worker
            .create_game(
                &owner_account_id.to_string(),
                &manifest,
                &game_id.to_string(),
                server_seed,
                &canonical_setup,
            )
            .await
            .map_err(|_| CommitError::WorkerRevisionMismatch)?;
        let proposal = created.proposal;
        if proposal.snapshot.is_empty() || proposal.snapshot.len() > MAX_SNAPSHOT_BYTES {
            return Err(CommitError::SnapshotTooLarge);
        }
        if state_hash(&proposal.snapshot) != proposal.canonical_state_hash {
            return Err(CommitError::InvalidSnapshotHash);
        }

        if created.available_civilization_ids.len() < usize::from(lobby.human_slots)
            || created.owner_civilization_id != canonical_setup.owner_civilization_id
            || !created
                .available_civilization_ids
                .contains(&created.owner_civilization_id)
        {
            return Err(CommitError::WorkerRevisionMismatch);
        }
        let mut authoritative_lobby = lobby;
        authoritative_lobby.available_civilizations = created.available_civilization_ids;
        self.create_game_in_transaction(
            &mut tx,
            NewGame {
                game_id,
                owner_account_id,
                ruleset_manifest_hash,
                snapshot: proposal.snapshot,
                owner_civilization_id: created.owner_civilization_id,
            },
        )
        .await?;
        self.insert_lobby(
            &mut tx,
            game_id,
            owner_account_id,
            &canonical_setup,
            &authoritative_lobby,
        )
        .await?;
        sqlx::query(
            "INSERT INTO game_creation_operations (operation_id, actor_account_id, request, game_id, server_seed, server_time_millis) VALUES ($1, $2, $3, $4, $5, $6)",
        )
        .bind(operation_id)
        .bind(owner_account_id)
        .bind(request)
        .bind(game_id)
        .bind(server_seed)
        .bind(proposal.server_time_millis)
        .execute(&mut *tx)
        .await
        .map_err(CommitError::storage)?;
        tx.commit().await.map_err(CommitError::storage)?;
        Ok(game_id)
    }
}
