use serde::{Deserialize, Serialize};

use crate::{CommitProposal, projection::PlayerProjection};

#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct WorkerManifest {
    pub engine_build: String,
    pub base_ruleset: WorkerRuleset,
    pub mods: Vec<WorkerRuleset>,
}

#[derive(Clone, Debug, Deserialize, Serialize, PartialEq, Eq)]
pub struct WorkerRuleset {
    pub name: String,
    pub sha256: String,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub(super) struct WorkerRequest<'a> {
    pub(super) protocol_version: u16,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub(super) actor_id: Option<&'a str>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub(super) ruleset_manifest: Option<&'a WorkerManifest>,
    pub(super) operation: WorkerOperation<'a>,
}

#[derive(Serialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub(super) enum WorkerOperation<'a> {
    Handshake,
    CreateGame {
        setup: &'a str,
    },
    AssignPlayer {
        snapshot: &'a str,
    },
    EndTurn {
        snapshot: &'a str,
        #[serde(rename = "actorCivilizationId")]
        actor_civilization_id: &'a str,
    },
    MoveUnit {
        snapshot: &'a str,
        #[serde(rename = "actorCivilizationId")]
        actor_civilization_id: &'a str,
        #[serde(rename = "unitId")]
        unit_id: i32,
        #[serde(rename = "destinationX")]
        destination_x: i32,
        #[serde(rename = "destinationY")]
        destination_y: i32,
    },
    QueueConstruction {
        snapshot: &'a str,
        #[serde(rename = "actorCivilizationId")]
        actor_civilization_id: &'a str,
        #[serde(rename = "cityId")]
        city_id: &'a str,
        #[serde(rename = "constructionName")]
        construction_name: &'a str,
    },
    QueueConstructionAtTile {
        snapshot: &'a str,
        #[serde(rename = "actorCivilizationId")]
        actor_civilization_id: &'a str,
        #[serde(rename = "cityId")]
        city_id: &'a str,
        #[serde(rename = "constructionName")]
        construction_name: &'a str,
        x: i32,
        y: i32,
    },
    SetPerpetualConstruction {
        snapshot: &'a str,
        #[serde(rename = "actorCivilizationId")]
        actor_civilization_id: &'a str,
        #[serde(rename = "cityId")]
        city_id: &'a str,
        #[serde(rename = "constructionName")]
        construction_name: &'a str,
    },
    RemoveConstruction {
        snapshot: &'a str,
        #[serde(rename = "actorCivilizationId")]
        actor_civilization_id: &'a str,
        #[serde(rename = "cityId")]
        city_id: &'a str,
        #[serde(rename = "queueIndex")]
        queue_index: u32,
        #[serde(rename = "expectedConstructionName")]
        expected_construction_name: &'a str,
    },
    MoveConstruction {
        snapshot: &'a str,
        #[serde(rename = "actorCivilizationId")]
        actor_civilization_id: &'a str,
        #[serde(rename = "cityId")]
        city_id: &'a str,
        #[serde(rename = "fromIndex")]
        from_index: u32,
        #[serde(rename = "toIndex")]
        to_index: u32,
        #[serde(rename = "expectedConstructionName")]
        expected_construction_name: &'a str,
    },
    PurchaseConstruction {
        snapshot: &'a str,
        #[serde(rename = "actorCivilizationId")]
        actor_civilization_id: &'a str,
        #[serde(rename = "cityId")]
        city_id: &'a str,
        #[serde(rename = "constructionName")]
        construction_name: &'a str,
        #[serde(rename = "currencyName")]
        currency_name: &'a str,
        #[serde(rename = "queueIndex")]
        queue_index: Option<u32>,
    },
    PurchaseConstructionAtTile {
        snapshot: &'a str,
        #[serde(rename = "actorCivilizationId")]
        actor_civilization_id: &'a str,
        #[serde(rename = "cityId")]
        city_id: &'a str,
        #[serde(rename = "constructionName")]
        construction_name: &'a str,
        #[serde(rename = "currencyName")]
        currency_name: &'a str,
        x: i32,
        y: i32,
        #[serde(rename = "queueIndex")]
        queue_index: Option<u32>,
    },
    BuyCityTile {
        snapshot: &'a str,
        #[serde(rename = "actorCivilizationId")]
        actor_civilization_id: &'a str,
        #[serde(rename = "cityId")]
        city_id: &'a str,
        x: i32,
        y: i32,
    },
    SetCityTileAssignment {
        snapshot: &'a str,
        #[serde(rename = "actorCivilizationId")]
        actor_civilization_id: &'a str,
        #[serde(rename = "cityId")]
        city_id: &'a str,
        x: i32,
        y: i32,
        assignment: crate::CityTileAssignment,
    },
    SetSpecialistCount {
        snapshot: &'a str,
        #[serde(rename = "actorCivilizationId")]
        actor_civilization_id: &'a str,
        #[serde(rename = "cityId")]
        city_id: &'a str,
        #[serde(rename = "specialistName")]
        specialist_name: &'a str,
        count: u32,
    },
    SetManualSpecialists {
        snapshot: &'a str,
        #[serde(rename = "actorCivilizationId")]
        actor_civilization_id: &'a str,
        #[serde(rename = "cityId")]
        city_id: &'a str,
        enabled: bool,
    },
    ResetCitizens {
        snapshot: &'a str,
        #[serde(rename = "actorCivilizationId")]
        actor_civilization_id: &'a str,
        #[serde(rename = "cityId")]
        city_id: &'a str,
    },
    SetResearchPath {
        snapshot: &'a str,
        #[serde(rename = "actorCivilizationId")]
        actor_civilization_id: &'a str,
        #[serde(rename = "technologyName")]
        technology_name: &'a str,
    },
    AdoptPolicy {
        snapshot: &'a str,
        #[serde(rename = "actorCivilizationId")]
        actor_civilization_id: &'a str,
        #[serde(rename = "policyName")]
        policy_name: &'a str,
    },
    ChooseFreeTechnology {
        snapshot: &'a str,
        #[serde(rename = "actorCivilizationId")]
        actor_civilization_id: &'a str,
        #[serde(rename = "technologyName")]
        technology_name: &'a str,
    },
    ProjectState {
        snapshot: &'a str,
        #[serde(rename = "actorCivilizationId")]
        actor_civilization_id: &'a str,
    },
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub(super) struct WorkerResponse {
    pub(super) protocol_version: u16,
    pub(super) engine_build: Option<String>,
    pub(super) installed_rulesets: Option<Vec<WorkerRuleset>>,
    pub(super) snapshot: Option<String>,
    pub(super) canonical_state_hash: Option<String>,
    pub(super) actor_civilization_id: Option<String>,
    pub(super) player_projection: Option<serde_json::Value>,
    pub(super) error: Option<WorkerError>,
}

pub struct CreatedGame {
    pub proposal: CommitProposal,
    pub owner_civilization_id: String,
}
pub struct AssignedPlayer {
    pub proposal: CommitProposal,
    pub civilization_id: String,
}
pub struct ProjectedState {
    pub projection: PlayerProjection,
}

pub struct MoveUnitIntent<'a> {
    pub actor_civilization_id: &'a str,
    pub unit_id: i32,
    pub destination_x: i32,
    pub destination_y: i32,
}
pub struct QueueConstructionIntent<'a> {
    pub actor_civilization_id: &'a str,
    pub city_id: &'a str,
    pub construction_name: &'a str,
}
pub struct QueueConstructionAtTileIntent<'a> {
    pub actor_civilization_id: &'a str,
    pub city_id: &'a str,
    pub construction_name: &'a str,
    pub x: i32,
    pub y: i32,
}
pub struct SetPerpetualConstructionIntent<'a> {
    pub actor_civilization_id: &'a str,
    pub city_id: &'a str,
    pub construction_name: &'a str,
}
pub struct RemoveConstructionIntent<'a> {
    pub actor_civilization_id: &'a str,
    pub city_id: &'a str,
    pub queue_index: u32,
    pub expected_construction_name: &'a str,
}
pub struct MoveConstructionIntent<'a> {
    pub actor_civilization_id: &'a str,
    pub city_id: &'a str,
    pub from_index: u32,
    pub to_index: u32,
    pub expected_construction_name: &'a str,
}
pub struct PurchaseConstructionIntent<'a> {
    pub actor_civilization_id: &'a str,
    pub city_id: &'a str,
    pub construction_name: &'a str,
    pub currency_name: &'a str,
    pub queue_index: Option<u32>,
}
pub struct PurchaseConstructionAtTileIntent<'a> {
    pub actor_civilization_id: &'a str,
    pub city_id: &'a str,
    pub construction_name: &'a str,
    pub currency_name: &'a str,
    pub x: i32,
    pub y: i32,
    pub queue_index: Option<u32>,
}
pub struct BuyCityTileIntent<'a> {
    pub actor_civilization_id: &'a str,
    pub city_id: &'a str,
    pub x: i32,
    pub y: i32,
}

pub struct SetCityTileAssignmentIntent<'a> {
    pub actor_civilization_id: &'a str,
    pub city_id: &'a str,
    pub x: i32,
    pub y: i32,
    pub assignment: crate::CityTileAssignment,
}

pub struct SetSpecialistCountIntent<'a> {
    pub actor_civilization_id: &'a str,
    pub city_id: &'a str,
    pub specialist_name: &'a str,
    pub count: u32,
}

pub struct SetManualSpecialistsIntent<'a> {
    pub actor_civilization_id: &'a str,
    pub city_id: &'a str,
    pub enabled: bool,
}

pub struct ResetCitizensIntent<'a> {
    pub actor_civilization_id: &'a str,
    pub city_id: &'a str,
}
pub struct SetResearchPathIntent<'a> {
    pub actor_civilization_id: &'a str,
    pub technology_name: &'a str,
}
pub struct AdoptPolicyIntent<'a> {
    pub actor_civilization_id: &'a str,
    pub policy_name: &'a str,
}
pub struct ChooseFreeTechnologyIntent<'a> {
    pub actor_civilization_id: &'a str,
    pub technology_name: &'a str,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct WorkerCapabilities {
    pub engine_build: String,
    pub installed_rulesets: Vec<WorkerRuleset>,
}

#[derive(Deserialize)]
pub(super) struct WorkerError {
    pub(super) code: String,
    pub(super) message: String,
}
