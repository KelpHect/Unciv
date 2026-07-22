use serde::{Deserialize, Serialize};

use crate::{
    CommitProposal, UnitPosture,
    projection::{PlayerProjection, ProjectedTrade},
};

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
    MoveUnitToward {
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
    CancelUnitMovementOrder {
        snapshot: &'a str,
        #[serde(rename = "actorCivilizationId")]
        actor_civilization_id: &'a str,
        #[serde(rename = "unitId")]
        unit_id: i32,
    },
    SetUnitExploration {
        snapshot: &'a str,
        #[serde(rename = "actorCivilizationId")]
        actor_civilization_id: &'a str,
        #[serde(rename = "unitId")]
        unit_id: i32,
        enabled: bool,
    },
    SetUnitAutomation {
        snapshot: &'a str,
        #[serde(rename = "actorCivilizationId")]
        actor_civilization_id: &'a str,
        #[serde(rename = "unitId")]
        unit_id: i32,
        enabled: bool,
    },
    SetUnitPosture {
        snapshot: &'a str,
        #[serde(rename = "actorCivilizationId")]
        actor_civilization_id: &'a str,
        #[serde(rename = "unitId")]
        unit_id: i32,
        posture: UnitPosture,
    },
    DisbandUnit {
        snapshot: &'a str,
        #[serde(rename = "actorCivilizationId")]
        actor_civilization_id: &'a str,
        #[serde(rename = "unitId")]
        unit_id: i32,
    },
    PillageTile {
        snapshot: &'a str,
        #[serde(rename = "actorCivilizationId")]
        actor_civilization_id: &'a str,
        #[serde(rename = "unitId")]
        unit_id: i32,
    },
    FoundCity {
        snapshot: &'a str,
        #[serde(rename = "actorCivilizationId")]
        actor_civilization_id: &'a str,
        #[serde(rename = "unitId")]
        unit_id: i32,
    },
    ParadropUnit {
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
    AttackWithUnit {
        snapshot: &'a str,
        #[serde(rename = "actorCivilizationId")]
        actor_civilization_id: &'a str,
        #[serde(rename = "unitId")]
        unit_id: i32,
        #[serde(rename = "targetX")]
        target_x: i32,
        #[serde(rename = "targetY")]
        target_y: i32,
    },
    BombardWithCity {
        snapshot: &'a str,
        #[serde(rename = "actorCivilizationId")]
        actor_civilization_id: &'a str,
        #[serde(rename = "cityId")]
        city_id: &'a str,
        #[serde(rename = "targetX")]
        target_x: i32,
        #[serde(rename = "targetY")]
        target_y: i32,
    },
    LaunchNuclearStrike {
        snapshot: &'a str,
        #[serde(rename = "actorCivilizationId")]
        actor_civilization_id: &'a str,
        #[serde(rename = "unitId")]
        unit_id: i32,
        #[serde(rename = "targetX")]
        target_x: i32,
        #[serde(rename = "targetY")]
        target_y: i32,
    },
    AirSweep {
        snapshot: &'a str,
        #[serde(rename = "actorCivilizationId")]
        actor_civilization_id: &'a str,
        #[serde(rename = "unitId")]
        unit_id: i32,
        #[serde(rename = "targetX")]
        target_x: i32,
        #[serde(rename = "targetY")]
        target_y: i32,
    },
    UpgradeUnits {
        snapshot: &'a str,
        #[serde(rename = "actorCivilizationId")]
        actor_civilization_id: &'a str,
        #[serde(rename = "unitIds")]
        unit_ids: &'a [i32],
        #[serde(rename = "targetUnitName")]
        target_unit_name: &'a str,
    },
    PromoteUnit {
        snapshot: &'a str,
        #[serde(rename = "actorCivilizationId")]
        actor_civilization_id: &'a str,
        #[serde(rename = "unitId")]
        unit_id: i32,
        #[serde(rename = "promotionNames")]
        promotion_names: &'a [String],
        #[serde(rename = "saveAsCityDefault")]
        save_as_city_default: bool,
    },
    SetCityUnitPromotionPreference {
        snapshot: &'a str,
        #[serde(rename = "actorCivilizationId")]
        actor_civilization_id: &'a str,
        #[serde(rename = "cityId")]
        city_id: &'a str,
        #[serde(rename = "baseUnitName")]
        base_unit_name: &'a str,
        enabled: bool,
    },
    RenameUnit {
        snapshot: &'a str,
        #[serde(rename = "actorCivilizationId")]
        actor_civilization_id: &'a str,
        #[serde(rename = "unitId")]
        unit_id: i32,
        #[serde(rename = "instanceName")]
        instance_name: Option<&'a str>,
    },
    SetTileImprovementOrder {
        snapshot: &'a str,
        #[serde(rename = "actorCivilizationId")]
        actor_civilization_id: &'a str,
        #[serde(rename = "unitId")]
        unit_id: i32,
        #[serde(rename = "improvementName")]
        improvement_name: Option<&'a str>,
        #[serde(rename = "queuedImprovementName")]
        queued_improvement_name: Option<&'a str>,
    },
    SetRoadConnectionOrder {
        snapshot: &'a str,
        #[serde(rename = "actorCivilizationId")]
        actor_civilization_id: &'a str,
        #[serde(rename = "unitId")]
        unit_id: i32,
        #[serde(rename = "destinationX")]
        destination_x: Option<i32>,
        #[serde(rename = "destinationY")]
        destination_y: Option<i32>,
    },
    SwapUnits {
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
    SellBuilding {
        snapshot: &'a str,
        #[serde(rename = "actorCivilizationId")]
        actor_civilization_id: &'a str,
        #[serde(rename = "cityId")]
        city_id: &'a str,
        #[serde(rename = "buildingName")]
        building_name: &'a str,
    },
    SetCityGovernance {
        snapshot: &'a str,
        #[serde(rename = "actorCivilizationId")]
        actor_civilization_id: &'a str,
        #[serde(rename = "cityId")]
        city_id: &'a str,
        action: crate::CityGovernanceAction,
    },
    ResolveCityDisposition {
        snapshot: &'a str,
        #[serde(rename = "actorCivilizationId")]
        actor_civilization_id: &'a str,
        #[serde(rename = "cityId")]
        city_id: &'a str,
        action: crate::CityDispositionAction,
    },
    CastDiplomaticVote {
        snapshot: &'a str,
        #[serde(rename = "actorCivilizationId")]
        actor_civilization_id: &'a str,
        #[serde(rename = "candidateCivilizationId")]
        candidate_civilization_id: Option<&'a str>,
    },
    ChooseGreatPerson {
        snapshot: &'a str,
        #[serde(rename = "actorCivilizationId")]
        actor_civilization_id: &'a str,
        #[serde(rename = "unitName")]
        unit_name: &'a str,
    },
    UseReligiousUnit {
        snapshot: &'a str,
        #[serde(rename = "actorCivilizationId")]
        actor_civilization_id: &'a str,
        #[serde(rename = "unitId")]
        unit_id: i32,
        action: crate::ReligiousUnitAction,
    },
    ChooseReligiousBeliefs {
        snapshot: &'a str,
        #[serde(rename = "actorCivilizationId")]
        actor_civilization_id: &'a str,
        #[serde(rename = "beliefNames")]
        belief_names: &'a [String],
        #[serde(rename = "religionIconName")]
        religion_icon_name: Option<&'a str>,
        #[serde(rename = "religionDisplayName")]
        religion_display_name: Option<&'a str>,
    },
    OfferTrade {
        snapshot: &'a str,
        #[serde(rename = "actorCivilizationId")]
        actor_civilization_id: &'a str,
        #[serde(rename = "otherCivilizationId")]
        other_civilization_id: &'a str,
        trade: &'a ProjectedTrade,
    },
    RetractTradeOffer {
        snapshot: &'a str,
        #[serde(rename = "actorCivilizationId")]
        actor_civilization_id: &'a str,
        #[serde(rename = "otherCivilizationId")]
        other_civilization_id: &'a str,
    },
    AcceptTrade {
        snapshot: &'a str,
        #[serde(rename = "actorCivilizationId")]
        actor_civilization_id: &'a str,
        #[serde(rename = "requestId")]
        request_id: &'a str,
    },
    DeclineTrade {
        snapshot: &'a str,
        #[serde(rename = "actorCivilizationId")]
        actor_civilization_id: &'a str,
        #[serde(rename = "requestId")]
        request_id: &'a str,
    },
    CounterTrade {
        snapshot: &'a str,
        #[serde(rename = "actorCivilizationId")]
        actor_civilization_id: &'a str,
        #[serde(rename = "requestId")]
        request_id: &'a str,
        trade: &'a ProjectedTrade,
    },
    DeclareWar {
        snapshot: &'a str,
        #[serde(rename = "actorCivilizationId")]
        actor_civilization_id: &'a str,
        #[serde(rename = "otherCivilizationId")]
        other_civilization_id: &'a str,
    },
    DenounceCivilization {
        snapshot: &'a str,
        #[serde(rename = "actorCivilizationId")]
        actor_civilization_id: &'a str,
        #[serde(rename = "otherCivilizationId")]
        other_civilization_id: &'a str,
    },
    OfferFriendship {
        snapshot: &'a str,
        #[serde(rename = "actorCivilizationId")]
        actor_civilization_id: &'a str,
        #[serde(rename = "otherCivilizationId")]
        other_civilization_id: &'a str,
    },
    MakeDiplomaticDemand {
        snapshot: &'a str,
        #[serde(rename = "actorCivilizationId")]
        actor_civilization_id: &'a str,
        #[serde(rename = "otherCivilizationId")]
        other_civilization_id: &'a str,
        demand: crate::projection::DiplomaticDemand,
    },
    RespondToDiplomaticPrompt {
        snapshot: &'a str,
        #[serde(rename = "actorCivilizationId")]
        actor_civilization_id: &'a str,
        #[serde(rename = "promptId")]
        prompt_id: &'a str,
        accept: bool,
    },
    GiftCityStateGold {
        snapshot: &'a str,
        #[serde(rename = "actorCivilizationId")]
        actor_civilization_id: &'a str,
        #[serde(rename = "cityStateCivilizationId")]
        city_state_civilization_id: &'a str,
        amount: u32,
    },
    SetCityStateProtection {
        snapshot: &'a str,
        #[serde(rename = "actorCivilizationId")]
        actor_civilization_id: &'a str,
        #[serde(rename = "cityStateCivilizationId")]
        city_state_civilization_id: &'a str,
        protect: bool,
    },
    DemandCityStateTribute {
        snapshot: &'a str,
        #[serde(rename = "actorCivilizationId")]
        actor_civilization_id: &'a str,
        #[serde(rename = "cityStateCivilizationId")]
        city_state_civilization_id: &'a str,
        worker: bool,
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
    SetAvoidGrowth {
        snapshot: &'a str,
        #[serde(rename = "actorCivilizationId")]
        actor_civilization_id: &'a str,
        #[serde(rename = "cityId")]
        city_id: &'a str,
        enabled: bool,
    },
    SetCitizenFocus {
        snapshot: &'a str,
        #[serde(rename = "actorCivilizationId")]
        actor_civilization_id: &'a str,
        #[serde(rename = "cityId")]
        city_id: &'a str,
        focus: crate::CitizenFocus,
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
pub struct MoveUnitTowardIntent<'a> {
    pub actor_civilization_id: &'a str,
    pub unit_id: i32,
    pub destination_x: i32,
    pub destination_y: i32,
}
pub struct CancelUnitMovementOrderIntent<'a> {
    pub actor_civilization_id: &'a str,
    pub unit_id: i32,
}
pub struct SetUnitExplorationIntent<'a> {
    pub actor_civilization_id: &'a str,
    pub unit_id: i32,
    pub enabled: bool,
}
pub struct SetUnitAutomationIntent<'a> {
    pub actor_civilization_id: &'a str,
    pub unit_id: i32,
    pub enabled: bool,
}
pub struct SetUnitPostureIntent<'a> {
    pub actor_civilization_id: &'a str,
    pub unit_id: i32,
    pub posture: UnitPosture,
}
pub struct DisbandUnitIntent<'a> {
    pub actor_civilization_id: &'a str,
    pub unit_id: i32,
}
pub struct PillageTileIntent<'a> {
    pub actor_civilization_id: &'a str,
    pub unit_id: i32,
}
pub struct FoundCityIntent<'a> {
    pub actor_civilization_id: &'a str,
    pub unit_id: i32,
}
pub struct ParadropUnitIntent<'a> {
    pub actor_civilization_id: &'a str,
    pub unit_id: i32,
    pub destination_x: i32,
    pub destination_y: i32,
}
pub struct AttackWithUnitIntent<'a> {
    pub actor_civilization_id: &'a str,
    pub unit_id: i32,
    pub target_x: i32,
    pub target_y: i32,
}
pub struct BombardWithCityIntent<'a> {
    pub actor_civilization_id: &'a str,
    pub city_id: &'a str,
    pub target_x: i32,
    pub target_y: i32,
}
pub struct LaunchNuclearStrikeIntent<'a> {
    pub actor_civilization_id: &'a str,
    pub unit_id: i32,
    pub target_x: i32,
    pub target_y: i32,
}
pub struct AirSweepIntent<'a> {
    pub actor_civilization_id: &'a str,
    pub unit_id: i32,
    pub target_x: i32,
    pub target_y: i32,
}
pub struct UpgradeUnitsIntent<'a> {
    pub actor_civilization_id: &'a str,
    pub unit_ids: &'a [i32],
    pub target_unit_name: &'a str,
}
pub struct PromoteUnitIntent<'a> {
    pub actor_civilization_id: &'a str,
    pub unit_id: i32,
    pub promotion_names: &'a [String],
    pub save_as_city_default: bool,
}
pub struct SetCityUnitPromotionPreferenceIntent<'a> {
    pub actor_civilization_id: &'a str,
    pub city_id: &'a str,
    pub base_unit_name: &'a str,
    pub enabled: bool,
}
pub struct RenameUnitIntent<'a> {
    pub actor_civilization_id: &'a str,
    pub unit_id: i32,
    pub instance_name: Option<&'a str>,
}
pub struct SetTileImprovementOrderIntent<'a> {
    pub actor_civilization_id: &'a str,
    pub unit_id: i32,
    pub improvement_name: Option<&'a str>,
    pub queued_improvement_name: Option<&'a str>,
}
pub struct SetRoadConnectionOrderIntent<'a> {
    pub actor_civilization_id: &'a str,
    pub unit_id: i32,
    pub destination_x: Option<i32>,
    pub destination_y: Option<i32>,
}
pub struct SwapUnitsIntent<'a> {
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

pub struct SellBuildingIntent<'a> {
    pub actor_civilization_id: &'a str,
    pub city_id: &'a str,
    pub building_name: &'a str,
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
