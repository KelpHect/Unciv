use serde_json::json;
use sqlx::{
    PgPool, Row,
    postgres::{PgPoolOptions, PgRow},
};
use uuid::Uuid;

use crate::auth::{
    Account, AuthError, PasswordError, PasswordService, SessionCredential, normalize_username,
    token_digest,
};
use crate::projection::PlayerProjection;
use crate::worker::{
    AdoptPolicyIntent, BuyCityTileIntent, ChooseFreeTechnologyIntent, EngineWorkerClient,
    MoveConstructionIntent, MoveUnitIntent, PurchaseConstructionAtTileIntent,
    PurchaseConstructionIntent, QueueConstructionAtTileIntent, QueueConstructionIntent,
    RemoveConstructionIntent, ResetCitizensIntent, SetAvoidGrowthIntent, SetCitizenFocusIntent,
    SetCityTileAssignmentIntent, SetManualSpecialistsIntent, SetPerpetualConstructionIntent,
    SetResearchPathIntent, SetSpecialistCountIntent, WorkerManifest,
};
use crate::{
    CommandAccepted, CommandEnvelope, CommitError, CommitProposal, MAX_SNAPSHOT_BYTES,
    PROJECTION_VERSION, PROTOCOL_VERSION, state_hash,
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

#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, utoipa::ToSchema)]
pub struct GameMetadata {
    pub game_id: Uuid,
    pub committed_revision: u64,
    pub canonical_state_hash: String,
    pub role: String,
    pub civilization_id: Option<String>,
}

#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, utoipa::ToSchema)]
pub struct GameSummary {
    pub game_id: Uuid,
    pub committed_revision: u64,
    pub canonical_state_hash: String,
    pub role: String,
    pub civilization_id: Option<String>,
    pub available: bool,
}

#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, utoipa::ToSchema)]
pub struct GamePage {
    pub games: Vec<GameSummary>,
    pub next_cursor: Option<Uuid>,
}

#[derive(Clone, Debug, serde::Serialize, utoipa::ToSchema)]
pub struct GameProjection {
    pub game_id: Uuid,
    pub projection_version: u16,
    pub committed_revision: u64,
    pub canonical_state_hash: String,
    pub projection_hash: String,
    pub projection: PlayerProjection,
}

#[derive(Clone, Debug)]
pub struct ClaimedOutboxEvent {
    pub id: i64,
    pub claim_token: Uuid,
    pub game_id: Uuid,
    pub revision: u64,
    pub payload: serde_json::Value,
}

mod accounts;
mod city_population;
mod commands;
mod games;
mod outbox;
mod security;

impl PostgresGameRepository {
    async fn validated_snapshot(&self, game_id: Uuid, row: &PgRow) -> Result<String, CommitError> {
        if row.get::<bool, _>("is_unavailable") {
            return Err(CommitError::GameUnavailable);
        }
        let payload: Vec<u8> = row.get("payload");
        let snapshot_revision: i64 = row.get("snapshot_revision");
        let declared_compressed_size: i64 = row.get("compressed_size");
        let declared_uncompressed_size: i64 = row.get("uncompressed_size");
        let codec: String = row.get("codec");
        let protocol_version: i32 = row.get("snapshot_protocol_version");
        let validation_status: String = row.get("validation_status");
        let payload_hash: String = row.get("payload_hash");
        let snapshot_state_hash: String = row.get("snapshot_state_hash");
        let revision_state_hash: String = row.get("revision_state_hash");
        let actual_hash = state_hash(&payload);
        let valid = codec == "identity"
            && protocol_version == i32::from(PROTOCOL_VERSION)
            && validation_status == "valid"
            && !payload.is_empty()
            && payload.len() <= MAX_SNAPSHOT_BYTES
            && declared_compressed_size == payload.len() as i64
            && declared_uncompressed_size == payload.len() as i64
            && payload_hash == actual_hash
            && snapshot_state_hash == actual_hash
            && revision_state_hash == actual_hash
            && std::str::from_utf8(&payload).is_ok();
        if !valid {
            let mut tx = self.pool.begin().await.map_err(CommitError::storage)?;
            sqlx::query(
                "UPDATE game_snapshots SET validation_status='corrupt' WHERE game_id=$1 AND revision=$2",
            )
            .bind(game_id)
            .bind(snapshot_revision)
            .execute(&mut *tx)
            .await
            .map_err(CommitError::storage)?;
            sqlx::query(
                "UPDATE games SET unavailable_at=COALESCE(unavailable_at, now()), unavailable_reason=COALESCE(unavailable_reason, 'corrupt_canonical_snapshot') WHERE id=$1",
            )
            .bind(game_id)
            .execute(&mut *tx)
            .await
            .map_err(CommitError::storage)?;
            tx.commit().await.map_err(CommitError::storage)?;
            return Err(CommitError::GameUnavailable);
        }
        Ok(String::from_utf8(payload).expect("UTF-8 was validated"))
    }

    /// Revalidates the canonical head without invoking a worker. Operators and
    /// restore drills can use this to prove stored bytes match every recorded
    /// integrity field. A failure quarantines the game instead of falling back
    /// to client state or silently rewriting history.
    pub async fn validate_canonical_head(&self, game_id: Uuid) -> Result<(), CommitError> {
        let row = sqlx::query(
            "SELECT g.unavailable_at IS NOT NULL AS is_unavailable, r.canonical_state_hash AS revision_state_hash, s.revision AS snapshot_revision, s.payload, s.codec, s.compressed_size, s.uncompressed_size, s.protocol_version AS snapshot_protocol_version, s.validation_status, s.payload_hash, s.canonical_state_hash AS snapshot_state_hash FROM games g JOIN game_revisions r ON r.game_id=g.id AND r.revision=g.head_revision JOIN game_snapshots s ON s.game_id=g.id AND s.revision=g.head_revision WHERE g.id=$1",
        )
        .bind(game_id)
        .fetch_optional(&self.pool)
        .await
        .map_err(CommitError::storage)?
        .ok_or(CommitError::NotFound)?;
        self.validated_snapshot(game_id, &row).await.map(|_| ())
    }

    /// Consumes one durable fixed-window bucket. Only the SHA-256 bucket hash
    /// is stored, so usernames and composite source identifiers are not
    /// recoverable from the rate-limit table.
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
    pub async fn create_game(&self, game: NewGame) -> Result<(), CommitError> {
        if game.snapshot.is_empty() || game.snapshot.len() > MAX_SNAPSHOT_BYTES {
            return Err(CommitError::SnapshotTooLarge);
        }
        let payload_hash = state_hash(&game.snapshot);
        let mut tx = self.pool.begin().await.map_err(CommitError::storage)?;
        let engine_build: String =
            sqlx::query_scalar("SELECT engine_build FROM ruleset_manifests WHERE hash = $1")
                .bind(&game.ruleset_manifest_hash)
                .fetch_one(&mut *tx)
                .await
                .map_err(CommitError::storage)?;
        sqlx::query("INSERT INTO games (id, ruleset_manifest_hash) VALUES ($1, $2)")
            .bind(game.game_id)
            .bind(&game.ruleset_manifest_hash)
            .execute(&mut *tx)
            .await
            .map_err(CommitError::storage)?;
        sqlx::query(
            "INSERT INTO game_members (game_id, account_id, role, civilization_id) VALUES ($1, $2, 'owner', $3)",
        )
        .bind(game.game_id)
        .bind(game.owner_account_id)
        .bind(game.owner_civilization_id)
        .execute(&mut *tx)
        .await
        .map_err(CommitError::storage)?;
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
        .await
        .map_err(CommitError::storage)?;
        sqlx::query(
            "INSERT INTO game_revisions (game_id, revision, parent_revision, command_id, snapshot_revision, canonical_state_hash) VALUES ($1, 0, NULL, NULL, 0, $2)",
        )
        .bind(game.game_id)
        .bind(&payload_hash)
        .execute(&mut *tx)
        .await
        .map_err(CommitError::storage)?;
        tx.commit().await.map_err(CommitError::storage)
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
        if proposal.snapshot.is_empty() || proposal.snapshot.len() > MAX_SNAPSHOT_BYTES {
            return Err(CommitError::SnapshotTooLarge);
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
            "SELECT g.unavailable_at IS NOT NULL AS is_unavailable, g.head_revision, g.ruleset_manifest_hash, m.engine_build FROM games g JOIN ruleset_manifests m ON m.hash = g.ruleset_manifest_hash WHERE g.id = $1 FOR UPDATE",
        )
        .bind(envelope.game_id)
        .fetch_optional(&mut *tx)
        .await
        .map_err(CommitError::storage)?
        .ok_or(CommitError::NotFound)?;
        if head.get::<bool, _>("is_unavailable") {
            return Err(CommitError::GameUnavailable);
        }
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
#[path = "postgres/integration_tests.rs"]
mod integration_tests;
