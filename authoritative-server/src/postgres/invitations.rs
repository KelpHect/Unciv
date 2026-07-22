use super::*;

impl PostgresGameRepository {
    /// Creates one durable owner-authorized invitation. Exact retries remain
    /// successful even after the target consumes the invitation.
    pub async fn invite_player(
        &self,
        actor: Uuid,
        game_id: Uuid,
        invitation_id: Uuid,
        username: &str,
    ) -> Result<(), CommitError> {
        let username = normalize_username(username).map_err(|_| CommitError::InvalidCommand)?;
        let mut tx = self.pool.begin().await.map_err(CommitError::storage)?;
        if let Some(row) = sqlx::query(
            "SELECT i.invited_by_account_id, a.username_normalized FROM game_player_invitations i JOIN accounts a ON a.id=i.invited_account_id WHERE i.game_id=$1 AND i.invitation_id=$2",
        )
        .bind(game_id)
        .bind(invitation_id)
        .fetch_optional(&mut *tx)
        .await
        .map_err(CommitError::storage)?
        {
            if row.get::<Uuid, _>("invited_by_account_id") == actor
                && row.get::<String, _>("username_normalized") == username
            {
                return tx.commit().await.map_err(CommitError::storage);
            }
            return Err(CommitError::InvalidCommand);
        }
        let game = sqlx::query("SELECT lifecycle_status FROM games WHERE id=$1 FOR UPDATE")
            .bind(game_id)
            .fetch_optional(&mut *tx)
            .await
            .map_err(CommitError::storage)?
            .ok_or(CommitError::NotFound)?;
        if game.get::<String, _>("lifecycle_status") != "active" {
            return Err(CommitError::InvalidCommand);
        }
        let owner: bool = sqlx::query_scalar(
            "SELECT EXISTS(SELECT 1 FROM game_members WHERE game_id=$1 AND account_id=$2 AND role='owner')",
        )
        .bind(game_id)
        .bind(actor)
        .fetch_one(&mut *tx)
        .await
        .map_err(CommitError::storage)?;
        if !owner {
            return Err(CommitError::Unauthorized);
        }
        let target: Option<Uuid> = sqlx::query_scalar(
            "SELECT id FROM accounts WHERE username_normalized=$1 AND disabled_at IS NULL",
        )
        .bind(&username)
        .fetch_optional(&mut *tx)
        .await
        .map_err(CommitError::storage)?;
        let target = target.ok_or(CommitError::NotFound)?;
        if target == actor {
            return Err(CommitError::InvalidCommand);
        }
        let already_member: bool = sqlx::query_scalar(
            "SELECT EXISTS(SELECT 1 FROM game_members WHERE game_id=$1 AND account_id=$2)",
        )
        .bind(game_id)
        .bind(target)
        .fetch_one(&mut *tx)
        .await
        .map_err(CommitError::storage)?;
        if already_member {
            return Err(CommitError::InvalidCommand);
        }
        let inserted = sqlx::query(
            "INSERT INTO game_player_invitations (game_id, invitation_id, invited_account_id, invited_by_account_id) VALUES ($1, $2, $3, $4) ON CONFLICT DO NOTHING",
        )
        .bind(game_id)
        .bind(invitation_id)
        .bind(target)
        .bind(actor)
        .execute(&mut *tx)
        .await
        .map_err(CommitError::storage)?;
        if inserted.rows_affected() != 1 {
            return Err(CommitError::InvalidCommand);
        }
        tx.commit().await.map_err(CommitError::storage)
    }

    pub async fn list_player_invitations(
        &self,
        actor: Uuid,
    ) -> Result<Vec<PlayerInvitation>, CommitError> {
        let rows = sqlx::query(
            "SELECT i.game_id, i.invitation_id, inviter.username_normalized AS invited_by, g.head_revision, r.canonical_state_hash FROM game_player_invitations i JOIN accounts inviter ON inviter.id=i.invited_by_account_id JOIN games g ON g.id=i.game_id JOIN game_revisions r ON r.game_id=g.id AND r.revision=g.head_revision WHERE i.invited_account_id=$1 AND i.consumed_at IS NULL AND g.unavailable_at IS NULL AND g.lifecycle_status='active' ORDER BY i.created_at, i.game_id LIMIT 100",
        )
        .bind(actor)
        .fetch_all(&self.pool)
        .await
        .map_err(CommitError::storage)?;
        Ok(rows
            .into_iter()
            .map(|row| PlayerInvitation {
                game_id: row.get("game_id"),
                invitation_id: row.get("invitation_id"),
                invited_by: row.get("invited_by"),
                committed_revision: u64::try_from(row.get::<i64, _>("head_revision"))
                    .expect("revision is non-negative"),
                canonical_state_hash: row.get("canonical_state_hash"),
            })
            .collect())
    }

    pub(super) async fn require_pending_player_invitation(
        &self,
        game_id: Uuid,
        actor: Uuid,
    ) -> Result<(), CommitError> {
        let pending: bool = sqlx::query_scalar(
            "SELECT EXISTS(SELECT 1 FROM game_player_invitations WHERE game_id=$1 AND invited_account_id=$2 AND consumed_at IS NULL)",
        )
        .bind(game_id)
        .bind(actor)
        .fetch_one(&self.pool)
        .await
        .map_err(CommitError::storage)?;
        if pending {
            Ok(())
        } else {
            Err(CommitError::Unauthorized)
        }
    }
}
