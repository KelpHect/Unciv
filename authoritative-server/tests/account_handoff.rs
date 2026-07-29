use reqwest::{Client, StatusCode};
use serde_json::{Value, json};
use sqlx::PgPool;
use std::net::{SocketAddr, TcpListener as StdTcpListener};
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};
use std::time::Duration;
use tokio::time::sleep;
use unciv_authoritative_server::postgres::PostgresGameRepository;
use unciv_authoritative_server::state_hash;
use unciv_authoritative_server::worker::{EngineWorkerClient, WorkerIdentityKey};
use uuid::Uuid;

const WORKER_SECRET_HEX: &str = "1111111111111111111111111111111111111111111111111111111111111111";

/// Production qualification for the protocol path shared by Android and desktop.
///
/// The test deliberately uses three independent bearer sessions: an Android-labelled
/// owner session, a freshly restored desktop session for that same account, and a
/// second human account. The owner creates a three-major lobby, the friend chooses
/// a faction, both become ready, and the owner starts it. The desktop session then
/// reopens the exact player projection without local state and resigns the current
/// civilization. The packaged Kotlin worker must run the intervening server AI turn
/// and hand control to the second human.
#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
#[ignore = "requires UNCIV_V3_DATABASE_URL and the packaged Kotlin worker jar"]
async fn android_to_desktop_handoff_reopens_exact_projection_and_server_ai_advances() {
    let database_url = std::env::var("UNCIV_V3_DATABASE_URL")
        .expect("UNCIV_V3_DATABASE_URL is required for PostgreSQL integration tests");
    let repository = PostgresGameRepository::connect(&database_url)
        .await
        .unwrap();
    repository.migrate().await.unwrap();
    let pool = PgPool::connect(&database_url).await.unwrap();
    truncate_qualification_data(&pool).await;

    let owner = repository
        .register_account("handoff-owner", "correct horse battery staple")
        .await
        .unwrap();
    let friend = repository
        .register_account("handoff-friend", "correct horse battery staple")
        .await
        .unwrap();
    let android_session = repository.issue_session(owner.id).await.unwrap();
    let desktop_session = repository.issue_session(owner.id).await.unwrap();
    let friend_session = repository.issue_session(friend.id).await.unwrap();

    let worker_jar = packaged_worker_jar();
    let assets = Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("..")
        .join("android")
        .join("assets");
    let worker_address = unused_address();
    let mut worker = spawn_worker(&worker_jar, &assets, worker_address);
    let capabilities = wait_for_worker(worker_address, &mut worker.0).await;
    let manifest_hash = register_vanilla_manifest(&pool, capabilities).await;

    let api_address = unused_address();
    let mut api = spawn_api(&database_url, api_address, worker_address);
    wait_until_api_ready(api_address, &mut api.0).await;
    let client = Client::builder()
        .timeout(Duration::from_secs(30))
        .build()
        .unwrap();
    let base_url = format!("http://{api_address}");

    let created = send_json(
        client
            .post(format!("{base_url}/api/v3/games"))
            .bearer_auth(&android_session.token)
            .json(&creation_request(&manifest_hash)),
        StatusCode::CREATED,
    )
    .await;
    let game_id = created["game_id"].as_str().unwrap();
    assert_eq!(created["committed_revision"], 0);
    let lobby = send_json(
        client
            .get(format!("{base_url}/api/v3/lobbies/{game_id}"))
            .bearer_auth(&friend_session.token),
        StatusCode::OK,
    )
    .await;
    let friend_civilization = lobby["available_civilizations"]
        .as_array()
        .unwrap()
        .iter()
        .filter_map(Value::as_str)
        .rfind(|civilization| *civilization != "Rome")
        .expect("the server-created game must expose a non-owner faction");

    let joined = send_json(
        client
            .post(format!("{base_url}/api/v3/games/{game_id}/join"))
            .bearer_auth(&friend_session.token)
            .json(&json!({
                "command_id": Uuid::new_v4(),
                "expected_revision": 0,
                "client_observed_state_hash": created["canonical_state_hash"],
                "civilization_id": friend_civilization,
                "password": null,
            })),
        StatusCode::OK,
    )
    .await;
    assert_eq!(joined["committed_revision"], 1);
    let alternative_civilization = lobby["available_civilizations"]
        .as_array()
        .unwrap()
        .iter()
        .filter_map(Value::as_str)
        .find(|civilization| *civilization != "Rome" && *civilization != friend_civilization)
        .expect("the canonical worker pool must contain another unclaimed faction");
    let faction_changed = send_json(
        client
            .put(format!("{base_url}/api/v3/lobbies/{game_id}/faction"))
            .bearer_auth(&friend_session.token)
            .json(&json!({
                "operation_id": Uuid::new_v4(),
                "expected_lobby_revision": 1,
                "civilization_id": alternative_civilization,
            })),
        StatusCode::OK,
    )
    .await;
    assert_eq!(faction_changed["committed_revision"], 2);
    assert_eq!(faction_changed["lobby_revision"], 2);
    assert_eq!(
        faction_changed["actor_civilization_id"],
        alternative_civilization,
    );
    let mut updated_setup = creation_request(&manifest_hash)["setup"].clone();
    updated_setup["max_turns"] = json!(600);
    updated_setup["map_seed"] = lobby["setup"]["mapSeed"].clone();
    let reconfigured = send_json(
        client
            .put(format!("{base_url}/api/v3/lobbies/{game_id}/configuration"))
            .bearer_auth(&android_session.token)
            .json(&json!({
                "operation_id": Uuid::new_v4(),
                "expected_lobby_revision": 2,
                "display_name": "Account handoff reconfigured",
                "human_slots": 2,
                "password": {"action": "keep"},
                "setup": updated_setup,
            })),
        StatusCode::OK,
    )
    .await;
    assert_eq!(reconfigured["committed_revision"], 3);
    assert_eq!(reconfigured["lobby_revision"], 3);
    assert_eq!(reconfigured["display_name"], "Account handoff reconfigured");
    assert!(
        reconfigured["members"]
            .as_array()
            .unwrap()
            .iter()
            .all(|member| member["ready"] == false),
    );
    let owner_ready = send_json(
        client
            .put(format!("{base_url}/api/v3/lobbies/{game_id}/ready"))
            .bearer_auth(&android_session.token)
            .json(&json!({"expected_lobby_revision": 3, "ready": true})),
        StatusCode::OK,
    )
    .await;
    assert_eq!(owner_ready["lobby_revision"], 4);
    let friend_ready = send_json(
        client
            .put(format!("{base_url}/api/v3/lobbies/{game_id}/ready"))
            .bearer_auth(&friend_session.token)
            .json(&json!({"expected_lobby_revision": 4, "ready": true})),
        StatusCode::OK,
    )
    .await;
    assert_eq!(friend_ready["lobby_revision"], 5);
    let started = send_json(
        client
            .post(format!("{base_url}/api/v3/lobbies/{game_id}/start"))
            .bearer_auth(&android_session.token)
            .json(&json!({"expected_lobby_revision": 5})),
        StatusCode::OK,
    )
    .await;
    assert_eq!(started["started"], true);

    let android_projection = projection(&client, &base_url, game_id, &android_session.token).await;
    let desktop_projection = projection(&client, &base_url, game_id, &desktop_session.token).await;
    assert_eq!(
        android_projection["canonical_state_hash"],
        desktop_projection["canonical_state_hash"]
    );
    assert_eq!(
        android_projection["projection_hash"],
        desktop_projection["projection_hash"]
    );
    assert_eq!(desktop_projection["committed_revision"], 3);
    assert_eq!(desktop_projection["projection"]["isCurrentTurn"], true);

    let resigned = send_json(
        client
            .post(format!("{base_url}/api/v3/games/{game_id}/commands/resign"))
            .bearer_auth(&desktop_session.token)
            .json(&json!({
                "command_id": Uuid::new_v4(),
                "expected_revision": 3,
                "client_observed_state_hash":
                    desktop_projection["canonical_state_hash"],
            })),
        StatusCode::OK,
    )
    .await;
    assert_eq!(resigned["committed_revision"], 4);

    let friend_projection = projection(&client, &base_url, game_id, &friend_session.token).await;
    assert_eq!(friend_projection["committed_revision"], 4);
    assert_eq!(friend_projection["projection"]["isCurrentTurn"], true);
    assert_ne!(
        friend_projection["canonical_state_hash"],
        desktop_projection["canonical_state_hash"]
    );
    assert_eq!(
        sqlx::query_scalar::<_, i64>("SELECT count(*) FROM game_commands WHERE game_id=$1",)
            .bind(Uuid::parse_str(game_id).unwrap())
            .fetch_one(&pool)
            .await
            .unwrap(),
        2,
        "join and resignation must be the only client-authored revisions"
    );

    api.stop();
    worker.stop();
}

async fn projection(client: &Client, base_url: &str, game_id: &str, token: &str) -> Value {
    send_json(
        client
            .get(format!("{base_url}/api/v3/games/{game_id}/projection"))
            .bearer_auth(token),
        StatusCode::OK,
    )
    .await
}

async fn send_json(request: reqwest::RequestBuilder, expected: StatusCode) -> Value {
    let response = request.send().await.unwrap();
    let status = response.status();
    let body = response.text().await.unwrap();
    assert_eq!(status, expected, "unexpected response {status}: {body}");
    serde_json::from_str(&body).unwrap()
}

fn creation_request(manifest_hash: &str) -> Value {
    json!({
        "operation_id": Uuid::new_v4(),
        "ruleset_manifest_hash": manifest_hash,
        "display_name": "Account handoff",
        "human_slots": 2,
        "password": null,
        "available_civilizations": ["Rome", "Greece", "Egypt"],
        "setup": {
            "owner_civilization_id": "Rome",
            "difficulty": "Prince",
            "speed": "Quick",
            "starting_era": "Ancient era",
            "victory_types": ["Domination"],
            "major_civilizations": 3,
            "city_states": 0,
            "max_turns": 500,
            "map_type": "pangaea",
            "map_shape": "hexagonal",
            "map_size": "tiny",
            "map_resources": "default",
            "barbarians": "disabled",
            "one_city_challenge": false,
            "nuclear_weapons_enabled": true,
            "espionage_enabled": true,
            "no_start_bias": false,
            "shuffle_player_order": false,
            "no_city_razing": true,
            "world_wrap": false,
            "strategic_balance": false,
            "legendary_start": false,
            "no_ruins": true,
            "no_natural_wonders": true
        }
    })
}

async fn register_vanilla_manifest(
    pool: &PgPool,
    capabilities: unciv_authoritative_server::worker::WorkerCapabilities,
) -> String {
    let base_ruleset = capabilities
        .installed_rulesets
        .into_iter()
        .find(|ruleset| ruleset.name == "Civ V - Vanilla")
        .expect("packaged worker must expose the vanilla ruleset");
    let manifest = json!({
        "engineBuild": capabilities.engine_build,
        "baseRuleset": {
            "name": base_ruleset.name,
            "sha256": base_ruleset.sha256,
        },
        "mods": [],
    });
    let manifest_hash = state_hash(&serde_json::to_vec(&manifest).unwrap());
    sqlx::query(
        "INSERT INTO ruleset_manifests (hash, engine_build, manifest)
         VALUES ($1, $2, $3)",
    )
    .bind(&manifest_hash)
    .bind(manifest["engineBuild"].as_str().unwrap())
    .bind(manifest)
    .execute(pool)
    .await
    .unwrap();
    sqlx::query("INSERT INTO ruleset_asset_versions (version_id, manifest_hash) VALUES ($1, $1)")
        .bind(&manifest_hash)
        .execute(pool)
        .await
        .unwrap();
    manifest_hash
}

async fn truncate_qualification_data(pool: &PgPool) {
    sqlx::query(
        "TRUNCATE game_creation_operations, game_outbox, game_revisions,
         game_commands, game_snapshots, game_members, games,
         ruleset_asset_versions, ruleset_manifests, sessions, accounts CASCADE",
    )
    .execute(pool)
    .await
    .unwrap();
}

fn packaged_worker_jar() -> PathBuf {
    let jar = Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("..")
        .join("server")
        .join("build")
        .join("libs")
        .join("UncivAuthoritativeWorker.jar");
    assert!(
        jar.is_file(),
        "run ./gradlew :server:authoritativeWorkerDist before this test"
    );
    jar
}

fn spawn_worker(jar: &Path, assets: &Path, address: SocketAddr) -> ChildGuard {
    ChildGuard(
        Command::new("java")
            .arg("-Djava.awt.headless=true")
            .arg("-jar")
            .arg(jar)
            .current_dir(assets)
            .env("UNCIV_ENGINE_WORKER_PORT", address.port().to_string())
            .env("UNCIV_ENGINE_WORKER_SECRET", WORKER_SECRET_HEX)
            .env("UNCIV_V3_UNPACKAGED_DEV", "1")
            .stdin(Stdio::null())
            .stdout(Stdio::null())
            .stderr(Stdio::inherit())
            .spawn()
            .unwrap(),
    )
}

fn spawn_api(
    database_url: &str,
    api_address: SocketAddr,
    worker_address: SocketAddr,
) -> ChildGuard {
    ChildGuard(
        Command::new(env!("CARGO_BIN_EXE_unciv-authoritative-server"))
            .env("UNCIV_V3_BIND", api_address.to_string())
            .env("UNCIV_V3_METRICS_BIND", unused_address().to_string())
            .env("UNCIV_V3_DATABASE_URL", database_url)
            .env("UNCIV_ENGINE_WORKER_ADDR", worker_address.to_string())
            .env("UNCIV_ENGINE_WORKER_SECRET", WORKER_SECRET_HEX)
            .env("UNCIV_V3_UNPACKAGED_DEV", "1")
            .stdin(Stdio::null())
            .stdout(Stdio::null())
            .stderr(Stdio::inherit())
            .spawn()
            .unwrap(),
    )
}

async fn wait_for_worker(
    address: SocketAddr,
    child: &mut Child,
) -> unciv_authoritative_server::worker::WorkerCapabilities {
    let key = WorkerIdentityKey::from_hex(WORKER_SECRET_HEX).unwrap();
    for _ in 0..600 {
        if let Some(status) = child.try_wait().unwrap() {
            panic!("packaged worker exited before readiness: {status}");
        }
        let client = EngineWorkerClient::new(address, Duration::from_secs(2), key.clone());
        if let Ok(capabilities) = client.handshake().await {
            return capabilities;
        }
        sleep(Duration::from_millis(25)).await;
    }
    panic!("packaged worker did not become ready");
}

async fn wait_until_api_ready(address: SocketAddr, child: &mut Child) {
    let client = Client::new();
    for _ in 0..400 {
        if let Some(status) = child.try_wait().unwrap() {
            panic!("authoritative API exited before readiness: {status}");
        }
        if client
            .get(format!("http://{address}/healthz"))
            .send()
            .await
            .is_ok_and(|response| response.status() == StatusCode::OK)
        {
            return;
        }
        sleep(Duration::from_millis(25)).await;
    }
    panic!("authoritative API did not become ready");
}

fn unused_address() -> SocketAddr {
    let listener = StdTcpListener::bind("127.0.0.1:0").unwrap();
    let address = listener.local_addr().unwrap();
    drop(listener);
    address
}

struct ChildGuard(Child);

impl ChildGuard {
    fn stop(&mut self) {
        let _ = self.0.kill();
        let _ = self.0.wait();
    }
}

impl Drop for ChildGuard {
    fn drop(&mut self) {
        self.stop();
    }
}
