use super::*;
use crate::worker::{
    BarbarianMode, EngineWorkerClient, GeneratedMapShape, GeneratedMapSize, GeneratedMapType,
    MapResourceDensity, WORKER_PROTOCOL_VERSION, WorkerGameSetup, WorkerIdentityKey,
    read_authenticated_test_frame, write_authenticated_test_frame,
};
use tokio::net::TcpListener;

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
            lobby(),
        ),
        repository.create_authoritative_game(
            &worker,
            owner,
            operation_id,
            manifest_hash.clone(),
            setup.clone(),
            lobby(),
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
                lobby(),
            )
            .await,
        Err(CommitError::InvalidCommand),
    );
    assert_eq!(
        repository
            .create_authoritative_game(&worker, other, operation_id, manifest_hash, setup, lobby())
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
    assert_eq!(
        sqlx::query_scalar::<_, Vec<String>>(
            "SELECT available_civilizations FROM game_lobbies WHERE game_id=$1",
        )
        .bind(first)
        .fetch_one(&repository.pool)
        .await
        .unwrap(),
        vec!["Rome".to_owned(), "Japan".to_owned()],
        "the private worker's canonical factions replace the untrusted client hint",
    );
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
                lobby(),
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
        .create_authoritative_game(
            &valid_worker,
            owner,
            operation_id,
            manifest_hash,
            setup(),
            lobby(),
        )
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

#[tokio::test]
#[ignore = "requires an explicit UNCIV_V3_DATABASE_URL"]
async fn game_creation_requires_a_registered_asset_version_before_worker_contact() {
    let (repository, owner, _, manifest_hash) = seeded_repository().await;
    sqlx::query("DELETE FROM ruleset_asset_versions WHERE manifest_hash=$1")
        .bind(&manifest_hash)
        .execute(&repository.pool)
        .await
        .unwrap();
    let worker = EngineWorkerClient::new(
        "127.0.0.1:9".parse().unwrap(),
        std::time::Duration::from_millis(10),
        WorkerIdentityKey::for_test(),
    );
    assert_eq!(
        repository
            .create_authoritative_game(
                &worker,
                owner,
                Uuid::new_v4(),
                manifest_hash,
                setup(),
                lobby(),
            )
            .await,
        Err(CommitError::NotFound),
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
    sqlx::query("INSERT INTO ruleset_asset_versions (version_id, manifest_hash) VALUES ($1, $1)")
        .bind(&manifest_hash)
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
        let (nonce, request) = read_authenticated_test_frame(&mut stream).await;
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
        write_authenticated_test_frame(
            &mut stream,
            nonce,
            json!({
                "protocolVersion": WORKER_PROTOCOL_VERSION,
                "serverTimeMillis": server_time_millis,
                "snapshot": snapshot,
                "canonicalStateHash": canonical_hash,
                "actorCivilizationId": "Rome",
                "availableCivilizationIds": ["Rome", "Japan"],
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
        owner_civilization_id: "Rome".to_owned(),
        ..WorkerGameSetup::default()
    }
}

fn lobby() -> LobbyCreateConfiguration {
    LobbyCreateConfiguration {
        display_name: "Test lobby".to_owned(),
        human_slots: 2,
        password_hash: None,
        password_identity: None,
        available_civilizations: vec!["Rome".to_owned(), "Greece".to_owned()],
    }
}
