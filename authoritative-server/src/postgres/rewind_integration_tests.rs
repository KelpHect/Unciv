use super::*;
use crate::worker::{
    WORKER_PROTOCOL_VERSION, WorkerIdentityKey, read_authenticated_test_frame,
    write_authenticated_test_frame,
};
use tokio::net::TcpListener;

#[tokio::test]
#[ignore = "requires an explicit UNCIV_V3_DATABASE_URL"]
async fn rewind_freezes_all_humans_accepts_only_turn_starts_and_rejection_preserves_head() {
    let repository = PostgresGameRepository::connect(&database_url())
        .await
        .unwrap();
    repository.migrate().await.unwrap();
    let (owner, game) = seed_repository(&repository).await;
    sqlx::query("UPDATE ruleset_manifests SET manifest=$2 WHERE hash=$1")
        .bind("a".repeat(64))
        .bind(json!({
            "engineBuild": "test-engine",
            "baseRuleset": {"name": "Base", "sha256": "b".repeat(64)},
            "mods": [],
        }))
        .execute(&repository.pool)
        .await
        .unwrap();
    let player = Uuid::new_v4();
    sqlx::query(
        "INSERT INTO accounts(id,username_normalized,password_hash)
         VALUES($1,$2,'test-hash')",
    )
    .bind(player)
    .bind(format!("rewind-{player}"))
    .execute(&repository.pool)
    .await
    .unwrap();
    sqlx::query(
        "INSERT INTO game_members(game_id,account_id,role,civilization_id)
         VALUES($1,$2,'player','second-civilization')",
    )
    .bind(game)
    .bind(player)
    .execute(&repository.pool)
    .await
    .unwrap();

    repository
        .commit(
            owner,
            command(game, Uuid::new_v4(), 0),
            proposal(0, b"start-of-next-turn"),
        )
        .await
        .unwrap();
    let worker = EngineWorkerClient::new(
        "127.0.0.1:9".parse().unwrap(),
        Duration::from_millis(10),
        crate::worker::WorkerIdentityKey::for_test(),
    );
    let invalid = repository
        .propose_rewind(
            &worker,
            owner,
            game,
            RewindRequest {
                request_id: Uuid::new_v4(),
                expected_head_revision: 1,
                target_revision: 1,
            },
        )
        .await
        .unwrap_err();
    assert_eq!(invalid, CommitError::InvalidCommand);

    let request_id = Uuid::new_v4();
    let pending = repository
        .propose_rewind(
            &worker,
            owner,
            game,
            RewindRequest {
                request_id,
                expected_head_revision: 1,
                target_revision: 0,
            },
        )
        .await
        .unwrap();
    assert_eq!(pending.status, "pending");
    assert_eq!(pending.approvals, 1);
    assert_eq!(pending.required_approvals, 2);
    assert_eq!(pending.actor_approved, Some(true));

    let player_status = repository.current_rewind(player, game).await.unwrap();
    assert_eq!(player_status.request_id, request_id);
    assert_eq!(player_status.actor_approved, None);
    let rejected = repository
        .vote_rewind(&worker, player, game, request_id, false)
        .await
        .unwrap();
    assert_eq!(rejected.status, "rejected");
    let head: i64 = sqlx::query_scalar("SELECT head_revision FROM games WHERE id=$1")
        .bind(game)
        .fetch_one(&repository.pool)
        .await
        .unwrap();
    assert_eq!(head, 1);
    assert_eq!(
        repository
            .vote_rewind(&worker, player, game, request_id, true)
            .await
            .unwrap_err(),
        CommitError::IdempotencyConflict,
    );
}

#[tokio::test]
#[ignore = "requires an explicit UNCIV_V3_DATABASE_URL"]
async fn unanimous_rewind_copies_the_complete_start_snapshot_into_a_new_immutable_head() {
    let repository = PostgresGameRepository::connect(&database_url())
        .await
        .unwrap();
    repository.migrate().await.unwrap();
    let (owner, game) = seed_repository(&repository).await;
    sqlx::query("UPDATE ruleset_manifests SET manifest=$2 WHERE hash=$1")
        .bind("a".repeat(64))
        .bind(json!({
            "engineBuild": "test-engine",
            "baseRuleset": {"name": "Base", "sha256": "b".repeat(64)},
            "mods": [],
        }))
        .execute(&repository.pool)
        .await
        .unwrap();
    let player = Uuid::new_v4();
    sqlx::query(
        "INSERT INTO accounts(id,username_normalized,password_hash)
         VALUES($1,$2,'test-hash')",
    )
    .bind(player)
    .bind(format!("rewind-apply-{player}"))
    .execute(&repository.pool)
    .await
    .unwrap();
    sqlx::query(
        "INSERT INTO game_members(game_id,account_id,role,civilization_id)
         VALUES($1,$2,'player','second-civilization')",
    )
    .bind(game)
    .bind(player)
    .execute(&repository.pool)
    .await
    .unwrap();
    repository
        .commit(
            owner,
            command(game, Uuid::new_v4(), 0),
            proposal(0, b"after-ai-turn-rotation"),
        )
        .await
        .unwrap();
    let request_id = Uuid::new_v4();
    let dead_worker = EngineWorkerClient::new(
        "127.0.0.1:9".parse().unwrap(),
        Duration::from_millis(10),
        WorkerIdentityKey::for_test(),
    );
    repository
        .propose_rewind(
            &dead_worker,
            owner,
            game,
            RewindRequest {
                request_id,
                expected_head_revision: 1,
                target_revision: 0,
            },
        )
        .await
        .unwrap();

    assert_eq!(
        repository
            .vote_rewind(&dead_worker, player, game, request_id, true)
            .await
            .unwrap_err(),
        CommitError::WorkerRevisionMismatch,
    );
    assert_eq!(
        sqlx::query_scalar::<_, i64>("SELECT head_revision FROM games WHERE id=$1")
            .bind(game)
            .fetch_one(&repository.pool)
            .await
            .unwrap(),
        1,
    );
    let (worker, worker_task) = one_shot_projection_worker().await;
    let applied = repository
        .vote_rewind(&worker, player, game, request_id, true)
        .await
        .unwrap();
    worker_task.await.unwrap();
    assert_eq!(applied.status, "applied");
    assert_eq!(applied.applied_revision, Some(2));

    let row = sqlx::query(
        "SELECT r.parent_revision,r.command_id,r.revision_kind,
                r.canonical_state_hash,b.payload,s.codec,s.compressed_size,s.uncompressed_size
         FROM game_revisions r JOIN game_snapshots s
           ON s.game_id=r.game_id AND s.revision=r.snapshot_revision
         JOIN game_snapshot_blobs b ON b.game_id=s.game_id AND b.revision=s.revision
         WHERE r.game_id=$1 AND r.revision=2",
    )
    .bind(game)
    .fetch_one(&repository.pool)
    .await
    .unwrap();
    assert_eq!(row.get::<i64, _>("parent_revision"), 1);
    assert_eq!(row.get::<Option<Uuid>, _>("command_id"), None);
    assert_eq!(row.get::<String, _>("revision_kind"), "rewind");
    let snapshot = decode_snapshot(
        row.get("codec"),
        row.get("payload"),
        row.get("compressed_size"),
        row.get("uncompressed_size"),
    )
    .unwrap();
    assert_eq!(snapshot, b"revision-0");
    assert_eq!(
        row.get::<String, _>("canonical_state_hash"),
        state_hash(b"revision-0"),
    );
    let rewind_events: i64 = sqlx::query_scalar(
        "SELECT count(*) FROM game_outbox
         WHERE game_id=$1 AND revision=2 AND topic='game.revision.rewound'",
    )
    .bind(game)
    .fetch_one(&repository.pool)
    .await
    .unwrap();
    assert_eq!(rewind_events, 1);
    assert_eq!(
        repository
            .vote_rewind(&dead_worker, player, game, request_id, true)
            .await
            .unwrap()
            .applied_revision,
        Some(2),
    );
}

async fn one_shot_projection_worker() -> (EngineWorkerClient, tokio::task::JoinHandle<()>) {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let address = listener.local_addr().unwrap();
    let task = tokio::spawn(async move {
        let (mut stream, _) = listener.accept().await.unwrap();
        let (nonce, request) = read_authenticated_test_frame(&mut stream).await;
        assert_eq!(request["operation"]["type"], "project_spectator_state");
        assert_eq!(request["operation"]["snapshot"], "revision-0");
        let server_time_millis = request["serverTimeMillis"].as_i64().unwrap();
        write_authenticated_test_frame(
            &mut stream,
            nonce,
            json!({
                "protocolVersion": WORKER_PROTOCOL_VERSION,
                "serverTimeMillis": server_time_millis,
                "spectatorProjection": {
                    "protocolVersion": PROTOCOL_VERSION,
                    "turn": 0,
                    "currentPlayerCivilizationId": "test-civilization",
                    "victory": null,
                    "majorCivilizations": [{
                        "civilizationId": "test-civilization",
                        "displayName": "Test",
                        "humanControlled": true,
                        "defeated": false
                    }]
                }
            }),
        )
        .await;
    });
    (
        EngineWorkerClient::new(
            address,
            Duration::from_secs(2),
            WorkerIdentityKey::for_test(),
        ),
        task,
    )
}
