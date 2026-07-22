use super::*;

#[tokio::test]
#[ignore = "requires an explicit UNCIV_V3_DATABASE_URL"]
async fn owner_kick_atomically_commits_and_removes_only_the_player_membership() {
    let repository = PostgresGameRepository::connect(&database_url())
        .await
        .unwrap();
    repository.migrate().await.unwrap();
    let (owner, game) = seed_repository(&repository).await;
    let target = Uuid::new_v4();
    sqlx::query("INSERT INTO accounts (id, username_normalized, password_hash) VALUES ($1, 'kick-target', 'test-hash')")
        .bind(target)
        .execute(&repository.pool)
        .await
        .unwrap();
    sqlx::query("INSERT INTO game_members (game_id, account_id, role, civilization_id) VALUES ($1, $2, 'player', 'Greece')")
        .bind(game)
        .bind(target)
        .execute(&repository.pool)
        .await
        .unwrap();
    let command_id = Uuid::new_v4();
    let envelope = CommandEnvelope {
        command: GameCommand::KickMember {},
        ..command(game, command_id, 0)
    };
    let unreachable_worker =
        EngineWorkerClient::new("127.0.0.1:1".parse().unwrap(), Duration::from_millis(50));
    let unauthorized = repository
        .execute_kick_member(
            &unreachable_worker,
            target,
            CommandEnvelope {
                command: GameCommand::KickMember {},
                ..command(game, Uuid::new_v4(), 0)
            },
            "kick-target",
        )
        .await;
    assert!(
        matches!(unauthorized, Err(CommitError::Unauthorized)),
        "unexpected non-owner result: {unauthorized:?}"
    );

    let accepted = repository
        .commit_civilization_removal(
            owner,
            envelope.clone(),
            proposal(0, b"kick-revision"),
            "Greece".to_owned(),
        )
        .await
        .unwrap();
    let duplicate = repository
        .commit_civilization_removal(
            owner,
            envelope,
            proposal(0, b"must-not-replace"),
            "Greece".to_owned(),
        )
        .await
        .unwrap();

    assert_eq!(accepted, duplicate);
    let memberships: Vec<(Uuid, String)> =
        sqlx::query_as("SELECT account_id, role FROM game_members WHERE game_id=$1 ORDER BY role")
            .bind(game)
            .fetch_all(&repository.pool)
            .await
            .unwrap();
    assert_eq!(memberships, vec![(owner, "owner".to_owned())]);
    let head_revision: i64 = sqlx::query_scalar("SELECT head_revision FROM games WHERE id=$1")
        .bind(game)
        .fetch_one(&repository.pool)
        .await
        .unwrap();
    assert_eq!(head_revision, 1);
}
