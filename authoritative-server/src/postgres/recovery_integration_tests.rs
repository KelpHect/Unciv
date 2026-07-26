use serde_json::Value;
use tokio::{
    io::{AsyncReadExt, AsyncWriteExt},
    net::TcpListener,
};

use super::*;

#[tokio::test]
#[ignore = "requires an explicit UNCIV_V3_DATABASE_URL"]
async fn bounded_recovery_replays_from_the_newest_valid_prior_snapshot() {
    let repository = PostgresGameRepository::connect(&database_url())
        .await
        .unwrap();
    repository.migrate().await.unwrap();
    let (owner, game) = seed_repository(&repository).await;
    let manifest = WorkerManifest {
        engine_build: "test-engine".to_owned(),
        base_ruleset: crate::worker::WorkerRuleset {
            name: "Base".to_owned(),
            sha256: "b".repeat(64),
        },
        mods: Vec::new(),
    };
    sqlx::query("UPDATE ruleset_manifests SET manifest=$2 WHERE hash=$1")
        .bind("a".repeat(64))
        .bind(serde_json::to_value(&manifest).unwrap())
        .execute(&repository.pool)
        .await
        .unwrap();
    repository
        .commit(
            owner,
            command(game, Uuid::new_v4(), 0),
            proposal(0, b"revision-1"),
        )
        .await
        .unwrap();
    sqlx::query(
        "UPDATE game_snapshots
         SET payload=$2, compressed_size=1, payload_hash=$3
         WHERE game_id=$1 AND revision=1",
    )
    .bind(game)
    .bind(vec![0_u8])
    .bind(state_hash(&[0_u8]))
    .execute(&repository.pool)
    .await
    .unwrap();

    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let worker = EngineWorkerClient::new(listener.local_addr().unwrap(), Duration::from_secs(1));
    let server = tokio::spawn(async move {
        let (mut stream, _) = listener.accept().await.unwrap();
        let size = stream.read_u32().await.unwrap() as usize;
        let mut request = vec![0; size];
        stream.read_exact(&mut request).await.unwrap();
        let request: Value = serde_json::from_slice(&request).unwrap();
        assert_eq!(request["actorId"], owner.to_string());
        assert_eq!(request["serverTimeMillis"], 0);
        assert_eq!(request["operation"]["type"], "test");
        assert_eq!(request["operation"]["snapshot"], "revision-0");
        let response = serde_json::to_vec(&serde_json::json!({
            "protocolVersion": crate::worker::WORKER_PROTOCOL_VERSION,
            "serverTimeMillis": 0,
            "snapshot": "revision-1",
            "canonicalStateHash": state_hash(b"revision-1"),
        }))
        .unwrap();
        stream.write_u32(response.len() as u32).await.unwrap();
        stream.write_all(&response).await.unwrap();
    });

    let recovered = repository.reconstruct_head(&worker, game, 1).await.unwrap();
    assert_eq!(recovered.source_revision, 0);
    assert_eq!(recovered.head_revision, 1);
    assert_eq!(recovered.commands_replayed, 1);
    assert_eq!(recovered.snapshot, b"revision-1");
    assert_eq!(recovered.canonical_state_hash, state_hash(b"revision-1"));
    server.await.unwrap();

    let stored_payload: Vec<u8> =
        sqlx::query_scalar("SELECT payload FROM game_snapshots WHERE game_id=$1 AND revision=1")
            .bind(game)
            .fetch_one(&repository.pool)
            .await
            .unwrap();
    assert_eq!(
        stored_payload,
        vec![0_u8],
        "reconstruction must be read-only"
    );

    sqlx::query(
        "UPDATE games
         SET unavailable_at=now(), unavailable_reason='corrupt_snapshot'
         WHERE id=$1",
    )
    .bind(game)
    .execute(&repository.pool)
    .await
    .unwrap();
    let recovery_revision = repository.publish_recovered_head(&recovered).await.unwrap();
    assert_eq!(recovery_revision, 2);
    let published: (i64, bool, String, String, i64, i64) = sqlx::query_as(
        "SELECT g.head_revision, g.unavailable_at IS NULL,
                r.revision_kind, r.canonical_state_hash,
                e.source_revision, e.commands_replayed
         FROM games g
         JOIN game_revisions r ON r.game_id=g.id AND r.revision=g.head_revision
         JOIN game_recovery_events e ON e.game_id=r.game_id AND e.revision=r.revision
         WHERE g.id=$1",
    )
    .bind(game)
    .fetch_one(&repository.pool)
    .await
    .unwrap();
    assert_eq!(
        published,
        (
            2,
            true,
            "recovery".to_owned(),
            state_hash(b"revision-1"),
            0,
            1
        )
    );
    let recovered_outbox: i64 = sqlx::query_scalar(
        "SELECT count(*) FROM game_outbox
         WHERE game_id=$1 AND revision=2 AND topic='game.revision.recovered'",
    )
    .bind(game)
    .fetch_one(&repository.pool)
    .await
    .unwrap();
    assert_eq!(recovered_outbox, 1);
    let old_payload: Vec<u8> =
        sqlx::query_scalar("SELECT payload FROM game_snapshots WHERE game_id=$1 AND revision=1")
            .bind(game)
            .fetch_one(&repository.pool)
            .await
            .unwrap();
    assert_eq!(
        old_payload,
        vec![0_u8],
        "damaged history must remain immutable"
    );
    assert!(matches!(
        repository.publish_recovered_head(&recovered).await,
        Err(CommitError::Stale {
            expected: 1,
            actual: 2
        })
    ));
}
