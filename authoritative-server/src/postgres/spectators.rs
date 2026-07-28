use super::*;

use crate::SPECTATOR_PROJECTION_VERSION;

impl PostgresGameRepository {
    pub async fn add_spectator(
        &self,
        owner_account_id: Uuid,
        game_id: Uuid,
        username: &str,
    ) -> Result<(), CommitError> {
        let username = normalize_username(username).map_err(|_| CommitError::InvalidCommand)?;
        let mut tx = self.pool.begin().await.map_err(CommitError::storage)?;
        let head_revision: i64 = sqlx::query_scalar(
            "SELECT g.head_revision FROM games g JOIN game_members gm ON gm.game_id=g.id AND gm.account_id=$2 AND gm.role='owner' WHERE g.id=$1 AND g.lifecycle_status='active' FOR UPDATE",
        )
        .bind(game_id)
        .bind(owner_account_id)
        .fetch_optional(&mut *tx)
        .await
        .map_err(CommitError::storage)?
        .ok_or(CommitError::Unauthorized)?;
        let target: Uuid = sqlx::query_scalar(
            "SELECT id FROM accounts WHERE username_normalized=$1 AND disabled_at IS NULL",
        )
        .bind(username)
        .fetch_optional(&mut *tx)
        .await
        .map_err(CommitError::storage)?
        .ok_or(CommitError::NotFound)?;
        let existing: Option<String> =
            sqlx::query_scalar("SELECT role FROM game_members WHERE game_id=$1 AND account_id=$2")
                .bind(game_id)
                .bind(target)
                .fetch_optional(&mut *tx)
                .await
                .map_err(CommitError::storage)?;
        match existing.as_deref() {
            Some("spectator") => return tx.commit().await.map_err(CommitError::storage),
            Some(_) => return Err(CommitError::InvalidCommand),
            None => {}
        }
        sqlx::query(
            "INSERT INTO game_members (game_id, account_id, role, civilization_id) VALUES ($1, $2, 'spectator', NULL)",
        )
        .bind(game_id)
        .bind(target)
        .execute(&mut *tx)
        .await
        .map_err(CommitError::storage)?;
        sqlx::query("INSERT INTO game_outbox (game_id, revision, topic, payload) VALUES ($1, $2, 'game.membership.changed', $3)")
            .bind(game_id)
            .bind(head_revision)
            .bind(json!({"game_id": game_id, "account_id": target, "role": "spectator"}))
            .execute(&mut *tx)
            .await
            .map_err(CommitError::storage)?;
        tx.commit().await.map_err(CommitError::storage)
    }

    pub async fn leave_spectator(
        &self,
        actor_account_id: Uuid,
        game_id: Uuid,
    ) -> Result<(), CommitError> {
        let mut tx = self.pool.begin().await.map_err(CommitError::storage)?;
        let head_revision: i64 =
            sqlx::query_scalar("SELECT head_revision FROM games WHERE id=$1 FOR UPDATE")
                .bind(game_id)
                .fetch_optional(&mut *tx)
                .await
                .map_err(CommitError::storage)?
                .ok_or(CommitError::NotFound)?;
        let result = sqlx::query(
            "DELETE FROM game_members WHERE game_id=$1 AND account_id=$2 AND role='spectator'",
        )
        .bind(game_id)
        .bind(actor_account_id)
        .execute(&mut *tx)
        .await
        .map_err(CommitError::storage)?;
        if result.rows_affected() == 1 {
            sqlx::query("INSERT INTO game_outbox (game_id, revision, topic, payload) VALUES ($1, $2, 'game.membership.changed', $3)")
                .bind(game_id)
                .bind(head_revision)
                .bind(json!({"game_id": game_id, "account_id": actor_account_id, "role": null}))
                .execute(&mut *tx)
                .await
                .map_err(CommitError::storage)?;
            tx.commit().await.map_err(CommitError::storage)
        } else {
            Err(CommitError::Unauthorized)
        }
    }

    pub async fn revoke_spectator(
        &self,
        owner_account_id: Uuid,
        game_id: Uuid,
        operation_id: Uuid,
        username: &str,
    ) -> Result<(), CommitError> {
        let username = normalize_username(username).map_err(|_| CommitError::InvalidCommand)?;
        let request = json!({"username": username});
        let mut tx = self.pool.begin().await.map_err(CommitError::storage)?;
        if let Some(row) = sqlx::query(
            "SELECT actor_account_id, operation_kind, request FROM game_admin_operations WHERE game_id=$1 AND operation_id=$2",
        )
        .bind(game_id)
        .bind(operation_id)
        .fetch_optional(&mut *tx)
        .await
        .map_err(CommitError::storage)?
        {
            if row.get::<Uuid, _>("actor_account_id") == owner_account_id
                && row.get::<String, _>("operation_kind") == "revoke_spectator"
                && row.get::<serde_json::Value, _>("request") == request
            {
                return tx.commit().await.map_err(CommitError::storage);
            }
            return Err(CommitError::InvalidCommand);
        }

        let game =
            sqlx::query("SELECT head_revision, lifecycle_status FROM games WHERE id=$1 FOR UPDATE")
                .bind(game_id)
                .fetch_optional(&mut *tx)
                .await
                .map_err(CommitError::storage)?
                .ok_or(CommitError::NotFound)?;
        if game.get::<String, _>("lifecycle_status") == "archived" {
            return Err(CommitError::InvalidCommand);
        }
        let owner: bool = sqlx::query_scalar(
            "SELECT EXISTS(SELECT 1 FROM game_members WHERE game_id=$1 AND account_id=$2 AND role='owner')",
        )
        .bind(game_id)
        .bind(owner_account_id)
        .fetch_one(&mut *tx)
        .await
        .map_err(CommitError::storage)?;
        if !owner {
            return Err(CommitError::Unauthorized);
        }
        let target: Uuid = sqlx::query_scalar(
            "SELECT gm.account_id FROM game_members gm JOIN accounts a ON a.id=gm.account_id WHERE gm.game_id=$1 AND a.username_normalized=$2 AND gm.role='spectator'",
        )
        .bind(game_id)
        .bind(&username)
        .fetch_optional(&mut *tx)
        .await
        .map_err(CommitError::storage)?
        .ok_or(CommitError::NotFound)?;
        let removed = sqlx::query(
            "DELETE FROM game_members WHERE game_id=$1 AND account_id=$2 AND role='spectator'",
        )
        .bind(game_id)
        .bind(target)
        .execute(&mut *tx)
        .await
        .map_err(CommitError::storage)?;
        if removed.rows_affected() != 1 {
            return Err(CommitError::InvalidCommand);
        }
        sqlx::query(
            "INSERT INTO game_admin_operations (game_id, operation_id, actor_account_id, operation_kind, request) VALUES ($1, $2, $3, 'revoke_spectator', $4)",
        )
        .bind(game_id)
        .bind(operation_id)
        .bind(owner_account_id)
        .bind(&request)
        .execute(&mut *tx)
        .await
        .map_err(CommitError::storage)?;
        sqlx::query("INSERT INTO game_outbox (game_id, revision, topic, payload) VALUES ($1, $2, 'game.membership.changed', $3)")
            .bind(game_id)
            .bind(game.get::<i64, _>("head_revision"))
            .bind(json!({"game_id": game_id, "account_id": target, "role": null}))
            .execute(&mut *tx)
            .await
            .map_err(CommitError::storage)?;
        tx.commit().await.map_err(CommitError::storage)
    }

    pub async fn spectator_projection(
        &self,
        worker: &EngineWorkerClient,
        actor_account_id: Uuid,
        game_id: Uuid,
    ) -> Result<SpectatorGameProjection, CommitError> {
        let row = sqlx::query(
            "SELECT g.unavailable_at IS NOT NULL AS is_unavailable, g.lifecycle_status, g.head_revision, r.canonical_state_hash AS revision_state_hash, s.revision AS snapshot_revision, b.payload, s.codec, s.compressed_size, s.uncompressed_size, s.protocol_version AS snapshot_protocol_version, s.validation_status, s.payload_hash, s.canonical_state_hash AS snapshot_state_hash, m.manifest FROM games g JOIN game_members gm ON gm.game_id=g.id AND gm.account_id=$2 AND gm.role='spectator' JOIN game_revisions r ON r.game_id=g.id AND r.revision=g.head_revision JOIN game_snapshots s ON s.game_id=g.id AND s.revision=g.head_revision JOIN game_snapshot_blobs b ON b.game_id=s.game_id AND b.revision=s.revision JOIN ruleset_manifests m ON m.hash=g.ruleset_manifest_hash WHERE g.id=$1",
        )
        .bind(game_id)
        .bind(actor_account_id)
        .fetch_optional(&self.pool)
        .await
        .map_err(CommitError::storage)?
            .ok_or(CommitError::Unauthorized)?;
        if row.get::<String, _>("lifecycle_status") == "archived" {
            return Err(CommitError::InvalidCommand);
        }
        let snapshot = self.validated_snapshot(game_id, &row).await?;
        let manifest = serde_json::from_value::<WorkerManifest>(row.get("manifest"))
            .map_err(|_| CommitError::WorkerRevisionMismatch)?;
        let projected = worker
            .project_spectator_state(&actor_account_id.to_string(), &manifest, &snapshot)
            .await
            .map_err(|error| match error {
                crate::worker::WorkerClientError::Rejected(reason) => {
                    CommitError::WorkerRejected(reason)
                }
                _ => CommitError::WorkerRevisionMismatch,
            })?;
        let projection_bytes = serde_json::to_vec(&projected.projection)
            .expect("worker spectator projection is serializable");
        Ok(SpectatorGameProjection {
            game_id,
            projection_version: SPECTATOR_PROJECTION_VERSION,
            committed_revision: u64::try_from(row.get::<i64, _>("head_revision"))
                .expect("revision is non-negative"),
            canonical_state_hash: row.get("revision_state_hash"),
            projection_hash: state_hash(&projection_bytes),
            projection: projected.projection,
        })
    }
}
