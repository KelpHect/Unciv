use std::{
    path::{Path, PathBuf},
    process::Command,
};

use serde_json::json;
use sqlx::PgPool;
use uuid::Uuid;

#[test]
#[ignore = "requires UNCIV_V3_DATABASE_URL and the packaged Kotlin worker jar"]
fn base_ruleset_acquisition_is_semantically_validated_registered_and_idempotent() {
    let database_url =
        std::env::var("UNCIV_V3_DATABASE_URL").expect("UNCIV_V3_DATABASE_URL is required");
    let manifest_dir = Path::new(env!("CARGO_MANIFEST_DIR"));
    let assets = manifest_dir.join("../android/assets");
    let worker_jar = manifest_dir.join("../server/build/libs/UncivAuthoritativeWorker.jar");
    assert!(
        worker_jar.is_file(),
        "build :server:authoritativeWorkerDist first"
    );
    let java = java_executable();
    let catalog_output = Command::new(&java)
        .arg("-Djava.awt.headless=true")
        .arg("-Xmx384m")
        .arg("-jar")
        .arg(&worker_jar)
        .arg("--print-catalog")
        .current_dir(&assets)
        .output()
        .unwrap();
    assert!(catalog_output.status.success());
    let catalog_line = String::from_utf8(catalog_output.stdout)
        .unwrap()
        .lines()
        .last()
        .unwrap()
        .to_owned();
    let catalog: serde_json::Value = serde_json::from_str(&catalog_line).unwrap();
    let base = catalog["installedRulesets"]
        .as_array()
        .unwrap()
        .iter()
        .find(|item| item["name"] == "Civ V - Vanilla")
        .unwrap()
        .clone();
    let policy = json!({
        "schema_version": 1,
        "engine_build": catalog["engineBuild"],
        "base_ruleset": base,
        "mods": [],
    });
    let root = std::env::temp_dir().join(format!("unciv-rulesets-{}", Uuid::new_v4()));
    std::fs::create_dir(&root).unwrap();
    let policy_path = root.join("policy.json");
    std::fs::write(&policy_path, serde_json::to_vec_pretty(&policy).unwrap()).unwrap();
    reset_database(&database_url);

    for _ in 0..2 {
        let result = Command::new(env!("CARGO_BIN_EXE_unciv-v3-rulesets"))
            .arg("acquire")
            .arg(&policy_path)
            .arg(&assets)
            .arg(&worker_jar)
            .arg(&root)
            .env("UNCIV_V3_DATABASE_URL", &database_url)
            .env("UNCIV_JAVA_BIN", &java)
            .output()
            .unwrap();
        assert!(
            result.status.success(),
            "{}",
            String::from_utf8_lossy(&result.stderr)
        );
    }

    let report: serde_json::Value = serde_json::from_slice(
        &Command::new(env!("CARGO_BIN_EXE_unciv-v3-rulesets"))
            .arg("acquire")
            .arg(&policy_path)
            .arg(&assets)
            .arg(&worker_jar)
            .arg(&root)
            .env("UNCIV_V3_DATABASE_URL", &database_url)
            .env("UNCIV_JAVA_BIN", &java)
            .output()
            .unwrap()
            .stdout,
    )
    .unwrap();
    let version_id = report["version_id"].as_str().unwrap();
    assert!(
        root.join("versions")
            .join(version_id)
            .join("manifest.json")
            .is_file()
    );
    let runtime = tokio::runtime::Runtime::new().unwrap();
    runtime.block_on(async {
        let pool = PgPool::connect(&database_url).await.unwrap();
        let count: i64 =
            sqlx::query_scalar("SELECT count(*) FROM ruleset_asset_versions WHERE version_id=$1")
                .bind(version_id)
                .fetch_one(&pool)
                .await
                .unwrap();
        assert_eq!(count, 1);
    });
    std::fs::remove_dir_all(root).unwrap();
}

fn reset_database(database_url: &str) {
    let runtime = tokio::runtime::Runtime::new().unwrap();
    runtime.block_on(async {
        let repository =
            unciv_authoritative_server::postgres::PostgresGameRepository::connect(database_url)
                .await
                .unwrap();
        repository.migrate().await.unwrap();
        sqlx::query("TRUNCATE ruleset_asset_versions, ruleset_manifests CASCADE")
            .execute(&PgPool::connect(database_url).await.unwrap())
            .await
            .unwrap();
    });
}

fn java_executable() -> PathBuf {
    std::env::var_os("JAVA_HOME")
        .map(PathBuf::from)
        .map(|home| {
            home.join("bin")
                .join(if cfg!(windows) { "java.exe" } else { "java" })
        })
        .filter(|path| path.is_file())
        .unwrap_or_else(|| PathBuf::from("java"))
}
