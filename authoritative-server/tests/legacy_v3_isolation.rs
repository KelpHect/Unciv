use std::{
    path::PathBuf,
    process::{Child, Command, Stdio},
    time::Duration,
};

use reqwest::StatusCode;
use sqlx::PgPool;
use unciv_authoritative_server::{
    postgres::{NewGame, PostgresGameRepository},
    state_hash,
};
use uuid::Uuid;

struct LegacyServerGuard {
    child: Child,
    root: PathBuf,
}

impl Drop for LegacyServerGuard {
    fn drop(&mut self) {
        let _ = self.child.kill();
        let _ = self.child.wait();
        if self.root.starts_with(std::env::temp_dir())
            && self.root.file_name().is_some_and(|name| {
                name.to_string_lossy()
                    .starts_with("unciv-legacy-isolation-")
            })
        {
            let _ = std::fs::remove_dir_all(&self.root);
        }
    }
}

#[tokio::test]
#[ignore = "requires UNCIV_V3_DATABASE_URL and UNCIV_LEGACY_SERVER_JAR"]
async fn same_uuid_legacy_upload_cannot_read_or_mutate_v3_canonical_state() {
    let database_url =
        std::env::var("UNCIV_V3_DATABASE_URL").expect("explicit disposable database URL");
    let legacy_jar = PathBuf::from(
        std::env::var("UNCIV_LEGACY_SERVER_JAR").expect("explicit legacy server jar"),
    );
    assert!(legacy_jar.is_file(), "legacy server jar must exist");
    let java = java_executable();

    let repository = PostgresGameRepository::connect(&database_url)
        .await
        .unwrap();
    repository.migrate().await.unwrap();
    let pool = PgPool::connect(&database_url).await.unwrap();
    let owner = repository
        .register_account("legacy-isolation-owner", "legacy-isolation-password")
        .await
        .unwrap();
    let manifest_hash = "a".repeat(64);
    sqlx::query(
        "INSERT INTO ruleset_manifests (hash, engine_build, manifest)
         VALUES ($1, 'legacy-isolation-engine', '{}'::jsonb)",
    )
    .bind(&manifest_hash)
    .execute(&pool)
    .await
    .unwrap();
    let game_id = Uuid::new_v4();
    let canonical_snapshot = b"server-only-canonical-v3-snapshot".to_vec();
    repository
        .create_game(NewGame {
            game_id,
            owner_account_id: owner.id,
            ruleset_manifest_hash: manifest_hash,
            snapshot: canonical_snapshot.clone(),
            owner_civilization_id: "Rome".to_owned(),
        })
        .await
        .unwrap();
    let before = canonical_evidence(&pool, game_id).await;

    let port = reserve_loopback_port();
    let root = std::env::temp_dir().join(format!("unciv-legacy-isolation-{}", Uuid::new_v4()));
    let files = root.join("legacy-files");
    std::fs::create_dir_all(&files).unwrap();
    let child = Command::new(java)
        .args([
            "-jar",
            legacy_jar.to_str().unwrap(),
            "-p",
            &port.to_string(),
            "-f",
            files.to_str().unwrap(),
            "-no-auth",
            "-no-chat",
        ])
        .env_remove("UNCIV_V3_DATABASE_URL")
        .env_remove("UNCIV_V3_MIGRATION_DATABASE_URL")
        .env_remove("UNCIV_ENGINE_WORKER_ADDR")
        .env_remove("UNCIV_ENGINE_WORKER_SECRET")
        .current_dir(&root)
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .spawn()
        .unwrap();
    let _guard = LegacyServerGuard { child, root };
    let client = reqwest::Client::new();
    let base_url = format!("http://127.0.0.1:{port}");
    wait_until_ready(&client, &base_url).await;

    let legacy_payload = "attacker-authored-legacy-whole-save";
    let file_url = format!("{base_url}/files/{game_id}");
    let legacy_user = Uuid::new_v4().to_string();
    let upload = client
        .put(&file_url)
        .basic_auth(&legacy_user, Some("legacy-password"))
        .body(legacy_payload)
        .send()
        .await
        .unwrap();
    assert_eq!(upload.status(), StatusCode::OK);
    let download = client
        .get(&file_url)
        .basic_auth(&legacy_user, Some("legacy-password"))
        .send()
        .await
        .unwrap();
    assert_eq!(download.status(), StatusCode::OK);
    assert_eq!(download.text().await.unwrap(), legacy_payload);

    let after = canonical_evidence(&pool, game_id).await;
    assert_eq!(after, before);
    assert_eq!(after.1, state_hash(&canonical_snapshot));
    repository.validate_canonical_head(game_id).await.unwrap();
    assert_eq!(
        std::fs::read_to_string(files.join(game_id.to_string())).unwrap(),
        legacy_payload,
    );
}

#[tokio::test]
#[ignore = "requires UNCIV_LEGACY_SERVER_JAR"]
async fn retirement_switch_rejects_writes_but_preserves_legacy_reads() {
    let legacy_jar = PathBuf::from(
        std::env::var("UNCIV_LEGACY_SERVER_JAR").expect("explicit legacy server jar"),
    );
    assert!(legacy_jar.is_file(), "legacy server jar must exist");
    let port = reserve_loopback_port();
    let root = std::env::temp_dir().join(format!("unciv-legacy-isolation-{}", Uuid::new_v4()));
    let files = root.join("legacy-files");
    std::fs::create_dir_all(&files).unwrap();
    let game_id = Uuid::new_v4();
    let preserved_payload = "existing-legacy-save-remains-readable";
    std::fs::write(files.join(game_id.to_string()), preserved_payload).unwrap();
    let child = Command::new(java_executable())
        .args([
            "-jar",
            legacy_jar.to_str().unwrap(),
            "-p",
            &port.to_string(),
            "-f",
            files.to_str().unwrap(),
            "-no-chat",
            "-no-legacy-writes",
        ])
        .env_remove("UNCIV_V3_DATABASE_URL")
        .env_remove("UNCIV_V3_MIGRATION_DATABASE_URL")
        .env_remove("UNCIV_ENGINE_WORKER_ADDR")
        .env_remove("UNCIV_ENGINE_WORKER_SECRET")
        .current_dir(&root)
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .spawn()
        .unwrap();
    let _guard = LegacyServerGuard { child, root };
    let client = reqwest::Client::new();
    let base_url = format!("http://127.0.0.1:{port}");
    wait_until_ready(&client, &base_url).await;
    let file_url = format!("{base_url}/files/{game_id}");
    let legacy_user = Uuid::new_v4().to_string();

    let download = client
        .get(&file_url)
        .basic_auth(&legacy_user, Some("legacy-password"))
        .send()
        .await
        .unwrap();
    assert_eq!(download.status(), StatusCode::OK);
    assert_eq!(download.text().await.unwrap(), preserved_payload);
    let upload = client
        .put(&file_url)
        .basic_auth(&legacy_user, Some("legacy-password"))
        .body("forbidden-replacement")
        .send()
        .await
        .unwrap();
    assert_eq!(upload.status(), StatusCode::GONE);
    let authentication_write = client
        .put(format!("{base_url}/auth"))
        .basic_auth(&legacy_user, Some("legacy-password"))
        .body("forbidden-new-password")
        .send()
        .await
        .unwrap();
    assert_eq!(authentication_write.status(), StatusCode::GONE);
    assert_eq!(
        std::fs::read_to_string(files.join(game_id.to_string())).unwrap(),
        preserved_payload,
    );

    let telemetry_response = client
        .get(format!("{base_url}/legacy-status"))
        .send()
        .await
        .unwrap();
    assert_eq!(telemetry_response.status(), StatusCode::OK);
    let telemetry: serde_json::Value =
        serde_json::from_str(&telemetry_response.text().await.unwrap()).unwrap();
    assert_eq!(telemetry["writesEnabled"], false);
    assert_eq!(telemetry["acceptedFileWrites"], 0);
    assert_eq!(telemetry["rejectedFileWrites"], 1);
    assert_eq!(telemetry["acceptedAuthenticationWrites"], 0);
    assert_eq!(telemetry["rejectedAuthenticationWrites"], 1);
}

async fn canonical_evidence(pool: &PgPool, game_id: Uuid) -> (i64, String, i64, i64, i64) {
    sqlx::query_as(
        "SELECT g.head_revision, r.canonical_state_hash,
                (SELECT count(*) FROM games),
                (SELECT count(*) FROM game_revisions),
                (SELECT count(*) FROM game_commands)
         FROM games g
         JOIN game_revisions r ON r.game_id=g.id AND r.revision=g.head_revision
         WHERE g.id=$1",
    )
    .bind(game_id)
    .fetch_one(pool)
    .await
    .unwrap()
}

async fn wait_until_ready(client: &reqwest::Client, base_url: &str) {
    for _ in 0..100 {
        if let Ok(response) = client.get(format!("{base_url}/isalive")).send().await
            && response.status() == StatusCode::OK
        {
            return;
        }
        tokio::time::sleep(Duration::from_millis(100)).await;
    }
    panic!("legacy server did not become ready");
}

fn reserve_loopback_port() -> u16 {
    std::net::TcpListener::bind(("127.0.0.1", 0))
        .unwrap()
        .local_addr()
        .unwrap()
        .port()
}

fn java_executable() -> PathBuf {
    let executable = if cfg!(windows) { "java.exe" } else { "java" };
    std::env::var_os("JAVA_HOME")
        .map(PathBuf::from)
        .map(|home| home.join("bin").join(executable))
        .filter(|candidate| candidate.is_file())
        .unwrap_or_else(|| PathBuf::from(executable))
}
