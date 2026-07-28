use super::*;

#[tokio::test]
#[ignore = "requires an explicit UNCIV_V3_DATABASE_URL"]
async fn cross_scope_stale_and_changed_id_attacks_preserve_both_canonical_heads() {
    let repository = PostgresGameRepository::connect(&database_url())
        .await
        .unwrap();
    repository.migrate().await.unwrap();
    let (owner, game) = seed_repository(&repository).await;

    let other_owner = Uuid::new_v4();
    let other_game = Uuid::new_v4();
    sqlx::query(
        "INSERT INTO accounts (id, username_normalized, password_hash)
         VALUES ($1, $2, 'test-hash')",
    )
    .bind(other_owner)
    .bind(format!("malicious-suite-{other_owner}"))
    .execute(&repository.pool)
    .await
    .unwrap();
    repository
        .create_game(NewGame {
            game_id: other_game,
            owner_account_id: other_owner,
            ruleset_manifest_hash: "a".repeat(64),
            snapshot: b"other-revision-0".to_vec(),
            owner_civilization_id: "other-civilization".to_owned(),
        })
        .await
        .unwrap();

    let command_id = Uuid::new_v4();
    let accepted = repository
        .commit(
            owner,
            command(game, command_id, 0),
            proposal(0, b"revision-1"),
        )
        .await
        .unwrap();
    let exact_retry_with_new_diagnostic = repository
        .commit(
            owner,
            CommandEnvelope {
                client_observed_state_hash: Some("diagnostic-is-not-authority".to_owned()),
                ..command(game, command_id, 0)
            },
            proposal(0, b"must-not-replace"),
        )
        .await
        .unwrap();
    let changed_meaning = repository
        .commit(
            owner,
            CommandEnvelope {
                command: GameCommand::Resign {},
                ..command(game, command_id, 0)
            },
            proposal(0, b"changed-meaning"),
        )
        .await
        .unwrap_err();
    let cross_account = repository
        .commit(
            other_owner,
            command(game, command_id, 0),
            proposal(0, b"cross-account"),
        )
        .await
        .unwrap_err();
    let cross_game = repository
        .commit(
            owner,
            command(other_game, Uuid::new_v4(), 0),
            proposal(0, b"cross-game"),
        )
        .await
        .unwrap_err();
    let reordered_stale = repository
        .commit(
            owner,
            command(game, Uuid::new_v4(), 0),
            proposal(0, b"stale-replacement"),
        )
        .await
        .unwrap_err();

    assert_eq!(exact_retry_with_new_diagnostic, accepted);
    assert_eq!(changed_meaning, CommitError::IdempotencyConflict);
    assert_eq!(cross_account, CommitError::Unauthorized);
    assert_eq!(cross_game, CommitError::Unauthorized);
    assert_eq!(
        reordered_stale,
        CommitError::Stale {
            expected: 0,
            actual: 1,
        }
    );

    let heads: Vec<(Uuid, i64)> = sqlx::query_as("SELECT id, head_revision FROM games ORDER BY id")
        .fetch_all(&repository.pool)
        .await
        .unwrap();
    assert_eq!(
        heads
            .iter()
            .find(|(id, _)| *id == game)
            .map(|(_, revision)| *revision),
        Some(1),
    );
    assert_eq!(
        heads
            .iter()
            .find(|(id, _)| *id == other_game)
            .map(|(_, revision)| *revision),
        Some(0),
    );
    let persisted: (i64, i64, i64) = sqlx::query_as(
        "SELECT
           (SELECT count(*) FROM game_commands WHERE game_id=$1),
           (SELECT count(*) FROM game_revisions WHERE game_id=$1),
           (SELECT count(*) FROM game_outbox WHERE game_id=$1)",
    )
    .bind(game)
    .fetch_one(&repository.pool)
    .await
    .unwrap();
    assert_eq!(persisted, (1, 2, 1));
}
