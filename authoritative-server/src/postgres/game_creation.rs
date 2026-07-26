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
    ) -> Result<Uuid, CommitError> {
        let request = json!({
            "ruleset_manifest_hash": ruleset_manifest_hash,
            "setup": &setup,
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

        let manifest: serde_json::Value =
            sqlx::query_scalar("SELECT manifest FROM ruleset_manifests WHERE hash = $1")
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
        let created = worker
            .create_game(
                &owner_account_id.to_string(),
                &manifest,
                i64::from_be_bytes(seed_bytes),
                &setup,
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

        let game_id = Uuid::new_v4();
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
        sqlx::query(
            "INSERT INTO game_creation_operations (operation_id, actor_account_id, request, game_id) VALUES ($1, $2, $3, $4)",
        )
        .bind(operation_id)
        .bind(owner_account_id)
        .bind(request)
        .bind(game_id)
        .execute(&mut *tx)
        .await
        .map_err(CommitError::storage)?;
        tx.commit().await.map_err(CommitError::storage)?;
        Ok(game_id)
    }
}
