use std::time::Duration;

use serde_json::{Value, json};
use tokio::net::TcpListener;

use super::*;
use crate::worker::{
    EngineWorkerClient, WORKER_PROTOCOL_VERSION, WorkerIdentityKey, read_authenticated_test_frame,
    write_authenticated_test_frame,
};

async fn end_turn_worker(
    respond: bool,
    snapshot: &'static str,
) -> (EngineWorkerClient, tokio::task::JoinHandle<()>) {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let address = listener.local_addr().unwrap();
    let task = tokio::spawn(async move {
        let (mut stream, _) = listener.accept().await.unwrap();
        let (nonce, request): (_, Value) = read_authenticated_test_frame(&mut stream).await;
        assert_eq!(request["operation"]["type"], "end_turn");
        assert_eq!(request["operation"]["snapshot"], "revision-0");
        assert_eq!(
            request["operation"]["actorCivilizationId"],
            "test-civilization"
        );
        if !respond {
            return;
        }
        let server_time_millis = request["serverTimeMillis"].as_i64().unwrap();
        write_authenticated_test_frame(
            &mut stream,
            nonce,
            json!({
                "protocolVersion": WORKER_PROTOCOL_VERSION,
                "serverTimeMillis": server_time_millis,
                "snapshot": snapshot,
                "canonicalStateHash": state_hash(snapshot.as_bytes()),
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

async fn commit_artifact_counts(
    repository: &PostgresGameRepository,
    game: Uuid,
) -> (i64, i64, i64, i64) {
    sqlx::query_as(
        "SELECT
            (SELECT count(*) FROM game_revisions WHERE game_id=$1 AND revision>0),
            (SELECT count(*) FROM game_snapshots WHERE game_id=$1 AND revision>0),
            (SELECT count(*) FROM game_commands WHERE game_id=$1),
            (SELECT count(*) FROM game_outbox WHERE game_id=$1)",
    )
    .bind(game)
    .fetch_one(&repository.pool)
    .await
    .unwrap()
}

#[tokio::test]
#[ignore = "requires an explicit UNCIV_V3_DATABASE_URL"]
async fn worker_connection_death_cannot_create_a_phantom_revision_and_retry_is_safe() {
    let repository = PostgresGameRepository::connect(&database_url())
        .await
        .unwrap();
    repository.migrate().await.unwrap();
    let (owner, game) = seed_repository(&repository).await;
    sqlx::query(
        "UPDATE ruleset_manifests
         SET manifest=$2
         WHERE hash=$1",
    )
    .bind("a".repeat(64))
    .bind(json!({
        "engineBuild": "test-engine",
        "baseRuleset": {
            "name": "Base",
            "sha256": "b".repeat(64),
        },
        "mods": [],
    }))
    .execute(&repository.pool)
    .await
    .unwrap();
    let command_id = Uuid::new_v4();
    let envelope = command(game, command_id, 0);

    let (dying_worker, dying_task) = end_turn_worker(false, "unused").await;
    let failed = repository
        .execute_end_turn(&dying_worker, owner, envelope.clone())
        .await;
    assert_eq!(failed, Err(CommitError::WorkerRevisionMismatch));
    dying_task.await.unwrap();
    assert_eq!(
        commit_artifact_counts(&repository, game).await,
        (0, 0, 0, 0)
    );

    let (healthy_worker, healthy_task) = end_turn_worker(true, "revision-1").await;
    let accepted = repository
        .execute_end_turn(&healthy_worker, owner, envelope.clone())
        .await
        .unwrap();
    healthy_task.await.unwrap();
    assert_eq!(accepted.previous_revision, 0);
    assert_eq!(accepted.committed_revision, 1);
    assert_eq!(
        commit_artifact_counts(&repository, game).await,
        (1, 1, 1, 1)
    );

    let unreachable_address = TcpListener::bind("127.0.0.1:0")
        .await
        .unwrap()
        .local_addr()
        .unwrap();
    let unreachable_worker = EngineWorkerClient::new(
        unreachable_address,
        Duration::from_millis(20),
        WorkerIdentityKey::for_test(),
    );
    let replayed = repository
        .execute_end_turn(&unreachable_worker, owner, envelope)
        .await
        .unwrap();
    assert_eq!(replayed, accepted);
    assert_eq!(
        commit_artifact_counts(&repository, game).await,
        (1, 1, 1, 1)
    );
    assert_eq!(
        repository
            .reconcile_authoritative_state()
            .await
            .unwrap()
            .total_findings,
        0
    );
}
