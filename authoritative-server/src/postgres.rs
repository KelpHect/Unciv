use serde_json::json;
use sqlx::{PgPool, Row, postgres::PgPoolOptions};
use uuid::Uuid;

use crate::auth::{
    Account, AuthError, PasswordService, SessionCredential, normalize_username, token_digest,
};
use crate::worker::{EngineWorkerClient, WorkerManifest};
use crate::{
    CommandAccepted, CommandEnvelope, CommitError, CommitProposal, PROTOCOL_VERSION, state_hash,
};

#[derive(Clone)]
pub struct PostgresGameRepository {
    pool: PgPool,
}

pub struct NewGame {
    pub game_id: Uuid,
    pub owner_account_id: Uuid,
    pub ruleset_manifest_hash: String,
    pub snapshot: Vec<u8>,
}

impl PostgresGameRepository {
    /// Creates an account with a normalized username and a per-password Argon2id
    /// hash. The transaction never writes a plaintext password or bearer token.
    pub async fn register_account(
        &self,
        username: &str,
        password: &str,
    ) -> Result<Account, AuthError> {
        let username_normalized = normalize_username(username)?;
        let password_hash = PasswordService.hash(password)?;
        let account = Account {
            id: Uuid::new_v4(),
            username_normalized,
        };
        match sqlx::query(
            "INSERT INTO accounts (id, username_normalized, password_hash) VALUES ($1, $2, $3)",
        )
        .bind(account.id)
        .bind(&account.username_normalized)
        .bind(password_hash)
        .execute(&self.pool)
        .await
        {
            Ok(_) => Ok(account),
            Err(error)
                if error
                    .as_database_error()
                    .and_then(|database| database.code())
                    .as_deref()
                    == Some("23505") =>
            {
                Err(AuthError::UsernameTaken)
            }
            Err(_) => Err(AuthError::Storage),
        }
    }

    /// Verifies credentials without exposing whether the username or password
    /// was wrong. Disabled status is kept distinct for server-side audit/UI
    /// policy; public handlers must still return a generic login rejection.
    pub async fn authenticate_account(
        &self,
        username: &str,
        password: &str,
    ) -> Result<Account, AuthError> {
        let username_normalized =
            normalize_username(username).map_err(|_| AuthError::InvalidCredentials)?;
        let row = sqlx::query("SELECT id, username_normalized, password_hash, disabled_at IS NOT NULL AS disabled FROM accounts WHERE username_normalized = $1")
            .bind(username_normalized)
            .fetch_optional(&self.pool)
            .await
            .map_err(|_| AuthError::Storage)?
            .ok_or(AuthError::InvalidCredentials)?;
        let password_hash: String = row.get("password_hash");
        if !PasswordService
            .verify(password, &password_hash)
            .map_err(|_| AuthError::InvalidCredentials)?
        {
            return Err(AuthError::InvalidCredentials);
        }
        if row.get::<bool, _>("disabled") {
            return Err(AuthError::AccountDisabled);
        }
        Ok(Account {
            id: row.get("id"),
            username_normalized: row.get("username_normalized"),
        })
    }

    /// Creates a revocable 30-day session. The raw token is returned once and
    /// never appears in SQL or logs; only its SHA-256 digest is persisted.
    pub async fn issue_session(&self, account_id: Uuid) -> Result<SessionCredential, AuthError> {
        let credential = SessionCredential::generate();
        sqlx::query("INSERT INTO sessions (id, account_id, token_digest, expires_at) VALUES ($1, $2, $3, now() + interval '30 days')")
            .bind(Uuid::new_v4())
            .bind(account_id)
            .bind(&credential.digest)
            .execute(&self.pool)
            .await
            .map_err(|_| AuthError::Storage)?;
        Ok(credential)
    }

    /// Resolves a non-revoked, non-expired bearer session and refreshes only
    /// its server-side activity timestamp. Authentication never trusts an ID
    /// supplied alongside the token.
    pub async fn authenticate_session(&self, bearer_token: &str) -> Result<Account, AuthError> {
        let digest = token_digest(bearer_token);
        let mut tx = self.pool.begin().await.map_err(|_| AuthError::Storage)?;
        let row = sqlx::query("SELECT a.id, a.username_normalized FROM sessions s JOIN accounts a ON a.id = s.account_id WHERE s.token_digest = $1 AND s.revoked_at IS NULL AND s.expires_at > now() AND a.disabled_at IS NULL FOR UPDATE")
            .bind(digest)
            .fetch_optional(&mut *tx)
            .await
            .map_err(|_| AuthError::Storage)?
            .ok_or(AuthError::InvalidCredentials)?;
        sqlx::query("UPDATE sessions SET last_used_at = now() WHERE token_digest = $1")
            .bind(token_digest(bearer_token))
            .execute(&mut *tx)
            .await
            .map_err(|_| AuthError::Storage)?;
        tx.commit().await.map_err(|_| AuthError::Storage)?;
        Ok(Account {
            id: row.get("id"),
            username_normalized: row.get("username_normalized"),
        })
    }

    pub async fn revoke_session(&self, bearer_token: &str) -> Result<(), AuthError> {
        sqlx::query(
            "UPDATE sessions SET revoked_at = now() WHERE token_digest = $1 AND revoked_at IS NULL",
        )
        .bind(token_digest(bearer_token))
        .execute(&self.pool)
        .await
        .map_err(|_| AuthError::Storage)?;
        Ok(())
    }

    /// Loads the canonical head, delegates rule execution to Kotlin, then uses
    /// the normal CAS commit path. A competing commit becomes a stale conflict;
    /// it can never merge or overwrite this result.
    pub async fn execute_end_turn(
        &self,
        worker: &EngineWorkerClient,
        actor_account_id: Uuid,
        envelope: CommandEnvelope,
    ) -> Result<CommandAccepted, CommitError> {
        let row = sqlx::query(
            "SELECT s.payload, m.manifest FROM games g JOIN game_snapshots s ON s.game_id=g.id AND s.revision=g.head_revision JOIN ruleset_manifests m ON m.hash=g.ruleset_manifest_hash WHERE g.id=$1",
        ).bind(envelope.game_id).fetch_optional(&self.pool).await.map_err(CommitError::storage)?.ok_or(CommitError::NotFound)?;
        let snapshot: Vec<u8> = row.get("payload");
        let manifest: serde_json::Value = row.get("manifest");
        let manifest: WorkerManifest =
            serde_json::from_value(manifest).map_err(|_| CommitError::WorkerRevisionMismatch)?;
        let snapshot =
            String::from_utf8(snapshot).map_err(|_| CommitError::WorkerRevisionMismatch)?;
        let proposal = worker
            .end_turn(
                &actor_account_id.to_string(),
                &manifest,
                envelope.expected_revision,
                &snapshot,
            )
            .await
            .map_err(|_| CommitError::WorkerRevisionMismatch)?;
        self.commit(actor_account_id, envelope, proposal).await
    }
    pub async fn connect(database_url: &str) -> Result<Self, sqlx::Error> {
        let pool = PgPoolOptions::new()
            .max_connections(10)
            .connect(database_url)
            .await?;
        Ok(Self { pool })
    }

    pub async fn migrate(&self) -> Result<(), sqlx::migrate::MigrateError> {
        sqlx::migrate!("./migrations").run(&self.pool).await
    }

    /// Creates revision zero atomically. The caller must have already stored a
    /// content-addressed ruleset manifest and account; the public API will do
    /// that through authenticated setup rather than accepting a save upload.
    pub async fn create_game(&self, game: NewGame) -> Result<(), sqlx::Error> {
        let payload_hash = state_hash(&game.snapshot);
        let mut tx = self.pool.begin().await?;
        let engine_build: String =
            sqlx::query_scalar("SELECT engine_build FROM ruleset_manifests WHERE hash = $1")
                .bind(&game.ruleset_manifest_hash)
                .fetch_one(&mut *tx)
                .await?;
        sqlx::query("INSERT INTO games (id, ruleset_manifest_hash) VALUES ($1, $2)")
            .bind(game.game_id)
            .bind(&game.ruleset_manifest_hash)
            .execute(&mut *tx)
            .await?;
        sqlx::query(
            "INSERT INTO game_members (game_id, account_id, role) VALUES ($1, $2, 'owner')",
        )
        .bind(game.game_id)
        .bind(game.owner_account_id)
        .execute(&mut *tx)
        .await?;
        sqlx::query(
            "INSERT INTO game_snapshots (game_id, revision, engine_build, ruleset_manifest_hash, codec, compressed_size, uncompressed_size, canonical_state_hash, payload_hash, payload) VALUES ($1, 0, $2, $3, 'identity', $4, $4, $5, $5, $6)",
        )
        .bind(game.game_id)
        .bind(engine_build)
        .bind(&game.ruleset_manifest_hash)
        .bind(i64::try_from(game.snapshot.len()).expect("snapshot length fits BIGINT"))
        .bind(&payload_hash)
        .bind(&game.snapshot)
        .execute(&mut *tx)
        .await?;
        sqlx::query(
            "INSERT INTO game_revisions (game_id, revision, parent_revision, command_id, snapshot_revision, canonical_state_hash) VALUES ($1, 0, NULL, NULL, 0, $2)",
        )
        .bind(game.game_id)
        .bind(&payload_hash)
        .execute(&mut *tx)
        .await?;
        tx.commit().await
    }

    /// Commits only a server-worker result. `FOR UPDATE` makes the database
    /// authoritative across processes; all journal, snapshot, head, and outbox
    /// writes share one transaction.
    pub async fn commit(
        &self,
        actor_account_id: Uuid,
        envelope: CommandEnvelope,
        proposal: CommitProposal,
    ) -> Result<CommandAccepted, CommitError> {
        if envelope.protocol_version != PROTOCOL_VERSION {
            return Err(CommitError::UnsupportedProtocol(envelope.protocol_version));
        }
        if state_hash(&proposal.snapshot) != proposal.canonical_state_hash {
            return Err(CommitError::InvalidSnapshotHash);
        }
        let mut tx = self.pool.begin().await.map_err(CommitError::storage)?;

        let duplicate = sqlx::query(
            "SELECT c.revision, r.canonical_state_hash FROM game_commands c JOIN game_revisions r ON r.game_id = c.game_id AND r.revision = c.revision WHERE c.game_id = $1 AND c.command_id = $2",
        )
        .bind(envelope.game_id)
        .bind(envelope.command_id)
        .fetch_optional(&mut *tx)
        .await
        .map_err(CommitError::storage)?;
        if let Some(row) = duplicate {
            let committed_revision: i64 = row.get("revision");
            let canonical_state_hash: String = row.get("canonical_state_hash");
            return Ok(CommandAccepted {
                game_id: envelope.game_id,
                command_id: envelope.command_id,
                previous_revision: u64::try_from(committed_revision - 1)
                    .expect("command revisions are positive"),
                committed_revision: u64::try_from(committed_revision)
                    .expect("revision is non-negative"),
                canonical_state_hash,
            });
        }

        let head = sqlx::query(
            "SELECT g.head_revision, g.ruleset_manifest_hash, m.engine_build FROM games g JOIN ruleset_manifests m ON m.hash = g.ruleset_manifest_hash WHERE g.id = $1 FOR UPDATE",
        )
        .bind(envelope.game_id)
        .fetch_optional(&mut *tx)
        .await
        .map_err(CommitError::storage)?
        .ok_or(CommitError::NotFound)?;
        let current_revision = u64::try_from(head.get::<i64, _>("head_revision"))
            .expect("head revision is non-negative");
        if envelope.expected_revision != current_revision {
            return Err(CommitError::Stale {
                expected: envelope.expected_revision,
                actual: current_revision,
            });
        }
        if proposal.previous_revision != current_revision {
            return Err(CommitError::WorkerRevisionMismatch);
        }

        let role: Option<String> = sqlx::query_scalar(
            "SELECT role FROM game_members WHERE game_id = $1 AND account_id = $2",
        )
        .bind(envelope.game_id)
        .bind(actor_account_id)
        .fetch_optional(&mut *tx)
        .await
        .map_err(CommitError::storage)?;
        if !matches!(role.as_deref(), Some("owner" | "player" | "admin")) {
            return Err(CommitError::Unauthorized);
        }

        let next_revision = current_revision
            .checked_add(1)
            .expect("revision overflow is impossible in practice");
        let next_revision_i64 = i64::try_from(next_revision).expect("revision fits BIGINT");
        let snapshot_size =
            i64::try_from(proposal.snapshot.len()).expect("snapshot length fits BIGINT");
        let manifest_hash: String = head.get("ruleset_manifest_hash");
        let engine_build: String = head.get("engine_build");
        let payload_hash = state_hash(&proposal.snapshot);
        let command_json =
            serde_json::to_value(&envelope).expect("command envelope is serializable");

        sqlx::query(
            "INSERT INTO game_snapshots (game_id, revision, engine_build, ruleset_manifest_hash, codec, compressed_size, uncompressed_size, canonical_state_hash, payload_hash, payload) VALUES ($1, $2, $3, $4, 'identity', $5, $5, $6, $7, $8)",
        )
        .bind(envelope.game_id)
        .bind(next_revision_i64)
        .bind(engine_build)
        .bind(manifest_hash)
        .bind(snapshot_size)
        .bind(&proposal.canonical_state_hash)
        .bind(payload_hash)
        .bind(&proposal.snapshot)
        .execute(&mut *tx)
        .await
        .map_err(CommitError::storage)?;
        sqlx::query(
            "INSERT INTO game_commands (game_id, command_id, revision, account_id, payload) VALUES ($1, $2, $3, $4, $5)",
        )
        .bind(envelope.game_id)
        .bind(envelope.command_id)
        .bind(next_revision_i64)
        .bind(actor_account_id)
        .bind(command_json)
        .execute(&mut *tx)
        .await
        .map_err(CommitError::storage)?;
        sqlx::query(
            "INSERT INTO game_revisions (game_id, revision, parent_revision, command_id, snapshot_revision, canonical_state_hash) VALUES ($1, $2, $3, $4, $2, $5)",
        )
        .bind(envelope.game_id)
        .bind(next_revision_i64)
        .bind(i64::try_from(current_revision).expect("revision fits BIGINT"))
        .bind(envelope.command_id)
        .bind(&proposal.canonical_state_hash)
        .execute(&mut *tx)
        .await
        .map_err(CommitError::storage)?;
        sqlx::query("UPDATE games SET head_revision = $2 WHERE id = $1 AND head_revision = $3")
            .bind(envelope.game_id)
            .bind(next_revision_i64)
            .bind(i64::try_from(current_revision).expect("revision fits BIGINT"))
            .execute(&mut *tx)
            .await
            .map_err(CommitError::storage)?;
        sqlx::query("INSERT INTO game_outbox (game_id, revision, topic, payload) VALUES ($1, $2, 'game.revision.committed', $3)")
            .bind(envelope.game_id)
            .bind(next_revision_i64)
            .bind(json!({"game_id": envelope.game_id, "revision": next_revision, "state_hash": proposal.canonical_state_hash}))
            .execute(&mut *tx)
            .await
            .map_err(CommitError::storage)?;
        tx.commit().await.map_err(CommitError::storage)?;

        Ok(CommandAccepted {
            game_id: envelope.game_id,
            command_id: envelope.command_id,
            previous_revision: current_revision,
            committed_revision: next_revision,
            canonical_state_hash: proposal.canonical_state_hash,
        })
    }
}

#[cfg(test)]
mod integration_tests {
    use std::env;

    use super::*;
    use crate::{GameCommand, state_hash};

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
            command: GameCommand::EndTurn,
        }
    }

    fn proposal(previous_revision: u64, snapshot: &[u8]) -> CommitProposal {
        CommitProposal {
            previous_revision,
            snapshot: snapshot.to_vec(),
            canonical_state_hash: state_hash(snapshot),
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
        let stale = repository
            .commit(
                account,
                command(game, Uuid::new_v4(), 0),
                proposal(0, b"replacement"),
            )
            .await
            .unwrap_err();

        assert_eq!(accepted, duplicate);
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

        repository.revoke_session(&session.token).await.unwrap();
        assert!(matches!(
            repository.authenticate_session(&session.token).await,
            Err(AuthError::InvalidCredentials)
        ));
    }
}
