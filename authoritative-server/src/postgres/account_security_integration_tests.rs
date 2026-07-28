use super::*;

async fn clean_auth(repository: &PostgresGameRepository) {
    sqlx::query(
        "TRUNCATE account_recovery_codes, api_rate_limits, security_audit_events,
         sessions, game_outbox, game_revisions, game_commands, game_snapshots,
         game_members, games, ruleset_manifests, accounts CASCADE",
    )
    .execute(&repository.pool)
    .await
    .unwrap();
}

#[tokio::test]
#[ignore = "requires an explicit UNCIV_V3_DATABASE_URL"]
async fn active_session_limit_is_cross_replica_serialized_and_lru_bounded() {
    let repository = PostgresGameRepository::connect(&database_url())
        .await
        .unwrap();
    repository.migrate().await.unwrap();
    clean_auth(&repository).await;
    let account = repository
        .register_account("session-limit", "session-limit-password")
        .await
        .unwrap();
    let policy = SessionPolicy::try_with_max_active_sessions(2).unwrap();
    let first = repository
        .issue_session_with_policy(account.id, policy)
        .await
        .unwrap();
    let second = repository
        .issue_session_with_policy(account.id, policy)
        .await
        .unwrap();
    sqlx::query(
        "UPDATE sessions SET last_used_at=now() + interval '1 hour'
         WHERE token_digest=$1",
    )
    .bind(token_digest(&first.token))
    .execute(&repository.pool)
    .await
    .unwrap();
    let third = repository
        .issue_session_with_policy(account.id, policy)
        .await
        .unwrap();

    assert!(repository.authenticate_session(&first.token).await.is_ok());
    assert_eq!(
        repository
            .authenticate_session(&second.token)
            .await
            .unwrap_err(),
        AuthError::InvalidCredentials,
    );
    assert!(repository.authenticate_session(&third.token).await.is_ok());
    let active: i64 = sqlx::query_scalar(
        "SELECT count(*) FROM sessions
         WHERE account_id=$1 AND revoked_at IS NULL AND expires_at>now()",
    )
    .bind(account.id)
    .fetch_one(&repository.pool)
    .await
    .unwrap();
    let reason: String =
        sqlx::query_scalar("SELECT revoked_reason FROM sessions WHERE token_digest=$1")
            .bind(token_digest(&second.token))
            .fetch_one(&repository.pool)
            .await
            .unwrap();
    assert_eq!(active, 2);
    assert_eq!(reason, "session_limit");
}

#[tokio::test]
#[ignore = "requires an explicit UNCIV_V3_DATABASE_URL"]
async fn one_time_recovery_revokes_sessions_password_and_complete_code_batch() {
    let repository = PostgresGameRepository::connect(&database_url())
        .await
        .unwrap();
    repository.migrate().await.unwrap();
    clean_auth(&repository).await;
    let account = repository
        .register_account("recoverable", "original-recovery-password")
        .await
        .unwrap();
    let old_session = repository.issue_session(account.id).await.unwrap();

    assert_eq!(
        repository
            .replace_recovery_codes(account.id, "wrong-recovery-password")
            .await
            .unwrap_err(),
        AuthError::InvalidCredentials,
    );
    let batch = repository
        .replace_recovery_codes(account.id, "original-recovery-password")
        .await
        .unwrap();
    assert_eq!(batch.codes.len(), crate::auth::RECOVERY_CODE_COUNT);
    let stored_digests: Vec<String> =
        sqlx::query_scalar("SELECT code_digest FROM account_recovery_codes WHERE account_id=$1")
            .bind(account.id)
            .fetch_all(&repository.pool)
            .await
            .unwrap();
    assert_eq!(stored_digests.len(), crate::auth::RECOVERY_CODE_COUNT);
    assert!(
        batch
            .codes
            .iter()
            .all(|code| !stored_digests.contains(code))
    );

    assert_eq!(
        repository
            .recover_account(
                "recoverable",
                &"0".repeat(64),
                "replacement-recovery-password",
            )
            .await
            .unwrap_err(),
        AuthError::InvalidCredentials,
    );
    assert!(
        repository
            .authenticate_session(&old_session.token)
            .await
            .is_ok()
    );

    let (recovered, replacement) = repository
        .recover_account(
            "recoverable",
            &batch.codes[0],
            "replacement-recovery-password",
        )
        .await
        .unwrap();
    assert_eq!(recovered, account);
    assert_eq!(
        repository
            .authenticate_session(&old_session.token)
            .await
            .unwrap_err(),
        AuthError::InvalidCredentials,
    );
    assert!(
        repository
            .authenticate_session(&replacement.token)
            .await
            .is_ok()
    );
    assert_eq!(
        repository
            .authenticate_account("recoverable", "original-recovery-password")
            .await
            .unwrap_err(),
        AuthError::InvalidCredentials,
    );
    assert!(
        repository
            .authenticate_account("recoverable", "replacement-recovery-password")
            .await
            .is_ok()
    );
    assert_eq!(
        repository
            .recover_account("recoverable", &batch.codes[1], "another-recovery-password",)
            .await
            .unwrap_err(),
        AuthError::InvalidCredentials,
    );
    let unused: i64 = sqlx::query_scalar(
        "SELECT count(*) FROM account_recovery_codes
         WHERE account_id=$1 AND used_at IS NULL",
    )
    .bind(account.id)
    .fetch_one(&repository.pool)
    .await
    .unwrap();
    let active_sessions: i64 = sqlx::query_scalar(
        "SELECT count(*) FROM sessions
         WHERE account_id=$1 AND revoked_at IS NULL AND expires_at>now()",
    )
    .bind(account.id)
    .fetch_one(&repository.pool)
    .await
    .unwrap();
    assert_eq!(unused, 0);
    assert_eq!(active_sessions, 1);
}
