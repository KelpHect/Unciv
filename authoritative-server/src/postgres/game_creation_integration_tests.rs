use super::*;
use crate::worker::{
    BarbarianMode, EngineWorkerClient, GeneratedMapShape, GeneratedMapSize, GeneratedMapType,
    MapResourceDensity, WORKER_PROTOCOL_VERSION, WorkerGameSetup,
};
use tokio::{
    io::{AsyncReadExt, AsyncWriteExt},
    net::TcpListener,
};

#[tokio::test]
#[ignore = "requires an explicit UNCIV_V3_DATABASE_URL"]
async fn game_creation_is_retry_safe_actor_bound_and_meaning_bound() {
    let (repository, owner, other, manifest_hash) = seeded_repository().await;

    let (worker, worker_task) = one_shot_creation_worker(true).await;
    let operation_id = Uuid::new_v4();
    let setup = setup();
    let (first, retry) = tokio::join!(
        repository.create_authoritative_game(
            &worker,
            owner,
            operation_id,
            manifest_hash.clone(),
            setup.clone(),
        ),
        repository.create_authoritative_game(
            &worker,
            owner,
            operation_id,
            manifest_hash.clone(),
            setup.clone(),
        ),
    );
    let first = first.unwrap();
    let retry = retry.unwrap();
    assert_eq!(first, retry);
    worker_task.await.unwrap();

    let mut changed = setup.clone();
    changed.max_turns += 1;
    assert_eq!(
        repository
            .create_authoritative_game(
                &worker,
                owner,
                operation_id,
                manifest_hash.clone(),
                changed,
            )
            .await,
        Err(CommitError::InvalidCommand),
    );
    assert_eq!(
        repository
            .create_authoritative_game(&worker, other, operation_id, manifest_hash, setup)
            .await,
        Err(CommitError::InvalidCommand),
    );
    assert_eq!(
        sqlx::query_scalar::<_, i64>("SELECT count(*) FROM games")
            .fetch_one(&repository.pool)
            .await
            .unwrap(),
        1,
    );
    assert_eq!(
        sqlx::query_scalar::<_, i64>("SELECT count(*) FROM game_creation_operations")
            .fetch_one(&repository.pool)
            .await
            .unwrap(),
        1,
    );
    let replay_context: (bool, bool, bool) = sqlx::query_as(
        "SELECT replay_context_available, server_seed IS NOT NULL, server_time_millis IS NOT NULL FROM game_creation_operations WHERE operation_id=$1",
    )
    .bind(operation_id)
    .fetch_one(&repository.pool)
    .await
    .unwrap();
    assert_eq!(replay_context, (true, true, true));
}

#[tokio::test]
#[ignore = "requires an explicit UNCIV_V3_DATABASE_URL"]
async fn failed_creation_leaves_no_record_and_the_same_id_can_retry() {
    let (repository, owner, _, manifest_hash) = seeded_repository().await;
    let operation_id = Uuid::new_v4();
    let (invalid_worker, invalid_task) = one_shot_creation_worker(false).await;

    assert_eq!(
        repository
            .create_authoritative_game(
                &invalid_worker,
                owner,
                operation_id,
                manifest_hash.clone(),
                setup(),
            )
            .await,
        Err(CommitError::InvalidSnapshotHash),
    );
    invalid_task.await.unwrap();
    assert_eq!(
        sqlx::query_scalar::<_, i64>("SELECT count(*) FROM game_creation_operations")
            .fetch_one(&repository.pool)
            .await
            .unwrap(),
        0,
    );
    assert_eq!(
        sqlx::query_scalar::<_, i64>("SELECT count(*) FROM games")
            .fetch_one(&repository.pool)
            .await
            .unwrap(),
        0,
    );

    let (valid_worker, valid_task) = one_shot_creation_worker(true).await;
    repository
        .create_authoritative_game(&valid_worker, owner, operation_id, manifest_hash, setup())
        .await
        .unwrap();
    valid_task.await.unwrap();
    assert_eq!(
        sqlx::query_scalar::<_, i64>("SELECT count(*) FROM games")
            .fetch_one(&repository.pool)
            .await
            .unwrap(),
        1,
    );
}

async fn seeded_repository() -> (PostgresGameRepository, Uuid, Uuid, String) {
    let repository = PostgresGameRepository::connect(&database_url())
        .await
        .unwrap();
    repository.migrate().await.unwrap();
    sqlx::query(
        "TRUNCATE game_creation_operations, game_outbox, game_revisions, game_commands, game_snapshots, game_members, games, ruleset_manifests, accounts CASCADE",
    )
    .execute(&repository.pool)
    .await
    .unwrap();
    let owner = Uuid::new_v4();
    let other = Uuid::new_v4();
    for account in [owner, other] {
        sqlx::query(
            "INSERT INTO accounts (id, username_normalized, password_hash) VALUES ($1, $2, 'test-hash')",
        )
        .bind(account)
        .bind(format!("account-{account}"))
        .execute(&repository.pool)
        .await
        .unwrap();
    }
    let manifest_hash = "a".repeat(64);
    sqlx::query(
        "INSERT INTO ruleset_manifests (hash, engine_build, manifest) VALUES ($1, 'engine-1', $2)",
    )
    .bind(&manifest_hash)
    .bind(json!({
        "engineBuild": "engine-1",
        "baseRuleset": {"name": "Base", "sha256": "b".repeat(64)},
        "mods": [],
    }))
    .execute(&repository.pool)
    .await
    .unwrap();
    (repository, owner, other, manifest_hash)
}

async fn one_shot_creation_worker(
    valid_hash: bool,
) -> (EngineWorkerClient, tokio::task::JoinHandle<()>) {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let address = listener.local_addr().unwrap();
    let task = tokio::spawn(async move {
        let (mut stream, _) = listener.accept().await.unwrap();
        let size = stream.read_u32().await.unwrap() as usize;
        let mut request = vec![0; size];
        stream.read_exact(&mut request).await.unwrap();
        let request: serde_json::Value = serde_json::from_slice(&request).unwrap();
        assert_eq!(request["operation"]["type"], "create_game");
        assert!(request["operation"].get("snapshot").is_none());
        let server_time_millis = request["serverTimeMillis"]
            .as_i64()
            .expect("control plane supplies a replayable server timestamp");

        let snapshot = "worker-created-revision-zero";
        let canonical_hash = if valid_hash {
            state_hash(snapshot.as_bytes())
        } else {
            "0".repeat(64)
        };
        let response = serde_json::to_vec(&json!({
            "protocolVersion": WORKER_PROTOCOL_VERSION,
            "serverTimeMillis": server_time_millis,
            "snapshot": snapshot,
            "canonicalStateHash": canonical_hash,
            "actorCivilizationId": "Owner civilization",
        }))
        .unwrap();
        stream.write_u32(response.len() as u32).await.unwrap();
        stream.write_all(&response).await.unwrap();
    });
    (
        EngineWorkerClient::new(address, Duration::from_secs(2)),
        task,
    )
}

fn setup() -> WorkerGameSetup {
    WorkerGameSetup {
        difficulty: "Prince".to_owned(),
        speed: "Standard".to_owned(),
        starting_era: "Ancient era".to_owned(),
        victory_types: vec!["Domination".to_owned()],
        major_civilizations: 4,
        city_states: 6,
        max_turns: 500,
        map_type: GeneratedMapType::Pangaea,
        map_shape: GeneratedMapShape::Hexagonal,
        map_size: GeneratedMapSize::Medium,
        map_resources: MapResourceDensity::Default,
        barbarians: BarbarianMode::Normal,
        one_city_challenge: false,
        nuclear_weapons_enabled: true,
        espionage_enabled: true,
        no_start_bias: false,
        shuffle_player_order: false,
        no_city_razing: false,
        world_wrap: false,
        strategic_balance: false,
        legendary_start: false,
        no_ruins: false,
        no_natural_wonders: false,
        minutes_until_skip_turn: 1_440,
        minutes_until_force_resign: 4_320,
        minutes_recovered_per_turn: 1_440,
    }
}
