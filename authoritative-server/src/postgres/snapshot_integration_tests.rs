use super::*;

#[tokio::test]
#[ignore = "requires an explicit UNCIV_V3_DATABASE_URL"]
async fn corrupt_canonical_snapshot_quarantines_game_without_advancing_head() {
    let repository = PostgresGameRepository::connect(&database_url())
        .await
        .unwrap();
    repository.migrate().await.unwrap();
    let (account, game) = seed_repository(&repository).await;
    repository.validate_canonical_head(game).await.unwrap();

    let stored: (String, i64, i64, String, Vec<u8>) = sqlx::query_as(
        "SELECT codec, compressed_size, uncompressed_size, canonical_state_hash, payload FROM game_snapshots WHERE game_id=$1 AND revision=0",
    )
    .bind(game)
    .fetch_one(&repository.pool)
    .await
    .unwrap();
    assert_eq!(stored.0, "zstd");
    assert_eq!(stored.1, stored.4.len() as i64);
    assert_eq!(stored.2, b"revision-0".len() as i64);
    assert_eq!(stored.3, state_hash(b"revision-0"));

    let invalid_zstd = vec![0_u8];
    sqlx::query("UPDATE game_snapshots SET payload=$2, compressed_size=1, payload_hash=$3 WHERE game_id=$1 AND revision=0")
        .bind(game)
        .bind(&invalid_zstd)
        .bind(state_hash(&invalid_zstd))
        .execute(&repository.pool)
        .await
        .unwrap();

    assert_eq!(
        repository.validate_canonical_head(game).await,
        Err(CommitError::GameUnavailable)
    );
    assert_eq!(
        repository.game_metadata(account, game).await,
        Err(CommitError::GameUnavailable)
    );
    assert_eq!(
        repository
            .commit(
                account,
                command(game, Uuid::new_v4(), 0),
                proposal(0, b"client-repair-must-not-commit"),
            )
            .await,
        Err(CommitError::GameUnavailable)
    );
    let quarantine_reason: Option<String> =
        sqlx::query_scalar("SELECT unavailable_reason FROM games WHERE id=$1")
            .bind(game)
            .fetch_one(&repository.pool)
            .await
            .unwrap();
    assert_eq!(
        quarantine_reason.as_deref(),
        Some("corrupt_canonical_snapshot")
    );
    let validation_status: String = sqlx::query_scalar(
        "SELECT validation_status FROM game_snapshots WHERE game_id=$1 AND revision=0",
    )
    .bind(game)
    .fetch_one(&repository.pool)
    .await
    .unwrap();
    assert_eq!(validation_status, "corrupt");
    let head_revision: i64 = sqlx::query_scalar("SELECT head_revision FROM games WHERE id=$1")
        .bind(game)
        .fetch_one(&repository.pool)
        .await
        .unwrap();
    assert_eq!(head_revision, 0);
}
