use super::*;

#[tokio::test]
#[ignore = "requires an explicit UNCIV_V3_DATABASE_URL"]
async fn membership_is_owner_controlled_revocable_and_player_projection_denied() {
    let repository = PostgresGameRepository::connect(&database_url())
        .await
        .unwrap();
    repository.migrate().await.unwrap();
    let (owner, game) = seed_repository(&repository).await;
    let spectator = Uuid::new_v4();
    sqlx::query("INSERT INTO accounts (id, username_normalized, password_hash) VALUES ($1, 'spectator_name', 'test-hash')")
        .bind(spectator)
        .execute(&repository.pool)
        .await
        .unwrap();

    repository
        .add_spectator(owner, game, " Spectator_Name ")
        .await
        .unwrap();
    repository
        .add_spectator(owner, game, "spectator_name")
        .await
        .unwrap();
    let metadata = repository.game_metadata(spectator, game).await.unwrap();
    assert_eq!(metadata.role, "spectator");
    assert_eq!(metadata.civilization_id, None);
    let worker = EngineWorkerClient::new(
        "127.0.0.1:9".parse().unwrap(),
        Duration::from_millis(10),
        crate::worker::WorkerIdentityKey::for_test(),
    );
    assert_eq!(
        repository
            .game_projection(&worker, spectator, game)
            .await
            .unwrap_err(),
        CommitError::Unauthorized,
    );
    let membership_events: i64 = sqlx::query_scalar(
        "SELECT count(*) FROM game_outbox WHERE game_id=$1 AND topic='game.membership.changed'",
    )
    .bind(game)
    .fetch_one(&repository.pool)
    .await
    .unwrap();
    assert_eq!(membership_events, 1);

    let outsider = Uuid::new_v4();
    sqlx::query("INSERT INTO accounts (id, username_normalized, password_hash) VALUES ($1, 'spectator_outsider', 'test-hash')")
        .bind(outsider)
        .execute(&repository.pool)
        .await
        .unwrap();
    assert_eq!(
        repository
            .revoke_spectator(outsider, game, Uuid::new_v4(), "spectator_name")
            .await
            .unwrap_err(),
        CommitError::Unauthorized,
    );
    let revocation_id = Uuid::new_v4();
    repository
        .revoke_spectator(owner, game, revocation_id, " Spectator_Name ")
        .await
        .unwrap();
    repository
        .revoke_spectator(owner, game, revocation_id, "spectator_name")
        .await
        .unwrap();
    assert_eq!(
        repository.game_metadata(spectator, game).await.unwrap_err(),
        CommitError::Unauthorized,
    );
    assert_eq!(
        repository
            .revoke_spectator(owner, game, revocation_id, "changed_name")
            .await
            .unwrap_err(),
        CommitError::InvalidCommand,
    );
    assert_eq!(
        repository
            .revoke_spectator(outsider, game, revocation_id, "spectator_name")
            .await
            .unwrap_err(),
        CommitError::InvalidCommand,
    );
    let revocation_operations: i64 = sqlx::query_scalar(
        "SELECT count(*) FROM game_admin_operations WHERE game_id=$1 AND operation_kind='revoke_spectator'",
    )
    .bind(game)
    .fetch_one(&repository.pool)
    .await
    .unwrap();
    let membership_events: i64 = sqlx::query_scalar(
        "SELECT count(*) FROM game_outbox WHERE game_id=$1 AND topic='game.membership.changed'",
    )
    .bind(game)
    .fetch_one(&repository.pool)
    .await
    .unwrap();
    assert_eq!(revocation_operations, 1);
    assert_eq!(membership_events, 2);

    repository
        .add_spectator(owner, game, "spectator_name")
        .await
        .unwrap();
    repository.leave_spectator(spectator, game).await.unwrap();
    assert_eq!(
        repository
            .leave_spectator(spectator, game)
            .await
            .unwrap_err(),
        CommitError::Unauthorized,
    );
}
