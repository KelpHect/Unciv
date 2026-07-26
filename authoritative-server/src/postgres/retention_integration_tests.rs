use super::*;

fn movement_command(game_id: Uuid, command_id: Uuid, expected_revision: u64) -> CommandEnvelope {
    CommandEnvelope {
        protocol_version: PROTOCOL_VERSION,
        game_id,
        command_id,
        expected_revision,
        client_observed_state_hash: None,
        command: GameCommand::MoveUnit {
            unit_id: 1,
            destination_x: expected_revision as i32,
            destination_y: 0,
            escort_unit_id: None,
        },
    }
}

#[tokio::test]
#[ignore = "requires an explicit UNCIV_V3_DATABASE_URL"]
async fn compaction_preserves_milestones_head_history_and_idempotency() {
    let repository = PostgresGameRepository::connect(&database_url())
        .await
        .unwrap();
    repository.migrate().await.unwrap();
    let (owner, game) = seed_repository(&repository).await;
    let mut command_ids = Vec::new();
    for previous_revision in 0_u64..12 {
        let command_id = Uuid::new_v4();
        command_ids.push(command_id);
        let envelope = if matches!(previous_revision + 1, 1 | 5) {
            command(game, command_id, previous_revision)
        } else {
            movement_command(game, command_id, previous_revision)
        };
        let snapshot = format!("revision-{}", previous_revision + 1);
        repository
            .commit(
                owner,
                envelope,
                proposal(previous_revision, snapshot.as_bytes()),
            )
            .await
            .unwrap();
    }

    let policy = SnapshotRetentionPolicy {
        recent_revisions: 2,
        long_term_interval: 10,
    };
    let hashes_before: Vec<(i64, String)> = sqlx::query_as(
        "SELECT revision, canonical_state_hash FROM game_revisions
         WHERE game_id=$1 ORDER BY revision",
    )
    .bind(game)
    .fetch_all(&repository.pool)
    .await
    .unwrap();
    let dry_run = repository
        .compact_snapshot_payloads(game, policy, true)
        .await
        .unwrap();
    assert_eq!(dry_run.head_revision, 12);
    assert_eq!(dry_run.retained_payloads, 6);
    assert_eq!(dry_run.compacted_payloads, 7);
    assert!(dry_run.bytes_reclaimed > 0);
    assert_eq!(
        sqlx::query_scalar::<_, i64>("SELECT count(*) FROM game_snapshot_blobs WHERE game_id=$1",)
            .bind(game)
            .fetch_one(&repository.pool)
            .await
            .unwrap(),
        13
    );

    let applied = repository
        .compact_snapshot_payloads(game, policy, false)
        .await
        .unwrap();
    assert_eq!(applied.compacted_payloads, 7);
    let retained: Vec<i64> = sqlx::query_scalar(
        "SELECT revision FROM game_snapshot_blobs WHERE game_id=$1 ORDER BY revision",
    )
    .bind(game)
    .fetch_all(&repository.pool)
    .await
    .unwrap();
    assert_eq!(retained, vec![0, 1, 5, 10, 11, 12]);
    assert_eq!(
        sqlx::query_scalar::<_, i64>("SELECT count(*) FROM game_revisions WHERE game_id=$1",)
            .bind(game)
            .fetch_one(&repository.pool)
            .await
            .unwrap(),
        13
    );
    assert_eq!(
        sqlx::query_scalar::<_, i64>("SELECT count(*) FROM game_commands WHERE game_id=$1")
            .bind(game)
            .fetch_one(&repository.pool)
            .await
            .unwrap(),
        12
    );
    let hashes_after: Vec<(i64, String)> = sqlx::query_as(
        "SELECT revision, canonical_state_hash FROM game_revisions
         WHERE game_id=$1 ORDER BY revision",
    )
    .bind(game)
    .fetch_all(&repository.pool)
    .await
    .unwrap();
    assert_eq!(hashes_after, hashes_before);
    repository.validate_canonical_head(game).await.unwrap();
    let unavailable_delta = repository
        .game_projection_delta(
            &EngineWorkerClient::new("127.0.0.1:9".parse().unwrap(), Duration::from_millis(10)),
            owner,
            game,
            2,
            &hashes_before[2].1,
            &"a".repeat(64),
        )
        .await;
    assert_eq!(
        unavailable_delta,
        Err(CommitError::ProjectionDeltaUnavailable)
    );

    let duplicate = repository
        .commit(
            owner,
            movement_command(game, command_ids[1], 1),
            proposal(1, b"different-content-after-compaction"),
        )
        .await
        .unwrap();
    assert_eq!(duplicate.committed_revision, 2);
    let reconciliation = repository.reconcile_authoritative_state().await.unwrap();
    assert!(
        !reconciliation
            .findings
            .iter()
            .any(|finding| finding.game_id == game)
    );
}
