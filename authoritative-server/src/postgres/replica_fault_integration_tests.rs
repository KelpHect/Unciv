use std::sync::Arc;

use tokio::sync::Barrier;

use super::*;

fn database_url_with_application_name(name: &str) -> String {
    let url = database_url();
    let separator = if url.contains('?') { '&' } else { '?' };
    format!("{url}{separator}application_name={name}")
}

#[tokio::test]
#[ignore = "requires an explicit UNCIV_V3_DATABASE_URL"]
async fn independent_replicas_accept_exactly_one_command_for_one_revision() {
    let first =
        PostgresGameRepository::connect(&database_url_with_application_name("unciv_replica_first"))
            .await
            .unwrap();
    let second = PostgresGameRepository::connect(&database_url_with_application_name(
        "unciv_replica_second",
    ))
    .await
    .unwrap();
    first.migrate().await.unwrap();
    let (owner, game) = seed_repository(&first).await;
    let first_command = Uuid::new_v4();
    let second_command = Uuid::new_v4();
    let barrier = Arc::new(Barrier::new(3));

    let first_task = {
        let repository = first.clone();
        let barrier = Arc::clone(&barrier);
        tokio::spawn(async move {
            barrier.wait().await;
            repository
                .commit(
                    owner,
                    command(game, first_command, 0),
                    proposal(0, b"replica-first"),
                )
                .await
        })
    };
    let second_task = {
        let repository = second.clone();
        let barrier = Arc::clone(&barrier);
        tokio::spawn(async move {
            barrier.wait().await;
            repository
                .commit(
                    owner,
                    command(game, second_command, 0),
                    proposal(0, b"replica-second"),
                )
                .await
        })
    };
    barrier.wait().await;
    let first_result = first_task.await.unwrap();
    let second_result = second_task.await.unwrap();

    let (winner, loser_command) = match (first_result, second_result) {
        (
            Ok(accepted),
            Err(CommitError::Stale {
                expected: 0,
                actual: 1,
            }),
        ) => (accepted, second_command),
        (
            Err(CommitError::Stale {
                expected: 0,
                actual: 1,
            }),
            Ok(accepted),
        ) => (accepted, first_command),
        results => panic!("unexpected replica race results: {results:?}"),
    };
    assert_ne!(winner.command_id, loser_command);
    assert_eq!(winner.previous_revision, 0);
    assert_eq!(winner.committed_revision, 1);

    let counts: (i64, i64, i64, i64) = sqlx::query_as(
        "SELECT (SELECT count(*) FROM game_revisions WHERE game_id=$1 AND revision=1), (SELECT count(*) FROM game_snapshots WHERE game_id=$1 AND revision=1), (SELECT count(*) FROM game_commands WHERE game_id=$1), (SELECT count(*) FROM game_outbox WHERE game_id=$1 AND revision=1 AND topic='game.revision.committed')",
    )
    .bind(game)
    .fetch_one(&first.pool)
    .await
    .unwrap();
    assert_eq!(counts, (1, 1, 1, 1));

    let replayed = second
        .commit(
            owner,
            command(game, winner.command_id, 0),
            proposal(0, b"response-was-lost"),
        )
        .await
        .unwrap();
    assert_eq!(replayed, winner);
    assert_eq!(
        second
            .reconcile_authoritative_state()
            .await
            .unwrap()
            .total_findings,
        0
    );
}

#[tokio::test]
#[ignore = "requires an explicit UNCIV_V3_DATABASE_URL"]
async fn terminated_blocked_commit_rolls_back_and_same_command_retry_succeeds() {
    let control =
        PostgresGameRepository::connect(&database_url_with_application_name("unciv_fault_control"))
            .await
            .unwrap();
    let victim =
        PostgresGameRepository::connect(&database_url_with_application_name("unciv_fault_victim"))
            .await
            .unwrap();
    control.migrate().await.unwrap();
    let (owner, game) = seed_repository(&control).await;
    let command_id = Uuid::new_v4();
    let mut holder = control.pool.begin().await.unwrap();
    sqlx::query("SELECT id FROM games WHERE id=$1 FOR UPDATE")
        .bind(game)
        .fetch_one(&mut *holder)
        .await
        .unwrap();

    let victim_task = {
        let repository = victim.clone();
        tokio::spawn(async move {
            repository
                .commit(
                    owner,
                    command(game, command_id, 0),
                    proposal(0, b"after-database-retry"),
                )
                .await
        })
    };
    let mut blocked_pid = None;
    for _ in 0..50 {
        blocked_pid = sqlx::query_scalar(
            "SELECT pid FROM pg_stat_activity WHERE application_name='unciv_fault_victim' AND wait_event_type='Lock' AND query LIKE 'SELECT g.unavailable_at%' LIMIT 1",
        )
        .fetch_optional(&control.pool)
        .await
        .unwrap();
        if blocked_pid.is_some() {
            break;
        }
        tokio::time::sleep(Duration::from_millis(20)).await;
    }
    let blocked_pid: i32 =
        blocked_pid.expect("victim commit must block on the canonical game lock");
    let terminated: bool = sqlx::query_scalar("SELECT pg_terminate_backend($1)")
        .bind(blocked_pid)
        .fetch_one(&control.pool)
        .await
        .unwrap();
    assert!(terminated);
    holder.rollback().await.unwrap();
    assert_eq!(victim_task.await.unwrap(), Err(CommitError::Storage));

    let failed_counts: (i64, i64, i64, i64) = sqlx::query_as(
        "SELECT (SELECT count(*) FROM game_revisions WHERE game_id=$1 AND revision>0), (SELECT count(*) FROM game_snapshots WHERE game_id=$1 AND revision>0), (SELECT count(*) FROM game_commands WHERE game_id=$1), (SELECT count(*) FROM game_outbox WHERE game_id=$1)",
    )
    .bind(game)
    .fetch_one(&control.pool)
    .await
    .unwrap();
    assert_eq!(failed_counts, (0, 0, 0, 0));

    let accepted = victim
        .commit(
            owner,
            command(game, command_id, 0),
            proposal(0, b"after-database-retry"),
        )
        .await
        .unwrap();
    assert_eq!(accepted.committed_revision, 1);
    assert_eq!(
        control
            .reconcile_authoritative_state()
            .await
            .unwrap()
            .total_findings,
        0
    );
}
