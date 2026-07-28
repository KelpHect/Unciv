use super::*;

const MAX_FRIENDS: i64 = 200;
const MAX_PENDING_REQUESTS: i64 = 100;

impl PostgresGameRepository {
    pub async fn list_social_graph(&self, actor: Uuid) -> Result<SocialGraph, CommitError> {
        let friend_rows = sqlx::query(
            "SELECT a.username_normalized AS username
             FROM social_friendships f
             JOIN accounts a ON a.id=CASE WHEN f.account_low=$1 THEN f.account_high ELSE f.account_low END
             WHERE (f.account_low=$1 OR f.account_high=$1) AND a.disabled_at IS NULL
             ORDER BY a.username_normalized
             LIMIT 201",
        )
        .bind(actor)
        .fetch_all(&self.pool)
        .await
        .map_err(CommitError::storage)?;
        let request_rows = sqlx::query(
            "SELECT r.request_id,
                    CASE WHEN r.requester_account_id=$1 THEN recipient.username_normalized
                         ELSE requester.username_normalized END AS username,
                    CASE WHEN r.requester_account_id=$1 THEN 'outgoing' ELSE 'incoming' END AS direction
             FROM social_friend_requests r
             JOIN accounts requester ON requester.id=r.requester_account_id
             JOIN accounts recipient ON recipient.id=r.recipient_account_id
             WHERE r.requester_account_id=$1 OR r.recipient_account_id=$1
             ORDER BY r.created_at, r.request_id
             LIMIT 201",
        )
        .bind(actor)
        .fetch_all(&self.pool)
        .await
        .map_err(CommitError::storage)?;
        if friend_rows.len() > MAX_FRIENDS as usize
            || request_rows.len() > (MAX_PENDING_REQUESTS * 2) as usize
        {
            return Err(CommitError::Storage);
        }
        Ok(SocialGraph {
            friends: friend_rows
                .into_iter()
                .map(|row| FriendSummary {
                    username: row.get("username"),
                })
                .collect(),
            requests: request_rows
                .into_iter()
                .map(|row| FriendRequestSummary {
                    request_id: row.get("request_id"),
                    username: row.get("username"),
                    direction: row.get("direction"),
                })
                .collect(),
        })
    }

    pub async fn request_friendship(
        &self,
        actor: Uuid,
        request_id: Uuid,
        username: &str,
    ) -> Result<(), CommitError> {
        let username = normalize_username(username).map_err(|_| CommitError::InvalidCommand)?;
        if request_id.is_nil() {
            return Err(CommitError::InvalidCommand);
        }
        let mut tx = self.pool.begin().await.map_err(CommitError::storage)?;
        if let Some(row) = sqlx::query(
            "SELECT requester_account_id, recipient_account_id
             FROM social_friend_requests WHERE request_id=$1",
        )
        .bind(request_id)
        .fetch_optional(&mut *tx)
        .await
        .map_err(CommitError::storage)?
        {
            let target: Option<Uuid> = sqlx::query_scalar(
                "SELECT id FROM accounts WHERE username_normalized=$1 AND disabled_at IS NULL",
            )
            .bind(&username)
            .fetch_optional(&mut *tx)
            .await
            .map_err(CommitError::storage)?;
            if row.get::<Uuid, _>("requester_account_id") == actor
                && Some(row.get::<Uuid, _>("recipient_account_id")) == target
            {
                return tx.commit().await.map_err(CommitError::storage);
            }
            return Err(CommitError::IdempotencyConflict);
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
        lock_social_pair(&mut tx, actor, target).await?;
        if friendship_exists(&mut tx, actor, target).await? {
            return Err(CommitError::InvalidCommand);
        }
        let outgoing: i64 = sqlx::query_scalar(
            "SELECT count(*) FROM social_friend_requests WHERE requester_account_id=$1",
        )
        .bind(actor)
        .fetch_one(&mut *tx)
        .await
        .map_err(CommitError::storage)?;
        let incoming: i64 = sqlx::query_scalar(
            "SELECT count(*) FROM social_friend_requests WHERE recipient_account_id=$1",
        )
        .bind(target)
        .fetch_one(&mut *tx)
        .await
        .map_err(CommitError::storage)?;
        if outgoing >= MAX_PENDING_REQUESTS || incoming >= MAX_PENDING_REQUESTS {
            return Err(CommitError::InvalidCommand);
        }
        let inserted = sqlx::query(
            "INSERT INTO social_friend_requests
             (request_id, requester_account_id, recipient_account_id)
             VALUES ($1, $2, $3) ON CONFLICT DO NOTHING",
        )
        .bind(request_id)
        .bind(actor)
        .bind(target)
        .execute(&mut *tx)
        .await
        .map_err(CommitError::storage)?;
        if inserted.rows_affected() != 1 {
            return Err(CommitError::InvalidCommand);
        }
        tx.commit().await.map_err(CommitError::storage)
    }

    pub async fn accept_friendship(
        &self,
        actor: Uuid,
        request_id: Uuid,
    ) -> Result<(), CommitError> {
        let mut tx = self.pool.begin().await.map_err(CommitError::storage)?;
        let request = sqlx::query(
            "SELECT requester_account_id, recipient_account_id
             FROM social_friend_requests WHERE request_id=$1 FOR UPDATE",
        )
        .bind(request_id)
        .fetch_optional(&mut *tx)
        .await
        .map_err(CommitError::storage)?
        .ok_or(CommitError::NotFound)?;
        let requester: Uuid = request.get("requester_account_id");
        let recipient: Uuid = request.get("recipient_account_id");
        if recipient != actor {
            return Err(CommitError::Unauthorized);
        }
        lock_social_pair(&mut tx, requester, recipient).await?;
        for account in [requester, recipient] {
            let count: i64 = sqlx::query_scalar(
                "SELECT count(*) FROM social_friendships
                 WHERE account_low=$1 OR account_high=$1",
            )
            .bind(account)
            .fetch_one(&mut *tx)
            .await
            .map_err(CommitError::storage)?;
            if count >= MAX_FRIENDS {
                return Err(CommitError::InvalidCommand);
            }
        }
        let (low, high) = ordered_pair(requester, recipient);
        sqlx::query(
            "INSERT INTO social_friendships (account_low, account_high)
             VALUES ($1, $2) ON CONFLICT DO NOTHING",
        )
        .bind(low)
        .bind(high)
        .execute(&mut *tx)
        .await
        .map_err(CommitError::storage)?;
        sqlx::query("DELETE FROM social_friend_requests WHERE request_id=$1")
            .bind(request_id)
            .execute(&mut *tx)
            .await
            .map_err(CommitError::storage)?;
        tx.commit().await.map_err(CommitError::storage)
    }

    pub async fn remove_friend_request(
        &self,
        actor: Uuid,
        request_id: Uuid,
    ) -> Result<(), CommitError> {
        let deleted = sqlx::query(
            "DELETE FROM social_friend_requests
             WHERE request_id=$1
               AND (requester_account_id=$2 OR recipient_account_id=$2)",
        )
        .bind(request_id)
        .bind(actor)
        .execute(&self.pool)
        .await
        .map_err(CommitError::storage)?;
        if deleted.rows_affected() == 1 {
            Ok(())
        } else {
            Err(CommitError::NotFound)
        }
    }

    pub async fn remove_friendship(&self, actor: Uuid, username: &str) -> Result<(), CommitError> {
        let username = normalize_username(username).map_err(|_| CommitError::InvalidCommand)?;
        let target: Option<Uuid> =
            sqlx::query_scalar("SELECT id FROM accounts WHERE username_normalized=$1")
                .bind(username)
                .fetch_optional(&self.pool)
                .await
                .map_err(CommitError::storage)?;
        let target = target.ok_or(CommitError::NotFound)?;
        let (low, high) = ordered_pair(actor, target);
        let deleted =
            sqlx::query("DELETE FROM social_friendships WHERE account_low=$1 AND account_high=$2")
                .bind(low)
                .bind(high)
                .execute(&self.pool)
                .await
                .map_err(CommitError::storage)?;
        if deleted.rows_affected() == 1 {
            Ok(())
        } else {
            Err(CommitError::NotFound)
        }
    }
}

async fn lock_social_pair(
    tx: &mut sqlx::Transaction<'_, sqlx::Postgres>,
    first: Uuid,
    second: Uuid,
) -> Result<(), CommitError> {
    let (low, high) = ordered_pair(first, second);
    sqlx::query("SELECT pg_advisory_xact_lock(hashtextextended($1, 0))")
        .bind(format!("unciv-v3-social:{low}:{high}"))
        .execute(&mut **tx)
        .await
        .map_err(CommitError::storage)?;
    Ok(())
}

async fn friendship_exists(
    tx: &mut sqlx::Transaction<'_, sqlx::Postgres>,
    first: Uuid,
    second: Uuid,
) -> Result<bool, CommitError> {
    let (low, high) = ordered_pair(first, second);
    sqlx::query_scalar(
        "SELECT EXISTS(
            SELECT 1 FROM social_friendships WHERE account_low=$1 AND account_high=$2
         )",
    )
    .bind(low)
    .bind(high)
    .fetch_one(&mut **tx)
    .await
    .map_err(CommitError::storage)
}

fn ordered_pair(first: Uuid, second: Uuid) -> (Uuid, Uuid) {
    if first < second {
        (first, second)
    } else {
        (second, first)
    }
}
