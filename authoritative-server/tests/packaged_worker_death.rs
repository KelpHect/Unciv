use std::{
    net::{SocketAddr, TcpListener as StdTcpListener},
    path::{Path, PathBuf},
    process::{Child, Command, Stdio},
    time::Duration,
};

use serde_json::{Value, json};
use sqlx::PgPool;
use tokio::{
    io::{AsyncReadExt, AsyncWriteExt},
    net::{TcpListener, TcpStream},
    sync::oneshot,
    time::{sleep, timeout},
};
use unciv_authoritative_server::{
    postgres::PostgresGameRepository,
    state_hash,
    worker::{EngineWorkerClient, WorkerIdentityKey},
};
use uuid::Uuid;

const WORKER_SECRET_HEX: &str = "5555555555555555555555555555555555555555555555555555555555555555";

struct ChildGuard(Child);

impl ChildGuard {
    fn stop(&mut self) {
        if self.0.try_wait().unwrap().is_none() {
            self.0.kill().unwrap();
        }
        self.0.wait().unwrap();
    }
}

impl Drop for ChildGuard {
    fn drop(&mut self) {
        let _ = self.0.kill();
        let _ = self.0.wait();
    }
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
#[ignore = "requires UNCIV_V3_DATABASE_URL and the packaged Kotlin worker jar"]
async fn packaged_worker_death_during_creation_leaves_no_game_and_retry_succeeds() {
    let database_url = std::env::var("UNCIV_V3_DATABASE_URL")
        .expect("UNCIV_V3_DATABASE_URL is required for PostgreSQL integration tests");
    let worker_jar = packaged_worker_jar();
    let assets = Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("..")
        .join("android")
        .join("assets");
    let repository = PostgresGameRepository::connect(&database_url)
        .await
        .unwrap();
    repository.migrate().await.unwrap();
    let pool = PgPool::connect(&database_url).await.unwrap();
    sqlx::query(
        "TRUNCATE game_creation_operations, game_outbox, game_revisions,
         game_commands, game_snapshots, game_members, games, ruleset_manifests,
         sessions, accounts CASCADE",
    )
    .execute(&pool)
    .await
    .unwrap();
    let account = repository
        .register_account("packaged-worker-death", "correct horse battery staple")
        .await
        .unwrap();
    let session = repository.issue_session(account.id).await.unwrap();
    let operation_id = Uuid::new_v4();

    let first_worker_address = unused_address();
    let mut first_worker = spawn_worker(&worker_jar, &assets, first_worker_address);
    let capabilities = wait_for_worker(first_worker_address, &mut first_worker.0).await;
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
    .execute(&pool)
    .await
    .unwrap();

    let proxy_listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let proxy_address = proxy_listener.local_addr().unwrap();
    let (forwarded_tx, forwarded_rx) = oneshot::channel();
    let (worker_killed_tx, worker_killed_rx) = oneshot::channel();
    let proxy_task = tokio::spawn(run_terminating_proxy(
        proxy_listener,
        first_worker_address,
        forwarded_tx,
        worker_killed_rx,
    ));
    let first_api_address = unused_address();
    let mut first_api = spawn_api(&database_url, first_api_address, proxy_address);
    wait_until_api_ready(first_api_address, &mut first_api.0).await;

    let creation_body = creation_request(operation_id, &manifest_hash);
    let first_request = tokio::spawn(send_http(
        first_api_address,
        game_creation_http_request(first_api_address, &session.token, &creation_body),
    ));
    timeout(Duration::from_secs(10), forwarded_rx)
        .await
        .expect("creation request was not forwarded to the packaged worker")
        .unwrap();
    first_worker.stop();
    worker_killed_tx.send(()).unwrap();
    let failed_response = first_request.await.unwrap();
    let (failed_status, failed_body) = split_http_response(&failed_response);
    assert!(
        failed_status.starts_with("HTTP/1.1 502"),
        "{failed_status}\n{failed_body}"
    );
    proxy_task.await.unwrap();
    first_api.stop();
    assert_eq!(creation_artifact_counts(&pool).await, (0, 0, 0, 0, 0));

    let retry_worker_address = unused_address();
    let mut retry_worker = spawn_worker(&worker_jar, &assets, retry_worker_address);
    wait_for_worker(retry_worker_address, &mut retry_worker.0).await;
    let retry_api_address = unused_address();
    let mut retry_api = spawn_api(&database_url, retry_api_address, retry_worker_address);
    wait_until_api_ready(retry_api_address, &mut retry_api.0).await;
    let retry_response = send_http(
        retry_api_address,
        game_creation_http_request(retry_api_address, &session.token, &creation_body),
    )
    .await;
    let (retry_status, retry_body) = split_http_response(&retry_response);
    assert!(
        retry_status.starts_with("HTTP/1.1 201"),
        "{retry_status}\n{retry_body}"
    );
    let metadata: Value = serde_json::from_str(retry_body).unwrap();
    assert_eq!(metadata["committed_revision"], 0);
    assert_eq!(metadata["role"], "owner");
    assert_eq!(creation_artifact_counts(&pool).await, (1, 1, 1, 1, 1));

    let duplicate_response = send_http(
        retry_api_address,
        game_creation_http_request(retry_api_address, &session.token, &creation_body),
    )
    .await;
    let (duplicate_status, duplicate_body) = split_http_response(&duplicate_response);
    assert!(
        duplicate_status.starts_with("HTTP/1.1 201"),
        "{duplicate_status}\n{duplicate_body}"
    );
    assert_eq!(
        serde_json::from_str::<Value>(duplicate_body).unwrap()["game_id"],
        metadata["game_id"]
    );
    assert_eq!(creation_artifact_counts(&pool).await, (1, 1, 1, 1, 1));

    retry_api.stop();
    retry_worker.stop();
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
            .env("UNCIV_V3_DATABASE_URL", database_url)
            .env("UNCIV_ENGINE_WORKER_ADDR", worker_address.to_string())
            .env("UNCIV_ENGINE_WORKER_SECRET", WORKER_SECRET_HEX)
            .stdin(Stdio::null())
            .stdout(Stdio::null())
            .stderr(Stdio::null())
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
    for _ in 0..400 {
        if let Some(status) = child.try_wait().unwrap() {
            panic!("authoritative API exited before readiness: {status}");
        }
        if let Ok(response) = timeout(
            Duration::from_millis(100),
            send_http(
                address,
                "GET /healthz HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n".to_owned(),
            ),
        )
        .await
            && response.starts_with("HTTP/1.1 200")
        {
            return;
        }
        sleep(Duration::from_millis(25)).await;
    }
    panic!("authoritative API did not become ready");
}

async fn run_terminating_proxy(
    listener: TcpListener,
    worker_address: SocketAddr,
    forwarded: oneshot::Sender<()>,
    worker_killed: oneshot::Receiver<()>,
) {
    let (mut api_handshake, _) = listener.accept().await.unwrap();
    let mut worker_handshake = connect_worker(worker_address).await;
    tokio::io::copy_bidirectional(&mut api_handshake, &mut worker_handshake)
        .await
        .unwrap();

    let (mut api_command, _) = listener.accept().await.unwrap();
    let mut worker_command = connect_worker(worker_address).await;
    let frame_size = api_command.read_u32().await.unwrap();
    let mut frame = vec![0_u8; 16 + 32 + frame_size as usize];
    api_command.read_exact(&mut frame).await.unwrap();
    worker_command.write_u32(frame_size).await.unwrap();
    worker_command.write_all(&frame).await.unwrap();
    worker_command.flush().await.unwrap();
    forwarded.send(()).unwrap();
    worker_killed.await.unwrap();
}

async fn connect_worker(address: SocketAddr) -> TcpStream {
    for _ in 0..600 {
        if let Ok(stream) = TcpStream::connect(address).await {
            return stream;
        }
        sleep(Duration::from_millis(25)).await;
    }
    panic!("proxy could not connect to packaged worker");
}

fn creation_request(operation_id: Uuid, manifest_hash: &str) -> String {
    json!({
        "operation_id": operation_id,
        "ruleset_manifest_hash": manifest_hash,
        "setup": {
            "difficulty": "Prince",
            "speed": "Standard",
            "starting_era": "Ancient era",
            "victory_types": ["Domination"],
            "major_civilizations": 2,
            "city_states": 0,
            "max_turns": 500,
            "map_type": "pangaea",
            "map_shape": "hexagonal",
            "map_size": "tiny",
            "map_resources": "default",
            "barbarians": "normal",
            "one_city_challenge": false,
            "nuclear_weapons_enabled": true,
            "espionage_enabled": true,
            "no_start_bias": false,
            "shuffle_player_order": false,
            "no_city_razing": false,
            "world_wrap": false,
            "strategic_balance": false,
            "legendary_start": false,
            "no_ruins": false,
            "no_natural_wonders": false,
            "minutes_until_skip_turn": 1440,
            "minutes_until_force_resign": 4320,
            "minutes_recovered_per_turn": 1440
        }
    })
    .to_string()
}

fn game_creation_http_request(address: SocketAddr, bearer_token: &str, body: &str) -> String {
    format!(
        "POST /api/v3/games HTTP/1.1\r\n\
         Host: {address}\r\n\
         Authorization: Bearer {bearer_token}\r\n\
         Content-Type: application/json\r\n\
         Content-Length: {}\r\n\
         Connection: close\r\n\r\n\
         {body}",
        body.len()
    )
}

async fn send_http(address: SocketAddr, request: String) -> String {
    let mut stream = TcpStream::connect(address).await.unwrap();
    stream.write_all(request.as_bytes()).await.unwrap();
    let mut response = Vec::new();
    stream.read_to_end(&mut response).await.unwrap();
    String::from_utf8(response).unwrap()
}

fn split_http_response(response: &str) -> (&str, &str) {
    let (headers, body) = response.split_once("\r\n\r\n").unwrap();
    (headers.lines().next().unwrap(), body)
}

fn unused_address() -> SocketAddr {
    let listener = StdTcpListener::bind("127.0.0.1:0").unwrap();
    let address = listener.local_addr().unwrap();
    drop(listener);
    address
}

async fn creation_artifact_counts(pool: &PgPool) -> (i64, i64, i64, i64, i64) {
    sqlx::query_as(
        "SELECT
            (SELECT count(*) FROM games),
            (SELECT count(*) FROM game_creation_operations),
            (SELECT count(*) FROM game_revisions),
            (SELECT count(*) FROM game_snapshots),
            (SELECT count(*) FROM game_members)",
    )
    .fetch_one(pool)
    .await
    .unwrap()
}
