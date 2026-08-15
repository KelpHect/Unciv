use serde::{Deserialize, Serialize};
use utoipa::ToSchema;

use crate::{
    CityDispositionAction, CityGovernanceAction, GreatPersonUnitAction, ReligiousBeliefType,
    ReligiousUnitAction, UnitPosture,
};

pub use crate::projection_city_economy::*;
pub use crate::projection_combat::*;
pub use crate::projection_replay::*;
pub use crate::projection_spectator::*;
pub use crate::projection_tiles::*;
pub use crate::projection_wonder_events::*;

/// Player-scoped state returned by the authoritative worker. This deliberately
/// is not a redacted canonical game: fields absent here cannot cross the public
/// Rust API boundary.
#[derive(Clone, Debug, Deserialize, Serialize, ToSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct PlayerProjection {
    pub protocol_version: u16,
    pub civilization_id: String,
    pub turn: i32,
    pub current_player_civilization_id: String,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub active_player_civilization_ids: Vec<String>,
    pub is_current_turn: bool,
    pub victory: Option<ProjectedVictory>,
    pub pending_turn_actions: Vec<PendingEndTurnAction>,
    pub research: ProjectedResearch,
    pub policies: ProjectedPolicies,
    pub gold: i32,
    pub known_civilizations: Vec<String>,
    pub own_cities: Vec<ProjectedCity>,
    pub own_units: Vec<ProjectedUnit>,
    pub explored_tiles: Vec<ProjectedTileVisibility>,
    pub visible_foreign_units: Vec<ProjectedUnit>,
    /// `default` so a worker predating projection 63 still parses; always
    /// serialized, so the Rust and Kotlin wire shapes stay identical.
    #[serde(default)]
    pub visible_foreign_cities: Vec<ProjectedForeignCity>,
    pub pending_city_dispositions: Vec<ProjectedCityDisposition>,
    pub diplomatic_vote_candidates: Vec<String>,
    pub selectable_great_people: Vec<String>,
    pub religion_choice: Option<ProjectedReligionChoice>,
    pub trade_partners: Vec<ProjectedTradePartner>,
    pub pending_trade_requests: Vec<ProjectedTradeRequest>,
    pub diplomacy_partners: Vec<ProjectedDiplomacyPartner>,
    pub diplomacy_prompts: Vec<ProjectedDiplomacyPrompt>,
    pub city_state_partners: Vec<ProjectedCityStatePartner>,
    pub spies: Vec<ProjectedSpy>,
    pub event_prompts: Vec<ProjectedEventPrompt>,
    pub wonder_events: Vec<ProjectedWonderEvent>,
}

#[derive(Clone, Debug, Deserialize, Serialize, ToSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ProjectedVictory {
    pub winning_civilization_id: String,
    pub victory_type: String,
    pub victory_turn: i32,
}

#[derive(Clone, Debug, Deserialize, Serialize, ToSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ProjectedEventPrompt {
    pub prompt_id: String,
    pub event_name: String,
    pub unit_id: Option<i32>,
    pub text: String,
    pub choices: Vec<ProjectedEventChoice>,
}

#[derive(Clone, Debug, Deserialize, Serialize, ToSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ProjectedEventChoice {
    pub choice_id: String,
    pub text: String,
}

#[derive(Clone, Debug, Deserialize, Serialize, ToSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ProjectedSpy {
    pub name: String,
    pub rank: u32,
    pub city_id: Option<String>,
    pub civilization_id: Option<String>,
    pub action: ProjectedSpyAction,
    pub turns_remaining: i32,
    pub available_city_ids: Vec<String>,
    pub can_move_to_hideout: bool,
    pub can_stage_coup: bool,
    pub can_cancel_coup: bool,
}

#[derive(Clone, Copy, Debug, Deserialize, Serialize, ToSchema, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum ProjectedSpyAction {
    None,
    Moving,
    EstablishNetwork,
    Surveillance,
    StealingTech,
    RiggingElections,
    Coup,
    CounterIntelligence,
    Dead,
}

#[derive(Clone, Debug, Deserialize, Serialize, ToSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ProjectedCityStatePartner {
    pub civilization_id: String,
    pub available_gold_gifts: Vec<u32>,
    pub can_pledge_protection: bool,
    pub can_revoke_protection: bool,
    pub tribute_gold_amount: Option<u32>,
    pub can_demand_worker: bool,
    pub improvement_gifts: Vec<ProjectedCityStateImprovementGift>,
    pub can_negotiate_peace: bool,
    pub can_declare_war: bool,
    pub diplomatic_marriage_cost: Option<u32>,
    #[serde(default)]
    pub influence: i32,
    #[serde(default)]
    pub influence_level: ProjectedCityStateInfluenceLevel,
    #[serde(default)]
    pub quests: Vec<ProjectedCityStateQuest>,
}

#[derive(Clone, Copy, Debug, Deserialize, Serialize, ToSchema, PartialEq, Eq, Default)]
#[serde(rename_all = "snake_case")]
pub enum ProjectedCityStateInfluenceLevel {
    Unforgivable,
    Enemy,
    #[default]
    Neutral,
    Friend,
    Ally,
}

#[derive(Clone, Debug, Deserialize, Serialize, ToSchema, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ProjectedCityStateQuest {
    pub quest_name: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub data1: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub data2: Option<String>,
    pub influence: i32,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub remaining_turns: Option<i32>,
    pub is_global: bool,
}

#[derive(Clone, Debug, Deserialize, Serialize, ToSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ProjectedCityStateImprovementGift {
    pub x: i32,
    pub y: i32,
    pub improvement_name: String,
    pub gold_cost: u32,
}

#[derive(Clone, Debug, Deserialize, Serialize, ToSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ProjectedDiplomacyPartner {
    pub civilization_id: String,
    pub adopted_policy_branches: Vec<String>,
    pub can_declare_war: bool,
    pub can_denounce: bool,
    pub can_offer_friendship: bool,
    pub available_demands: Vec<DiplomaticDemand>,
}

#[derive(Clone, Debug, Deserialize, Serialize, ToSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ProjectedDiplomacyPrompt {
    pub prompt_id: String,
    pub requesting_civilization_id: String,
    pub r#type: DiplomacyPromptType,
    pub demand: Option<DiplomaticDemand>,
    pub city_state_civilization_id: Option<String>,
    pub available_city_state_responses: Vec<CityStateProtectionResponse>,
}

#[derive(Clone, Copy, Debug, Deserialize, Serialize, ToSchema, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum DiplomacyPromptType {
    Friendship,
    Demand,
    BulliedProtectedMinor,
    AttackedProtectedMinor,
    AttackedAllyMinor,
}

#[derive(Clone, Copy, Debug, Deserialize, Serialize, ToSchema, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum CityStateProtectionResponse {
    DeclareWar,
    Condemn,
    WithdrawProtection,
}

#[derive(Clone, Copy, Debug, Deserialize, Serialize, ToSchema, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum DiplomaticDemand {
    DontSpyOnUs,
    DoNotSpreadReligion,
    DoNotSettleNearUs,
    DoNotAttackUs,
}

#[derive(Clone, Debug, Deserialize, Serialize, ToSchema, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ProjectedTradePartner {
    pub civilization_id: String,
    pub our_available_offers: Vec<ProjectedTradeOffer>,
    pub their_available_offers: Vec<ProjectedTradeOffer>,
    pub has_pending_outgoing_offer: bool,
}

#[derive(Clone, Debug, Deserialize, Serialize, ToSchema, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ProjectedTradeRequest {
    pub request_id: String,
    pub requesting_civilization_id: String,
    pub trade: ProjectedTrade,
}

#[derive(Clone, Debug, Deserialize, Serialize, ToSchema, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ProjectedTrade {
    pub our_offers: Vec<ProjectedTradeOffer>,
    pub their_offers: Vec<ProjectedTradeOffer>,
}

#[derive(Clone, Debug, Deserialize, Serialize, ToSchema, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ProjectedTradeOffer {
    pub name: String,
    pub r#type: String,
    pub amount: i32,
    pub duration: i32,
}

#[derive(Clone, Debug, Deserialize, Serialize, ToSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ProjectedReligionChoice {
    pub required_belief_types: Vec<ReligiousBeliefType>,
    pub available_beliefs: Vec<ProjectedReligiousBelief>,
    pub available_religion_icons: Vec<String>,
    pub requires_religion_identity: bool,
}

#[derive(Clone, Debug, Deserialize, Serialize, ToSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ProjectedReligiousBelief {
    pub name: String,
    pub r#type: ReligiousBeliefType,
}

#[derive(Clone, Debug, Deserialize, Serialize, ToSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ProjectedCityDisposition {
    pub city_id: String,
    pub city_name: String,
    pub available_actions: Vec<CityDispositionAction>,
}

#[derive(Clone, Debug, Deserialize, Serialize, ToSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ProjectedCity {
    pub id: String,
    pub name: String,
    pub x: i32,
    pub y: i32,
    pub population: i32,
    pub health: i32,
    pub construction_queue: Vec<String>,
    pub available_constructions: Vec<String>,
    pub construction_queue_entries: Vec<ProjectedConstructionQueueEntry>,
    pub construction_options: Vec<ProjectedConstructionOption>,
    pub tile_states: Vec<ProjectedCityTileState>,
    pub tile_purchases: Vec<ProjectedCityTilePurchase>,
    pub tile_batch_purchases: Vec<ProjectedCityTileBatchPurchase>,
    pub assignable_tiles: Vec<ProjectedCityTile>,
    pub manual_specialists: bool,
    pub specialists: Vec<ProjectedSpecialist>,
    pub avoid_growth: bool,
    pub citizen_focus: CitizenFocus,
    pub selectable_citizen_focuses: Vec<CitizenFocus>,
    pub unit_promotion_preferences: Vec<ProjectedUnitPromotionPreference>,
    pub is_puppet: bool,
    pub is_being_razed: bool,
    pub available_governance_actions: Vec<CityGovernanceAction>,
    pub sellable_buildings: Vec<String>,
    pub bombard_targets: Vec<ProjectedBombardTarget>,
}

/// A rival city on a tile the worker already decided this player can see.
///
/// Deliberately much smaller than [`ProjectedCity`]: enough to draw a city and
/// its borders, and nothing about how that city is doing. `owned_tiles` is
/// clipped to the player's own vision by the worker.
#[derive(Clone, Debug, Deserialize, Serialize, ToSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ProjectedForeignCity {
    pub id: String,
    pub name: String,
    pub civilization_id: String,
    pub x: i32,
    pub y: i32,
    #[serde(default)]
    pub owned_tiles: Vec<ProjectedTargetCoordinate>,
}

#[derive(Clone, Debug, Deserialize, Serialize, ToSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ProjectedUnitPromotionPreference {
    pub base_unit_name: String,
    pub enabled: bool,
    pub saved_promotions: Vec<String>,
}

#[derive(Clone, Debug, Deserialize, Serialize, ToSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ProjectedCityTile {
    pub x: i32,
    pub y: i32,
    pub worked: bool,
    pub locked: bool,
}

#[derive(Clone, Debug, Deserialize, Serialize, ToSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ProjectedSpecialist {
    pub name: String,
    pub assigned: i32,
    pub capacity: i32,
}

#[derive(Clone, Copy, Debug, Deserialize, Serialize, ToSchema, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum CitizenFocus {
    NoFocus,
    Manual,
    FoodFocus,
    ProductionFocus,
    GoldFocus,
    ScienceFocus,
    CultureFocus,
    HappinessFocus,
    FaithFocus,
    GoldGrowthFocus,
    ProductionGrowthFocus,
}

#[derive(Clone, Debug, Deserialize, Serialize, ToSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ProjectedResearch {
    pub current_technology: Option<String>,
    pub researched_technologies: Vec<String>,
    pub queue: Vec<String>,
    pub queue_entries: Vec<ProjectedResearchQueueEntry>,
    pub overflow_science: i32,
    pub selectable_targets: Vec<String>,
    pub appendable_targets: Vec<String>,
    pub free_technology_choices: Vec<String>,
    pub completion_prompts: Vec<ProjectedResearchCompletion>,
}

#[derive(Clone, Debug, Deserialize, Serialize, ToSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ProjectedResearchCompletion {
    pub prompt_id: String,
    pub technology_name: String,
}

#[derive(Clone, Debug, Deserialize, Serialize, ToSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ProjectedResearchQueueEntry {
    pub technology_name: String,
    pub stored_science: i32,
    pub cost: i32,
    pub estimated_turns: Option<i32>,
    pub available_actions: Vec<crate::ResearchQueueAction>,
}

impl ProjectedResearch {
    pub(crate) fn is_consistent(&self) -> bool {
        self.current_technology.as_deref() == self.queue.first().map(String::as_str)
            && self.queue.len() == self.queue_entries.len()
            && self.queue.iter().zip(&self.queue_entries).enumerate().all(
                |(index, (technology_name, entry))| {
                    technology_name == &entry.technology_name
                        && entry.stored_science >= 0
                        && entry.cost >= 0
                        && entry.estimated_turns.is_none_or(|turns| turns >= 0)
                        && entry
                            .available_actions
                            .windows(2)
                            .all(|pair| pair[0] < pair[1])
                        && entry.available_actions.iter().all(|action| match action {
                            crate::ResearchQueueAction::MoveToTop
                            | crate::ResearchQueueAction::MoveUp => index > 0,
                            crate::ResearchQueueAction::MoveDown
                            | crate::ResearchQueueAction::MoveToEnd => {
                                index + 1 < self.queue_entries.len()
                            }
                            crate::ResearchQueueAction::Remove => true,
                        })
                },
            )
            && self.overflow_science >= 0
            && self
                .researched_technologies
                .windows(2)
                .all(|pair| pair[0] < pair[1])
            && self.completion_prompts.iter().all(|prompt| {
                prompt.prompt_id.len() == 64
                    && prompt
                        .prompt_id
                        .bytes()
                        .all(|value| value.is_ascii_digit() || (b'a'..=b'f').contains(&value))
                    && !prompt.technology_name.is_empty()
            })
    }
}

#[derive(Clone, Debug, Deserialize, Serialize, ToSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ProjectedPolicies {
    pub stored_culture: i32,
    pub culture_needed_for_next_policy: i32,
    pub free_policies: i32,
    pub adopted_policies: Vec<String>,
    pub selectable_policies: Vec<String>,
}

#[derive(Clone, Debug, Deserialize, Serialize, ToSchema, PartialEq, Eq, PartialOrd, Ord)]
#[serde(rename_all = "snake_case")]
pub enum PendingEndTurnAction {
    PickConstruction,
    PickTechnology,
    PickPolicy,
    MoveSpies,
    FoundOrExpandPantheon,
    FoundReligion,
    EnhanceReligion,
    ReformReligion,
    CastDiplomaticVote,
    PickGreatPerson,
}

#[derive(Clone, Debug, Deserialize, Serialize, ToSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ProjectedUnit {
    pub id: i32,
    pub civilization_id: String,
    pub name: String,
    pub x: i32,
    pub y: i32,
    pub health: i32,
    pub current_movement: Option<f32>,
    pub movement_destination_x: Option<i32>,
    pub movement_destination_y: Option<i32>,
    #[serde(default)]
    pub movement_escort_unit_id: Option<i32>,
    pub automated: bool,
    pub exploring: bool,
    pub posture: Option<UnitPosture>,
    pub promotions: Vec<String>,
    pub promotion_xp: Option<i32>,
    pub next_promotion_xp: Option<i32>,
    pub available_promotions: Vec<String>,
    pub instance_name: Option<String>,
    pub available_postures: Vec<UnitPosture>,
    pub can_disband: bool,
    pub can_pillage: bool,
    pub can_found_city: bool,
    pub can_rename: bool,
    pub paradrop_destinations: Vec<ProjectedMovementDestination>,
    pub available_upgrade_targets: Vec<ProjectedUnitUpgradeTarget>,
    pub move_toward_destinations: Vec<ProjectedMovementDestination>,
    pub available_improvement_orders: Vec<ProjectedImprovementOrderChoice>,
    pub available_road_destinations: Vec<ProjectedMovementDestination>,
    pub improvement_order: Vec<ProjectedImprovementOrderEntry>,
    pub road_connection_destination_x: Option<i32>,
    pub road_connection_destination_y: Option<i32>,
    pub road_connection_path: Vec<ProjectedRoadPathTile>,
    pub available_religious_actions: Vec<ReligiousUnitAction>,
    pub available_great_person_actions: Vec<GreatPersonUnitAction>,
    pub can_gift: bool,
    pub capital_project_name: Option<String>,
    pub available_instant_improvement_actions: Vec<ProjectedInstantImprovementAction>,
    pub available_transform_actions: Vec<ProjectedUnitTransformAction>,
    pub available_trigger_actions: Vec<ProjectedUnitTriggerAction>,
    pub move_destinations: Vec<ProjectedMovementDestination>,
    pub swap_destinations: Vec<ProjectedMovementDestination>,
    pub attack_targets: Vec<ProjectedAttackTarget>,
    pub nuclear_target_candidates: Vec<ProjectedNuclearTarget>,
    pub air_sweep_targets: Vec<ProjectedAirSweepTarget>,
}

#[derive(Clone, Debug, Deserialize, Serialize, ToSchema, PartialEq, Eq, PartialOrd, Ord)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ProjectedMovementDestination {
    pub x: i32,
    pub y: i32,
}

#[derive(Clone, Debug, Deserialize, Serialize, ToSchema, PartialEq, Eq, PartialOrd, Ord)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ProjectedUnitUpgradeTarget {
    pub target_unit_name: String,
    pub gold_cost: i32,
}

#[derive(Clone, Debug, Deserialize, Serialize, ToSchema, PartialEq, Eq, PartialOrd, Ord)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ProjectedImprovementOrderChoice {
    pub improvement_name: Option<String>,
    pub queued_improvement_name: Option<String>,
}

#[derive(Clone, Debug, Deserialize, Serialize, ToSchema, PartialEq, Eq, PartialOrd, Ord)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ProjectedTargetCoordinate {
    pub x: i32,
    pub y: i32,
}

#[derive(Clone, Debug, Deserialize, Serialize, ToSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ProjectedUnitTransformAction {
    pub action_id: String,
    pub target_unit_name: String,
}

#[derive(Clone, Debug, Deserialize, Serialize, ToSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ProjectedUnitTriggerAction {
    pub action_id: String,
    pub title: String,
}

#[derive(Clone, Debug, Deserialize, Serialize, ToSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ProjectedInstantImprovementAction {
    pub action_id: String,
    pub title: String,
}

#[derive(Clone, Debug, Deserialize, Serialize, ToSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ProjectedImprovementOrderEntry {
    pub improvement_name: String,
    pub turns_remaining: i32,
}

#[derive(Clone, Debug, Deserialize, Serialize, ToSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ProjectedRoadPathTile {
    pub x: i32,
    pub y: i32,
}
