use serde_json::json;
use sqlx::{PgPool, Row, postgres::PgPoolOptions};
use uuid::Uuid;

use crate::auth::{
    Account, AuthError, PasswordService, SessionCredential, normalize_username, token_digest,
};
use crate::worker::{EngineWorkerClient, MoveUnitIntent, WorkerManifest};
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
    pub owner_civilization_id: String,
}

struct NewMemberAssignment {
    civilization_id: String,
}

#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize)]
pub struct GameMetadata {
    pub game_id: Uuid,
    pub committed_revision: u64,
    pub canonical_state_hash: String,
    pub role: String,
    pub civilization_id: Option<String>,
}

#[derive(Clone, Debug, serde::Serialize)]
pub struct GameProjection {
    pub game_id: Uuid,
    pub committed_revision: u64,
    pub canonical_state_hash: String,
    pub projection_hash: String,
    pub projection: serde_json::Value,
}

#[derive(Clone, Debug)]
pub struct ClaimedOutboxEvent {
    pub id: i64,
    pub claim_token: Uuid,
    pub game_id: Uuid,
    pub revision: u64,
    pub payload: serde_json::Value,
}

impl PostgresGameRepository {
    /// Consumes one durable fixed-window bucket. Only the SHA-256 bucket hash
    /// is stored, so usernames and composite source identifiers are not
    /// recoverable from the rate-limit table.
    pub async fn consume_rate_limit(
        &self,
        bucket_material: &str,
        window_seconds: i32,
        max_requests: i32,
        block_seconds: i32,
    ) -> Result<(), AuthError> {
        let bucket_hash = state_hash(bucket_material.as_bytes());
        let allowed: bool = sqlx::query_scalar(
            "WITH consumed AS (INSERT INTO api_rate_limits (bucket_hash, request_count) VALUES ($1, 1) ON CONFLICT (bucket_hash) DO UPDATE SET request_count=CASE WHEN api_rate_limits.window_started_at <= now() - $2 * interval '1 second' THEN 1 ELSE api_rate_limits.request_count + 1 END, window_started_at=CASE WHEN api_rate_limits.window_started_at <= now() - $2 * interval '1 second' THEN now() ELSE api_rate_limits.window_started_at END, blocked_until=CASE WHEN api_rate_limits.blocked_until > now() THEN api_rate_limits.blocked_until WHEN (CASE WHEN api_rate_limits.window_started_at <= now() - $2 * interval '1 second' THEN 1 ELSE api_rate_limits.request_count + 1 END) > $3 THEN now() + $4 * interval '1 second' ELSE NULL END, updated_at=now() RETURNING request_count, blocked_until) SELECT request_count <= $3 AND COALESCE(blocked_until <= now(), true) FROM consumed",
        )
        .bind(bucket_hash)
        .bind(window_seconds.max(1))
        .bind(max_requests.max(1))
        .bind(block_seconds.max(1))
        .fetch_one(&self.pool)
        .await
        .map_err(|_| AuthError::Storage)?;
        if allowed {
            Ok(())
        } else {
            Err(AuthError::RateLimited)
        }
    }

    pub async fn clear_rate_limit(&self, bucket_material: &str) -> Result<(), AuthError> {
        sqlx::query("DELETE FROM api_rate_limits WHERE bucket_hash=$1")
            .bind(state_hash(bucket_material.as_bytes()))
            .execute(&self.pool)
            .await
            .map_err(|_| AuthError::Storage)?;
        Ok(())
    }

    /// Security audit data is deliberately bounded: a network prefix and a
    /// one-way identity hash, never credentials, bearer tokens, or request bodies.
    pub async fn record_security_audit(
        &self,
        account_id: Option<Uuid>,
        event_type: &str,
        outcome: &str,
        source_ip_prefix: &str,
        identity: Option<&str>,
    ) -> Result<(), AuthError> {
        let identity_hash = identity.map(|value| state_hash(value.as_bytes()));
        sqlx::query(
            "INSERT INTO security_audit_events (account_id, event_type, outcome, source_ip_prefix, identity_hash) VALUES ($1, $2, $3, $4::inet, $5)",
        )
        .bind(account_id)
        .bind(event_type)
        .bind(outcome)
        .bind(source_ip_prefix)
        .bind(identity_hash)
        .execute(&self.pool)
        .await
        .map_err(|_| AuthError::Storage)?;
        Ok(())
    }

    /// Claims a bounded batch with a renewable lease. `SKIP LOCKED` permits
    /// multiple dispatchers without double-claiming; expired claims recover
    /// automatically after a process crash.
    pub async fn claim_outbox_batch(
        &self,
        limit: i64,
    ) -> Result<Vec<ClaimedOutboxEvent>, CommitError> {
        let claim_token = Uuid::new_v4();
        let rows = sqlx::query(
            "WITH candidates AS (SELECT id FROM game_outbox WHERE delivered_at IS NULL AND available_at <= now() AND (claimed_at IS NULL OR claimed_at < now() - interval '30 seconds') ORDER BY id FOR UPDATE SKIP LOCKED LIMIT $1) UPDATE game_outbox o SET claimed_at=now(), claim_token=$2, attempt_count=attempt_count+1, last_error=NULL FROM candidates c WHERE o.id=c.id RETURNING o.id, o.game_id, o.revision, o.payload",
        )
        .bind(limit.clamp(1, 1_000))
        .bind(claim_token)
        .fetch_all(&self.pool)
        .await
        .map_err(CommitError::storage)?;
        rows.into_iter()
            .map(|row| {
                let revision = u64::try_from(row.get::<i64, _>("revision"))
                    .map_err(|_| CommitError::Storage)?;
                Ok(ClaimedOutboxEvent {
                    id: row.get("id"),
                    claim_token,
                    game_id: row.get("game_id"),
                    revision,
                    payload: row.get("payload"),
                })
            })
            .collect()
    }

    pub async fn outbox_recipients(&self, game_id: Uuid) -> Result<Vec<Uuid>, CommitError> {
        sqlx::query_scalar("SELECT account_id FROM game_members WHERE game_id=$1")
            .bind(game_id)
            .fetch_all(&self.pool)
            .await
            .map_err(CommitError::storage)
    }

    pub async fn acknowledge_outbox(
        &self,
        event_id: i64,
        claim_token: Uuid,
    ) -> Result<(), CommitError> {
        let result = sqlx::query(
            "UPDATE game_outbox SET delivered_at=now(), claimed_at=NULL, claim_token=NULL WHERE id=$1 AND claim_token=$2 AND delivered_at IS NULL",
        )
        .bind(event_id)
        .bind(claim_token)
        .execute(&self.pool)
        .await
        .map_err(CommitError::storage)?;
        if result.rows_affected() != 1 {
            return Err(CommitError::Storage);
        }
        Ok(())
    }

    pub async fn retry_outbox(
        &self,
        event_id: i64,
        claim_token: Uuid,
        error: &str,
    ) -> Result<(), CommitError> {
        sqlx::query(
            "UPDATE game_outbox SET available_at=now() + interval '1 second' * LEAST(attempt_count, 60), claimed_at=NULL, claim_token=NULL, last_error=left($3, 500) WHERE id=$1 AND claim_token=$2 AND delivered_at IS NULL",
        )
        .bind(event_id)
        .bind(claim_token)
        .bind(error)
        .execute(&self.pool)
        .await
        .map_err(CommitError::storage)?;
        Ok(())
    }

    /// Creates a new canonical game exclusively through the private Kotlin
    /// worker. The caller supplies a previously stored manifest hash; it cannot
    /// upload a revision-zero save or choose an unpinned ruleset payload.
    pub async fn create_authoritative_game(
        &self,
        worker: &EngineWorkerClient,
        owner_account_id: Uuid,
        game_id: Uuid,
        ruleset_manifest_hash: String,
    ) -> Result<(), CommitError> {
        let manifest: serde_json::Value =
            sqlx::query_scalar("SELECT manifest FROM ruleset_manifests WHERE hash = $1")
                .bind(&ruleset_manifest_hash)
                .fetch_optional(&self.pool)
                .await
                .map_err(CommitError::storage)?
                .ok_or(CommitError::NotFound)?;
        let manifest: WorkerManifest =
            serde_json::from_value(manifest).map_err(|_| CommitError::WorkerRevisionMismatch)?;
        // Defaults are a deliberately minimal setup intent. The worker is the
        // sole component that turns it into a GameInfo through GameStarter.
        let created = worker
            .create_game(&owner_account_id.to_string(), &manifest, "{}")
            .await
            .map_err(|_| CommitError::WorkerRevisionMismatch)?;
        let proposal = created.proposal;
        if state_hash(&proposal.snapshot) != proposal.canonical_state_hash {
            return Err(CommitError::InvalidSnapshotHash);
        }
        self.create_game(NewGame {
            game_id,
            owner_account_id,
            ruleset_manifest_hash,
            snapshot: proposal.snapshot,
            owner_civilization_id: created.owner_civilization_id,
        })
        .await
        .map_err(CommitError::storage)
    }

    /// Returns a safe metadata projection. It deliberately excludes the
    /// canonical snapshot: callers must later use a player-scoped projection
    /// endpoint rather than receiving serialized `GameInfo`.
    pub async fn game_metadata(
        &self,
        actor_account_id: Uuid,
        game_id: Uuid,
    ) -> Result<GameMetadata, CommitError> {
        let membership = sqlx::query(
            "SELECT role, civilization_id FROM game_members WHERE game_id = $1 AND account_id = $2",
        )
        .bind(game_id)
        .bind(actor_account_id)
        .fetch_optional(&self.pool)
        .await
        .map_err(CommitError::storage)?;
        let membership = match membership {
            Some(membership) => membership,
            None => {
                let game_exists: bool =
                    sqlx::query_scalar("SELECT EXISTS(SELECT 1 FROM games WHERE id = $1)")
                        .bind(game_id)
                        .fetch_one(&self.pool)
                        .await
                        .map_err(CommitError::storage)?;
                return Err(if game_exists {
                    CommitError::Unauthorized
                } else {
                    CommitError::NotFound
                });
            }
        };
        let role: String = membership.get("role");
        let civilization_id: Option<String> = membership.get("civilization_id");
        let row = sqlx::query(
            "SELECT g.head_revision, r.canonical_state_hash FROM games g JOIN game_revisions r ON r.game_id = g.id AND r.revision = g.head_revision WHERE g.id = $1",
        )
        .bind(game_id)
        .fetch_optional(&self.pool)
        .await
        .map_err(CommitError::storage)?
        .ok_or(CommitError::NotFound)?;
        Ok(GameMetadata {
            game_id,
            committed_revision: u64::try_from(row.get::<i64, _>("head_revision"))
                .expect("revision is non-negative"),
            canonical_state_hash: row.get("canonical_state_hash"),
            role,
            civilization_id,
        })
    }

    /// Builds a player-scoped view from one consistent canonical head. The
    /// full snapshot crosses only the private worker boundary and is never
    /// returned by this public repository operation.
    pub async fn game_projection(
        &self,
        worker: &EngineWorkerClient,
        actor_account_id: Uuid,
        game_id: Uuid,
    ) -> Result<GameProjection, CommitError> {
        let row = sqlx::query(
            "SELECT g.head_revision, r.canonical_state_hash, s.payload, m.manifest, gm.role, gm.civilization_id FROM games g JOIN game_members gm ON gm.game_id=g.id AND gm.account_id=$2 JOIN game_revisions r ON r.game_id=g.id AND r.revision=g.head_revision JOIN game_snapshots s ON s.game_id=g.id AND s.revision=g.head_revision JOIN ruleset_manifests m ON m.hash=g.ruleset_manifest_hash WHERE g.id=$1",
        )
        .bind(game_id)
        .bind(actor_account_id)
        .fetch_optional(&self.pool)
        .await
        .map_err(CommitError::storage)?
        .ok_or(CommitError::Unauthorized)?;
        let role: String = row.get("role");
        if !matches!(role.as_str(), "owner" | "player") {
            return Err(CommitError::Unauthorized);
        }
        let actor_civilization_id: Option<String> = row.get("civilization_id");
        let actor_civilization_id = actor_civilization_id.ok_or(CommitError::Unauthorized)?;
        let snapshot = String::from_utf8(row.get::<Vec<u8>, _>("payload"))
            .map_err(|_| CommitError::WorkerRevisionMismatch)?;
        let manifest = serde_json::from_value::<WorkerManifest>(row.get("manifest"))
            .map_err(|_| CommitError::WorkerRevisionMismatch)?;
        let projected = worker
            .project_state(
                &actor_account_id.to_string(),
                &manifest,
                &snapshot,
                &actor_civilization_id,
            )
            .await
            .map_err(|error| match error {
                crate::worker::WorkerClientError::Rejected(reason) => {
                    CommitError::WorkerRejected(reason)
                }
                other => {
                    eprintln!("authoritative worker projection failure: {other}");
                    CommitError::WorkerRevisionMismatch
                }
            })?;
        Ok(GameProjection {
            game_id,
            committed_revision: u64::try_from(row.get::<i64, _>("head_revision"))
                .expect("revision is non-negative"),
            canonical_state_hash: row.get("canonical_state_hash"),
            projection_hash: state_hash(
                &serde_json::to_vec(&projected.projection)
                    .expect("worker projection JSON value is serializable"),
            ),
            projection: projected.projection,
        })
    }

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

    /// Rotates a live session atomically. The successor keeps a parent pointer
    /// for audit/revocation chains while the presented credential is revoked
    /// before the transaction becomes visible.
    pub async fn rotate_session(&self, bearer_token: &str) -> Result<SessionCredential, AuthError> {
        let digest = token_digest(bearer_token);
        let mut tx = self.pool.begin().await.map_err(|_| AuthError::Storage)?;
        let account_id: Uuid = sqlx::query_scalar(
            "SELECT account_id FROM sessions WHERE token_digest = $1 AND revoked_at IS NULL AND expires_at > now() FOR UPDATE",
        )
        .bind(&digest)
        .fetch_optional(&mut *tx)
        .await
        .map_err(|_| AuthError::Storage)?
        .ok_or(AuthError::InvalidCredentials)?;
        let credential = SessionCredential::generate();
        sqlx::query(
            "INSERT INTO sessions (id, account_id, token_digest, parent_session_id, expires_at) SELECT $1, $2, $3, id, now() + interval '30 days' FROM sessions WHERE token_digest = $4",
        )
        .bind(Uuid::new_v4())
        .bind(account_id)
        .bind(&credential.digest)
        .bind(&digest)
        .execute(&mut *tx)
        .await
        .map_err(|_| AuthError::Storage)?;
        sqlx::query("UPDATE sessions SET revoked_at = now() WHERE token_digest = $1")
            .bind(&digest)
            .execute(&mut *tx)
            .await
            .map_err(|_| AuthError::Storage)?;
        tx.commit().await.map_err(|_| AuthError::Storage)?;
        Ok(credential)
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
        // A lost response is retried after the head may already have moved.
        // Return its durable original result before contacting the worker, so
        // a duplicate can neither re-run turn processing nor fail as stale.
        if let Some(accepted) = self
            .committed_command(envelope.game_id, envelope.command_id, actor_account_id)
            .await?
        {
            return Ok(accepted);
        }
        let row = sqlx::query(
            "SELECT s.payload, m.manifest FROM games g JOIN game_snapshots s ON s.game_id=g.id AND s.revision=g.head_revision JOIN ruleset_manifests m ON m.hash=g.ruleset_manifest_hash WHERE g.id=$1",
        ).bind(envelope.game_id).fetch_optional(&self.pool).await.map_err(CommitError::storage)?.ok_or(CommitError::NotFound)?;
        let snapshot: Vec<u8> = row.get("payload");
        let manifest: serde_json::Value = row.get("manifest");
        let manifest: WorkerManifest =
            serde_json::from_value(manifest).map_err(|_| CommitError::WorkerRevisionMismatch)?;
        let snapshot =
            String::from_utf8(snapshot).map_err(|_| CommitError::WorkerRevisionMismatch)?;
        let actor_civilization_id: Option<String> = sqlx::query_scalar(
            "SELECT civilization_id FROM game_members WHERE game_id = $1 AND account_id = $2 AND role IN ('owner', 'player')",
        )
        .bind(envelope.game_id)
        .bind(actor_account_id)
        .fetch_optional(&self.pool)
        .await
        .map_err(CommitError::storage)?
        .flatten();
        let actor_civilization_id = actor_civilization_id.ok_or(CommitError::Unauthorized)?;
        let proposal = worker
            .end_turn(
                &actor_account_id.to_string(),
                &manifest,
                envelope.expected_revision,
                &snapshot,
                &actor_civilization_id,
            )
            .await
            .map_err(|error| match error {
                crate::worker::WorkerClientError::Rejected(reason) => {
                    CommitError::WorkerRejected(reason)
                }
                other => {
                    eprintln!("authoritative worker EndTurn transport/protocol failure: {other}");
                    CommitError::WorkerRevisionMismatch
                }
            })?;
        self.commit(actor_account_id, envelope, proposal).await
    }

    pub async fn execute_move_unit(
        &self,
        worker: &EngineWorkerClient,
        actor_account_id: Uuid,
        envelope: CommandEnvelope,
    ) -> Result<CommandAccepted, CommitError> {
        let (unit_id, destination_x, destination_y) = match &envelope.command {
            crate::GameCommand::MoveUnit {
                unit_id,
                destination_x,
                destination_y,
            } => (*unit_id, *destination_x, *destination_y),
            _ => return Err(CommitError::InvalidCommand),
        };
        if let Some(accepted) = self
            .committed_command(envelope.game_id, envelope.command_id, actor_account_id)
            .await?
        {
            return Ok(accepted);
        }
        let row = sqlx::query(
            "SELECT s.payload, m.manifest FROM games g JOIN game_snapshots s ON s.game_id=g.id AND s.revision=g.head_revision JOIN ruleset_manifests m ON m.hash=g.ruleset_manifest_hash WHERE g.id=$1",
        )
        .bind(envelope.game_id)
        .fetch_optional(&self.pool)
        .await
        .map_err(CommitError::storage)?
        .ok_or(CommitError::NotFound)?;
        let snapshot = String::from_utf8(row.get::<Vec<u8>, _>("payload"))
            .map_err(|_| CommitError::WorkerRevisionMismatch)?;
        let manifest = serde_json::from_value::<WorkerManifest>(row.get("manifest"))
            .map_err(|_| CommitError::WorkerRevisionMismatch)?;
        let actor_civilization_id: Option<String> = sqlx::query_scalar(
            "SELECT civilization_id FROM game_members WHERE game_id = $1 AND account_id = $2 AND role IN ('owner', 'player')",
        )
        .bind(envelope.game_id)
        .bind(actor_account_id)
        .fetch_optional(&self.pool)
        .await
        .map_err(CommitError::storage)?
        .flatten();
        let actor_civilization_id = actor_civilization_id.ok_or(CommitError::Unauthorized)?;
        let proposal = worker
            .move_unit(
                &actor_account_id.to_string(),
                &manifest,
                envelope.expected_revision,
                &snapshot,
                MoveUnitIntent {
                    actor_civilization_id: &actor_civilization_id,
                    unit_id,
                    destination_x,
                    destination_y,
                },
            )
            .await
            .map_err(|error| match error {
                crate::worker::WorkerClientError::Rejected(reason) => {
                    CommitError::WorkerRejected(reason)
                }
                other => {
                    eprintln!("authoritative worker MoveUnit transport/protocol failure: {other}");
                    CommitError::WorkerRevisionMismatch
                }
            })?;
        self.commit(actor_account_id, envelope, proposal).await
    }

    /// Joins an authenticated account without accepting a civilization choice.
    /// The worker deterministically assigns an unclaimed canonical civilization;
    /// the snapshot revision and membership are committed atomically.
    pub async fn execute_join(
        &self,
        worker: &EngineWorkerClient,
        actor_account_id: Uuid,
        envelope: CommandEnvelope,
    ) -> Result<CommandAccepted, CommitError> {
        if !matches!(&envelope.command, crate::GameCommand::JoinGame)
            || envelope.expected_revision != 0
        {
            return Err(CommitError::InvalidCommand);
        }
        if let Some(accepted) = self
            .committed_command(envelope.game_id, envelope.command_id, actor_account_id)
            .await?
        {
            return Ok(accepted);
        }
        let row = sqlx::query(
            "SELECT s.payload, m.manifest FROM games g JOIN game_snapshots s ON s.game_id=g.id AND s.revision=g.head_revision JOIN ruleset_manifests m ON m.hash=g.ruleset_manifest_hash WHERE g.id=$1",
        )
        .bind(envelope.game_id)
        .fetch_optional(&self.pool)
        .await
        .map_err(CommitError::storage)?
        .ok_or(CommitError::NotFound)?;
        let snapshot = String::from_utf8(row.get::<Vec<u8>, _>("payload"))
            .map_err(|_| CommitError::WorkerRevisionMismatch)?;
        let manifest = serde_json::from_value::<WorkerManifest>(row.get("manifest"))
            .map_err(|_| CommitError::WorkerRevisionMismatch)?;
        let assigned = worker
            .assign_player(
                &actor_account_id.to_string(),
                &manifest,
                envelope.expected_revision,
                &snapshot,
            )
            .await
            .map_err(|error| match error {
                crate::worker::WorkerClientError::Rejected(reason) => {
                    CommitError::WorkerRejected(reason)
                }
                other => {
                    eprintln!(
                        "authoritative worker AssignPlayer transport/protocol failure: {other}"
                    );
                    CommitError::WorkerRevisionMismatch
                }
            })?;
        self.commit_internal(
            actor_account_id,
            envelope,
            assigned.proposal,
            Some(NewMemberAssignment {
                civilization_id: assigned.civilization_id,
            }),
        )
        .await
    }

    async fn committed_command(
        &self,
        game_id: Uuid,
        command_id: Uuid,
        actor_account_id: Uuid,
    ) -> Result<Option<CommandAccepted>, CommitError> {
        let row = sqlx::query(
            "SELECT c.revision, c.account_id, r.canonical_state_hash FROM game_commands c JOIN game_revisions r ON r.game_id = c.game_id AND r.revision = c.revision WHERE c.game_id = $1 AND c.command_id = $2",
        )
        .bind(game_id)
        .bind(command_id)
        .fetch_optional(&self.pool)
        .await
        .map_err(CommitError::storage)?;
        row.map(|row| {
            if row.get::<Uuid, _>("account_id") != actor_account_id {
                return Err(CommitError::Unauthorized);
            }
            let committed_revision: i64 = row.get("revision");
            Ok(CommandAccepted {
                game_id,
                command_id,
                previous_revision: u64::try_from(committed_revision - 1)
                    .expect("command revisions are positive"),
                committed_revision: u64::try_from(committed_revision)
                    .expect("revision is non-negative"),
                canonical_state_hash: row.get("canonical_state_hash"),
            })
        })
        .transpose()
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
            "INSERT INTO game_members (game_id, account_id, role, civilization_id) VALUES ($1, $2, 'owner', $3)",
        )
        .bind(game.game_id)
        .bind(game.owner_account_id)
        .bind(game.owner_civilization_id)
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
        self.commit_internal(actor_account_id, envelope, proposal, None)
            .await
    }

    async fn commit_internal(
        &self,
        actor_account_id: Uuid,
        envelope: CommandEnvelope,
        proposal: CommitProposal,
        new_member: Option<NewMemberAssignment>,
    ) -> Result<CommandAccepted, CommitError> {
        if envelope.protocol_version != PROTOCOL_VERSION {
            return Err(CommitError::UnsupportedProtocol(envelope.protocol_version));
        }
        if state_hash(&proposal.snapshot) != proposal.canonical_state_hash {
            return Err(CommitError::InvalidSnapshotHash);
        }
        let mut tx = self.pool.begin().await.map_err(CommitError::storage)?;

        let duplicate = sqlx::query(
            "SELECT c.revision, c.account_id, r.canonical_state_hash FROM game_commands c JOIN game_revisions r ON r.game_id = c.game_id AND r.revision = c.revision WHERE c.game_id = $1 AND c.command_id = $2",
        )
        .bind(envelope.game_id)
        .bind(envelope.command_id)
        .fetch_optional(&mut *tx)
        .await
        .map_err(CommitError::storage)?;
        if let Some(row) = duplicate {
            if row.get::<Uuid, _>("account_id") != actor_account_id {
                return Err(CommitError::Unauthorized);
            }
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

        if let Some(assignment) = new_member {
            if !matches!(&envelope.command, crate::GameCommand::JoinGame) || current_revision != 0 {
                return Err(CommitError::InvalidCommand);
            }
            let membership_exists: bool = sqlx::query_scalar(
                "SELECT EXISTS(SELECT 1 FROM game_members WHERE game_id = $1 AND account_id = $2)",
            )
            .bind(envelope.game_id)
            .bind(actor_account_id)
            .fetch_one(&mut *tx)
            .await
            .map_err(CommitError::storage)?;
            if membership_exists {
                return Err(CommitError::InvalidCommand);
            }
            sqlx::query(
                "INSERT INTO game_members (game_id, account_id, role, civilization_id) VALUES ($1, $2, 'player', $3)",
            )
            .bind(envelope.game_id)
            .bind(actor_account_id)
            .bind(assignment.civilization_id)
            .execute(&mut *tx)
            .await
            .map_err(CommitError::storage)?;
        } else {
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
                "login",
                "rejected",
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
            "SELECT identity_hash, source_ip_prefix::text AS source_ip_prefix FROM security_audit_events",
        )
        .fetch_one(&repository.pool)
        .await
        .unwrap();
        assert_eq!(stored_bucket.len(), 64);
        assert!(!stored_bucket.contains("private-user"));
        assert_eq!(audit.get::<String, _>("identity_hash").len(), 64);
        assert_eq!(audit.get::<String, _>("source_ip_prefix"), "192.0.2.0/24");
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
}
