use super::*;

#[tokio::test]
#[ignore = "requires an explicit UNCIV_V3_DATABASE_URL"]
async fn ruleset_manifest_discovery_is_bounded_ordered_and_closed() {
    let repository = PostgresGameRepository::connect(&database_url())
        .await
        .unwrap();
    repository.migrate().await.unwrap();
    sqlx::query("TRUNCATE ruleset_manifests CASCADE")
        .execute(&repository.pool)
        .await
        .unwrap();
    for (hash, base_name) in [("a".repeat(64), "Alpha"), ("b".repeat(64), "Beta")] {
        insert_manifest(
            &repository,
            &hash,
            "engine-1",
            json!({
                "engineBuild": "engine-1",
                "baseRuleset": {"name": base_name, "sha256": "c".repeat(64)},
                "mods": [{"name": "Shared Mod", "sha256": "d".repeat(64)}],
            }),
        )
        .await;
    }

    let first = repository.list_ruleset_manifests(None, 1).await.unwrap();
    assert_eq!(first.manifests.len(), 1);
    assert_eq!(first.manifests[0].base_ruleset.name, "Alpha");
    assert_eq!(first.manifests[0].mods.len(), 1);
    assert_eq!(first.next_cursor, Some("a".repeat(64)));

    let second = repository
        .list_ruleset_manifests(first.next_cursor.as_deref(), 1)
        .await
        .unwrap();
    assert_eq!(second.manifests[0].base_ruleset.name, "Beta");
    assert_eq!(second.next_cursor, None);

    assert_invalid_manifest(
        &repository,
        "e",
        "different-engine",
        json!({
            "engineBuild": "engine-1",
            "baseRuleset": {"name": "Corrupt", "sha256": "c".repeat(64)},
            "mods": [],
        }),
    )
    .await;
    assert_invalid_manifest(
        &repository,
        "f",
        "engine-1",
        json!({
            "engineBuild": "engine-1",
            "baseRuleset": {"name": "Corrupt", "sha256": "c".repeat(64)},
            "mods": [{"name": "Corrupt", "sha256": "d".repeat(64)}],
        }),
    )
    .await;

    assert_eq!(
        repository
            .list_ruleset_manifests(Some("not-a-hash"), 1)
            .await,
        Err(CommitError::InvalidCommand),
    );
    assert_eq!(
        repository.list_ruleset_manifests(None, 101).await,
        Err(CommitError::InvalidCommand),
    );
}

#[tokio::test]
#[ignore = "requires an explicit UNCIV_V3_DATABASE_URL"]
async fn asset_version_registration_is_exact_idempotent_and_reference_safe() {
    let repository = PostgresGameRepository::connect(&database_url())
        .await
        .unwrap();
    repository.migrate().await.unwrap();
    sqlx::query("TRUNCATE ruleset_asset_versions, ruleset_manifests CASCADE")
        .execute(&repository.pool)
        .await
        .unwrap();
    let manifest = WorkerManifest {
        engine_build: "engine-1".to_owned(),
        base_ruleset: crate::worker::WorkerRuleset {
            name: "Base".to_owned(),
            sha256: "a".repeat(64),
        },
        mods: vec![crate::worker::WorkerRuleset {
            name: "Mod".to_owned(),
            sha256: "b".repeat(64),
        }],
    };
    let manifest_hash = state_hash(&serde_json::to_vec(&manifest).unwrap());
    let version_id = "c".repeat(64);
    repository
        .register_ruleset_asset_version(&version_id, &manifest_hash, &manifest)
        .await
        .unwrap();
    repository
        .register_ruleset_asset_version(&version_id, &manifest_hash, &manifest)
        .await
        .unwrap();
    assert_eq!(
        repository
            .referenced_ruleset_asset_versions()
            .await
            .unwrap(),
        std::collections::HashSet::new(),
    );

    let account_id = Uuid::new_v4();
    let game_id = Uuid::new_v4();
    sqlx::query(
        "INSERT INTO accounts (id, username_normalized, password_hash)
         VALUES ($1, 'asset-owner', 'hash')",
    )
    .bind(account_id)
    .execute(&repository.pool)
    .await
    .unwrap();
    sqlx::query("INSERT INTO games (id, ruleset_manifest_hash) VALUES ($1, $2)")
        .bind(game_id)
        .bind(&manifest_hash)
        .execute(&repository.pool)
        .await
        .unwrap();
    assert!(
        repository
            .referenced_ruleset_asset_versions()
            .await
            .unwrap()
            .contains(&version_id)
    );
    assert!(
        !repository
            .unregister_unreferenced_ruleset_asset_version(&version_id)
            .await
            .unwrap()
    );

    sqlx::query("DELETE FROM games WHERE id=$1")
        .bind(game_id)
        .execute(&repository.pool)
        .await
        .unwrap();
    assert!(
        repository
            .unregister_unreferenced_ruleset_asset_version(&version_id)
            .await
            .unwrap()
    );
}

async fn assert_invalid_manifest(
    repository: &PostgresGameRepository,
    hash_character: &str,
    stored_engine_build: &str,
    manifest: serde_json::Value,
) {
    let hash = hash_character.repeat(64);
    insert_manifest(repository, &hash, stored_engine_build, manifest).await;
    assert_eq!(
        repository
            .list_ruleset_manifests(Some(&"b".repeat(64)), 100)
            .await,
        Err(CommitError::InvalidCommand),
    );
    sqlx::query("DELETE FROM ruleset_manifests WHERE hash = $1")
        .bind(hash)
        .execute(&repository.pool)
        .await
        .unwrap();
}

async fn insert_manifest(
    repository: &PostgresGameRepository,
    hash: &str,
    engine_build: &str,
    manifest: serde_json::Value,
) {
    sqlx::query("INSERT INTO ruleset_manifests (hash, engine_build, manifest) VALUES ($1, $2, $3)")
        .bind(hash)
        .bind(engine_build)
        .bind(manifest)
        .execute(&repository.pool)
        .await
        .unwrap();
}
