use super::*;

fn archive_command(game_id: Uuid, command_id: Uuid, expected_revision: u64) -> CommandEnvelope {
    CommandEnvelope {
        command: GameCommand::MoveUnit {
            unit_id: 1,
            destination_x: expected_revision as i32,
            destination_y: 0,
            escort_unit_id: None,
        },
        ..command(game_id, command_id, expected_revision)
    }
}

#[tokio::test]
#[ignore = "requires PostgreSQL and a Lockwell native access key"]
async fn lockwell_archival_verifies_objects_and_removes_only_cold_blobs() {
    let repository = PostgresGameRepository::connect(&database_url())
        .await
        .unwrap();
    repository.migrate().await.unwrap();
    let (owner, _) = seed_repository(&repository).await;

    let mut seed = 0x9e37_79b9_u32;
    let mut previous: Vec<u8> = (0..40_000)
        .map(|_| {
            seed = seed.wrapping_mul(1_664_525).wrapping_add(1_013_904_223);
            b'a' + ((seed >> 24) % 26) as u8
        })
        .collect();
    let game = Uuid::new_v4();
    repository
        .create_game(NewGame {
            game_id: game,
            owner_account_id: owner,
            ruleset_manifest_hash: "a".repeat(64),
            snapshot: previous.clone(),
            owner_civilization_id: "archive-civilization".to_owned(),
        })
        .await
        .unwrap();

    let mut direct_target = previous.clone();
    direct_target.extend(b"revision-1:");
    let direct_delta =
        crate::snapshot_delta::SnapshotDelta::encode(0, &previous, &direct_target, 9).unwrap();
    assert!(
        direct_delta.payload.len()
            < zstd::stream::encode_all(direct_target.as_slice(), 9)
                .unwrap()
                .len()
    );
    for revision in 0_u64..12 {
        let snapshot = if revision == 0 {
            previous.clone()
        } else {
            let mut next = previous.clone();
            let marker = format!("revision-{revision}:");
            next.extend(marker.bytes());
            previous = next.clone();
            next
        };
        if revision > 0 {
            repository
                .commit(
                    owner,
                    archive_command(game, Uuid::new_v4(), revision - 1),
                    proposal(revision - 1, &snapshot),
                )
                .await
                .unwrap();
        }
    }

    let reencode = repository
        .reencode_snapshot_payloads(game, true)
        .await
        .unwrap();
    assert_eq!(reencode.scanned_payloads, 12);
    assert!(reencode.bytes_after <= reencode.bytes_before);

    let policy = SnapshotRetentionPolicy {
        recent_revisions: 2,
        long_term_interval: 10,
    };
    let dry_run = repository
        .archive_snapshot_payloads(game, policy, true, true)
        .await
        .unwrap();
    assert_eq!(dry_run.head_revision, 11);
    assert!(dry_run.candidates > 0);
    assert!(dry_run.bytes_archived > 0);

    let applied = repository
        .archive_snapshot_payloads(game, policy, false, true)
        .await
        .unwrap();
    assert_eq!(applied.archived_payloads, applied.candidates);
    assert!(applied.archived_payloads > 0);
    assert!(applied.delta_payloads > 0);

    let archived: Vec<(i64, String, i64)> = sqlx::query_as(
        "SELECT revision, archive_codec, object_size
         FROM game_snapshot_archives WHERE game_id=$1 ORDER BY revision",
    )
    .bind(game)
    .fetch_all(&repository.pool)
    .await
    .unwrap();
    assert_eq!(archived.len() as u64, applied.archived_payloads);
    assert!(archived.iter().all(|(_, _, size)| *size > 0));
    assert_eq!(
        sqlx::query_scalar::<_, i64>(
            "SELECT count(*) FROM game_snapshot_blobs WHERE game_id=$1 AND revision IN (1,2,3,4,5,6,7,8,9)",
        )
        .bind(game)
        .fetch_one(&repository.pool)
        .await
        .unwrap(),
        0
    );
    assert_eq!(
        sqlx::query_scalar::<_, i64>(
            "SELECT count(*) FROM game_snapshot_blobs WHERE game_id=$1 AND revision IN (0,10,11)",
        )
        .bind(game)
        .fetch_one(&repository.pool)
        .await
        .unwrap(),
        3
    );
    assert!(
        sqlx::query_scalar::<_, i64>(
            "SELECT count(*) FROM game_snapshots
             WHERE game_id=$1 AND payload_retention_status='compacted'",
        )
        .bind(game)
        .fetch_one(&repository.pool)
        .await
        .unwrap()
            > 0
    );
    repository.validate_canonical_head(game).await.unwrap();
    let reconciliation = repository.reconcile_authoritative_state().await.unwrap();
    assert!(
        !reconciliation
            .findings
            .iter()
            .any(|finding| finding.game_id == game)
    );

    let archive_bytes = archived
        .iter()
        .map(|(_, _, size)| u64::try_from(*size).unwrap())
        .sum();
    let quota_report = repository
        .run_snapshot_maintenance_once(SnapshotMaintenanceConfig {
            enabled: true,
            interval: std::time::Duration::from_secs(60),
            policy,
            use_deltas: true,
            max_games_per_tick: 1,
            max_revisions_per_game: 128,
            postgres_budget_bytes: None,
            archive_budget_bytes: Some(archive_bytes),
            game_storage_budget_bytes: None,
        })
        .await
        .unwrap();
    assert!(quota_report.archive_quota_exceeded);
    assert_eq!(quota_report.archive_bytes, archive_bytes);
}
