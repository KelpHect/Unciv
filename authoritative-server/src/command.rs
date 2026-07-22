use serde::{Deserialize, Serialize};
use uuid::Uuid;

pub use crate::projection::CitizenFocus;

#[derive(Clone, Copy, Debug, Deserialize, Serialize, PartialEq, Eq, utoipa::ToSchema)]
#[serde(rename_all = "snake_case")]
pub enum CityTileAssignment {
    Unworked,
    Worked,
    Locked,
}

#[derive(Clone, Copy, Debug, Deserialize, Serialize, PartialEq, Eq, utoipa::ToSchema)]
#[serde(rename_all = "snake_case")]
pub enum CityGovernanceAction {
    Annex,
    StartRazing,
    StopRazing,
}

#[derive(Clone, Copy, Debug, Deserialize, Serialize, PartialEq, Eq, utoipa::ToSchema)]
#[serde(rename_all = "snake_case")]
pub enum UnitPosture {
    Sleep,
    SleepUntilHealed,
    Fortify,
    FortifyUntilHealed,
    Guard,
}

#[derive(Clone, Debug, Deserialize, Serialize, PartialEq, Eq)]
#[serde(tag = "type", rename_all = "snake_case", deny_unknown_fields)]
pub enum GameCommand {
    JoinGame,
    EndTurn,
    MoveUnit {
        unit_id: i32,
        destination_x: i32,
        destination_y: i32,
    },
    MoveUnitToward {
        unit_id: i32,
        destination_x: i32,
        destination_y: i32,
    },
    CancelUnitMovementOrder {
        unit_id: i32,
    },
    SetUnitExploration {
        unit_id: i32,
        enabled: bool,
    },
    SetUnitAutomation {
        unit_id: i32,
        enabled: bool,
    },
    SetUnitPosture {
        unit_id: i32,
        posture: UnitPosture,
    },
    DisbandUnit {
        unit_id: i32,
    },
    PillageTile {
        unit_id: i32,
    },
    FoundCity {
        unit_id: i32,
    },
    ParadropUnit {
        unit_id: i32,
        destination_x: i32,
        destination_y: i32,
    },
    AttackWithUnit {
        unit_id: i32,
        target_x: i32,
        target_y: i32,
    },
    BombardWithCity {
        city_id: String,
        target_x: i32,
        target_y: i32,
    },
    LaunchNuclearStrike {
        unit_id: i32,
        target_x: i32,
        target_y: i32,
    },
    AirSweep {
        unit_id: i32,
        target_x: i32,
        target_y: i32,
    },
    UpgradeUnits {
        unit_ids: Vec<i32>,
        target_unit_name: String,
    },
    PromoteUnit {
        unit_id: i32,
        promotion_names: Vec<String>,
        save_as_city_default: bool,
    },
    RenameUnit {
        unit_id: i32,
        instance_name: Option<String>,
    },
    SetTileImprovementOrder {
        unit_id: i32,
        improvement_name: Option<String>,
        queued_improvement_name: Option<String>,
    },
    SetRoadConnectionOrder {
        unit_id: i32,
        destination_x: Option<i32>,
        destination_y: Option<i32>,
    },
    SetCityUnitPromotionPreference {
        city_id: String,
        base_unit_name: String,
        enabled: bool,
    },
    SwapUnits {
        unit_id: i32,
        destination_x: i32,
        destination_y: i32,
    },
    QueueConstruction {
        city_id: String,
        construction_name: String,
    },
    QueueConstructionAtTile {
        city_id: String,
        construction_name: String,
        x: i32,
        y: i32,
    },
    SetPerpetualConstruction {
        city_id: String,
        construction_name: String,
    },
    RemoveConstruction {
        city_id: String,
        queue_index: u32,
        expected_construction_name: String,
    },
    MoveConstruction {
        city_id: String,
        from_index: u32,
        to_index: u32,
        expected_construction_name: String,
    },
    PurchaseConstruction {
        city_id: String,
        construction_name: String,
        currency_name: String,
        queue_index: Option<u32>,
    },
    PurchaseConstructionAtTile {
        city_id: String,
        construction_name: String,
        currency_name: String,
        x: i32,
        y: i32,
        queue_index: Option<u32>,
    },
    BuyCityTile {
        city_id: String,
        x: i32,
        y: i32,
    },
    SellBuilding {
        city_id: String,
        building_name: String,
    },
    SetCityGovernance {
        city_id: String,
        action: CityGovernanceAction,
    },
    SetCityTileAssignment {
        city_id: String,
        x: i32,
        y: i32,
        assignment: CityTileAssignment,
    },
    SetSpecialistCount {
        city_id: String,
        specialist_name: String,
        count: u32,
    },
    SetManualSpecialists {
        city_id: String,
        enabled: bool,
    },
    ResetCitizens {
        city_id: String,
    },
    SetAvoidGrowth {
        city_id: String,
        enabled: bool,
    },
    SetCitizenFocus {
        city_id: String,
        focus: CitizenFocus,
    },
    SetResearchPath {
        technology_name: String,
    },
    AdoptPolicy {
        policy_name: String,
    },
    ChooseFreeTechnology {
        technology_name: String,
    },
}

#[derive(Clone, Debug, Deserialize, Serialize, PartialEq, Eq)]
pub struct CommandEnvelope {
    pub protocol_version: u16,
    pub game_id: Uuid,
    pub command_id: Uuid,
    pub expected_revision: u64,
    pub client_observed_state_hash: Option<String>,
    pub command: GameCommand,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct CommitProposal {
    pub previous_revision: u64,
    pub snapshot: Vec<u8>,
    pub canonical_state_hash: String,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, utoipa::ToSchema)]
pub struct CommandAccepted {
    pub game_id: Uuid,
    pub command_id: Uuid,
    pub previous_revision: u64,
    pub committed_revision: u64,
    pub canonical_state_hash: String,
}
