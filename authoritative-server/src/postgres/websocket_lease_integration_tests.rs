use super::*;
use crate::postgres::{WebSocketConnectionLease, WebSocketLeaseError};

#[tokio::test]
#[ignore = "requires an explicit UNCIV_V3_DATABASE_URL"]
async fn fleet_admission_is_atomic_across_repositories() {
    let repository = PostgresGameRepository::connect(&database_url())
        .await
        .unwrap();
    repository.migrate().await.unwrap();
    let (first_account, _) = seed_repository(&repository).await;
    let second_account = Uuid::new_v4();
    sqlx::query(
        "INSERT INTO accounts (id, username_normalized, password_hash)
         VALUES ($1, $2, 'test-hash')",
    )
    .bind(second_account)
    .bind(format!("account-{second_account}"))
    .execute(&repository.pool)
    .await
    .unwrap();
    let second_repository = PostgresGameRepository::connect(&database_url())
        .await
        .unwrap();
    let first_replica = Uuid::new_v4();
    let second_replica = Uuid::new_v4();

    let (first_result, second_result) = tokio::join!(
        repository.acquire_websocket_lease(first_account, first_replica, 2, 1, 90),
        second_repository.acquire_websocket_lease(first_account, second_replica, 2, 1, 90),
    );
    let account_winner = exactly_one_winner(
        first_result,
        second_result,
        WebSocketLeaseError::AccountLimit,
    );
    assert!(
        repository
            .release_websocket_lease(account_winner)
            .await
            .unwrap()
    );

    let (first_result, second_result) = tokio::join!(
        repository.acquire_websocket_lease(first_account, first_replica, 1, 1, 90),
        second_repository.acquire_websocket_lease(second_account, second_replica, 1, 1, 90),
    );
    let global_winner = exactly_one_winner(
        first_result,
        second_result,
        WebSocketLeaseError::GlobalLimit,
    );
    assert!(
        repository
            .release_websocket_lease(global_winner)
            .await
            .unwrap()
    );
}

fn exactly_one_winner(
    first: Result<WebSocketConnectionLease, WebSocketLeaseError>,
    second: Result<WebSocketConnectionLease, WebSocketLeaseError>,
    expected_loser: WebSocketLeaseError,
) -> WebSocketConnectionLease {
    match (first, second) {
        (Ok(winner), Err(error)) | (Err(error), Ok(winner)) => {
            assert_eq!(error, expected_loser);
            winner
        }
        result => panic!("expected exactly one fleet admission winner, got {result:?}"),
    }
}

#[tokio::test]
#[ignore = "requires an explicit UNCIV_V3_DATABASE_URL"]
async fn expired_crash_lease_is_reclaimed_and_ownership_is_bound() {
    let repository = PostgresGameRepository::connect(&database_url())
        .await
        .unwrap();
    repository.migrate().await.unwrap();
    let (account, _) = seed_repository(&repository).await;
    let replica = Uuid::new_v4();
    let lease = repository
        .acquire_websocket_lease(account, replica, 1, 1, 90)
        .await
        .unwrap();
    assert!(repository.renew_websocket_lease(lease, 90).await.unwrap());
    let forged = WebSocketConnectionLease {
        lease_id: lease.lease_id,
        replica_id: Uuid::new_v4(),
    };
    assert!(!repository.renew_websocket_lease(forged, 90).await.unwrap());
    assert!(!repository.release_websocket_lease(forged).await.unwrap());

    sqlx::query(
        "UPDATE websocket_connection_leases
         SET acquired_at = acquired_at - INTERVAL '120 seconds',
             renewed_at = renewed_at - INTERVAL '120 seconds',
             expires_at = expires_at - INTERVAL '120 seconds'
         WHERE lease_id = $1",
    )
    .bind(lease.lease_id)
    .execute(&repository.pool)
    .await
    .unwrap();
    assert!(!repository.renew_websocket_lease(lease, 90).await.unwrap());
    let replacement = repository
        .acquire_websocket_lease(account, Uuid::new_v4(), 1, 1, 90)
        .await
        .unwrap();
    assert!(
        repository
            .release_websocket_lease(replacement)
            .await
            .unwrap()
    );
}
