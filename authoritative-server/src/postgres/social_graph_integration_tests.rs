use super::*;

async fn add_social_account(repository: &PostgresGameRepository, username: &str) -> Uuid {
    let account_id = Uuid::new_v4();
    sqlx::query(
        "INSERT INTO accounts (id, username_normalized, password_hash)
         VALUES ($1, $2, 'test-hash')",
    )
    .bind(account_id)
    .bind(username)
    .execute(&repository.pool)
    .await
    .unwrap();
    account_id
}

#[tokio::test]
#[ignore = "requires an explicit UNCIV_V3_DATABASE_URL"]
async fn friendship_lifecycle_is_authorized_bounded_and_retry_safe() {
    let repository = PostgresGameRepository::connect(&database_url())
        .await
        .unwrap();
    repository.migrate().await.unwrap();
    let (owner, _) = seed_repository(&repository).await;
    let recipient = add_social_account(&repository, "social-recipient").await;
    let outsider = add_social_account(&repository, "social-outsider").await;
    let request_id = Uuid::new_v4();

    repository
        .request_friendship(owner, request_id, " SOCIAL-RECIPIENT ")
        .await
        .unwrap();
    repository
        .request_friendship(owner, request_id, "social-recipient")
        .await
        .unwrap();
    assert_eq!(
        repository
            .request_friendship(owner, request_id, "social-outsider")
            .await
            .unwrap_err(),
        CommitError::IdempotencyConflict
    );
    assert_eq!(
        repository
            .request_friendship(recipient, Uuid::new_v4(), &format!("account-{owner}"))
            .await
            .unwrap_err(),
        CommitError::InvalidCommand
    );

    let owner_graph = repository.list_social_graph(owner).await.unwrap();
    assert!(owner_graph.friends.is_empty());
    assert_eq!(
        owner_graph.requests,
        vec![FriendRequestSummary {
            request_id,
            username: "social-recipient".to_owned(),
            direction: "outgoing".to_owned(),
        }]
    );
    let recipient_graph = repository.list_social_graph(recipient).await.unwrap();
    assert_eq!(recipient_graph.requests[0].direction, "incoming");
    assert_eq!(
        repository
            .accept_friendship(outsider, request_id)
            .await
            .unwrap_err(),
        CommitError::Unauthorized
    );

    repository
        .accept_friendship(recipient, request_id)
        .await
        .unwrap();
    let owner_graph = repository.list_social_graph(owner).await.unwrap();
    let recipient_graph = repository.list_social_graph(recipient).await.unwrap();
    assert_eq!(
        owner_graph.friends,
        vec![FriendSummary {
            username: "social-recipient".to_owned(),
        }]
    );
    assert_eq!(
        recipient_graph.friends,
        vec![FriendSummary {
            username: format!("account-{owner}"),
        }]
    );
    assert!(owner_graph.requests.is_empty());
    assert!(recipient_graph.requests.is_empty());

    repository
        .remove_friendship(owner, "social-recipient")
        .await
        .unwrap();
    assert!(
        repository
            .list_social_graph(owner)
            .await
            .unwrap()
            .friends
            .is_empty()
    );
    assert!(
        repository
            .list_social_graph(recipient)
            .await
            .unwrap()
            .friends
            .is_empty()
    );
}

#[tokio::test]
#[ignore = "requires an explicit UNCIV_V3_DATABASE_URL"]
async fn either_participant_can_cancel_a_pending_request_but_outsiders_cannot() {
    let repository = PostgresGameRepository::connect(&database_url())
        .await
        .unwrap();
    repository.migrate().await.unwrap();
    let (owner, _) = seed_repository(&repository).await;
    let recipient = add_social_account(&repository, "cancel-recipient").await;
    let outsider = add_social_account(&repository, "cancel-outsider").await;
    let request_id = Uuid::new_v4();

    repository
        .request_friendship(owner, request_id, "cancel-recipient")
        .await
        .unwrap();
    assert_eq!(
        repository
            .remove_friend_request(outsider, request_id)
            .await
            .unwrap_err(),
        CommitError::NotFound
    );
    repository
        .remove_friend_request(recipient, request_id)
        .await
        .unwrap();
    assert!(
        repository
            .list_social_graph(owner)
            .await
            .unwrap()
            .requests
            .is_empty()
    );
}
