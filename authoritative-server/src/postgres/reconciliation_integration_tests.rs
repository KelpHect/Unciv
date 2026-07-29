use std::collections::HashSet;

use super::*;

#[tokio::test]
#[ignore = "requires an explicit UNCIV_V3_DATABASE_URL"]
async fn reconciliation_detects_damage_without_mutating_canonical_state() {
    let repository = PostgresGameRepository::connect(&database_url())
        .await
        .unwrap();
    repository.migrate().await.unwrap();
    let (owner, game) = seed_repository(&repository).await;
    let accepted = repository
        .commit(
            owner,
            command(game, Uuid::new_v4(), 0),
            proposal(0, b"revision-1"),
        )
        .await
        .unwrap();
    assert_eq!(accepted.committed_revision, 1);
    let clean = repository.reconcile_authoritative_state().await.unwrap();
    assert_eq!(clean.total_findings, 0);

    sqlx::query("DELETE FROM game_outbox WHERE game_id=$1 AND revision=1")
        .bind(game)
        .execute(&repository.pool)
        .await
        .unwrap();
    let invalid_parent =
        sqlx::query("UPDATE game_revisions SET parent_revision=99 WHERE game_id=$1 AND revision=1")
            .bind(game)
            .execute(&repository.pool)
            .await
            .unwrap_err();
    assert_eq!(
        invalid_parent
            .as_database_error()
            .and_then(|error| error.constraint()),
        Some("game_revisions_parent_fk")
    );
    let invalid_command =
        sqlx::query("UPDATE game_revisions SET command_id=$2 WHERE game_id=$1 AND revision=1")
            .bind(game)
            .bind(Uuid::new_v4())
            .execute(&repository.pool)
            .await
            .unwrap_err();
    assert_eq!(
        invalid_command
            .as_database_error()
            .and_then(|error| error.constraint()),
        Some("game_revisions_command_fk")
    );
    sqlx::query("UPDATE game_members SET role='player' WHERE game_id=$1 AND account_id=$2")
        .bind(game)
        .bind(owner)
        .execute(&repository.pool)
        .await
        .unwrap();
    sqlx::query("INSERT INTO game_snapshots (game_id, revision, engine_build, ruleset_manifest_hash, codec, compressed_size, uncompressed_size, canonical_state_hash, payload_hash, protocol_version, validation_status) SELECT game_id, 99, engine_build, ruleset_manifest_hash, codec, compressed_size, uncompressed_size, canonical_state_hash, payload_hash, protocol_version, validation_status FROM game_snapshots WHERE game_id=$1 AND revision=0")
        .bind(game)
        .execute(&repository.pool)
        .await
        .unwrap();
    sqlx::query("INSERT INTO game_snapshot_blobs (game_id, revision, compressed_size, payload_hash, payload) SELECT game_id, 99, compressed_size, payload_hash, payload FROM game_snapshot_blobs WHERE game_id=$1 AND revision=0")
        .bind(game)
        .execute(&repository.pool)
        .await
        .unwrap();
    let omitted_actor = sqlx::query(
        "INSERT INTO game_commands (game_id, command_id, revision, account_id, payload) VALUES ($1, $2, 98, $3, '{}'::jsonb)",
    )
    .bind(game)
    .bind(Uuid::new_v4())
    .bind(owner)
    .execute(&repository.pool)
    .await
    .unwrap_err();
    assert_eq!(
        omitted_actor
            .as_database_error()
            .and_then(|error| error.constraint()),
        Some("game_commands_replay_identity_complete")
    );
    let omitted_time = sqlx::query(
        "INSERT INTO game_commands (game_id, command_id, revision, account_id, actor_civilization_id, replay_operation, payload) VALUES ($1, $2, 98, $3, 'test-civilization', '{\"type\":\"test\"}'::jsonb, '{}'::jsonb)",
    )
    .bind(game)
    .bind(Uuid::new_v4())
    .bind(owner)
    .execute(&repository.pool)
    .await
    .unwrap_err();
    assert_eq!(
        omitted_time
            .as_database_error()
            .and_then(|error| error.constraint()),
        Some("game_commands_replay_time_complete")
    );
    let omitted_replay_operation = sqlx::query(
        "INSERT INTO game_commands (game_id, command_id, revision, account_id, actor_civilization_id, server_time_millis, payload) VALUES ($1, $2, 98, $3, 'test-civilization', 0, '{}'::jsonb)",
    )
    .bind(game)
    .bind(Uuid::new_v4())
    .bind(owner)
    .execute(&repository.pool)
    .await
    .unwrap_err();
    assert_eq!(
        omitted_replay_operation
            .as_database_error()
            .and_then(|error| error.constraint()),
        Some("game_commands_replay_operation_complete")
    );
    sqlx::query("INSERT INTO game_commands (game_id, command_id, revision, account_id, actor_civilization_id, replay_identity_available, server_time_millis, replay_time_available, replay_operation, replay_operation_available, payload) VALUES ($1, $2, 99, $3, NULL, FALSE, NULL, FALSE, NULL, FALSE, '{}'::jsonb)")
        .bind(game)
        .bind(Uuid::new_v4())
        .bind(owner)
        .execute(&repository.pool)
        .await
        .unwrap();
    let orphan_outbox = sqlx::query("INSERT INTO game_outbox (game_id, revision, topic, payload) VALUES ($1, 77, 'game.revision.committed', '{}'::jsonb)")
        .bind(game)
        .execute(&repository.pool)
        .await
        .unwrap_err();
    assert_eq!(
        orphan_outbox
            .as_database_error()
            .and_then(|error| error.constraint()),
        Some("game_outbox_revision_fk")
    );
    let omitted_creation_context = sqlx::query(
        "INSERT INTO game_creation_operations (operation_id, actor_account_id, request, game_id) VALUES ($1, $2, '{}'::jsonb, $3)",
    )
    .bind(Uuid::new_v4())
    .bind(owner)
    .bind(game)
    .execute(&repository.pool)
    .await
    .unwrap_err();
    assert_eq!(
        omitted_creation_context
            .as_database_error()
            .and_then(|error| error.constraint()),
        Some("game_creation_operations_replay_context_complete")
    );
    sqlx::query(
        "INSERT INTO game_creation_operations (operation_id, actor_account_id, request, game_id, server_seed, server_time_millis, replay_context_available) VALUES ($1, $2, '{}'::jsonb, $3, NULL, NULL, FALSE)",
    )
    .bind(Uuid::new_v4())
    .bind(owner)
    .bind(game)
    .execute(&repository.pool)
    .await
    .unwrap();
    let compressed_size: i64 = sqlx::query_scalar(
        "SELECT compressed_size FROM game_snapshots WHERE game_id=$1 AND revision=1",
    )
    .bind(game)
    .fetch_one(&repository.pool)
    .await
    .unwrap();
    let invalid_zstd = vec![0_u8; compressed_size as usize];
    sqlx::query("UPDATE game_snapshot_blobs SET payload=$2 WHERE game_id=$1 AND revision=1")
        .bind(game)
        .bind(&invalid_zstd)
        .execute(&repository.pool)
        .await
        .unwrap();
    sqlx::query(
        "UPDATE games SET unavailable_at=now(), unavailable_reason='operator_test' WHERE id=$1",
    )
    .bind(game)
    .execute(&repository.pool)
    .await
    .unwrap();

    let before: (i64, i64, i64, i64, i64, Option<String>, String) = sqlx::query_as(
        "SELECT (SELECT count(*) FROM game_revisions WHERE game_id=$1), (SELECT count(*) FROM game_snapshots WHERE game_id=$1), (SELECT count(*) FROM game_commands WHERE game_id=$1), (SELECT count(*) FROM game_outbox WHERE game_id=$1), (SELECT count(*) FROM game_creation_operations WHERE game_id=$1), unavailable_reason, (SELECT validation_status FROM game_snapshots WHERE game_id=$1 AND revision=1) FROM games WHERE id=$1",
    )
    .bind(game)
    .fetch_one(&repository.pool)
    .await
    .unwrap();
    let report = repository.reconcile_authoritative_state().await.unwrap();
    let after: (i64, i64, i64, i64, i64, Option<String>, String) = sqlx::query_as(
        "SELECT (SELECT count(*) FROM game_revisions WHERE game_id=$1), (SELECT count(*) FROM game_snapshots WHERE game_id=$1), (SELECT count(*) FROM game_commands WHERE game_id=$1), (SELECT count(*) FROM game_outbox WHERE game_id=$1), (SELECT count(*) FROM game_creation_operations WHERE game_id=$1), unavailable_reason, (SELECT validation_status FROM game_snapshots WHERE game_id=$1 AND revision=1) FROM games WHERE id=$1",
    )
    .bind(game)
    .fetch_one(&repository.pool)
    .await
    .unwrap();
    assert_eq!(before, after, "reconciliation must be read-only");

    let kinds = report
        .findings
        .iter()
        .map(|finding| finding.kind.clone())
        .collect::<HashSet<_>>();
    for expected in [
        ReconciliationKind::MissingCommandActor,
        ReconciliationKind::MissingCommandTime,
        ReconciliationKind::MissingCommandReplayOperation,
        ReconciliationKind::MissingCreationReplayContext,
        ReconciliationKind::OrphanCommand,
        ReconciliationKind::MissingCommitOutbox,
        ReconciliationKind::OrphanSnapshot,
        ReconciliationKind::InvalidOwnerCount,
        ReconciliationKind::QuarantinedGame,
        ReconciliationKind::InvalidSnapshotPayload,
    ] {
        assert!(kinds.contains(&expected), "missing finding {expected:?}");
    }
    assert!(!report.findings_truncated);
    assert_eq!(report.games_scanned, 1);
    assert_eq!(report.revisions_scanned, 2);
    assert_eq!(report.snapshots_scanned, 3);
}
