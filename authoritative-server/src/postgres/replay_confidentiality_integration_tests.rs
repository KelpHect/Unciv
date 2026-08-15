//! A replay is a full no-fog-of-war view of a game. These pin that it is only
//! ever readable once the match can no longer be played, so a live opponent can
//! never read the whole map and every rival's economy through it.

use super::*;

/// Marks a seeded game public and puts it in the given lifecycle state.
async fn set_game_state(
    repository: &PostgresGameRepository,
    game: Uuid,
    visibility: &str,
    lifecycle_status: &str,
) {
    sqlx::query("UPDATE games SET visibility = $2, lifecycle_status = $3 WHERE id = $1")
        .bind(game)
        .bind(visibility)
        .bind(lifecycle_status)
        .execute(&repository.pool)
        .await
        .unwrap();
}

#[tokio::test]
#[ignore = "requires an explicit UNCIV_V3_DATABASE_URL"]
async fn an_active_game_exposes_no_replay_revisions_or_public_listing() {
    let repository = PostgresGameRepository::connect(&database_url())
        .await
        .unwrap();
    repository.migrate().await.unwrap();
    let (_account, game) = seed_repository(&repository).await;
    set_game_state(&repository, game, "public", "active").await;

    // A live match is not advertised for watching...
    let listed = repository.list_public_matches(50, 0).await.unwrap();
    assert!(
        !listed.iter().any(|summary| summary.game_id == game),
        "an active game must not appear in the public replay directory",
    );

    // ...its turn timing is not readable...
    let revisions = repository.list_revisions(game).await.unwrap();
    assert!(
        revisions.revisions.is_empty(),
        "an active game must not expose its revision history",
    );

    // ...and the no-fog projection itself is refused at the repository layer,
    // independently of the API handler's own authorization gate.
    let unreachable_worker = EngineWorkerClient::new(
        "127.0.0.1:1".parse().unwrap(),
        Duration::from_millis(50),
        crate::worker::WorkerIdentityKey::for_test(),
    );
    let denied = repository
        .replay_projection(&unreachable_worker, &Uuid::new_v4().to_string(), game, 0)
        .await;
    assert!(
        matches!(denied, Err(CommitError::NotFound)),
        "an active game must not resolve a replay snapshot, got {denied:?}",
    );

    let access = repository.replay_access(game).await.unwrap();
    assert!(access.is_public);
    assert!(!access.is_concluded);
}

#[tokio::test]
#[ignore = "requires an explicit UNCIV_V3_DATABASE_URL"]
async fn a_concluded_game_becomes_listable_and_replayable() {
    let repository = PostgresGameRepository::connect(&database_url())
        .await
        .unwrap();
    repository.migrate().await.unwrap();
    let (_account, game) = seed_repository(&repository).await;

    for lifecycle_status in ["closed", "archived"] {
        set_game_state(&repository, game, "public", lifecycle_status).await;

        let listed = repository.list_public_matches(50, 0).await.unwrap();
        assert!(
            listed.iter().any(|summary| summary.game_id == game),
            "a {lifecycle_status} public game must be watchable",
        );
        let revisions = repository.list_revisions(game).await.unwrap();
        assert_eq!(
            revisions.revisions.len(),
            1,
            "a {lifecycle_status} game must expose its revision history",
        );

        let access = repository.replay_access(game).await.unwrap();
        assert!(access.is_public);
        assert!(
            access.is_concluded,
            "{lifecycle_status} must count as concluded play",
        );
    }
}

/// Ending play must be the only thing that opens a replay — a private game that
/// concludes stays private, and a public game that is still live stays sealed.
#[tokio::test]
#[ignore = "requires an explicit UNCIV_V3_DATABASE_URL"]
async fn replay_access_reports_visibility_and_conclusion_independently() {
    let repository = PostgresGameRepository::connect(&database_url())
        .await
        .unwrap();
    repository.migrate().await.unwrap();
    let (_account, game) = seed_repository(&repository).await;

    for (visibility, lifecycle_status, expect_public, expect_concluded) in [
        ("private", "active", false, false),
        ("private", "closed", false, true),
        ("public", "active", true, false),
        ("public", "closed", true, true),
    ] {
        set_game_state(&repository, game, visibility, lifecycle_status).await;
        let access = repository.replay_access(game).await.unwrap();
        assert_eq!(
            access.is_public, expect_public,
            "{visibility}/{lifecycle_status} visibility",
        );
        assert_eq!(
            access.is_concluded, expect_concluded,
            "{visibility}/{lifecycle_status} conclusion",
        );

        // A private game is never advertised, whatever its lifecycle.
        let listed = repository.list_public_matches(50, 0).await.unwrap();
        assert_eq!(
            listed.iter().any(|summary| summary.game_id == game),
            expect_public && expect_concluded,
            "{visibility}/{lifecycle_status} public listing",
        );
    }
}

#[tokio::test]
#[ignore = "requires an explicit UNCIV_V3_DATABASE_URL"]
async fn replay_access_reports_a_missing_game_as_not_found() {
    let repository = PostgresGameRepository::connect(&database_url())
        .await
        .unwrap();
    repository.migrate().await.unwrap();
    let missing = repository.replay_access(Uuid::new_v4()).await;
    assert!(matches!(missing, Err(CommitError::NotFound)));
}
