use serde::{Deserialize, Serialize};

use crate::{
    CommitProposal, UnitPosture,
    projection::{PlayerProjection, ProjectedTrade, SpectatorProjection},
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
    Resign {
        snapshot: &'a str,
        #[serde(rename = "actorCivilizationId")]
        actor_civilization_id: &'a str,
    },
    ForceResign {
        snapshot: &'a str,
        #[serde(rename = "actorCivilizationId")]
        actor_civilization_id: &'a str,
    },
    KickPlayer {
        snapshot: &'a str,
        #[serde(rename = "actorCivilizationId")]
        actor_civilization_id: &'a str,
        #[serde(rename = "targetCivilizationId")]
        target_civilization_id: &'a str,
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
        #[serde(rename = "escortUnitId")]
        escort_unit_id: Option<i32>,
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
        #[serde(rename = "escortUnitId")]
        escort_unit_id: Option<i32>,
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
    ManageConstructionQueues {
        snapshot: &'a str,
        #[serde(rename = "actorCivilizationId")]
        actor_civilization_id: &'a str,
        #[serde(rename = "cityId")]
        city_id: &'a str,
        #[serde(rename = "constructionName")]
        construction_name: &'a str,
        #[serde(rename = "queueIndex")]
        queue_index: Option<u32>,
        action: crate::ConstructionQueueAction,
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
    BuyCityTileBatch {
        snapshot: &'a str,
        #[serde(rename = "actorCivilizationId")]
        actor_civilization_id: &'a str,
        #[serde(rename = "cityId")]
        city_id: &'a str,
        ring: u32,
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
    UseGreatPersonUnit {
        snapshot: &'a str,
        #[serde(rename = "actorCivilizationId")]
        actor_civilization_id: &'a str,
        #[serde(rename = "unitId")]
        unit_id: i32,
        action: crate::GreatPersonUnitAction,
    },
    GiftUnit {
        snapshot: &'a str,
        #[serde(rename = "actorCivilizationId")]
        actor_civilization_id: &'a str,
        #[serde(rename = "unitId")]
        unit_id: i32,
    },
    AddUnitToCapitalProject {
        snapshot: &'a str,
        #[serde(rename = "actorCivilizationId")]
        actor_civilization_id: &'a str,
        #[serde(rename = "unitId")]
        unit_id: i32,
    },
    TransformUnit {
        snapshot: &'a str,
        #[serde(rename = "actorCivilizationId")]
        actor_civilization_id: &'a str,
        #[serde(rename = "unitId")]
        unit_id: i32,
        #[serde(rename = "actionId")]
        action_id: &'a str,
    },
    TriggerUnitUnique {
        snapshot: &'a str,
        #[serde(rename = "actorCivilizationId")]
        actor_civilization_id: &'a str,
        #[serde(rename = "unitId")]
        unit_id: i32,
        #[serde(rename = "actionId")]
        action_id: &'a str,
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
    RespondToCityStateProtectionPrompt {
        snapshot: &'a str,
        #[serde(rename = "actorCivilizationId")]
        actor_civilization_id: &'a str,
        #[serde(rename = "promptId")]
        prompt_id: &'a str,
        response: crate::projection::CityStateProtectionResponse,
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
    GiftCityStateImprovement {
        snapshot: &'a str,
        #[serde(rename = "actorCivilizationId")]
        actor_civilization_id: &'a str,
        #[serde(rename = "cityStateCivilizationId")]
        city_state_civilization_id: &'a str,
        x: i32,
        y: i32,
        #[serde(rename = "improvementName")]
        improvement_name: &'a str,
    },
    NegotiateCityStatePeace {
        snapshot: &'a str,
        #[serde(rename = "actorCivilizationId")]
        actor_civilization_id: &'a str,
        #[serde(rename = "cityStateCivilizationId")]
        city_state_civilization_id: &'a str,
    },
    MarryCityState {
        snapshot: &'a str,
        #[serde(rename = "actorCivilizationId")]
        actor_civilization_id: &'a str,
        #[serde(rename = "cityStateCivilizationId")]
        city_state_civilization_id: &'a str,
    },
    MoveSpy {
        snapshot: &'a str,
        #[serde(rename = "actorCivilizationId")]
        actor_civilization_id: &'a str,
        #[serde(rename = "spyName")]
        spy_name: &'a str,
        #[serde(rename = "cityId")]
        city_id: Option<&'a str>,
    },
    SetSpyCoup {
        snapshot: &'a str,
        #[serde(rename = "actorCivilizationId")]
        actor_civilization_id: &'a str,
        #[serde(rename = "spyName")]
        spy_name: &'a str,
        enabled: bool,
    },
    ResolveEventChoice {
        snapshot: &'a str,
        #[serde(rename = "actorCivilizationId")]
        actor_civilization_id: &'a str,
        #[serde(rename = "promptId")]
        prompt_id: &'a str,
        #[serde(rename = "choiceId")]
        choice_id: &'a str,
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
        append: bool,
    },
    ManageResearchQueue {
        snapshot: &'a str,
        #[serde(rename = "actorCivilizationId")]
        actor_civilization_id: &'a str,
        #[serde(rename = "technologyName")]
        technology_name: &'a str,
        #[serde(rename = "queueIndex")]
        queue_index: u32,
        action: crate::ResearchQueueAction,
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
    AcknowledgeResearchCompletion {
        snapshot: &'a str,
        #[serde(rename = "actorCivilizationId")]
        actor_civilization_id: &'a str,
        #[serde(rename = "promptId")]
        prompt_id: &'a str,
    },
    ProjectState {
        snapshot: &'a str,
        #[serde(rename = "actorCivilizationId")]
        actor_civilization_id: &'a str,
    },
    ProjectSpectatorState {
        snapshot: &'a str,
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
    pub(super) spectator_projection: Option<serde_json::Value>,
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
    pub engine_build: String,
    pub installed_rulesets: Vec<WorkerRuleset>,
}

#[derive(Deserialize)]
pub(super) struct WorkerError {
    pub(super) code: String,
    pub(super) message: String,
}
