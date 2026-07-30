use serde::Deserialize;

use crate::{
    CommitProposal,
    projection::{PlayerProjection, SpectatorProjection},
};

use super::WorkerRuleset;

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(super) struct WorkerResponse {
    pub(super) protocol_version: u16,
    pub(super) release_bundle_id: Option<String>,
    pub(super) server_time_millis: Option<i64>,
    pub(super) engine_build: Option<String>,
    pub(super) installed_rulesets: Option<Vec<WorkerRuleset>>,
    pub(super) snapshot: Option<String>,
    pub(super) canonical_state_hash: Option<String>,
    pub(super) actor_civilization_id: Option<String>,
    pub(super) available_civilization_ids: Option<Vec<String>>,
    pub(super) legacy_import: Option<super::LegacyImportMetadata>,
    pub(super) player_projection: Option<serde_json::Value>,
    pub(super) spectator_projection: Option<serde_json::Value>,
    pub(super) lobby_terrain_projection: Option<serde_json::Value>,
    pub(super) error: Option<WorkerError>,
    #[serde(skip)]
    pub(super) replay_operation: Option<serde_json::Value>,
}

pub struct CreatedGame {
    pub proposal: CommitProposal,
    pub owner_civilization_id: String,
    pub available_civilization_ids: Vec<String>,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct NormalizedLegacyGame {
    pub snapshot: String,
    pub canonical_state_hash: String,
    pub owner_civilization_id: String,
    pub metadata: super::LegacyImportMetadata,
}

pub struct AssignedPlayer {
    pub proposal: CommitProposal,
    pub civilization_id: String,
}

pub struct ForcedResignation {
    pub proposal: CommitProposal,
    pub civilization_id: String,
}

pub struct ProjectedState {
    pub projection: PlayerProjection,
}

pub struct ProjectedSpectatorState {
    pub projection: SpectatorProjection,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct WorkerCapabilities {
    pub release_bundle_id: String,
    pub engine_build: String,
    pub installed_rulesets: Vec<WorkerRuleset>,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub(super) struct WorkerError {
    pub(super) code: String,
    pub(super) message: String,
}
