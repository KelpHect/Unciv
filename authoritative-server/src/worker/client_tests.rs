use serde_json::Value;
use tokio::net::TcpListener;

use super::{
    EngineWorkerClient, WORKER_PROTOCOL_VERSION, WorkerClientError, WorkerIdentityKey,
    WorkerManifest, WorkerOperation, WorkerRuleset,
    transport::{read_authenticated_test_frame, write_authenticated_test_frame},
};
use std::time::Duration;

#[tokio::test]
async fn handshake_uses_the_versioned_actorless_contract() {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let address = listener.local_addr().unwrap();
    let server = tokio::spawn(async move {
        let (mut stream, _) = listener.accept().await.unwrap();
        let (nonce, request): (_, Value) = read_authenticated_test_frame(&mut stream).await;
        assert_eq!(request["protocolVersion"], WORKER_PROTOCOL_VERSION);
        assert_eq!(request["operation"]["type"], "handshake");
        assert!(request.get("actorId").is_none());
        assert!(request.get("rulesetManifest").is_none());

        write_authenticated_test_frame(
            &mut stream,
            nonce,
            serde_json::json!({
                "protocolVersion": WORKER_PROTOCOL_VERSION,
                "releaseBundleId": "dev-unpackaged",
                "engineBuild": "4.21.1",
                "installedRulesets": [{
                    "name": "Civ V - Vanilla",
                    "sha256": "a".repeat(64),
                }],
            }),
        )
        .await;
    });

    let capabilities = EngineWorkerClient::new(
        address,
        Duration::from_secs(1),
        WorkerIdentityKey::for_test(),
    )
    .handshake()
    .await
    .unwrap();
    assert_eq!(capabilities.release_bundle_id, "dev-unpackaged");
    assert_eq!(capabilities.engine_build, "4.21.1");
    assert_eq!(capabilities.installed_rulesets.len(), 1);
    server.await.unwrap();
}

#[tokio::test]
async fn worker_cannot_rewrite_the_control_plane_timestamp() {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let address = listener.local_addr().unwrap();
    let server = tokio::spawn(async move {
        let (mut stream, _) = listener.accept().await.unwrap();
        let (nonce, request): (_, Value) = read_authenticated_test_frame(&mut stream).await;
        let supplied = request["serverTimeMillis"].as_i64().unwrap();
        write_authenticated_test_frame(
            &mut stream,
            nonce,
            serde_json::json!({
                "protocolVersion": WORKER_PROTOCOL_VERSION,
                "serverTimeMillis": supplied + 1,
            }),
        )
        .await;
    });
    let manifest = manifest();
    let result = EngineWorkerClient::new(
        address,
        Duration::from_secs(1),
        WorkerIdentityKey::for_test(),
    )
    .execute("account-1", &manifest, WorkerOperation::Handshake)
    .await;
    assert!(matches!(result, Err(WorkerClientError::Protocol)));
    server.await.unwrap();
}

#[tokio::test]
async fn commit_proposal_retains_the_exact_operation_without_snapshot_bytes() {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let address = listener.local_addr().unwrap();
    let server = tokio::spawn(async move {
        let (mut stream, _) = listener.accept().await.unwrap();
        let (nonce, request): (_, Value) = read_authenticated_test_frame(&mut stream).await;
        let supplied = request["serverTimeMillis"].as_i64().unwrap();
        assert_eq!(request["operation"]["snapshot"], "canonical-before");
        write_authenticated_test_frame(
            &mut stream,
            nonce,
            serde_json::json!({
                "protocolVersion": WORKER_PROTOCOL_VERSION,
                "serverTimeMillis": supplied,
                "snapshot": "canonical-after",
                "canonicalStateHash": "a".repeat(64),
            }),
        )
        .await;
    });
    let proposal = EngineWorkerClient::new(
        address,
        Duration::from_secs(1),
        WorkerIdentityKey::for_test(),
    )
    .end_turn("account-1", &manifest(), 7, "canonical-before", "Rome")
    .await
    .unwrap();
    assert_eq!(proposal.previous_revision, 7);
    assert_eq!(proposal.replay_operation["type"], "end_turn");
    assert_eq!(proposal.replay_operation["actorCivilizationId"], "Rome");
    assert!(proposal.replay_operation.get("snapshot").is_none());
    server.await.unwrap();
}

#[tokio::test]
async fn replay_injects_only_the_validated_snapshot_and_original_timestamp() {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let address = listener.local_addr().unwrap();
    let server = tokio::spawn(async move {
        let (mut stream, _) = listener.accept().await.unwrap();
        let (nonce, request): (_, Value) = read_authenticated_test_frame(&mut stream).await;
        assert_eq!(request["serverTimeMillis"], 1_700_000_000_000_i64);
        assert_eq!(request["operation"]["snapshot"], "validated-prior");
        assert_eq!(request["operation"]["type"], "end_turn");
        write_authenticated_test_frame(
            &mut stream,
            nonce,
            serde_json::json!({
                "protocolVersion": WORKER_PROTOCOL_VERSION,
                "serverTimeMillis": 1_700_000_000_000_i64,
                "snapshot": "replayed-next",
                "canonicalStateHash": "b".repeat(64),
            }),
        )
        .await;
    });
    let replay_operation = serde_json::json!({
        "type": "end_turn",
        "actorCivilizationId": "Rome",
    });
    let proposal = EngineWorkerClient::new(
        address,
        Duration::from_secs(1),
        WorkerIdentityKey::for_test(),
    )
    .replay_operation(
        3,
        "account-1",
        &manifest(),
        1_700_000_000_000,
        "validated-prior",
        replay_operation.clone(),
    )
    .await
    .unwrap();
    assert_eq!(proposal.previous_revision, 3);
    assert_eq!(proposal.replay_operation, replay_operation);
    assert_eq!(proposal.snapshot, b"replayed-next");
    server.await.unwrap();
}

fn manifest() -> WorkerManifest {
    WorkerManifest {
        engine_build: "engine-1".to_owned(),
        base_ruleset: WorkerRuleset {
            name: "Base".to_owned(),
            sha256: "a".repeat(64),
        },
        mods: Vec::new(),
    }
}
