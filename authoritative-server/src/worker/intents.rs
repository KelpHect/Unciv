pub struct SetCityGovernanceIntent<'a> {
    pub actor_civilization_id: &'a str,
    pub city_id: &'a str,
    pub action: crate::CityGovernanceAction,
}

pub struct ResolveCityDispositionIntent<'a> {
    pub actor_civilization_id: &'a str,
    pub city_id: &'a str,
    pub action: crate::CityDispositionAction,
}

pub struct CastDiplomaticVoteIntent<'a> {
    pub actor_civilization_id: &'a str,
    pub candidate_civilization_id: Option<&'a str>,
}

pub struct ChooseGreatPersonIntent<'a> {
    pub actor_civilization_id: &'a str,
    pub unit_name: &'a str,
}

pub struct UseGreatPersonUnitIntent<'a> {
    pub actor_civilization_id: &'a str,
    pub unit_id: i32,
    pub action: crate::GreatPersonUnitAction,
}

pub struct GiftUnitIntent<'a> {
    pub actor_civilization_id: &'a str,
    pub unit_id: i32,
}

pub struct AddUnitToCapitalProjectIntent<'a> {
    pub actor_civilization_id: &'a str,
    pub unit_id: i32,
}

pub struct TransformUnitIntent<'a> {
    pub actor_civilization_id: &'a str,
    pub unit_id: i32,
    pub action_id: &'a str,
}

pub struct TriggerUnitUniqueIntent<'a> {
    pub actor_civilization_id: &'a str,
    pub unit_id: i32,
    pub action_id: &'a str,
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

pub struct SetAvoidGrowthIntent<'a> {
    pub actor_civilization_id: &'a str,
    pub city_id: &'a str,
    pub enabled: bool,
}

pub struct SetCitizenFocusIntent<'a> {
    pub actor_civilization_id: &'a str,
    pub city_id: &'a str,
    pub focus: crate::CitizenFocus,
}

pub struct SetResearchPathIntent<'a> {
    pub actor_civilization_id: &'a str,
    pub technology_name: &'a str,
    pub append: bool,
}

pub struct ManageResearchQueueIntent<'a> {
    pub actor_civilization_id: &'a str,
    pub technology_name: &'a str,
    pub queue_index: u32,
    pub action: crate::ResearchQueueAction,
}

pub struct AdoptPolicyIntent<'a> {
    pub actor_civilization_id: &'a str,
    pub policy_name: &'a str,
}

pub struct ChooseFreeTechnologyIntent<'a> {
    pub actor_civilization_id: &'a str,
    pub technology_name: &'a str,
}

pub struct AcknowledgeResearchCompletionIntent<'a> {
    pub actor_civilization_id: &'a str,
    pub prompt_id: &'a str,
}
use crate::UnitPosture;

pub struct MoveSpyIntent<'a> {
    pub actor_civilization_id: &'a str,
    pub spy_name: &'a str,
    pub city_id: Option<&'a str>,
}

pub struct SetSpyCoupIntent<'a> {
    pub actor_civilization_id: &'a str,
    pub spy_name: &'a str,
    pub enabled: bool,
}
pub struct ResolveEventChoiceIntent<'a> {
    pub actor_civilization_id: &'a str,
    pub prompt_id: &'a str,
    pub choice_id: &'a str,
}

pub struct MoveUnitIntent<'a> {
    pub actor_civilization_id: &'a str,
    pub unit_id: i32,
    pub destination_x: i32,
    pub destination_y: i32,
    pub escort_unit_id: Option<i32>,
}
pub struct MoveUnitTowardIntent<'a> {
    pub actor_civilization_id: &'a str,
    pub unit_id: i32,
    pub destination_x: i32,
    pub destination_y: i32,
    pub escort_unit_id: Option<i32>,
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
pub struct ManageConstructionQueuesIntent<'a> {
    pub actor_civilization_id: &'a str,
    pub city_id: &'a str,
    pub construction_name: &'a str,
    pub queue_index: Option<u32>,
    pub action: crate::ConstructionQueueAction,
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
pub struct BuyCityTileBatchIntent<'a> {
    pub actor_civilization_id: &'a str,
    pub city_id: &'a str,
    pub ring: u32,
}
pub struct SellBuildingIntent<'a> {
    pub actor_civilization_id: &'a str,
    pub city_id: &'a str,
    pub building_name: &'a str,
}
