use super::*;
use crate::{GameCommand, state_hash};
use std::{env, time::Duration};
#[path = "account_security_integration_tests.rs"]
mod account_security;
#[path = "administration_integration_tests.rs"]
mod administration;
#[path = "backup_restore_integration_tests.rs"]
mod backup_restore;
#[path = "disk_full_integration_tests.rs"]
mod disk_full;
#[path = "game_creation_integration_tests.rs"]
mod game_creation;
#[path = "invitation_integration_tests.rs"]
mod invitations;
#[path = "legacy_import_integration_tests.rs"]
mod legacy_import;
#[path = "malicious_client_integration_tests.rs"]
mod malicious_clients;
#[path = "manifest_integration_tests.rs"]
mod manifests;
#[path = "outbox_operations_integration_tests.rs"]
mod outbox_operations;
#[path = "reconciliation_integration_tests.rs"]
mod reconciliation;
#[path = "recovery_integration_tests.rs"]
mod recovery;
#[path = "repair_integration_tests.rs"]
mod repair;
#[path = "replica_fault_integration_tests.rs"]
mod replica_faults;
#[path = "retention_integration_tests.rs"]
mod retention;
#[path = "snapshot_integration_tests.rs"]
mod snapshots;
#[path = "social_graph_integration_tests.rs"]
mod social_graph;
#[path = "spectator_integration_tests.rs"]
mod spectators;
#[path = "websocket_lease_integration_tests.rs"]
mod websocket_leases;
#[path = "worker_fault_integration_tests.rs"]
mod worker_faults;

fn database_url() -> String {
    env::var("UNCIV_V3_DATABASE_URL")
        .expect("UNCIV_V3_DATABASE_URL is required for PostgreSQL integration tests")
}

async fn seed_repository(repository: &PostgresGameRepository) -> (Uuid, Uuid) {
    sqlx::query("TRUNCATE game_outbox, game_revisions, game_commands, game_snapshots, game_members, games, ruleset_manifests, accounts CASCADE")
        .execute(&repository.pool)
        .await
        .unwrap();
    let account = Uuid::new_v4();
    let game = Uuid::new_v4();
    let manifest_hash = "a".repeat(64);
    sqlx::query("INSERT INTO accounts (id, username_normalized, password_hash) VALUES ($1, $2, 'test-hash')")
        .bind(account)
        .bind(format!("account-{}", account))
        .execute(&repository.pool)
        .await
        .unwrap();
    sqlx::query("INSERT INTO ruleset_manifests (hash, engine_build, manifest) VALUES ($1, 'test-engine', '{}'::jsonb)")
        .bind(&manifest_hash)
        .execute(&repository.pool)
        .await
        .unwrap();
    repository
        .create_game(NewGame {
            game_id: game,
            owner_account_id: account,
            ruleset_manifest_hash: manifest_hash,
            snapshot: b"revision-0".to_vec(),
            owner_civilization_id: "test-civilization".to_owned(),
        })
        .await
        .unwrap();
    (account, game)
}

fn command(game_id: Uuid, command_id: Uuid, expected_revision: u64) -> CommandEnvelope {
    CommandEnvelope {
        protocol_version: PROTOCOL_VERSION,
        game_id,
        command_id,
        expected_revision,
        client_observed_state_hash: None,
        command: GameCommand::EndTurn {},
    }
}

fn proposal(previous_revision: u64, snapshot: &[u8]) -> CommitProposal {
    CommitProposal {
        previous_revision,
        snapshot: snapshot.to_vec(),
        canonical_state_hash: state_hash(snapshot),
        server_time_millis: 0,
        replay_operation: serde_json::json!({"type": "test"}),
    }
}

#[tokio::test]
#[ignore = "requires an explicit UNCIV_V3_DATABASE_URL"]
async fn postgres_commit_is_atomic_idempotent_and_stale_safe() {
    let repository = PostgresGameRepository::connect(&database_url())
        .await
        .unwrap();
    repository.migrate().await.unwrap();
    let (account, game) = seed_repository(&repository).await;
    let command_id = Uuid::new_v4();

    let accepted = repository
        .commit(
            account,
            command(game, command_id, 0),
            proposal(0, b"revision-1"),
        )
        .await
        .unwrap();
    let duplicate = repository
        .commit(
            account,
            command(game, command_id, 0),
            proposal(0, b"tampered"),
        )
        .await
        .unwrap();
    let outsider = Uuid::new_v4();
    sqlx::query("INSERT INTO accounts (id, username_normalized, password_hash) VALUES ($1, $2, 'test-hash')")
        .bind(outsider)
        .bind(format!("account-{outsider}"))
        .execute(&repository.pool)
        .await
        .unwrap();
    let unauthorized_duplicate = repository
        .commit(
            outsider,
            command(game, command_id, 0),
            proposal(0, b"revision-1"),
        )
        .await
        .unwrap_err();
    let stale = repository
        .commit(
            account,
            command(game, Uuid::new_v4(), 0),
            proposal(0, b"replacement"),
        )
        .await
        .unwrap_err();

    assert_eq!(accepted, duplicate);
    assert_eq!(unauthorized_duplicate, CommitError::Unauthorized);
    assert_eq!(
        stale,
        CommitError::Stale {
            expected: 0,
            actual: 1
        }
    );
    let outbox_count: i64 =
        sqlx::query_scalar("SELECT count(*) FROM game_outbox WHERE game_id = $1")
            .bind(game)
            .fetch_one(&repository.pool)
            .await
            .unwrap();
    assert_eq!(outbox_count, 1);
    let journal_actor: (String, bool, i64, bool, serde_json::Value, bool) = sqlx::query_as(
        "SELECT actor_civilization_id, replay_identity_available, server_time_millis, replay_time_available, replay_operation, replay_operation_available FROM game_commands WHERE game_id=$1 AND command_id=$2",
    )
    .bind(game)
    .bind(command_id)
    .fetch_one(&repository.pool)
    .await
    .unwrap();
    assert_eq!(
        journal_actor,
        (
            "test-civilization".to_owned(),
            true,
            0,
            true,
            serde_json::json!({"type": "test"}),
            true
        )
    );
}

#[tokio::test]
#[ignore = "requires an explicit UNCIV_V3_DATABASE_URL"]
async fn resignation_atomically_commits_and_removes_membership_but_remains_idempotent() {
    let repository = PostgresGameRepository::connect(&database_url())
        .await
        .unwrap();
    repository.migrate().await.unwrap();
    let (account, game) = seed_repository(&repository).await;
    let command_id = Uuid::new_v4();
    let envelope = CommandEnvelope {
        command: GameCommand::Resign {},
        ..command(game, command_id, 0)
    };

    let accepted = repository
        .commit_resignation(account, envelope.clone(), proposal(0, b"resigned-revision"))
        .await
        .unwrap();
    let duplicate = repository
        .commit_resignation(account, envelope, proposal(0, b"must-not-replace"))
        .await
        .unwrap();

    assert_eq!(accepted, duplicate);
    let membership_count: i64 = sqlx::query_scalar(
        "SELECT count(*) FROM game_members WHERE game_id = $1 AND account_id = $2",
    )
    .bind(game)
    .bind(account)
    .fetch_one(&repository.pool)
    .await
    .unwrap();
    let head_revision: i64 = sqlx::query_scalar("SELECT head_revision FROM games WHERE id = $1")
        .bind(game)
        .fetch_one(&repository.pool)
        .await
        .unwrap();
    assert_eq!(membership_count, 0);
    assert_eq!(head_revision, 1);
    let journal_actor: String = sqlx::query_scalar(
        "SELECT actor_civilization_id FROM game_commands WHERE game_id=$1 AND command_id=$2",
    )
    .bind(game)
    .bind(command_id)
    .fetch_one(&repository.pool)
    .await
    .unwrap();
    assert_eq!(
        journal_actor, "test-civilization",
        "replay identity must survive resignation membership removal"
    );
}

#[tokio::test]
#[ignore = "requires an explicit UNCIV_V3_DATABASE_URL"]
async fn force_resignation_removes_only_the_worker_identified_membership() {
    let repository = PostgresGameRepository::connect(&database_url())
        .await
        .unwrap();
    repository.migrate().await.unwrap();
    let (owner, game) = seed_repository(&repository).await;
    let target = Uuid::new_v4();
    sqlx::query("INSERT INTO accounts (id, username_normalized, password_hash) VALUES ($1, $2, 'test-hash')")
        .bind(target)
        .bind(format!("account-{target}"))
        .execute(&repository.pool)
        .await
        .unwrap();
    sqlx::query("INSERT INTO game_members (game_id, account_id, role, civilization_id) VALUES ($1, $2, 'player', 'Greece')")
        .bind(game)
        .bind(target)
        .execute(&repository.pool)
        .await
        .unwrap();
    let command_id = Uuid::new_v4();
    let envelope = CommandEnvelope {
        command: GameCommand::ForceResign {},
        ..command(game, command_id, 0)
    };

    let accepted = repository
        .commit_civilization_removal(
            owner,
            envelope.clone(),
            proposal(0, b"force-resigned-revision"),
            "Greece".to_owned(),
        )
        .await
        .unwrap();
    let duplicate = repository
        .commit_civilization_removal(
            owner,
            envelope,
            proposal(0, b"must-not-replace"),
            "Greece".to_owned(),
        )
        .await
        .unwrap();

    assert_eq!(accepted, duplicate);
    let owner_exists: bool = sqlx::query_scalar(
        "SELECT EXISTS(SELECT 1 FROM game_members WHERE game_id = $1 AND account_id = $2)",
    )
    .bind(game)
    .bind(owner)
    .fetch_one(&repository.pool)
    .await
    .unwrap();
    let target_exists: bool = sqlx::query_scalar(
        "SELECT EXISTS(SELECT 1 FROM game_members WHERE game_id = $1 AND account_id = $2)",
    )
    .bind(game)
    .bind(target)
    .fetch_one(&repository.pool)
    .await
    .unwrap();
    assert!(owner_exists);
    assert!(!target_exists);
}

#[tokio::test]
#[ignore = "requires an explicit UNCIV_V3_DATABASE_URL"]
async fn game_discovery_is_membership_scoped_paginated_and_quarantine_aware() {
    let repository = PostgresGameRepository::connect(&database_url())
        .await
        .unwrap();
    repository.migrate().await.unwrap();
    let (account, first_game) = seed_repository(&repository).await;
    let manifest_hash = "a".repeat(64);
    let second_game = Uuid::new_v4();
    repository
        .create_game(NewGame {
            game_id: second_game,
            owner_account_id: account,
            ruleset_manifest_hash: manifest_hash.clone(),
            snapshot: b"second-game".to_vec(),
            owner_civilization_id: "second-civilization".to_owned(),
        })
        .await
        .unwrap();
    let outsider = Uuid::new_v4();
    sqlx::query("INSERT INTO accounts (id, username_normalized, password_hash) VALUES ($1, $2, 'test-hash')")
        .bind(outsider)
        .bind(format!("account-{outsider}"))
        .execute(&repository.pool)
        .await
        .unwrap();
    repository
        .create_game(NewGame {
            game_id: Uuid::new_v4(),
            owner_account_id: outsider,
            ruleset_manifest_hash: manifest_hash,
            snapshot: b"outsider-game".to_vec(),
            owner_civilization_id: "hidden-civilization".to_owned(),
        })
        .await
        .unwrap();
    sqlx::query(
        "UPDATE games SET unavailable_at=now(), unavailable_reason='restore_required' WHERE id=$1",
    )
    .bind(second_game)
    .execute(&repository.pool)
    .await
    .unwrap();

    let first_page = repository.list_games(account, None, 1).await.unwrap();
    assert_eq!(first_page.games.len(), 1);
    assert!(first_page.next_cursor.is_some());
    let second_page = repository
        .list_games(account, first_page.next_cursor, 1)
        .await
        .unwrap();
    assert_eq!(second_page.games.len(), 1);
    assert_eq!(second_page.next_cursor, None);
    let mut discovered = first_page
        .games
        .into_iter()
        .chain(second_page.games)
        .collect::<Vec<_>>();
    discovered.sort_by_key(|game| game.game_id);
    let mut expected_ids = vec![first_game, second_game];
    expected_ids.sort();
    assert_eq!(
        discovered
            .iter()
            .map(|game| game.game_id)
            .collect::<Vec<_>>(),
        expected_ids
    );
    assert!(
        !discovered
            .iter()
            .find(|game| game.game_id == second_game)
            .unwrap()
            .available
    );
}

#[tokio::test]
#[ignore = "requires an explicit UNCIV_V3_DATABASE_URL"]
async fn shared_notifications_reach_every_active_replica_listener() {
    let repository = PostgresGameRepository::connect(&database_url())
        .await
        .unwrap();
    repository.migrate().await.unwrap();
    let (account, game) = seed_repository(&repository).await;
    let first_hub = crate::notifications::NotificationHub::default();
    let second_hub = crate::notifications::NotificationHub::default();
    let mut first_socket = first_hub.try_subscribe(account).unwrap();
    let mut second_socket = second_hub.try_subscribe(account).unwrap();
    let first_listener = repository.shared_notification_listener().await.unwrap();
    let second_listener = repository.shared_notification_listener().await.unwrap();
    let first_task = tokio::spawn(crate::notifications::run_shared_listener(
        repository.clone(),
        first_hub,
        first_listener,
    ));
    let second_task = tokio::spawn(crate::notifications::run_shared_listener(
        repository.clone(),
        second_hub,
        second_listener,
    ));
    let payload = serde_json::json!({
        "schema_version": 1,
        "event_type": "revision_committed",
        "protocol_version": PROTOCOL_VERSION,
        "game_id": game,
        "committed_revision": 11,
        "canonical_state_hash": "ab".repeat(32),
    })
    .to_string();

    repository
        .publish_shared_notification(&payload)
        .await
        .unwrap();

    let expected = crate::notifications::NotificationDelivery::Revision(
        crate::notifications::RevisionNotification {
            event_type: "revision_committed",
            protocol_version: PROTOCOL_VERSION,
            game_id: game,
            committed_revision: 11,
            canonical_state_hash: "ab".repeat(32),
        },
    );
    let first = tokio::time::timeout(Duration::from_secs(2), first_socket.recv())
        .await
        .expect("first replica did not fan out shared notification")
        .unwrap();
    let second = tokio::time::timeout(Duration::from_secs(2), second_socket.recv())
        .await
        .expect("second replica did not fan out shared notification")
        .unwrap();
    assert_eq!(first, expected);
    assert_eq!(second, expected);
    first_task.abort();
    second_task.abort();
}

#[tokio::test]
#[ignore = "requires an explicit UNCIV_V3_DATABASE_URL"]
async fn outbox_claims_are_exclusive_recoverable_and_token_bound() {
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

    let first = repository.claim_outbox_batch(1).await.unwrap().remove(0);
    assert!(repository.claim_outbox_batch(1).await.unwrap().is_empty());
    sqlx::query("UPDATE game_outbox SET claimed_at=now() - interval '31 seconds' WHERE id=$1")
        .bind(first.id)
        .execute(&repository.pool)
        .await
        .unwrap();
    let reclaimed = repository.claim_outbox_batch(1).await.unwrap().remove(0);

    assert_eq!(first.id, reclaimed.id);
    assert_ne!(first.claim_token, reclaimed.claim_token);
    assert_eq!(
        repository
            .acknowledge_outbox(first.id, first.claim_token)
            .await,
        Err(CommitError::Storage)
    );
    repository
        .acknowledge_outbox(reclaimed.id, reclaimed.claim_token)
        .await
        .unwrap();
    assert!(repository.claim_outbox_batch(1).await.unwrap().is_empty());
}

#[tokio::test]
#[ignore = "requires an explicit UNCIV_V3_DATABASE_URL"]
async fn auth_rate_limits_are_durable_resettable_and_privacy_bounded() {
    let repository = PostgresGameRepository::connect(&database_url())
        .await
        .unwrap();
    repository.migrate().await.unwrap();
    sqlx::query("TRUNCATE api_rate_limits, security_audit_events")
        .execute(&repository.pool)
        .await
        .unwrap();
    let bucket = "login:identity:192.0.2.0/24:private-user";

    repository
        .consume_rate_limit(bucket, 60, 2, 60)
        .await
        .unwrap();
    repository
        .consume_rate_limit(bucket, 60, 2, 60)
        .await
        .unwrap();
    assert!(matches!(
        repository.consume_rate_limit(bucket, 60, 2, 60).await,
        Err(AuthError::RateLimited)
    ));
    repository.clear_rate_limit(bucket).await.unwrap();
    repository
        .consume_rate_limit(bucket, 60, 2, 60)
        .await
        .unwrap();
    repository
        .record_security_audit(
            None,
            SecurityAuditEvent::Login,
            SecurityAuditOutcome::Rejected,
            "192.0.2.0/24",
            Some("private-user"),
        )
        .await
        .unwrap();

    let stored_bucket: String = sqlx::query_scalar("SELECT bucket_hash FROM api_rate_limits")
        .fetch_one(&repository.pool)
        .await
        .unwrap();
    let audit = sqlx::query(
        "SELECT event_type, outcome, identity_hash, source_ip_prefix::text AS source_ip_prefix, details FROM security_audit_events",
    )
    .fetch_one(&repository.pool)
    .await
    .unwrap();
    assert_eq!(stored_bucket.len(), 64);
    assert!(!stored_bucket.contains("private-user"));
    assert_eq!(audit.get::<String, _>("identity_hash").len(), 64);
    assert_eq!(audit.get::<String, _>("source_ip_prefix"), "192.0.2.0/24");
    assert_eq!(audit.get::<String, _>("event_type"), "login");
    assert_eq!(audit.get::<String, _>("outcome"), "rejected");
    assert_eq!(audit.get::<serde_json::Value, _>("details"), json!({}));
    assert!(
        sqlx::query("UPDATE security_audit_events SET details=$1")
            .bind(json!({"snapshot": "private-canonical-state"}))
            .execute(&repository.pool)
            .await
            .is_err()
    );
}

#[tokio::test]
#[ignore = "requires an explicit UNCIV_V3_DATABASE_URL"]
async fn one_civilization_cannot_be_assigned_to_two_accounts() {
    let repository = PostgresGameRepository::connect(&database_url())
        .await
        .unwrap();
    repository.migrate().await.unwrap();
    let (_owner, game) = seed_repository(&repository).await;
    let second_account = Uuid::new_v4();
    sqlx::query("INSERT INTO accounts (id, username_normalized, password_hash) VALUES ($1, $2, 'test-hash')")
        .bind(second_account)
        .bind(format!("account-{second_account}"))
        .execute(&repository.pool)
        .await
        .unwrap();

    let duplicate = sqlx::query(
        "INSERT INTO game_members (game_id, account_id, role, civilization_id) VALUES ($1, $2, 'player', 'test-civilization')",
    )
    .bind(game)
    .bind(second_account)
    .execute(&repository.pool)
    .await;

    assert!(duplicate.is_err());
}

#[tokio::test]
#[ignore = "requires an explicit UNCIV_V3_DATABASE_URL"]
async fn accounts_and_revocable_sessions_are_persisted_without_raw_tokens() {
    let repository = PostgresGameRepository::connect(&database_url())
        .await
        .unwrap();
    repository.migrate().await.unwrap();
    sqlx::query("TRUNCATE sessions, game_outbox, game_revisions, game_commands, game_snapshots, game_members, games, ruleset_manifests, accounts CASCADE")
        .execute(&repository.pool)
        .await
        .unwrap();

    let account = repository
        .register_account("  Player-One  ", "long-enough-password")
        .await
        .unwrap();
    assert_eq!(account.username_normalized, "player-one");
    assert!(matches!(
        repository
            .register_account("player-one", "long-enough-password")
            .await,
        Err(AuthError::UsernameTaken)
    ));
    assert!(matches!(
        repository
            .authenticate_account("player-one", "incorrect-password")
            .await,
        Err(AuthError::InvalidCredentials)
    ));

    let authenticated = repository
        .authenticate_account("PLAYER-ONE", "long-enough-password")
        .await
        .unwrap();
    assert_eq!(authenticated, account);
    let session = repository.issue_session(account.id).await.unwrap();
    let stored_digest: String =
        sqlx::query_scalar("SELECT token_digest FROM sessions WHERE account_id = $1")
            .bind(account.id)
            .fetch_one(&repository.pool)
            .await
            .unwrap();
    assert_eq!(stored_digest, session.digest);
    assert_ne!(stored_digest, session.token);
    assert_eq!(
        repository
            .authenticate_session(&session.token)
            .await
            .unwrap(),
        account
    );

    let rotated = repository.rotate_session(&session.token).await.unwrap();
    assert!(matches!(
        repository.authenticate_session(&session.token).await,
        Err(AuthError::InvalidCredentials)
    ));
    assert_eq!(
        repository
            .authenticate_session(&rotated.token)
            .await
            .unwrap(),
        account
    );
    let parent_is_set: bool = sqlx::query_scalar(
        "SELECT parent_session_id IS NOT NULL FROM sessions WHERE token_digest = $1",
    )
    .bind(&rotated.digest)
    .fetch_one(&repository.pool)
    .await
    .unwrap();
    assert!(parent_is_set);

    repository.revoke_session(&rotated.token).await.unwrap();
    assert!(matches!(
        repository.authenticate_session(&rotated.token).await,
        Err(AuthError::InvalidCredentials)
    ));
}

#[tokio::test]
#[ignore = "requires an explicit UNCIV_V3_DATABASE_URL"]
async fn account_lifecycle_revokes_sessions_and_preserves_history_references() {
    let repository = PostgresGameRepository::connect(&database_url())
        .await
        .unwrap();
    repository.migrate().await.unwrap();
    seed_repository(&repository).await;

    let account = repository
        .register_account("password-owner", "original-password")
        .await
        .unwrap();
    let first = repository.issue_session(account.id).await.unwrap();
    let second = repository.issue_session(account.id).await.unwrap();
    assert!(matches!(
        repository
            .change_password(account.id, "original-password", "original-password")
            .await,
        Err(AuthError::InvalidPassword(PasswordError::Unchanged))
    ));
    repository.authenticate_session(&first.token).await.unwrap();
    let replacement = repository
        .change_password(account.id, "original-password", "replacement-password")
        .await
        .unwrap();
    assert!(matches!(
        repository.authenticate_session(&first.token).await,
        Err(AuthError::InvalidCredentials)
    ));
    assert!(matches!(
        repository.authenticate_session(&second.token).await,
        Err(AuthError::InvalidCredentials)
    ));
    assert_eq!(
        repository
            .authenticate_session(&replacement.token)
            .await
            .unwrap(),
        account
    );
    assert!(matches!(
        repository
            .authenticate_account("password-owner", "original-password")
            .await,
        Err(AuthError::InvalidCredentials)
    ));
    repository
        .authenticate_account("password-owner", "replacement-password")
        .await
        .unwrap();
    let active_sessions: i64 = sqlx::query_scalar(
        "SELECT count(*) FROM sessions WHERE account_id=$1 AND revoked_at IS NULL",
    )
    .bind(account.id)
    .fetch_one(&repository.pool)
    .await
    .unwrap();
    assert_eq!(active_sessions, 1);

    let disabled = repository
        .register_account("disable-me", "disable-password")
        .await
        .unwrap();
    let disabled_session = repository.issue_session(disabled.id).await.unwrap();
    assert!(matches!(
        repository
            .disable_account(disabled.id, "wrong-password")
            .await,
        Err(AuthError::InvalidCredentials)
    ));
    repository
        .authenticate_session(&disabled_session.token)
        .await
        .unwrap();
    repository
        .disable_account(disabled.id, "disable-password")
        .await
        .unwrap();
    assert!(matches!(
        repository
            .authenticate_session(&disabled_session.token)
            .await,
        Err(AuthError::InvalidCredentials)
    ));
    assert!(matches!(
        repository
            .authenticate_account("disable-me", "disable-password")
            .await,
        Err(AuthError::AccountDisabled)
    ));

    let deleted = repository
        .register_account("delete-me", "delete-password")
        .await
        .unwrap();
    let deleted_game = Uuid::new_v4();
    repository
        .create_game(NewGame {
            game_id: deleted_game,
            owner_account_id: deleted.id,
            ruleset_manifest_hash: "a".repeat(64),
            snapshot: b"deletion-history".to_vec(),
            owner_civilization_id: "deleted-civilization".to_owned(),
        })
        .await
        .unwrap();
    repository
        .delete_account(deleted.id, "delete-password")
        .await
        .unwrap();
    let deleted_row = sqlx::query(
        "SELECT username_normalized, disabled_at IS NOT NULL AS disabled, deleted_at IS NOT NULL AS deleted FROM accounts WHERE id=$1",
    )
    .bind(deleted.id)
    .fetch_one(&repository.pool)
    .await
    .unwrap();
    assert_eq!(
        deleted_row.get::<String, _>("username_normalized"),
        format!("deleted-{}", deleted.id)
    );
    assert!(deleted_row.get::<bool, _>("disabled"));
    assert!(deleted_row.get::<bool, _>("deleted"));
    let membership_survived: bool = sqlx::query_scalar(
        "SELECT EXISTS(SELECT 1 FROM game_members WHERE game_id=$1 AND account_id=$2)",
    )
    .bind(deleted_game)
    .bind(deleted.id)
    .fetch_one(&repository.pool)
    .await
    .unwrap();
    assert!(membership_survived);
    assert!(matches!(
        repository
            .authenticate_account("delete-me", "delete-password")
            .await,
        Err(AuthError::InvalidCredentials)
    ));
}
