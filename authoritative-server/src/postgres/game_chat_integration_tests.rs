use super::*;

#[tokio::test]
#[ignore = "requires an explicit UNCIV_V3_DATABASE_URL"]
async fn game_chat_is_membership_scoped_retry_safe_paginated_and_retained() {
    let repository = PostgresGameRepository::connect(&database_url())
        .await
        .unwrap();
    repository.migrate().await.unwrap();
    let (owner, game) = seed_repository(&repository).await;
    let player = Uuid::new_v4();
    let outsider = Uuid::new_v4();
    for (account, username) in [(player, "chat-player"), (outsider, "chat-outsider")] {
        sqlx::query(
            "INSERT INTO accounts (id, username_normalized, password_hash)
             VALUES ($1, $2, 'test-hash')",
        )
        .bind(account)
        .bind(username)
        .execute(&repository.pool)
        .await
        .unwrap();
    }
    sqlx::query(
        "INSERT INTO game_members
         (game_id, account_id, role, civilization_id)
         VALUES ($1, $2, 'player', 'chat-civilization')",
    )
    .bind(game)
    .bind(player)
    .execute(&repository.pool)
    .await
    .unwrap();

    let first = Uuid::new_v4();
    repository
        .post_game_chat(owner, game, first, "  hello player  ")
        .await
        .unwrap();
    repository
        .post_game_chat(owner, game, first, "hello player")
        .await
        .unwrap();
    assert_eq!(
        repository
            .post_game_chat(owner, game, first, "changed meaning")
            .await
            .unwrap_err(),
        CommitError::IdempotencyConflict
    );
    assert_eq!(
        repository
            .post_game_chat(outsider, game, Uuid::new_v4(), "forbidden")
            .await
            .unwrap_err(),
        CommitError::Unauthorized
    );
    assert_eq!(
        repository
            .list_game_chat(outsider, game, None, 50)
            .await
            .unwrap_err(),
        CommitError::Unauthorized
    );
    assert_eq!(
        repository
            .post_game_chat(player, game, Uuid::new_v4(), "\u{0000}")
            .await
            .unwrap_err(),
        CommitError::InvalidCommand
    );

    let second = Uuid::new_v4();
    repository
        .post_game_chat(player, game, second, "reply")
        .await
        .unwrap();
    sqlx::query(
        "UPDATE game_chat_messages SET created_at =
         CASE WHEN message_id=$2 THEN now() - interval '1 minute' ELSE now() END
         WHERE game_id=$1",
    )
    .bind(game)
    .bind(first)
    .execute(&repository.pool)
    .await
    .unwrap();
    let newest = repository
        .list_game_chat(owner, game, None, 1)
        .await
        .unwrap();
    assert_eq!(newest.messages[0].message_id, second);
    assert_eq!(newest.next_cursor, Some(second));
    let older = repository
        .list_game_chat(player, game, newest.next_cursor, 1)
        .await
        .unwrap();
    assert_eq!(older.messages[0].message_id, first);
    assert_eq!(older.messages[0].body, "hello player");
    assert_eq!(
        older.messages[0].sender_username,
        format!("account-{owner}")
    );
    assert_eq!(
        repository
            .list_game_chat(owner, game, Some(Uuid::new_v4()), 1)
            .await
            .unwrap_err(),
        CommitError::InvalidCommand
    );

    sqlx::query(
        "INSERT INTO game_chat_messages
         (game_id, message_id, sender_account_id, body, created_at)
         SELECT $1, gen_random_uuid(), $2, 'retained', now() - make_interval(secs => n)
         FROM generate_series(1, 501) AS n",
    )
    .bind(game)
    .bind(owner)
    .execute(&repository.pool)
    .await
    .unwrap();
    repository
        .post_game_chat(owner, game, Uuid::new_v4(), "retention trigger")
        .await
        .unwrap();
    let retained: i64 =
        sqlx::query_scalar("SELECT count(*) FROM game_chat_messages WHERE game_id=$1")
            .bind(game)
            .fetch_one(&repository.pool)
            .await
            .unwrap();
    assert_eq!(retained, 500);
}
