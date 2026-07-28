use super::*;

const ACCOUNT_ID: Uuid = Uuid::from_u128(0xd15f_0000_0000_0000_0000_0000_0000_0001);
const GAME_ID: Uuid = Uuid::from_u128(0xd15f_0000_0000_0000_0000_0000_0000_0002);
const COMMAND_ID: Uuid = Uuid::from_u128(0xd15f_0000_0000_0000_0000_0000_0000_0003);

#[tokio::test]
#[ignore = "requires the destructive PostgreSQL disk-full qualification"]
async fn seed_disk_full_qualification_fixture() {
    let repository = PostgresGameRepository::connect(&database_url())
        .await
        .unwrap();
    repository.migrate().await.unwrap();
    sqlx::query("TRUNCATE game_outbox, game_revisions, game_commands, game_snapshots, game_members, games, ruleset_manifests, accounts CASCADE")
        .execute(&repository.pool)
        .await
        .unwrap();
    let manifest_hash = "d".repeat(64);
    sqlx::query(
        "INSERT INTO accounts (id, username_normalized, password_hash)
         VALUES ($1, 'disk-full-qualification', 'test-hash')",
    )
    .bind(ACCOUNT_ID)
    .execute(&repository.pool)
    .await
    .unwrap();
    sqlx::query(
        "INSERT INTO ruleset_manifests (hash, engine_build, manifest)
         VALUES ($1, 'test-engine', '{}'::jsonb)",
    )
    .bind(&manifest_hash)
    .execute(&repository.pool)
    .await
    .unwrap();
    repository
        .create_game(NewGame {
            game_id: GAME_ID,
            owner_account_id: ACCOUNT_ID,
            ruleset_manifest_hash: manifest_hash,
            snapshot: b"disk-full-revision-0".to_vec(),
            owner_civilization_id: "test-civilization".to_owned(),
        })
        .await
        .unwrap();
    assert_head(&repository, 0, 0).await;
}

#[tokio::test]
#[ignore = "requires the destructive PostgreSQL disk-full qualification"]
async fn disk_full_commit_leaves_no_phantom_revision() {
    let repository = PostgresGameRepository::connect(&database_url())
        .await
        .unwrap();
    let result = repository
        .commit(
            ACCOUNT_ID,
            command(GAME_ID, COMMAND_ID, 0),
            proposal(0, &large_incompressible_snapshot()),
        )
        .await;
    assert!(matches!(result, Err(CommitError::Storage)), "{result:?}");
    assert_head(&repository, 0, 0).await;
}

#[tokio::test]
#[ignore = "requires the destructive PostgreSQL disk-full qualification"]
async fn recovered_space_allows_one_idempotent_retry() {
    let repository = PostgresGameRepository::connect(&database_url())
        .await
        .unwrap();
    let snapshot = large_incompressible_snapshot();
    let accepted = repository
        .commit(
            ACCOUNT_ID,
            command(GAME_ID, COMMAND_ID, 0),
            proposal(0, &snapshot),
        )
        .await
        .unwrap();
    let duplicate = repository
        .commit(
            ACCOUNT_ID,
            command(GAME_ID, COMMAND_ID, 0),
            proposal(0, b"changed-retry-must-not-apply"),
        )
        .await
        .unwrap();
    assert_eq!(accepted, duplicate);
    assert_eq!(accepted.committed_revision, 1);
    assert_head(&repository, 1, 1).await;
    repository.validate_canonical_head(GAME_ID).await.unwrap();
}

fn large_incompressible_snapshot() -> Vec<u8> {
    let mut state = 0x9e37_79b9_7f4a_7c15_u64;
    let mut snapshot = vec![0_u8; 12 * 1024 * 1024];
    for byte in &mut snapshot {
        state ^= state << 13;
        state ^= state >> 7;
        state ^= state << 17;
        *byte = 32 + (state % 95) as u8;
    }
    snapshot
}

async fn assert_head(
    repository: &PostgresGameRepository,
    expected_revision: i64,
    expected_commands: i64,
) {
    let head: i64 = sqlx::query_scalar("SELECT head_revision FROM games WHERE id=$1")
        .bind(GAME_ID)
        .fetch_one(&repository.pool)
        .await
        .unwrap();
    let commands: i64 = sqlx::query_scalar("SELECT count(*) FROM game_commands WHERE game_id=$1")
        .bind(GAME_ID)
        .fetch_one(&repository.pool)
        .await
        .unwrap();
    assert_eq!(head, expected_revision);
    assert_eq!(commands, expected_commands);
}
