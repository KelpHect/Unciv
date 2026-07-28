use super::*;

const MAX_CHAT_MESSAGES_PER_GAME: i64 = 500;
const MAX_CHAT_MESSAGE_BYTES: usize = 1000;
const MAX_CHAT_PAGE: i64 = 100;

impl PostgresGameRepository {
    pub async fn list_game_chat(
        &self,
        actor: Uuid,
        game_id: Uuid,
        before: Option<Uuid>,
        limit: u32,
    ) -> Result<GameChatPage, CommitError> {
        if limit == 0 || i64::from(limit) > MAX_CHAT_PAGE {
            return Err(CommitError::InvalidCommand);
        }
        require_game_member(&self.pool, actor, game_id).await?;
        let rows = sqlx::query(
            "SELECT m.message_id, a.username_normalized AS sender_username, m.body,
                    (extract(epoch FROM m.created_at) * 1000)::bigint AS created_at_millis
             FROM game_chat_messages m
             JOIN accounts a ON a.id=m.sender_account_id
             WHERE m.game_id=$1
               AND (
                   $2::uuid IS NULL OR
                   (m.created_at, m.message_id) < (
                       SELECT cursor.created_at, cursor.message_id
                       FROM game_chat_messages cursor
                       WHERE cursor.game_id=$1 AND cursor.message_id=$2
                   )
               )
             ORDER BY m.created_at DESC, m.message_id DESC
             LIMIT $3",
        )
        .bind(game_id)
        .bind(before)
        .bind(i64::from(limit) + 1)
        .fetch_all(&self.pool)
        .await
        .map_err(CommitError::storage)?;
        if before.is_some() && rows.is_empty() {
            let cursor_exists: bool = sqlx::query_scalar(
                "SELECT EXISTS(
                    SELECT 1 FROM game_chat_messages WHERE game_id=$1 AND message_id=$2
                 )",
            )
            .bind(game_id)
            .bind(before)
            .fetch_one(&self.pool)
            .await
            .map_err(CommitError::storage)?;
            if !cursor_exists {
                return Err(CommitError::InvalidCommand);
            }
        }
        let has_more = rows.len() > limit as usize;
        let mut messages = rows
            .into_iter()
            .take(limit as usize)
            .map(|row| GameChatMessage {
                message_id: row.get("message_id"),
                sender_username: row.get("sender_username"),
                body: row.get("body"),
                created_at_millis: row.get("created_at_millis"),
            })
            .collect::<Vec<_>>();
        let next_cursor = has_more.then(|| {
            messages
                .last()
                .expect("a page with more rows is non-empty")
                .message_id
        });
        messages.reverse();
        Ok(GameChatPage {
            messages,
            next_cursor,
        })
    }

    pub async fn post_game_chat(
        &self,
        actor: Uuid,
        game_id: Uuid,
        message_id: Uuid,
        body: &str,
    ) -> Result<(), CommitError> {
        let body = validate_chat_body(body)?;
        if message_id.is_nil() {
            return Err(CommitError::InvalidCommand);
        }
        let mut tx = self.pool.begin().await.map_err(CommitError::storage)?;
        sqlx::query("SELECT pg_advisory_xact_lock(hashtextextended($1, 0))")
            .bind(format!("unciv-v3-game-chat:{game_id}"))
            .execute(&mut *tx)
            .await
            .map_err(CommitError::storage)?;
        require_game_member(&mut *tx, actor, game_id).await?;
        if let Some(row) = sqlx::query(
            "SELECT sender_account_id, body FROM game_chat_messages
             WHERE game_id=$1 AND message_id=$2",
        )
        .bind(game_id)
        .bind(message_id)
        .fetch_optional(&mut *tx)
        .await
        .map_err(CommitError::storage)?
        {
            if row.get::<Uuid, _>("sender_account_id") == actor
                && row.get::<String, _>("body") == body
            {
                return tx.commit().await.map_err(CommitError::storage);
            }
            return Err(CommitError::IdempotencyConflict);
        }
        sqlx::query(
            "INSERT INTO game_chat_messages
             (game_id, message_id, sender_account_id, body)
             VALUES ($1, $2, $3, $4)",
        )
        .bind(game_id)
        .bind(message_id)
        .bind(actor)
        .bind(&body)
        .execute(&mut *tx)
        .await
        .map_err(CommitError::storage)?;
        sqlx::query(
            "DELETE FROM game_chat_messages
             WHERE game_id=$1 AND message_id IN (
                 SELECT message_id FROM game_chat_messages
                 WHERE game_id=$1
                 ORDER BY created_at DESC, message_id DESC
                 OFFSET $2
             )",
        )
        .bind(game_id)
        .bind(MAX_CHAT_MESSAGES_PER_GAME)
        .execute(&mut *tx)
        .await
        .map_err(CommitError::storage)?;
        tx.commit().await.map_err(CommitError::storage)
    }
}

async fn require_game_member<'e, E>(
    executor: E,
    actor: Uuid,
    game_id: Uuid,
) -> Result<(), CommitError>
where
    E: sqlx::Executor<'e, Database = sqlx::Postgres>,
{
    let membership: Option<bool> = sqlx::query_scalar(
        "SELECT EXISTS(
            SELECT 1 FROM game_members WHERE game_id=$1 AND account_id=$2
         )
         FROM games WHERE id=$1",
    )
    .bind(game_id)
    .bind(actor)
    .fetch_optional(executor)
    .await
    .map_err(CommitError::storage)?;
    match membership {
        None => Err(CommitError::NotFound),
        Some(false) => Err(CommitError::Unauthorized),
        Some(true) => Ok(()),
    }
}

fn validate_chat_body(body: &str) -> Result<String, CommitError> {
    let body = body.trim();
    if body.is_empty()
        || body.len() > MAX_CHAT_MESSAGE_BYTES
        || body
            .chars()
            .any(|character| character.is_control() && character != '\n' && character != '\t')
    {
        return Err(CommitError::InvalidCommand);
    }
    Ok(body.to_owned())
}
