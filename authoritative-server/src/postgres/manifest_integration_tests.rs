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
