use serde_json::json;
use sqlx::{PgPool, Row, postgres::PgRow};
use uuid::Uuid;

use crate::auth::{
    Account, AuthError, PasswordError, PasswordService, RecoveryCodeBatch, SessionCredential,
    SessionPolicy, normalize_username, token_digest,
};
use crate::object_store::LockwellObjectStore;
use crate::projection::PlayerProjection;
use crate::projection::SpectatorProjection;
use crate::worker::{
    AcknowledgeResearchCompletionIntent, AdoptPolicyIntent, AirSweepIntent, AttackWithUnitIntent,
    BombardWithCityIntent, BuyCityTileIntent, CancelUnitMovementOrderIntent,
    CastDiplomaticVoteIntent, ChooseFreeTechnologyIntent, ChooseGreatPersonIntent,
    ChooseReligiousBeliefsIntent, CounterTradeIntent, CreateInstantImprovementIntent,
    DiplomacyPartnerIntent, DiplomaticDemandIntent, DiplomaticPromptIntent, DisbandUnitIntent,
    EngineWorkerClient, FoundCityIntent, GiftUnitIntent, LaunchNuclearStrikeIntent,
    ManageConstructionQueuesIntent, ManageResearchQueueIntent, MoveConstructionIntent,
    MoveUnitIntent, MoveUnitTowardIntent, OfferTradeIntent, ParadropUnitIntent, PillageTileIntent,
    PromoteUnitIntent, PurchaseConstructionAtTileIntent, PurchaseConstructionIntent,
    QueueConstructionAtTileIntent, QueueConstructionIntent, RemoveConstructionIntent,
    RenameUnitIntent, ResetCitizensIntent, ResolveCityDispositionIntent, ResolveEventChoiceIntent,
    SellBuildingIntent, SetAvoidGrowthIntent, SetCitizenFocusIntent, SetCityGovernanceIntent,
    SetCityTileAssignmentIntent, SetCityUnitPromotionPreferenceIntent, SetManualSpecialistsIntent,
    SetPerpetualConstructionIntent, SetResearchPathIntent, SetRoadConnectionOrderIntent,
    SetSpecialistCountIntent, SetTileImprovementOrderIntent, SetUnitAutomationIntent,
    SetUnitExplorationIntent, SetUnitPostureIntent, SwapUnitsIntent, TradePartnerIntent,
    TradeRequestIntent, TransformUnitIntent, TriggerUnitUniqueIntent, UpgradeUnitsIntent,
    UseGreatPersonUnitIntent, UseReligiousUnitIntent, WorkerManifest,
};
use crate::{
    CommandAccepted, CommandEnvelope, CommitError, CommitProposal, GameProjectionDelta,
    MAX_SNAPSHOT_BYTES, PROJECTION_VERSION, PROTOCOL_VERSION, state_hash,
};

pub use archive::SnapshotArchiveReport;
pub use lobby_reconfiguration::{LobbyConfigurationUpdate, LobbyPasswordUpdate};
pub use repair::RepairReport;
pub use retention::{
    GameStorageBreakdown, SnapshotCompactionReport, SnapshotMaintenanceConfig,
    SnapshotMaintenanceReport, SnapshotRetentionPolicy,
};
use snapshot_codec::{SnapshotCodecError, StoredSnapshot, decode_snapshot, encode_snapshot};

const ARCHIVE_OBJECT_MAGIC: &[u8; 8] = b"UCVARCH1";

fn archive_object_payload(payload: &[u8]) -> Vec<u8> {
    let mut object = Vec::with_capacity(ARCHIVE_OBJECT_MAGIC.len() + payload.len());
    object.extend_from_slice(ARCHIVE_OBJECT_MAGIC);
    object.extend_from_slice(payload);
    object
}

fn unarchive_object_payload(payload: &[u8]) -> Result<Vec<u8>, CommitError> {
    if payload.starts_with(ARCHIVE_OBJECT_MAGIC) {
        return payload
            .get(ARCHIVE_OBJECT_MAGIC.len()..)
            .map(ToOwned::to_owned)
            .ok_or(CommitError::RecoveryEvidenceMissing);
    }
    // Accept pre-envelope objects written by the first archival build.
    Ok(payload.to_vec())
}

fn stored_snapshot(snapshot: &[u8]) -> Result<StoredSnapshot, CommitError> {
    encode_snapshot(snapshot).map_err(|error| match error {
        SnapshotCodecError::Codec => CommitError::Storage,
        _ => CommitError::SnapshotTooLarge,
    })
}

#[derive(Clone)]
pub struct PostgresGameRepository {
    pool: PgPool,
    pub(super) object_store: Option<LockwellObjectStore>,
}

pub struct NewGame {
    pub game_id: Uuid,
    pub owner_account_id: Uuid,
    pub ruleset_manifest_hash: String,
    pub snapshot: Vec<u8>,
    pub owner_civilization_id: String,
}

/// Journal identity stored for spectator-driven all-AI commands, which have no
/// actor civilization. Mirrors the engine's spectator civilization identifier.
const ADVANCE_AI_ACTOR_CIVILIZATION: &str = "Spectator";

struct NewMemberAssignment {
    civilization_id: String,
    lobby_join: bool,
}

enum MembershipRemoval {
    Actor,
    Civilization(String),
}

#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, utoipa::ToSchema)]
pub struct GameMetadata {
    pub game_id: Uuid,
    pub committed_revision: u64,
    pub canonical_state_hash: String,
    pub role: String,
    pub civilization_id: Option<String>,
    pub lifecycle_status: String,
}

#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, utoipa::ToSchema)]
pub struct GameSummary {
    pub game_id: Uuid,
    pub display_name: String,
    pub committed_revision: u64,
    pub canonical_state_hash: String,
    pub role: String,
    pub civilization_id: Option<String>,
    pub available: bool,
    pub lifecycle_status: String,
    /// Number of AI civilizations in this match. Derived from the lobby setup's
    /// `major_civilizations` minus `human_slots`; zero when no lobby exists.
    pub ai_count: u8,
}

#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, utoipa::ToSchema)]
pub struct GamePage {
    pub games: Vec<GameSummary>,
    pub next_cursor: Option<Uuid>,
}

#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, utoipa::ToSchema)]
pub struct LobbyMemberSummary {
    pub username: String,
    pub role: String,
    pub civilization_id: String,
    pub ready: bool,
}

#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, utoipa::ToSchema)]
pub struct LobbySummary {
    pub game_id: Uuid,
    pub committed_revision: u64,
    pub canonical_state_hash: String,
    pub display_name: String,
    pub owner_username: String,
    pub ruleset_manifest_hash: String,
    pub base_ruleset_name: String,
    pub mod_names: Vec<String>,
    pub human_slots: u8,
    pub occupied_slots: u8,
    pub password_required: bool,
    pub lobby_revision: u64,
    pub started: bool,
    pub actor_role: Option<String>,
    pub actor_ready: Option<bool>,
    pub actor_civilization_id: Option<String>,
    pub setup: serde_json::Value,
    pub available_civilizations: Vec<String>,
    pub members: Vec<LobbyMemberSummary>,
}

#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, utoipa::ToSchema)]
pub struct LobbyPage {
    pub lobbies: Vec<LobbySummary>,
    pub next_cursor: Option<Uuid>,
}

/// Terrain of the map a pregame lobby revision committed. Bound to that
/// revision so a client can never present a stale map as current.
#[derive(Clone, Debug, PartialEq, serde::Serialize, utoipa::ToSchema)]
pub struct LobbyMapPreview {
    pub game_id: Uuid,
    pub preview_version: u16,
    pub lobby_revision: u64,
    pub canonical_state_hash: String,
    pub terrain: crate::LobbyTerrainProjection,
}

#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, utoipa::ToSchema)]
pub struct PlayerInvitation {
    pub game_id: Uuid,
    pub invitation_id: Uuid,
    pub invited_by: String,
    pub committed_revision: u64,
    pub canonical_state_hash: String,
}

#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, utoipa::ToSchema)]
pub struct FriendSummary {
    pub username: String,
}

#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, utoipa::ToSchema)]
pub struct FriendRequestSummary {
    pub request_id: Uuid,
    pub username: String,
    pub direction: String,
}

#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, utoipa::ToSchema)]
pub struct SocialGraph {
    pub friends: Vec<FriendSummary>,
    pub requests: Vec<FriendRequestSummary>,
}

#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize, utoipa::ToSchema)]
pub struct GameChatMessage {
    pub message_id: Uuid,
    pub sender_username: String,
    pub body: String,
    pub created_at_millis: i64,
}

#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize, utoipa::ToSchema)]
pub struct GameChatPage {
    pub messages: Vec<GameChatMessage>,
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

#[derive(Clone, Debug, serde::Serialize, utoipa::ToSchema)]
pub struct SpectatorGameProjection {
    pub game_id: Uuid,
    pub projection_version: u16,
    pub committed_revision: u64,
    pub canonical_state_hash: String,
    pub projection_hash: String,
    pub projection: SpectatorProjection,
}

#[derive(Clone, Debug)]
pub struct ClaimedOutboxEvent {
    pub id: i64,
    pub claim_token: Uuid,
    pub game_id: Uuid,
    pub revision: u64,
    pub topic: String,
    pub payload: serde_json::Value,
}

mod account_recovery;
mod accounts;
mod administration;
mod archive;
mod capital_project;
mod city_disposition;
mod city_economy;
mod city_governance;
mod city_population;
mod city_state;
mod city_tile_batches;
mod commands;
mod commit;
mod connection;
mod construction_commands;
mod construction_queues;
mod diplomacy;
mod espionage;
mod event_choices;
mod game_chat;
mod game_creation;
mod games;
mod great_people;
mod instant_improvements;
mod invitations;
mod legacy_import;
mod lifecycle;
mod lobbies;
mod lobby_reconfiguration;
mod major_diplomacy;
mod manifests;
mod outbox;
mod reconciliation;
mod recovery;
mod reencode;
mod religion;
mod repair;
mod replay;
mod research;
mod retention;
mod rewinds;
mod security;
mod security_audit_export;
mod snapshot_codec;
mod snapshot_storage;
mod snapshot_validation;
mod social_graph;
mod spectators;
mod trade;
mod unit_actions;
mod unit_gifts;
mod unit_movement;
mod unit_orders;
mod unit_transforms;
mod unit_triggers;
mod websocket_leases;

pub use connection::{
    MIGRATOR, PostgresConfigurationError, PostgresRuntimeConfig, SchemaCompatibilityError,
};
pub use legacy_import::{
    LegacyImportApplication, LegacyImportCandidateEvidence, LegacyImportConflictReport,
    LegacyImportOutcome, LegacyImportProjectionEvidence, LegacyImportProjectionReport,
    LegacyImportRole, legacy_import_game_id,
};
pub use lobbies::LobbyCreateConfiguration;
pub use manifests::{PublicRulesetIdentity, RulesetManifestPage, RulesetManifestSummary};
pub use outbox::{
    OutboxCompactionReport, OutboxHealthReport, OutboxRequeueReport, OutboxRetryDisposition,
    OutboxRuntimePolicy,
};
pub use reconciliation::{ReconciliationFinding, ReconciliationKind, ReconciliationReport};
pub use recovery::RecoveredHead;
pub use reencode::SnapshotReencodeReport;
pub use replay::{
    PublicMatchSummary, REPLAY_PROJECTION_VERSION, ReplayGameProjection, RevisionList,
    RevisionSummary,
};
pub use rewinds::{RewindCheckpoint, RewindRequest, RewindStatus};
pub use security::{SecurityAuditEvent, SecurityAuditOutcome};
pub use security_audit_export::{SECURITY_AUDIT_EXPORT_PAGE_SIZE, SecurityAuditExportEvent};
pub use websocket_leases::{WebSocketConnectionLease, WebSocketLeaseError};

#[cfg(test)]
#[path = "postgres/integration_tests.rs"]
mod integration_tests;
