use super::*;
use crate::worker::{
    BarbarianMode, EngineWorkerClient, GeneratedMapShape, GeneratedMapSize, GeneratedMapType,
    MapResourceDensity, WORKER_PROTOCOL_VERSION, WorkerGameSetup, WorkerIdentityKey,
    read_authenticated_test_frame, write_authenticated_test_frame,
};
use tokio::net::TcpListener;

#[tokio::test]
#[ignore = "requires an explicit UNCIV_V3_DATABASE_URL"]
async fn owner_configuration_is_revisioned_authorized_idempotent_and_stale_safe() {
    let fixture = seeded_lobby().await;
    set_everyone_ready(&fixture).await;
    let operation_id = Uuid::new_v4();
    let mut updated_setup = setup();
    updated_setup.max_turns = 600;
    let update = LobbyConfigurationUpdate {
        display_name: "Reconfigured lobby".to_owned(),
        human_slots: 2,
        password: LobbyPasswordUpdate::Keep,
        setup: updated_setup,
    };
    let expected = vec![
        (fixture.owner.to_string(), "Rome".to_owned()),
        (fixture.player.to_string(), "Greece".to_owned()),
    ];
    let (worker, worker_task) = one_shot_reconfiguration_worker(expected, true).await;

    let summary = fixture
        .repository
        .reconfigure_lobby(
            &worker,
            fixture.owner,
            fixture.game,
            operation_id,
            0,
            update.clone(),
        )
        .await
        .unwrap();
    worker_task.await.unwrap();

    assert_eq!(summary.display_name, "Reconfigured lobby");
    assert_eq!(summary.committed_revision, 1);
    assert_eq!(summary.lobby_revision, 1);
    assert_eq!(summary.actor_civilization_id.as_deref(), Some("Rome"));
    assert!(summary.members.iter().all(|member| !member.ready));
    assert_eq!(
        summary
            .setup
            .get("maxTurns")
            .and_then(serde_json::Value::as_u64),
        Some(600),
    );

    let unavailable_worker = unreachable_worker();
    let duplicate = fixture
        .repository
        .reconfigure_lobby(
            &unavailable_worker,
            fixture.owner,
            fixture.game,
            operation_id,
            0,
            update.clone(),
        )
        .await
        .unwrap();
    assert_eq!(duplicate.committed_revision, 1);
    let mut changed = update.clone();
    changed.display_name = "Changed meaning".to_owned();
    assert_eq!(
        fixture
            .repository
            .reconfigure_lobby(
                &unavailable_worker,
                fixture.owner,
                fixture.game,
                operation_id,
                0,
                changed,
            )
            .await,
        Err(CommitError::InvalidCommand),
    );
    assert_eq!(
        fixture
            .repository
            .reconfigure_lobby(
                &unavailable_worker,
                fixture.owner,
                fixture.game,
                Uuid::new_v4(),
                0,
                update.clone(),
            )
            .await,
        Err(CommitError::Stale {
            expected: 0,
            actual: 1,
        }),
    );
    assert_eq!(
        fixture
            .repository
            .reconfigure_lobby(
                &unavailable_worker,
                fixture.outsider,
                fixture.game,
                Uuid::new_v4(),
                1,
                update,
            )
            .await,
        Err(CommitError::Unauthorized),
    );

    let revision: (String, Option<i64>, Option<Uuid>) = sqlx::query_as(
        "SELECT revision_kind, parent_revision, command_id
         FROM game_revisions WHERE game_id=$1 AND revision=1",
    )
    .bind(fixture.game)
    .fetch_one(&fixture.repository.pool)
    .await
    .unwrap();
    assert_eq!(
        revision,
        ("lobby_reconfiguration".to_owned(), Some(0), None)
    );
    assert_eq!(
        sqlx::query_scalar::<_, i64>(
            "SELECT count(*) FROM game_lobby_reconfiguration_operations
             WHERE game_id=$1 AND operation_id=$2 AND committed_revision=1",
        )
        .bind(fixture.game)
        .bind(operation_id)
        .fetch_one(&fixture.repository.pool)
        .await
        .unwrap(),
        1,
    );
    assert_eq!(
        sqlx::query_scalar::<_, i64>(
            "SELECT count(*) FROM game_outbox
             WHERE game_id=$1 AND revision=1 AND topic='game.lobby.reconfigured'",
        )
        .bind(fixture.game)
        .fetch_one(&fixture.repository.pool)
        .await
        .unwrap(),
        1,
    );
}

#[tokio::test]
#[ignore = "requires an explicit UNCIV_V3_DATABASE_URL"]
async fn faction_change_rolls_back_on_worker_failure_and_same_operation_retries_once() {
    let fixture = seeded_lobby().await;
    set_everyone_ready(&fixture).await;
    let operation_id = Uuid::new_v4();
    let expected = vec![
        (fixture.owner.to_string(), "Rome".to_owned()),
        (fixture.player.to_string(), "Japan".to_owned()),
    ];
    let (invalid_worker, invalid_task) =
        one_shot_reconfiguration_worker(expected.clone(), false).await;

    assert_eq!(
        fixture
            .repository
            .reselect_lobby_faction(
                &invalid_worker,
                fixture.player,
                fixture.game,
                operation_id,
                0,
                "Japan".to_owned(),
            )
            .await,
        Err(CommitError::WorkerRevisionMismatch),
    );
    invalid_task.await.unwrap();
    assert_eq!(head_revision(&fixture).await, 0);
    assert_eq!(operation_count(&fixture).await, 0);

    let (valid_worker, valid_task) = one_shot_reconfiguration_worker(expected, true).await;
    let summary = fixture
        .repository
        .reselect_lobby_faction(
            &valid_worker,
            fixture.player,
            fixture.game,
            operation_id,
            0,
            "Japan".to_owned(),
        )
        .await
        .unwrap();
    valid_task.await.unwrap();

    assert_eq!(summary.actor_civilization_id.as_deref(), Some("Japan"));
    assert_eq!(summary.committed_revision, 1);
    assert!(summary.members.iter().all(|member| !member.ready));
    assert_eq!(
        summary
            .members
            .iter()
            .find(|member| member.username == format!("account-{}", fixture.player))
            .map(|member| member.civilization_id.as_str()),
        Some("Japan"),
    );

    let unavailable_worker = unreachable_worker();
    let duplicate = fixture
        .repository
        .reselect_lobby_faction(
            &unavailable_worker,
            fixture.player,
            fixture.game,
            operation_id,
            0,
            "Japan".to_owned(),
        )
        .await
        .unwrap();
    assert_eq!(duplicate.committed_revision, 1);
    assert_eq!(
        fixture
            .repository
            .reselect_lobby_faction(
                &unavailable_worker,
                fixture.player,
                fixture.game,
                operation_id,
                0,
                "Egypt".to_owned(),
            )
            .await,
        Err(CommitError::InvalidCommand),
    );
    assert_eq!(head_revision(&fixture).await, 1);
    assert_eq!(operation_count(&fixture).await, 1);
}

struct LobbyFixture {
    repository: PostgresGameRepository,
    owner: Uuid,
    player: Uuid,
    outsider: Uuid,
    game: Uuid,
}

async fn seeded_lobby() -> LobbyFixture {
    let repository = PostgresGameRepository::connect(&database_url())
        .await
        .unwrap();
    repository.migrate().await.unwrap();
    sqlx::query(
        "TRUNCATE game_lobby_reconfiguration_operations, game_creation_operations,
         game_outbox, game_revisions, game_commands, game_snapshots, game_members,
         games, ruleset_manifests, accounts CASCADE",
    )
    .execute(&repository.pool)
    .await
    .unwrap();
    let owner = Uuid::new_v4();
    let player = Uuid::new_v4();
    let outsider = Uuid::new_v4();
    for account in [owner, player, outsider] {
        sqlx::query(
            "INSERT INTO accounts (id, username_normalized, password_hash)
             VALUES ($1, $2, 'test-hash')",
        )
        .bind(account)
        .bind(format!("account-{account}"))
        .execute(&repository.pool)
        .await
        .unwrap();
    }
    let manifest_hash = "a".repeat(64);
    sqlx::query(
        "INSERT INTO ruleset_manifests (hash, engine_build, manifest)
         VALUES ($1, 'engine-1', $2)",
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
    let (worker, worker_task) = one_shot_creation_worker().await;
    let game = repository
        .create_authoritative_game(
            &worker,
            owner,
            Uuid::new_v4(),
            manifest_hash,
            setup(),
            LobbyCreateConfiguration {
                display_name: "Test lobby".to_owned(),
                human_slots: 2,
                password_hash: None,
                password_identity: None,
                available_civilizations: vec!["Rome".to_owned(), "Greece".to_owned()],
            },
        )
        .await
        .unwrap();
    worker_task.await.unwrap();
    sqlx::query(
        "INSERT INTO game_members (game_id, account_id, role, civilization_id)
         VALUES ($1, $2, 'player', 'Greece')",
    )
    .bind(game)
    .bind(player)
    .execute(&repository.pool)
    .await
    .unwrap();
    sqlx::query(
        "INSERT INTO game_lobby_readiness (game_id, account_id)
         VALUES ($1, $2)",
    )
    .bind(game)
    .bind(player)
    .execute(&repository.pool)
    .await
    .unwrap();
    LobbyFixture {
        repository,
        owner,
        player,
        outsider,
        game,
    }
}

async fn set_everyone_ready(fixture: &LobbyFixture) {
    sqlx::query("UPDATE game_lobby_readiness SET ready=TRUE WHERE game_id=$1")
        .bind(fixture.game)
        .execute(&fixture.repository.pool)
        .await
        .unwrap();
}

async fn head_revision(fixture: &LobbyFixture) -> i64 {
    sqlx::query_scalar("SELECT head_revision FROM games WHERE id=$1")
        .bind(fixture.game)
        .fetch_one(&fixture.repository.pool)
        .await
        .unwrap()
}

async fn operation_count(fixture: &LobbyFixture) -> i64 {
    sqlx::query_scalar(
        "SELECT count(*) FROM game_lobby_reconfiguration_operations WHERE game_id=$1",
    )
    .bind(fixture.game)
    .fetch_one(&fixture.repository.pool)
    .await
    .unwrap()
}

async fn one_shot_creation_worker() -> (EngineWorkerClient, tokio::task::JoinHandle<()>) {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let address = listener.local_addr().unwrap();
    let task = tokio::spawn(async move {
        let (mut stream, _) = listener.accept().await.unwrap();
        let (nonce, request) = read_authenticated_test_frame(&mut stream).await;
        assert_eq!(request["operation"]["type"], "create_game");
        let snapshot = b"created lobby";
        write_authenticated_test_frame(
            &mut stream,
            nonce,
            json!({
                "protocolVersion": WORKER_PROTOCOL_VERSION,
                "serverTimeMillis": request["serverTimeMillis"],
                "snapshot": String::from_utf8_lossy(snapshot),
                "canonicalStateHash": state_hash(snapshot),
                "actorCivilizationId": "Rome",
                "availableCivilizationIds": ["Rome", "Greece", "Japan", "Egypt"],
            }),
        )
        .await;
    });
    (worker_client(address), task)
}

async fn one_shot_reconfiguration_worker(
    expected_participants: Vec<(String, String)>,
    valid_hash: bool,
) -> (EngineWorkerClient, tokio::task::JoinHandle<()>) {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let address = listener.local_addr().unwrap();
    let task = tokio::spawn(async move {
        let (mut stream, _) = listener.accept().await.unwrap();
        let (nonce, request) = read_authenticated_test_frame(&mut stream).await;
        assert_eq!(request["operation"]["type"], "reconfigure_lobby");
        assert!(request["operation"].get("snapshot").is_none());
        let participants = request["operation"]["participants"]
            .as_array()
            .unwrap()
            .iter()
            .map(|participant| {
                (
                    participant["accountId"].as_str().unwrap().to_owned(),
                    participant["civilizationId"].as_str().unwrap().to_owned(),
                )
            })
            .collect::<Vec<_>>();
        assert_eq!(participants, expected_participants);
        let snapshot = b"reconfigured lobby";
        write_authenticated_test_frame(
            &mut stream,
            nonce,
            json!({
                "protocolVersion": WORKER_PROTOCOL_VERSION,
                "serverTimeMillis": request["serverTimeMillis"],
                "snapshot": String::from_utf8_lossy(snapshot),
                "canonicalStateHash": if valid_hash {
                    state_hash(snapshot)
                } else {
                    "0".repeat(64)
                },
                "actorCivilizationId": "Rome",
                "availableCivilizationIds": ["Rome", "Greece", "Japan", "Egypt"],
            }),
        )
        .await;
    });
    (worker_client(address), task)
}

fn worker_client(address: std::net::SocketAddr) -> EngineWorkerClient {
    EngineWorkerClient::new(
        address,
        Duration::from_secs(2),
        WorkerIdentityKey::for_test(),
    )
}

fn unreachable_worker() -> EngineWorkerClient {
    worker_client("127.0.0.1:9".parse().unwrap())
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
