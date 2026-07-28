use super::*;

const RESTORED_SNAPSHOT: &[u8] = b"backup-revision-1";

#[tokio::test]
#[ignore = "requires an explicit UNCIV_V3_DATABASE_URL and is driven by the destructive restore qualification"]
async fn seed_backup_restore_qualification_fixture() {
    let repository = PostgresGameRepository::connect(&database_url())
        .await
        .unwrap();
    repository.migrate().await.unwrap();
    sqlx::query(
        "TRUNCATE api_rate_limits, security_audit_events, sessions, game_outbox, game_revisions, game_commands, game_snapshots, game_members, games, ruleset_manifests, accounts CASCADE",
    )
    .execute(&repository.pool)
    .await
    .unwrap();
    let (account_id, game_id) = seed_repository(&repository).await;
    repository.issue_session(account_id).await.unwrap();
    repository
        .record_security_audit(
            Some(account_id),
            SecurityAuditEvent::Login,
            SecurityAuditOutcome::Success,
            "203.0.113.0/24",
            Some("backup-qualification"),
        )
        .await
        .unwrap();
    repository
        .commit(
            account_id,
            command(game_id, Uuid::new_v4(), 0),
            proposal(0, RESTORED_SNAPSHOT),
        )
        .await
        .unwrap();
    assert_backup_restore_fixture(&repository).await;
}

#[tokio::test]
#[ignore = "requires an explicit restored UNCIV_V3_DATABASE_URL and is driven by the destructive restore qualification"]
async fn restored_backup_fixture_preserves_every_required_invariant() {
    let repository = PostgresGameRepository::connect(&database_url())
        .await
        .unwrap();
    repository.verify_schema_compatibility().await.unwrap();
    assert_backup_restore_fixture(&repository).await;
}

async fn assert_backup_restore_fixture(repository: &PostgresGameRepository) {
    let reconciliation = repository.reconcile_authoritative_state().await.unwrap();
    assert_eq!(reconciliation.total_findings, 0, "{reconciliation:?}");
    let (game_id, head_revision, canonical_state_hash): (Uuid, i64, String) = sqlx::query_as(
        "SELECT g.id, g.head_revision, r.canonical_state_hash
         FROM games g
         JOIN game_revisions r
           ON r.game_id = g.id
          AND r.revision = g.head_revision",
    )
    .fetch_one(&repository.pool)
    .await
    .unwrap();
    assert_eq!(head_revision, 1);
    assert_eq!(canonical_state_hash, state_hash(RESTORED_SNAPSHOT));
    repository.validate_canonical_head(game_id).await.unwrap();

    for (label, query, expected) in [
        (
            "game_revisions",
            "SELECT count(*) FROM game_revisions",
            2_i64,
        ),
        ("game_snapshots", "SELECT count(*) FROM game_snapshots", 2),
        (
            "game_snapshot_blobs",
            "SELECT count(*) FROM game_snapshot_blobs",
            2,
        ),
        ("game_commands", "SELECT count(*) FROM game_commands", 1),
        ("game_members", "SELECT count(*) FROM game_members", 1),
        ("sessions", "SELECT count(*) FROM sessions", 1),
        (
            "security_audit_events",
            "SELECT count(*) FROM security_audit_events",
            1,
        ),
        ("game_outbox", "SELECT count(*) FROM game_outbox", 1),
    ] {
        let count: i64 = sqlx::query_scalar(query)
            .fetch_one(&repository.pool)
            .await
            .unwrap();
        assert_eq!(count, expected, "{label}");
    }
}
