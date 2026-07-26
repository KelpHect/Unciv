use std::{
    net::{SocketAddr, TcpListener as StdTcpListener},
    process::{Child, Command, Stdio},
    sync::{
        Arc,
        atomic::{AtomicUsize, Ordering},
    },
    time::Duration,
};

use hmac::{Hmac, KeyInit, Mac};
use serde_json::{Value, json};
use sha2::Sha256;
use sqlx::PgPool;
use tokio::{
    io::{AsyncReadExt, AsyncWriteExt},
    net::{TcpListener, TcpStream},
    time::{sleep, timeout},
};
use unciv_authoritative_server::{
    postgres::{NewGame, PostgresGameRepository},
    state_hash,
};
use uuid::Uuid;

const WORKER_PROTOCOL_VERSION: u16 = 2;
const WORKER_SECRET_HEX: &str = "5555555555555555555555555555555555555555555555555555555555555555";
const WORKER_KEY: [u8; 32] = [0x55; 32];
const REQUEST_DOMAIN: &[u8] = b"UNCIV-WORKER-V2\0request\0";
const RESPONSE_DOMAIN: &[u8] = b"UNCIV-WORKER-V2\0response\0";

struct ChildGuard(Child);

impl Drop for ChildGuard {
    fn drop(&mut self) {
        let _ = self.0.kill();
        let _ = self.0.wait();
    }
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
#[ignore = "requires an explicit UNCIV_V3_DATABASE_URL"]
async fn lost_http_response_retries_without_reexecuting_the_worker() {
    let database_url = std::env::var("UNCIV_V3_DATABASE_URL")
        .expect("UNCIV_V3_DATABASE_URL is required for PostgreSQL integration tests");
    let repository = PostgresGameRepository::connect(&database_url)
        .await
        .unwrap();
    repository.migrate().await.unwrap();
    let pool = PgPool::connect(&database_url).await.unwrap();
    sqlx::query(
        "TRUNCATE game_outbox, game_revisions, game_commands, game_snapshots,
         game_members, games, ruleset_manifests, sessions, accounts CASCADE",
    )
    .execute(&pool)
    .await
    .unwrap();

    let account = repository
        .register_account("http-fault-owner", "correct horse battery staple")
        .await
        .unwrap();
    let session = repository.issue_session(account.id).await.unwrap();
    let game_id = Uuid::new_v4();
    let command_id = Uuid::new_v4();
    let manifest_hash = "a".repeat(64);
    sqlx::query(
        "INSERT INTO ruleset_manifests (hash, engine_build, manifest)
         VALUES ($1, 'test-engine', $2)",
    )
    .bind(&manifest_hash)
    .bind(json!({
        "engineBuild": "test-engine",
        "baseRuleset": {
            "name": "Base",
            "sha256": "b".repeat(64),
        },
        "mods": [],
    }))
    .execute(&pool)
    .await
    .unwrap();
    repository
        .create_game(NewGame {
            game_id,
            owner_account_id: account.id,
            ruleset_manifest_hash: manifest_hash,
            snapshot: b"revision-0".to_vec(),
            owner_civilization_id: "test-civilization".to_owned(),
        })
        .await
        .unwrap();

    let worker_listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let worker_address = worker_listener.local_addr().unwrap();
    let command_executions = Arc::new(AtomicUsize::new(0));
    let worker_task = tokio::spawn(run_worker(worker_listener, Arc::clone(&command_executions)));

    let api_address = unused_address();
    let mut api = spawn_api(&database_url, api_address, worker_address);
    wait_until_ready(api_address, &mut api.0).await;

    let request_body = json!({
        "command_id": command_id,
        "expected_revision": 0,
        "client_observed_state_hash": null,
    })
    .to_string();
    let first_request = command_request(api_address, game_id, &session.token, &request_body);
    let mut abandoned_response = TcpStream::connect(api_address).await.unwrap();
    abandoned_response
        .write_all(first_request.as_bytes())
        .await
        .unwrap();

    wait_for_command(&pool, game_id, command_id).await;
    drop(abandoned_response);

    let retry_response = send_http(
        api_address,
        &command_request(api_address, game_id, &session.token, &request_body),
    )
    .await;
    let (status, body) = split_http_response(&retry_response);
    assert!(status.starts_with("HTTP/1.1 200"), "{status}\n{body}");
    let accepted: Value = serde_json::from_str(body).unwrap();
    assert_eq!(accepted["game_id"], game_id.to_string());
    assert_eq!(accepted["command_id"], command_id.to_string());
    assert_eq!(accepted["previous_revision"], 0);
    assert_eq!(accepted["committed_revision"], 1);
    assert_eq!(command_executions.load(Ordering::SeqCst), 1);

    let counts: (i64, i64, i64, i64) = sqlx::query_as(
        "SELECT
            (SELECT count(*) FROM game_revisions WHERE game_id=$1 AND revision=1),
            (SELECT count(*) FROM game_snapshots WHERE game_id=$1 AND revision=1),
            (SELECT count(*) FROM game_commands WHERE game_id=$1 AND command_id=$2),
            (SELECT count(*) FROM game_outbox WHERE game_id=$1 AND revision=1)",
    )
    .bind(game_id)
    .bind(command_id)
    .fetch_one(&pool)
    .await
    .unwrap();
    assert_eq!(counts, (1, 1, 1, 1));
    assert_eq!(
        repository
            .reconcile_authoritative_state()
            .await
            .unwrap()
            .total_findings,
        0
    );

    api.0.kill().unwrap();
    api.0.wait().unwrap();
    worker_task.await.unwrap();
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
#[ignore = "requires an explicit UNCIV_V3_DATABASE_URL"]
async fn rust_process_death_after_worker_execution_leaves_no_phantom_commit() {
    let database_url = std::env::var("UNCIV_V3_DATABASE_URL")
        .expect("UNCIV_V3_DATABASE_URL is required for PostgreSQL integration tests");
    let repository = PostgresGameRepository::connect(&database_url)
        .await
        .unwrap();
    repository.migrate().await.unwrap();
    let pool = PgPool::connect(&database_url).await.unwrap();
    sqlx::query(
        "TRUNCATE game_outbox, game_revisions, game_commands, game_snapshots,
         game_members, games, ruleset_manifests, sessions, accounts CASCADE",
    )
    .execute(&pool)
    .await
    .unwrap();

    let account = repository
        .register_account("rust-death-owner", "correct horse battery staple")
        .await
        .unwrap();
    let session = repository.issue_session(account.id).await.unwrap();
    let game_id = Uuid::new_v4();
    let command_id = Uuid::new_v4();
    let manifest_hash = "a".repeat(64);
    sqlx::query(
        "INSERT INTO ruleset_manifests (hash, engine_build, manifest)
         VALUES ($1, 'test-engine', $2)",
    )
    .bind(&manifest_hash)
    .bind(json!({
        "engineBuild": "test-engine",
        "baseRuleset": {
            "name": "Base",
            "sha256": "b".repeat(64),
        },
        "mods": [],
    }))
    .execute(&pool)
    .await
    .unwrap();
    repository
        .create_game(NewGame {
            game_id,
            owner_account_id: account.id,
            ruleset_manifest_hash: manifest_hash,
            snapshot: b"revision-0".to_vec(),
            owner_civilization_id: "test-civilization".to_owned(),
        })
        .await
        .unwrap();

    let mut lock_holder = pool.begin().await.unwrap();
    sqlx::query("SELECT id FROM games WHERE id=$1 FOR UPDATE")
        .bind(game_id)
        .fetch_one(&mut *lock_holder)
        .await
        .unwrap();

    let first_worker_listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let first_worker_address = first_worker_listener.local_addr().unwrap();
    let first_executions = Arc::new(AtomicUsize::new(0));
    let first_worker_task = tokio::spawn(run_worker(
        first_worker_listener,
        Arc::clone(&first_executions),
    ));
    let first_api_address = unused_address();
    let mut first_api = spawn_api(&database_url, first_api_address, first_worker_address);
    wait_until_ready(first_api_address, &mut first_api.0).await;

    let request_body = json!({
        "command_id": command_id,
        "expected_revision": 0,
        "client_observed_state_hash": null,
    })
    .to_string();
    let mut interrupted_request = TcpStream::connect(first_api_address).await.unwrap();
    interrupted_request
        .write_all(
            command_request(first_api_address, game_id, &session.token, &request_body).as_bytes(),
        )
        .await
        .unwrap();

    wait_for_blocked_commit(&pool).await;
    assert_eq!(first_executions.load(Ordering::SeqCst), 1);
    first_api.0.kill().unwrap();
    first_api.0.wait().unwrap();
    drop(interrupted_request);
    lock_holder.rollback().await.unwrap();
    first_worker_task.await.unwrap();

    assert_eq!(
        canonical_artifact_counts(&pool, game_id, command_id).await,
        (0, 0, 0, 0)
    );

    let retry_worker_listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let retry_worker_address = retry_worker_listener.local_addr().unwrap();
    let retry_executions = Arc::new(AtomicUsize::new(0));
    let retry_worker_task = tokio::spawn(run_worker(
        retry_worker_listener,
        Arc::clone(&retry_executions),
    ));
    let retry_api_address = unused_address();
    let mut retry_api = spawn_api(&database_url, retry_api_address, retry_worker_address);
    wait_until_ready(retry_api_address, &mut retry_api.0).await;

    let retry_response = send_http(
        retry_api_address,
        &command_request(retry_api_address, game_id, &session.token, &request_body),
    )
    .await;
    let (status, body) = split_http_response(&retry_response);
    assert!(status.starts_with("HTTP/1.1 200"), "{status}\n{body}");
    let accepted: Value = serde_json::from_str(body).unwrap();
    assert_eq!(accepted["command_id"], command_id.to_string());
    assert_eq!(accepted["committed_revision"], 1);
    assert_eq!(retry_executions.load(Ordering::SeqCst), 1);
    assert_eq!(
        canonical_artifact_counts(&pool, game_id, command_id).await,
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

    retry_api.0.kill().unwrap();
    retry_api.0.wait().unwrap();
    retry_worker_task.await.unwrap();
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
            .env("UNCIV_V3_UNPACKAGED_DEV", "1")
            .stdin(Stdio::null())
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .spawn()
            .unwrap(),
    )
}

fn unused_address() -> SocketAddr {
    let listener = StdTcpListener::bind("127.0.0.1:0").unwrap();
    let address = listener.local_addr().unwrap();
    drop(listener);
    address
}

async fn wait_until_ready(address: SocketAddr, child: &mut Child) {
    for _ in 0..100 {
        if let Some(status) = child.try_wait().unwrap() {
            panic!("authoritative API exited before readiness: {status}");
        }
        if let Ok(response) = timeout(
            Duration::from_millis(100),
            send_http(
                address,
                "GET /healthz HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n",
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

async fn wait_for_blocked_commit(pool: &PgPool) {
    for _ in 0..200 {
        let blocked: bool = sqlx::query_scalar(
            "SELECT EXISTS(
                SELECT 1 FROM pg_stat_activity
                WHERE wait_event_type='Lock'
                  AND query LIKE 'SELECT g.unavailable_at%'
            )",
        )
        .fetch_one(pool)
        .await
        .unwrap();
        if blocked {
            return;
        }
        sleep(Duration::from_millis(10)).await;
    }
    panic!("API commit did not block on the canonical game lock");
}

async fn wait_for_command(pool: &PgPool, game_id: Uuid, command_id: Uuid) {
    for _ in 0..200 {
        let committed: bool = sqlx::query_scalar(
            "SELECT EXISTS(
                SELECT 1 FROM game_commands WHERE game_id=$1 AND command_id=$2
            )",
        )
        .bind(game_id)
        .bind(command_id)
        .fetch_one(pool)
        .await
        .unwrap();
        if committed {
            return;
        }
        sleep(Duration::from_millis(10)).await;
    }
    panic!("command did not commit before the response-loss deadline");
}

async fn canonical_artifact_counts(
    pool: &PgPool,
    game_id: Uuid,
    command_id: Uuid,
) -> (i64, i64, i64, i64) {
    sqlx::query_as(
        "SELECT
            (SELECT count(*) FROM game_revisions WHERE game_id=$1 AND revision=1),
            (SELECT count(*) FROM game_snapshots WHERE game_id=$1 AND revision=1),
            (SELECT count(*) FROM game_commands WHERE game_id=$1 AND command_id=$2),
            (SELECT count(*) FROM game_outbox WHERE game_id=$1 AND revision=1)",
    )
    .bind(game_id)
    .bind(command_id)
    .fetch_one(pool)
    .await
    .unwrap()
}

fn command_request(address: SocketAddr, game_id: Uuid, bearer_token: &str, body: &str) -> String {
    format!(
        "POST /api/v3/games/{game_id}/commands/end-turn HTTP/1.1\r\n\
         Host: {address}\r\n\
         Authorization: Bearer {bearer_token}\r\n\
         Content-Type: application/json\r\n\
         Content-Length: {}\r\n\
         Connection: close\r\n\r\n\
         {body}",
        body.len()
    )
}

async fn send_http(address: SocketAddr, request: &str) -> String {
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

async fn run_worker(listener: TcpListener, command_executions: Arc<AtomicUsize>) {
    let (mut handshake, _) = listener.accept().await.unwrap();
    let (nonce, request) = read_worker_frame(&mut handshake).await;
    assert_eq!(request["operation"]["type"], "handshake");
    write_worker_frame(
        &mut handshake,
        nonce,
        json!({
            "protocolVersion": WORKER_PROTOCOL_VERSION,
            "releaseBundleId": "dev-unpackaged",
            "engineBuild": "test-engine",
            "installedRulesets": [{
                "name": "Base",
                "sha256": "b".repeat(64),
            }],
        }),
    )
    .await;

    let (mut command, _) = listener.accept().await.unwrap();
    let (nonce, request) = read_worker_frame(&mut command).await;
    assert_eq!(request["protocolVersion"], WORKER_PROTOCOL_VERSION);
    assert_eq!(request["operation"]["type"], "end_turn");
    assert_eq!(request["operation"]["snapshot"], "revision-0");
    assert_eq!(
        request["operation"]["actorCivilizationId"],
        "test-civilization"
    );
    command_executions.fetch_add(1, Ordering::SeqCst);
    let server_time_millis = request["serverTimeMillis"].as_i64().unwrap();
    write_worker_frame(
        &mut command,
        nonce,
        json!({
            "protocolVersion": WORKER_PROTOCOL_VERSION,
            "serverTimeMillis": server_time_millis,
            "snapshot": "revision-1",
            "canonicalStateHash": state_hash(b"revision-1"),
        }),
    )
    .await;

    assert!(
        timeout(Duration::from_secs(1), listener.accept())
            .await
            .is_err(),
        "idempotent HTTP retry unexpectedly contacted the worker"
    );
}

async fn read_worker_frame(stream: &mut TcpStream) -> ([u8; 16], Value) {
    let size = stream.read_u32().await.unwrap() as usize;
    let mut nonce = [0_u8; 16];
    stream.read_exact(&mut nonce).await.unwrap();
    let mut tag = [0_u8; 32];
    stream.read_exact(&mut tag).await.unwrap();
    let mut payload = vec![0_u8; size];
    stream.read_exact(&mut payload).await.unwrap();
    verify_worker_tag(REQUEST_DOMAIN, &nonce, &payload, &tag);
    (nonce, serde_json::from_slice(&payload).unwrap())
}

async fn write_worker_frame(stream: &mut TcpStream, nonce: [u8; 16], response: Value) {
    let payload = serde_json::to_vec(&response).unwrap();
    let tag = worker_tag(RESPONSE_DOMAIN, &nonce, &payload);
    stream.write_u32(payload.len() as u32).await.unwrap();
    stream.write_all(&nonce).await.unwrap();
    stream.write_all(&tag).await.unwrap();
    stream.write_all(&payload).await.unwrap();
}

fn verify_worker_tag(domain: &[u8], nonce: &[u8; 16], payload: &[u8], tag: &[u8]) {
    let mut mac = <Hmac<Sha256> as KeyInit>::new_from_slice(&WORKER_KEY).unwrap();
    mac.update(domain);
    mac.update(nonce);
    mac.update(&(payload.len() as u32).to_be_bytes());
    mac.update(payload);
    mac.verify_slice(tag).unwrap();
}

fn worker_tag(domain: &[u8], nonce: &[u8; 16], payload: &[u8]) -> [u8; 32] {
    let mut mac = <Hmac<Sha256> as KeyInit>::new_from_slice(&WORKER_KEY).unwrap();
    mac.update(domain);
    mac.update(nonce);
    mac.update(&(payload.len() as u32).to_be_bytes());
    mac.update(payload);
    mac.finalize().into_bytes().into()
}
