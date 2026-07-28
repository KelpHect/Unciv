use super::*;
use crate::{
    postgres::{
        LegacyImportApplication, LegacyImportCandidateEvidence, LegacyImportConflictReport,
        LegacyImportProjectionEvidence, LegacyImportProjectionReport, LegacyImportRole,
        legacy_import_game_id,
    },
    worker::{
        LegacyImportMetadata, LegacyImportedMember, NormalizedLegacyGame, WorkerManifest,
        WorkerRuleset,
    },
};

#[tokio::test]
#[ignore = "requires an explicit UNCIV_V3_DATABASE_URL"]
async fn legacy_import_is_atomic_idempotent_and_source_unique() {
    let (repository, owner, player, spectator, manifest_hash) = seeded_import_repository().await;
    let operation_id = Uuid::new_v4();
    let import_application = application(
        operation_id,
        owner,
        player,
        spectator,
        manifest_hash.clone(),
    );

    let first = repository
        .apply_legacy_import(import_application.clone())
        .await
        .unwrap();
    assert!(first.created);
    assert_eq!(first.game_id, legacy_import_game_id(operation_id));

    let retry = repository
        .apply_legacy_import(import_application.clone())
        .await
        .unwrap();
    assert!(!retry.created);
    assert_eq!(retry.game_id, first.game_id);
    assert_eq!(retry.canonical_state_hash, first.canonical_state_hash);

    assert_eq!(
        sqlx::query_scalar::<_, i64>("SELECT count(*) FROM legacy_game_imports")
            .fetch_one(&repository.pool)
            .await
            .unwrap(),
        1,
    );
    assert_eq!(
        sqlx::query_scalar::<_, i64>("SELECT count(*) FROM games WHERE id=$1 AND head_revision=0")
            .bind(first.game_id)
            .fetch_one(&repository.pool)
            .await
            .unwrap(),
        1,
    );
    let memberships: Vec<(Uuid, String, Option<String>)> = sqlx::query_as(
        "SELECT account_id, role, civilization_id
         FROM game_members WHERE game_id=$1 ORDER BY role, account_id",
    )
    .bind(first.game_id)
    .fetch_all(&repository.pool)
    .await
    .unwrap();
    assert_eq!(memberships.len(), 3);
    assert!(memberships.contains(&(owner, "owner".to_owned(), Some("Rome".to_owned()))));
    assert!(memberships.contains(&(player, "player".to_owned(), Some("Greece".to_owned()))));
    assert!(memberships.contains(&(spectator, "spectator".to_owned(), None)));

    let changed_operation = application(Uuid::new_v4(), owner, player, spectator, manifest_hash);
    assert_eq!(
        repository.apply_legacy_import(changed_operation).await,
        Err(CommitError::InvalidCommand),
    );
}

#[tokio::test]
#[ignore = "requires an explicit UNCIV_V3_DATABASE_URL"]
async fn legacy_import_rejects_changed_retry_and_unverified_projection_without_rows() {
    let (repository, owner, player, spectator, manifest_hash) = seeded_import_repository().await;
    let operation_id = Uuid::new_v4();
    let original = application(
        operation_id,
        owner,
        player,
        spectator,
        manifest_hash.clone(),
    );
    repository
        .apply_legacy_import(original.clone())
        .await
        .unwrap();

    let mut changed = original;
    changed.operator_label = "different-admin".to_owned();
    changed.normalized_game.snapshot = "changed".to_owned();
    changed.normalized_game.canonical_state_hash = state_hash(b"changed");
    changed.conflict_report.candidates[0].normalized_state_hash =
        changed.normalized_game.canonical_state_hash.clone();
    assert_eq!(
        repository.apply_legacy_import(changed).await,
        Err(CommitError::InvalidCommand),
    );

    sqlx::query(
        "TRUNCATE legacy_game_imports, game_outbox, game_revisions, game_commands,
         game_snapshots, game_members, games CASCADE",
    )
    .execute(&repository.pool)
    .await
    .unwrap();
    let mut unverified = application(Uuid::new_v4(), owner, player, spectator, manifest_hash);
    unverified.projection_report.projections[0].identity_leak_scan_passed = false;
    assert_eq!(
        repository.apply_legacy_import(unverified).await,
        Err(CommitError::InvalidCommand),
    );
    assert_eq!(
        sqlx::query_scalar::<_, i64>("SELECT count(*) FROM games")
            .fetch_one(&repository.pool)
            .await
            .unwrap(),
        0,
    );
    assert_eq!(
        sqlx::query_scalar::<_, i64>("SELECT count(*) FROM legacy_game_imports")
            .fetch_one(&repository.pool)
            .await
            .unwrap(),
        0,
    );
}

async fn seeded_import_repository() -> (PostgresGameRepository, Uuid, Uuid, Uuid, String) {
    let repository = PostgresGameRepository::connect(&database_url())
        .await
        .unwrap();
    repository.migrate().await.unwrap();
    sqlx::query(
        "TRUNCATE legacy_game_imports, game_creation_operations, game_outbox, game_revisions,
         game_commands, game_snapshots, game_members, games, ruleset_asset_versions,
         ruleset_manifests, accounts CASCADE",
    )
    .execute(&repository.pool)
    .await
    .unwrap();
    let owner = Uuid::new_v4();
    let player = Uuid::new_v4();
    let spectator = Uuid::new_v4();
    for account in [owner, player, spectator] {
        sqlx::query(
            "INSERT INTO accounts (id, username_normalized, password_hash)
             VALUES ($1, $2, 'test-hash')",
        )
        .bind(account)
        .bind(format!("legacy-import-{account}"))
        .execute(&repository.pool)
        .await
        .unwrap();
    }
    let manifest = WorkerManifest {
        engine_build: "engine-1".to_owned(),
        base_ruleset: WorkerRuleset {
            name: "Base".to_owned(),
            sha256: "b".repeat(64),
        },
        mods: vec![],
    };
    let manifest_json = serde_json::to_value(&manifest).unwrap();
    let manifest_hash = state_hash(&serde_json::to_vec(&manifest).unwrap());
    sqlx::query(
        "INSERT INTO ruleset_manifests (hash, engine_build, manifest)
         VALUES ($1, $2, $3)",
    )
    .bind(&manifest_hash)
    .bind(&manifest.engine_build)
    .bind(manifest_json)
    .execute(&repository.pool)
    .await
    .unwrap();
    sqlx::query("INSERT INTO ruleset_asset_versions (version_id, manifest_hash) VALUES ($1, $1)")
        .bind(&manifest_hash)
        .execute(&repository.pool)
        .await
        .unwrap();
    (repository, owner, player, spectator, manifest_hash)
}

fn application(
    operation_id: Uuid,
    owner: Uuid,
    player: Uuid,
    spectator: Uuid,
    manifest_hash: String,
) -> LegacyImportApplication {
    let game_id = legacy_import_game_id(operation_id);
    let snapshot = "normalized-legacy-snapshot".to_owned();
    let members = vec![
        imported_member("legacy-owner", owner, "Rome", false),
        imported_member("legacy-player", player, "Greece", false),
        imported_member("legacy-spectator", spectator, "Spectator", true),
    ];
    let metadata = LegacyImportMetadata {
        legacy_game_id: "legacy-game".to_owned(),
        canonical_game_id: game_id.to_string(),
        serialization_version: 4,
        created_with: "4.15.0 (Build 1000)".to_owned(),
        turns: 12,
        current_player: "Rome".to_owned(),
        base_ruleset: "Base".to_owned(),
        mods: vec![],
        members: members.clone(),
    };
    let canonical_state_hash = state_hash(snapshot.as_bytes());
    let candidate = LegacyImportCandidateEvidence {
        index: 0,
        source_label: "legacy-game.json".to_owned(),
        source_path_hash: "c".repeat(64),
        source_hash: "d".repeat(64),
        source_bytes: 1024,
        normalized_state_hash: canonical_state_hash.clone(),
        metadata: metadata.clone(),
    };
    let projections = vec![
        projection(owner, LegacyImportRole::Owner, Some("Rome")),
        projection(player, LegacyImportRole::Player, Some("Greece")),
        projection(spectator, LegacyImportRole::Spectator, None),
    ];
    let mut checked_legacy_identity_hashes = members
        .iter()
        .map(|member| state_hash(member.legacy_player_id.as_bytes()))
        .collect::<Vec<_>>();
    checked_legacy_identity_hashes.sort();
    LegacyImportApplication {
        operation_id,
        owner_account_id: owner,
        operator_label: "migration-admin".to_owned(),
        legacy_origin: "legacy.example".to_owned(),
        legacy_game_id: "legacy-game".to_owned(),
        ruleset_manifest_hash: manifest_hash,
        selected_candidate_index: 0,
        conflict_report: LegacyImportConflictReport {
            candidates: vec![candidate],
            divergent: false,
            turns_differ: false,
            current_player_differs: false,
            normalized_hashes_differ: false,
        },
        projection_report: LegacyImportProjectionReport {
            projections,
            checked_legacy_identity_hashes,
        },
        normalized_game: NormalizedLegacyGame {
            snapshot,
            canonical_state_hash,
            owner_civilization_id: "Rome".to_owned(),
            metadata,
        },
    }
}

fn imported_member(
    legacy_player_id: &str,
    account_id: Uuid,
    civilization_id: &str,
    spectator: bool,
) -> LegacyImportedMember {
    LegacyImportedMember {
        legacy_player_id: legacy_player_id.to_owned(),
        account_id: account_id.to_string(),
        civilization_id: civilization_id.to_owned(),
        spectator,
    }
}

fn projection(
    account_id: Uuid,
    role: LegacyImportRole,
    civilization_id: Option<&str>,
) -> LegacyImportProjectionEvidence {
    LegacyImportProjectionEvidence {
        account_id,
        role,
        civilization_id: civilization_id.map(str::to_owned),
        projection_hash: state_hash(account_id.as_bytes()),
        serialized_bytes: 100,
        identity_leak_scan_passed: true,
    }
}
