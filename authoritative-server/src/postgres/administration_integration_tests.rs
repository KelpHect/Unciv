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

#[tokio::test]
#[ignore = "requires an explicit UNCIV_V3_DATABASE_URL"]
async fn ownership_and_lifecycle_operations_are_idempotent_authorized_and_gate_commands() {
    let repository = PostgresGameRepository::connect(&database_url())
        .await
        .unwrap();
    repository.migrate().await.unwrap();
    let (owner, game) = seed_repository(&repository).await;
    let successor = Uuid::new_v4();
    sqlx::query("INSERT INTO accounts (id, username_normalized, password_hash) VALUES ($1, 'successor', 'test-hash')")
        .bind(successor)
        .execute(&repository.pool)
        .await
        .unwrap();
    sqlx::query("INSERT INTO game_members (game_id, account_id, role, civilization_id) VALUES ($1, $2, 'player', 'Greece')")
        .bind(game)
        .bind(successor)
        .execute(&repository.pool)
        .await
        .unwrap();

    let transfer_id = Uuid::new_v4();
    repository
        .transfer_ownership(owner, game, transfer_id, " Successor ")
        .await
        .unwrap();
    repository
        .transfer_ownership(owner, game, transfer_id, "successor")
        .await
        .unwrap();
    assert!(matches!(
        repository
            .transfer_ownership(owner, game, transfer_id, "different")
            .await,
        Err(CommitError::InvalidCommand)
    ));
    let roles: Vec<(Uuid, String)> = sqlx::query_as(
        "SELECT account_id, role FROM game_members WHERE game_id=$1 ORDER BY account_id",
    )
    .bind(game)
    .fetch_all(&repository.pool)
    .await
    .unwrap();
    assert!(roles.contains(&(owner, "player".to_owned())));
    assert!(roles.contains(&(successor, "owner".to_owned())));

    let close_id = Uuid::new_v4();
    assert!(matches!(
        repository.close_game(owner, game, close_id).await,
        Err(CommitError::Unauthorized)
    ));
    repository
        .close_game(successor, game, close_id)
        .await
        .unwrap();
    repository
        .close_game(successor, game, close_id)
        .await
        .unwrap();
    assert!(matches!(
        repository.worker_command_state(game).await,
        Err(CommitError::InvalidCommand)
    ));
    assert!(matches!(
        repository
            .commit(
                successor,
                command(game, Uuid::new_v4(), 0),
                proposal(0, b"forbidden")
            )
            .await,
        Err(CommitError::InvalidCommand)
    ));
    let closed = repository.game_metadata(owner, game).await.unwrap();
    assert_eq!(closed.lifecycle_status, "closed");
    assert_eq!(closed.committed_revision, 0);

    let archive_id = Uuid::new_v4();
    repository
        .archive_game(successor, game, archive_id)
        .await
        .unwrap();
    repository
        .archive_game(successor, game, archive_id)
        .await
        .unwrap();
    let archived = repository.game_metadata(owner, game).await.unwrap();
    assert_eq!(archived.lifecycle_status, "archived");
    let page = repository.list_games(owner, None, 10).await.unwrap();
    assert_eq!(page.games[0].lifecycle_status, "archived");
    assert!(!page.games[0].available);
    let unreachable_worker =
        EngineWorkerClient::new("127.0.0.1:1".parse().unwrap(), Duration::from_millis(50));
    assert!(matches!(
        repository
            .game_projection(&unreachable_worker, owner, game)
            .await,
        Err(CommitError::InvalidCommand)
    ));
    assert!(matches!(
        repository.close_game(successor, game, Uuid::new_v4()).await,
        Err(CommitError::InvalidCommand)
    ));
    let operation_count: i64 =
        sqlx::query_scalar("SELECT count(*) FROM game_admin_operations WHERE game_id=$1")
            .bind(game)
            .fetch_one(&repository.pool)
            .await
            .unwrap();
    assert_eq!(operation_count, 3);
    let lifecycle_outbox_count: i64 = sqlx::query_scalar(
        "SELECT count(*) FROM game_outbox WHERE game_id=$1 AND topic IN ('game.membership.changed', 'game.lifecycle.changed')",
    )
    .bind(game)
    .fetch_one(&repository.pool)
    .await
    .unwrap();
    assert_eq!(lifecycle_outbox_count, 3);
    let claimed = repository.claim_outbox_batch(10).await.unwrap();
    assert_eq!(claimed.len(), 3);
    assert_eq!(
        claimed
            .iter()
            .map(|event| event.topic.as_str())
            .collect::<Vec<_>>(),
        vec![
            "game.membership.changed",
            "game.lifecycle.changed",
            "game.lifecycle.changed",
        ]
    );
    for event in claimed {
        assert_eq!(
            repository
                .outbox_state_hash(event.game_id, event.revision)
                .await
                .unwrap(),
            state_hash(b"revision-0"),
        );
        repository
            .acknowledge_outbox(event.id, event.claim_token)
            .await
            .unwrap();
    }
}
