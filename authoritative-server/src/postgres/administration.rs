use super::*;

impl PostgresGameRepository {
    pub async fn transfer_ownership(
        &self,
        actor: Uuid,
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
            if row.get::<Uuid, _>("actor_account_id") == actor
                && row.get::<String, _>("operation_kind") == "transfer_ownership"
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
        let owner_role: Option<String> =
            sqlx::query_scalar("SELECT role FROM game_members WHERE game_id=$1 AND account_id=$2")
                .bind(game_id)
                .bind(actor)
                .fetch_optional(&mut *tx)
                .await
                .map_err(CommitError::storage)?;
        if owner_role.as_deref() != Some("owner") {
            return Err(CommitError::Unauthorized);
        }
        let target: Option<Uuid> = sqlx::query_scalar(
            "SELECT gm.account_id FROM game_members gm JOIN accounts a ON a.id=gm.account_id WHERE gm.game_id=$1 AND a.username_normalized=$2 AND a.disabled_at IS NULL AND gm.role='player' AND gm.civilization_id IS NOT NULL",
        )
        .bind(game_id)
        .bind(username)
        .fetch_optional(&mut *tx)
        .await
        .map_err(CommitError::storage)?;
        let target = target.ok_or(CommitError::NotFound)?;
        sqlx::query("UPDATE game_members SET role='player' WHERE game_id=$1 AND account_id=$2 AND role='owner'")
            .bind(game_id)
            .bind(actor)
            .execute(&mut *tx)
            .await
            .map_err(CommitError::storage)?;
        let promoted = sqlx::query(
            "UPDATE game_members SET role='owner' WHERE game_id=$1 AND account_id=$2 AND role='player'",
        )
        .bind(game_id)
        .bind(target)
        .execute(&mut *tx)
        .await
        .map_err(CommitError::storage)?;
        if promoted.rows_affected() != 1 {
            return Err(CommitError::InvalidCommand);
        }
        sqlx::query("INSERT INTO game_admin_operations (game_id, operation_id, actor_account_id, operation_kind, request) VALUES ($1, $2, $3, 'transfer_ownership', $4)")
            .bind(game_id).bind(operation_id).bind(actor).bind(&request)
            .execute(&mut *tx).await.map_err(CommitError::storage)?;
        sqlx::query("INSERT INTO game_outbox (game_id, revision, topic, payload) VALUES ($1, $2, 'game.membership.changed', $3)")
            .bind(game_id)
            .bind(game.get::<i64, _>("head_revision"))
            .bind(json!({"game_id": game_id, "previous_owner_account_id": actor, "owner_account_id": target}))
            .execute(&mut *tx).await.map_err(CommitError::storage)?;
        tx.commit().await.map_err(CommitError::storage)
    }

    pub async fn close_game(
        &self,
        actor: Uuid,
        game_id: Uuid,
        operation_id: Uuid,
    ) -> Result<(), CommitError> {
        self.change_lifecycle_status(
            actor,
            game_id,
            operation_id,
            "close_game",
            "active",
            "closed",
        )
        .await
    }

    pub async fn archive_game(
        &self,
        actor: Uuid,
        game_id: Uuid,
        operation_id: Uuid,
    ) -> Result<(), CommitError> {
        self.change_lifecycle_status(
            actor,
            game_id,
            operation_id,
            "archive_game",
            "closed",
            "archived",
        )
        .await
    }

    async fn change_lifecycle_status(
        &self,
        actor: Uuid,
        game_id: Uuid,
        operation_id: Uuid,
        operation_kind: &str,
        expected_status: &str,
        next_status: &str,
    ) -> Result<(), CommitError> {
        let request = json!({});
        let mut tx = self.pool.begin().await.map_err(CommitError::storage)?;
        if let Some(row) = sqlx::query(
            "SELECT actor_account_id, operation_kind, request FROM game_admin_operations WHERE game_id=$1 AND operation_id=$2",
        )
        .bind(game_id).bind(operation_id)
        .fetch_optional(&mut *tx).await.map_err(CommitError::storage)?
        {
            if row.get::<Uuid, _>("actor_account_id") == actor
                && row.get::<String, _>("operation_kind") == operation_kind
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
        let owner: bool = sqlx::query_scalar(
            "SELECT EXISTS(SELECT 1 FROM game_members WHERE game_id=$1 AND account_id=$2 AND role='owner')",
        )
        .bind(game_id).bind(actor).fetch_one(&mut *tx).await.map_err(CommitError::storage)?;
        if !owner {
            return Err(CommitError::Unauthorized);
        }
        if game.get::<String, _>("lifecycle_status") != expected_status {
            return Err(CommitError::InvalidCommand);
        }
        sqlx::query(
            "UPDATE games SET lifecycle_status=$2, lifecycle_status_changed_at=now() WHERE id=$1",
        )
        .bind(game_id)
        .bind(next_status)
        .execute(&mut *tx)
        .await
        .map_err(CommitError::storage)?;
        sqlx::query("INSERT INTO game_admin_operations (game_id, operation_id, actor_account_id, operation_kind, request) VALUES ($1, $2, $3, $4, $5)")
            .bind(game_id).bind(operation_id).bind(actor).bind(operation_kind).bind(&request)
            .execute(&mut *tx).await.map_err(CommitError::storage)?;
        sqlx::query("INSERT INTO game_outbox (game_id, revision, topic, payload) VALUES ($1, $2, 'game.lifecycle.changed', $3)")
            .bind(game_id).bind(game.get::<i64, _>("head_revision"))
            .bind(json!({"game_id": game_id, "lifecycle_status": next_status}))
            .execute(&mut *tx).await.map_err(CommitError::storage)?;
        tx.commit().await.map_err(CommitError::storage)
    }
}
