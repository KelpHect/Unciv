use super::*;

#[tokio::test]
#[ignore = "requires an explicit UNCIV_V3_DATABASE_URL"]
async fn outbox_poison_requeue_health_and_compaction_are_bounded_and_audited() {
    let repository = PostgresGameRepository::connect(&database_url())
        .await
        .unwrap();
    repository.migrate().await.unwrap();
    let (account, game) = seed_repository(&repository).await;
    repository
        .commit(
            account,
            command(game, Uuid::new_v4(), 0),
            proposal(0, b"revision-1"),
        )
        .await
        .unwrap();

    let mut outbox_id = 0_i64;
    for attempt in 1..=3 {
        let event = repository.claim_outbox_batch(1).await.unwrap().remove(0);
        outbox_id = event.id;
        let disposition = repository
            .retry_outbox(event.id, event.claim_token, "bounded test failure", 3)
            .await
            .unwrap();
        assert_eq!(
            disposition,
            if attempt == 3 {
                OutboxRetryDisposition::DeadLettered
            } else {
                OutboxRetryDisposition::RetryScheduled
            }
        );
        if attempt < 3 {
            sqlx::query("UPDATE game_outbox SET available_at=now() WHERE id=$1")
                .bind(event.id)
                .execute(&repository.pool)
                .await
                .unwrap();
        }
    }
    assert!(repository.claim_outbox_batch(1).await.unwrap().is_empty());
    let unhealthy = repository
        .outbox_health(Duration::from_secs(60))
        .await
        .unwrap();
    assert_eq!(
        unhealthy,
        OutboxHealthReport {
            pending_events: 0,
            dead_letter_events: 1,
            oldest_pending_age_seconds: 0,
            oldest_dead_letter_age_seconds: 0,
            maximum_attempt_count: 3,
            alert: true,
        }
    );

    let preview = repository
        .requeue_dead_letter(outbox_id, true)
        .await
        .unwrap();
    assert_eq!(
        preview,
        OutboxRequeueReport {
            dry_run: true,
            outbox_id,
            requeued: false,
        }
    );
    let applied = repository
        .requeue_dead_letter(outbox_id, false)
        .await
        .unwrap();
    assert!(applied.requeued);
    let event = repository.claim_outbox_batch(1).await.unwrap().remove(0);
    assert_eq!(event.id, outbox_id);
    repository
        .acknowledge_outbox(event.id, event.claim_token)
        .await
        .unwrap();
    sqlx::query("UPDATE game_outbox SET created_at=now()-interval '31 days' WHERE id=$1")
        .bind(outbox_id)
        .execute(&repository.pool)
        .await
        .unwrap();

    let preview = repository
        .compact_delivered_outbox(30, 100, true)
        .await
        .unwrap();
    assert_eq!(preview.selected_events, 1);
    assert_eq!(preview.compacted_events, 0);
    assert_eq!(
        repository
            .reconcile_authoritative_state()
            .await
            .unwrap()
            .total_findings,
        0
    );
    let applied = repository
        .compact_delivered_outbox(30, 100, false)
        .await
        .unwrap();
    assert_eq!(applied.selected_events, 1);
    assert_eq!(applied.compacted_events, 1);
    assert_eq!(
        repository
            .reconcile_authoritative_state()
            .await
            .unwrap()
            .total_findings,
        0
    );

    let (active, receipts, requeues, compactions): (i64, i64, i64, i64) = sqlx::query_as(
        "SELECT (SELECT count(*) FROM game_outbox WHERE id=$1), (SELECT count(*) FROM game_outbox_receipts WHERE outbox_id=$1), (SELECT count(*) FROM outbox_operator_audit WHERE action='requeue_dead_letter' AND outbox_id=$1), (SELECT count(*) FROM outbox_operator_audit WHERE action='compact_delivered' AND affected_count=1)",
    )
    .bind(outbox_id)
    .fetch_one(&repository.pool)
    .await
    .unwrap();
    assert_eq!((active, receipts, requeues, compactions), (0, 1, 1, 1));
}
