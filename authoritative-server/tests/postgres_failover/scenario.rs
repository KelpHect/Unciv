use std::{sync::Arc, time::Duration};

use sqlx::{PgPool, postgres::PgPoolOptions};
use tokio::{sync::Barrier, task::JoinSet, time::sleep};
use unciv_authoritative_server::{
    CommandAccepted, CommandEnvelope, CommitError, CommitProposal, GameCommand, PROTOCOL_VERSION,
    postgres::{NewGame, PostgresGameRepository},
    state_hash,
};
use uuid::Uuid;

use super::{docker::PostgresCluster, proxy::DatabaseProxy};

const GAME_COUNT: usize = 4;
const COMMANDS_PER_GAME: u64 = 16;
const PRE_FAILOVER_COMMANDS: u64 = 4;

#[tokio::test(flavor = "multi_thread", worker_threads = 8)]
#[ignore = "starts disposable PostgreSQL 19 Beta 2 primary and standby containers"]
async fn postgres_promotion_reconnects_under_load_without_splitting_canonical_history() {
    let cluster = PostgresCluster::start();
    let proxy = DatabaseProxy::start(cluster.primary_port).await;
    let proxy_url = cluster.database_url(proxy.port());
    let repository = PostgresGameRepository::connect(&proxy_url)
        .await
        .expect("connect repository through stable endpoint");
    repository.migrate().await.expect("migrate primary");
    let inspection_pool = PgPoolOptions::new()
        .max_connections(4)
        .connect(&proxy_url)
        .await
        .expect("connect inspection pool");
    let games = seed_games(&repository, &inspection_pool).await;

    let barrier = Arc::new(Barrier::new(GAME_COUNT + 1));
    let mut workloads = JoinSet::new();
    for (account_id, game_id) in games.iter().copied() {
        let repository = repository.clone();
        let barrier = Arc::clone(&barrier);
        workloads.spawn(async move {
            run_game_workload(repository, account_id, game_id, barrier).await
        });
    }

    barrier.wait().await;
    cluster.kill_primary();
    cluster.promote_standby();
    proxy.route_to(cluster.standby_port).await;

    let mut observed_storage_failures = 0;
    while let Some(result) = workloads.join_next().await {
        observed_storage_failures += result.expect("workload task survives failover");
    }
    assert!(
        observed_storage_failures > 0,
        "the workload must observe and recover from at least one dead-primary connection"
    );

    verify_history(&inspection_pool, &games).await;
    let report = retry_storage(|| repository.reconcile_authoritative_state())
        .await
        .expect("reconcile promoted primary");
    assert!(
        report.findings.is_empty(),
        "promoted canonical state has reconciliation findings: {:?}",
        report.findings
    );
}

async fn seed_games(repository: &PostgresGameRepository, pool: &PgPool) -> Vec<(Uuid, Uuid)> {
    let manifest_hash = "f".repeat(64);
    sqlx::query(
        "INSERT INTO ruleset_manifests (hash, engine_build, manifest) VALUES ($1, 'failover-test-engine', '{}'::jsonb)",
    )
    .bind(&manifest_hash)
    .execute(pool)
    .await
    .expect("insert failover manifest");

    let mut games = Vec::with_capacity(GAME_COUNT);
    for index in 0..GAME_COUNT {
        let account_id = Uuid::new_v4();
        let game_id = Uuid::new_v4();
        sqlx::query(
            "INSERT INTO accounts (id, username_normalized, password_hash) VALUES ($1, $2, 'failover-test-hash')",
        )
        .bind(account_id)
        .bind(format!("failover-account-{index}-{account_id}"))
        .execute(pool)
        .await
        .expect("insert failover account");
        repository
            .create_game(NewGame {
                game_id,
                owner_account_id: account_id,
                ruleset_manifest_hash: manifest_hash.clone(),
                snapshot: snapshot(index, 0),
                owner_civilization_id: format!("failover-civilization-{index}"),
            })
            .await
            .expect("create failover game");
        games.push((account_id, game_id));
    }
    games
}

async fn run_game_workload(
    repository: PostgresGameRepository,
    account_id: Uuid,
    game_id: Uuid,
    barrier: Arc<Barrier>,
) -> usize {
    let mut storage_failures = 0;
    for revision in 0..COMMANDS_PER_GAME {
        let command_id = deterministic_command_id(game_id, revision);
        let next_snapshot = format!("{game_id}:revision-{}", revision + 1).into_bytes();
        let envelope = CommandEnvelope {
            protocol_version: PROTOCOL_VERSION,
            game_id,
            command_id,
            expected_revision: revision,
            client_observed_state_hash: None,
            command: GameCommand::EndTurn {},
        };
        let proposal = CommitProposal {
            previous_revision: revision,
            canonical_state_hash: state_hash(&next_snapshot),
            snapshot: next_snapshot,
            server_time_millis: revision as i64,
            replay_operation: serde_json::json!({"type": "failover_test", "revision": revision}),
        };
        storage_failures += commit_with_retry(&repository, account_id, &envelope, &proposal)
            .await
            .expect("idempotent command eventually commits")
            .1;
        if revision + 1 == PRE_FAILOVER_COMMANDS {
            barrier.wait().await;
        }
    }
    storage_failures
}

async fn commit_with_retry(
    repository: &PostgresGameRepository,
    account_id: Uuid,
    envelope: &CommandEnvelope,
    proposal: &CommitProposal,
) -> Result<(CommandAccepted, usize), CommitError> {
    let mut storage_failures = 0;
    for _ in 0..240 {
        match repository
            .commit(account_id, envelope.clone(), proposal.clone())
            .await
        {
            Err(CommitError::Storage) => {
                storage_failures += 1;
                sleep(Duration::from_millis(250)).await;
            }
            result => return result.map(|accepted| (accepted, storage_failures)),
        }
    }
    Err(CommitError::Storage)
}

async fn verify_history(pool: &PgPool, games: &[(Uuid, Uuid)]) {
    for (_, game_id) in games {
        let (head_revision, command_count, revision_count, snapshot_count, outbox_count): (
            i64,
            i64,
            i64,
            i64,
            i64,
        ) = retry_sql(|| {
            sqlx::query_as(
                "SELECT g.head_revision,
                        (SELECT count(*) FROM game_commands WHERE game_id=g.id),
                        (SELECT count(*) FROM game_revisions WHERE game_id=g.id),
                        (SELECT count(*) FROM game_snapshots WHERE game_id=g.id),
                        (SELECT count(*) FROM game_outbox WHERE game_id=g.id)
                     FROM games g WHERE g.id=$1",
            )
            .bind(game_id)
            .fetch_one(pool)
        })
        .await
        .expect("read promoted history");
        assert_eq!(head_revision, COMMANDS_PER_GAME as i64);
        assert_eq!(command_count, COMMANDS_PER_GAME as i64);
        assert_eq!(revision_count, COMMANDS_PER_GAME as i64 + 1);
        assert_eq!(snapshot_count, COMMANDS_PER_GAME as i64 + 1);
        assert_eq!(outbox_count, COMMANDS_PER_GAME as i64);

        let revisions: Vec<i64> = sqlx::query_scalar(
            "SELECT revision FROM game_revisions WHERE game_id=$1 ORDER BY revision",
        )
        .bind(game_id)
        .fetch_all(pool)
        .await
        .expect("read contiguous revisions");
        assert_eq!(
            revisions,
            (0..=COMMANDS_PER_GAME as i64).collect::<Vec<_>>()
        );
    }
}

async fn retry_storage<F, Fut, T>(mut operation: F) -> Result<T, CommitError>
where
    F: FnMut() -> Fut,
    Fut: Future<Output = Result<T, CommitError>>,
{
    for _ in 0..240 {
        match operation().await {
            Err(CommitError::Storage) => sleep(Duration::from_millis(250)).await,
            result => return result,
        }
    }
    Err(CommitError::Storage)
}

async fn retry_sql<F, Fut, T>(mut operation: F) -> Result<T, sqlx::Error>
where
    F: FnMut() -> Fut,
    Fut: Future<Output = Result<T, sqlx::Error>>,
{
    for _ in 0..240 {
        match operation().await {
            Err(error) if is_transient(&error) => sleep(Duration::from_millis(250)).await,
            result => return result,
        }
    }
    Err(sqlx::Error::PoolTimedOut)
}

fn is_transient(error: &sqlx::Error) -> bool {
    matches!(
        error,
        sqlx::Error::Io(_)
            | sqlx::Error::Tls(_)
            | sqlx::Error::PoolTimedOut
            | sqlx::Error::PoolClosed
    )
}

fn deterministic_command_id(game_id: Uuid, revision: u64) -> Uuid {
    let mut bytes = *game_id.as_bytes();
    for (target, source) in bytes[8..].iter_mut().zip(revision.to_be_bytes()) {
        *target ^= source;
    }
    Uuid::from_bytes(bytes)
}

fn snapshot(game_index: usize, revision: u64) -> Vec<u8> {
    format!("failover-game-{game_index}:revision-{revision}").into_bytes()
}
