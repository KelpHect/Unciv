use super::*;

#[tokio::test]
#[ignore = "requires an explicit UNCIV_V3_DATABASE_URL"]
async fn repair_backfills_only_derived_outbox_and_quarantines_canonical_damage() {
    let repository = PostgresGameRepository::connect(&database_url())
        .await
        .unwrap();
    repository.migrate().await.unwrap();
    let (owner, game) = seed_repository(&repository).await;
    repository
        .commit(
            owner,
            command(game, Uuid::new_v4(), 0),
            proposal(0, b"revision-1"),
        )
        .await
        .unwrap();
    sqlx::query(
        "DELETE FROM game_outbox
         WHERE game_id=$1 AND revision=1 AND topic='game.revision.committed'",
    )
    .bind(game)
    .execute(&repository.pool)
    .await
    .unwrap();

    let preview = repository
        .repair_authoritative_game(game, true)
        .await
        .unwrap();
    assert_eq!(preview.outbox_events_backfilled, 1);
    assert!(!preview.quarantine_required);
    assert_eq!(
        sqlx::query_scalar::<_, i64>("SELECT count(*) FROM game_repair_events WHERE game_id=$1")
            .bind(game)
            .fetch_one(&repository.pool)
            .await
            .unwrap(),
        0
    );

    let applied = repository
        .repair_authoritative_game(game, false)
        .await
        .unwrap();
    assert_eq!(applied.outbox_events_backfilled, 1);
    assert!(!applied.quarantined);
    assert_eq!(
        sqlx::query_scalar::<_, i64>(
            "SELECT count(*) FROM game_outbox
             WHERE game_id=$1 AND revision=1 AND topic='game.revision.committed'",
        )
        .bind(game)
        .fetch_one(&repository.pool)
        .await
        .unwrap(),
        1
    );
    assert_eq!(
        sqlx::query_scalar::<_, i64>(
            "SELECT count(*) FROM game_repair_events
             WHERE game_id=$1 AND action='outbox_backfill' AND revision=1",
        )
        .bind(game)
        .fetch_one(&repository.pool)
        .await
        .unwrap(),
        1
    );
    assert_eq!(
        repository
            .repair_authoritative_game(game, false)
            .await
            .unwrap()
            .outbox_events_backfilled,
        0
    );

    sqlx::query("DELETE FROM game_snapshot_blobs WHERE game_id=$1 AND revision=1")
        .bind(game)
        .execute(&repository.pool)
        .await
        .unwrap();
    let damage_preview = repository
        .repair_authoritative_game(game, true)
        .await
        .unwrap();
    assert!(damage_preview.quarantine_required);
    assert!(!damage_preview.quarantined);
    let contained = repository
        .repair_authoritative_game(game, false)
        .await
        .unwrap();
    assert!(contained.quarantined);
    assert_eq!(
        sqlx::query_scalar::<_, Option<String>>(
            "SELECT unavailable_reason FROM games WHERE id=$1",
        )
        .bind(game)
        .fetch_one(&repository.pool)
        .await
        .unwrap()
        .as_deref(),
        Some("reconciliation_required")
    );
    assert_eq!(
        sqlx::query_scalar::<_, i64>(
            "SELECT count(*) FROM game_repair_events
             WHERE game_id=$1 AND action='quarantine'",
        )
        .bind(game)
        .fetch_one(&repository.pool)
        .await
        .unwrap(),
        1
    );
    assert_eq!(
        repository
            .commit(
                owner,
                command(game, Uuid::new_v4(), 1),
                proposal(1, b"must-not-commit"),
            )
            .await,
        Err(CommitError::GameUnavailable)
    );
}
