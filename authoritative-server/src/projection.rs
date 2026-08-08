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

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn shared_projection_fixture_is_closed_and_round_trips_semantically() {
        let fixture = include_str!("../../protocol/player-projection-v60.fixture.json");
        let expected: serde_json::Value = serde_json::from_str(fixture).unwrap();
        let projection: PlayerProjection = serde_json::from_value(expected.clone()).unwrap();
        assert_eq!(projection.protocol_version, 3);
        assert_eq!(
            projection.pending_turn_actions,
            [
                PendingEndTurnAction::PickPolicy,
                PendingEndTurnAction::CastDiplomaticVote,
                PendingEndTurnAction::PickGreatPerson,
            ]
        );
        assert_eq!(projection.diplomatic_vote_candidates, ["Greece"]);
        assert_eq!(projection.research.appendable_targets, ["Archery"]);
        assert_eq!(projection.research.queue_entries[0].stored_science, 14);
        assert!(projection.tiles_are_consistent());
        assert_eq!(projection.explored_tiles[0].base_terrain, "Grassland");
        assert_eq!(projection.explored_tiles[0].terrain_features, ["Forest"]);
        assert_eq!(
            projection.explored_tiles[0].resource_name.as_deref(),
            Some("Wheat")
        );
        assert!(
            projection.research.queue_entries[0]
                .available_actions
                .is_empty()
        );
        assert_eq!(
            projection.research.queue_entries[1].available_actions,
            [crate::ResearchQueueAction::Remove]
        );
        assert_eq!(
            projection.research.queue_entries[0].estimated_turns,
            Some(4)
        );
        assert_eq!(projection.research.overflow_science, 3);
        assert_eq!(
            projection.research.completion_prompts[0].technology_name,
            "Mining"
        );
        assert!(projection.research.is_consistent());
        assert_eq!(projection.diplomacy_partners[0].civilization_id, "Greece");
        assert_eq!(
            projection.diplomacy_partners[0].adopted_policy_branches,
            ["Tradition"]
        );
        assert!(projection.diplomacy_is_consistent());
        assert_eq!(
            projection.diplomacy_partners[0].available_demands,
            [DiplomaticDemand::DoNotSettleNearUs]
        );
        assert_eq!(
            projection.diplomacy_prompts[0].r#type,
            DiplomacyPromptType::Friendship
        );
        assert_eq!(projection.city_state_partners[0].civilization_id, "Geneva");
        assert_eq!(
            projection.city_state_partners[0].available_gold_gifts,
            [250]
        );
        assert_eq!(
            projection.selectable_great_people,
            ["Great Engineer", "Great Scientist"]
        );
        let religion_choice = projection.religion_choice.as_ref().unwrap();
        assert_eq!(
            religion_choice.required_belief_types,
            [ReligiousBeliefType::Founder, ReligiousBeliefType::Follower]
        );
        assert!(religion_choice.requires_religion_identity);
        assert_eq!(
            projection.own_units[0].available_religious_actions,
            [ReligiousUnitAction::SpreadReligion]
        );
        assert!(projection.own_units[0].capital_project_name.is_none());
        assert!(projection.unit_actions_are_consistent());
        assert_eq!(projection.own_units[0].current_movement, Some(1.5));
        assert_eq!(projection.visible_foreign_units[0].current_movement, None);
        let mut missing_private_movement = projection.clone();
        missing_private_movement.own_units[0].current_movement = None;
        assert!(!missing_private_movement.unit_actions_are_consistent());
        let mut leaked_private_movement = projection.clone();
        leaked_private_movement.visible_foreign_units[0].current_movement = Some(0.0);
        assert!(!leaked_private_movement.unit_actions_are_consistent());
        assert!(
            projection.visible_foreign_units[0]
                .available_religious_actions
                .is_empty()
        );
        assert_eq!(projection.own_cities[0].construction_queue, ["Monument"]);
        assert!(projection.own_cities[0].assignable_tiles[0].worked);
        assert_eq!(projection.own_cities[0].specialists[0].name, "Scientist");
        assert_eq!(
            projection.own_cities[0].unit_promotion_preferences[0].base_unit_name,
            "Warrior"
        );
        assert_eq!(
            projection.own_cities[0].citizen_focus,
            CitizenFocus::GoldFocus
        );
        assert_eq!(
            projection.own_cities[0].available_governance_actions,
            [CityGovernanceAction::StartRazing]
        );
        assert_eq!(projection.pending_city_dispositions[0].city_name, "Athens");
        assert_eq!(
            projection.pending_city_dispositions[0].available_actions,
            [
                CityDispositionAction::Liberate,
                CityDispositionAction::Annex,
                CityDispositionAction::Puppet,
                CityDispositionAction::Raze,
            ]
        );
        assert_eq!(
            projection.research.current_technology.as_deref(),
            Some("Pottery")
        );
        assert_eq!(projection.policies.selectable_policies, ["Tradition"]);
        assert_eq!(projection.own_units[0].movement_destination_x, Some(7));
        assert_eq!(
            projection.own_units[0].move_destinations,
            [ProjectedMovementDestination { x: 2, y: -1 }]
        );
        assert_eq!(
            projection.own_units[0].swap_destinations,
            [ProjectedMovementDestination { x: 2, y: -1 }]
        );
        assert!(projection.movement_is_consistent());
        let attack = &projection.own_units[0].attack_targets[0];
        assert_eq!(
            (
                attack.x,
                attack.y,
                attack.attack_from_x,
                attack.attack_from_y
            ),
            (2, -1, 3, -1)
        );
        assert_eq!(attack.preview.attacker_effective_strength, 11);
        let bombard = &projection.own_cities[0].bombard_targets[0];
        assert_eq!((bombard.x, bombard.y), (2, -1));
        assert_eq!(bombard.preview.defender_min_remaining_health, Some(70));
        assert!(projection.combat_is_consistent());
        assert!(projection.own_units[0].automated);
        assert!(!projection.own_units[0].exploring);
        assert_eq!(projection.own_units[0].posture, Some(UnitPosture::Fortify));
        assert_eq!(projection.own_units[0].promotions, ["Drill I"]);
        assert_eq!(projection.own_units[0].promotion_xp, Some(12));
        assert_eq!(projection.own_units[0].next_promotion_xp, Some(30));
        assert_eq!(
            projection.own_units[0].instance_name.as_deref(),
            Some("First Mission")
        );
        assert_eq!(projection.own_units[0].improvement_order.len(), 2);
        assert_eq!(
            projection.own_units[0].road_connection_destination_x,
            Some(8)
        );
        assert_eq!(projection.own_units[0].road_connection_path.len(), 4);
        assert_eq!(
            projection.visible_foreign_units[0].promotions,
            Vec::<String>::new()
        );
        assert_eq!(projection.visible_foreign_units[0].promotion_xp, None);
        assert_eq!(projection.visible_foreign_units[0].instance_name, None);
        assert!(
            projection.visible_foreign_units[0]
                .improvement_order
                .is_empty()
        );
        assert!(
            projection.visible_foreign_units[0]
                .road_connection_path
                .is_empty()
        );
        assert_eq!(
            projection.visible_foreign_units[0].movement_destination_x,
            None
        );
        assert!(!projection.visible_foreign_units[0].automated);
        assert!(!projection.visible_foreign_units[0].exploring);
        assert_eq!(projection.visible_foreign_units[0].posture, None);
        assert!(
            projection.visible_foreign_units[0]
                .move_destinations
                .is_empty()
        );
        assert!(
            projection.visible_foreign_units[0]
                .swap_destinations
                .is_empty()
        );
        assert!(
            projection.visible_foreign_units[0]
                .attack_targets
                .is_empty()
        );
        assert!(
            projection.visible_foreign_units[0]
                .nuclear_target_candidates
                .is_empty()
        );
        assert!(
            projection.visible_foreign_units[0]
                .air_sweep_targets
                .is_empty()
        );
        assert_eq!(serde_json::to_value(projection).unwrap(), expected);

        let mut unknown = expected;
        unknown["canonicalGameInfo"] = serde_json::json!({"secret": true});
        assert!(serde_json::from_value::<PlayerProjection>(unknown).is_err());

        let unknown_action = serde_json::from_str::<serde_json::Value>(fixture)
            .unwrap()
            .to_string()
            .replace("pick_policy", "replace_canonical_state");
        assert!(serde_json::from_str::<PlayerProjection>(&unknown_action).is_err());
    }

    #[test]
    fn terminal_victory_is_bounded_and_disables_turn_actions() {
        let fixture = include_str!("../../protocol/player-projection-v60.fixture.json");
        let mut projection: PlayerProjection = serde_json::from_str(fixture).unwrap();
        projection.is_current_turn = false;
        projection.pending_turn_actions.clear();
        projection.victory = Some(ProjectedVictory {
            winning_civilization_id: "Rome".to_owned(),
            victory_type: "Domination".to_owned(),
            victory_turn: projection.turn,
        });
        assert!(projection.victory_is_consistent());

        projection.is_current_turn = true;
        assert!(!projection.victory_is_consistent());
        projection.is_current_turn = false;
        projection.victory.as_mut().unwrap().victory_type = String::new();
        assert!(!projection.victory_is_consistent());
    }

    #[test]
    fn inconsistent_research_queue_metadata_fails_semantic_validation() {
        let fixture = include_str!("../../protocol/player-projection-v60.fixture.json");
        let mut projection: PlayerProjection = serde_json::from_str(fixture).unwrap();
        projection.research.queue_entries[0].technology_name = "Writing".into();
        assert!(!projection.research.is_consistent());
        projection.research.queue_entries[0].technology_name = "Pottery".into();
        projection.research.queue_entries[0].cost = -1;
        assert!(!projection.research.is_consistent());
        projection.research.queue_entries[0].cost = 35;
        projection.research.researched_technologies.reverse();
        assert!(!projection.research.is_consistent());
    }

    #[test]
    fn tile_metadata_rejects_hidden_mutations_and_incoherent_resources() {
        let fixture = include_str!("../../protocol/player-projection-v60.fixture.json");
        let projection: PlayerProjection = serde_json::from_str(fixture).unwrap();

        let mut hidden_improvement = projection.clone();
        hidden_improvement.explored_tiles[2].improvement_name = Some("Secret Mine".into());
        hidden_improvement.explored_tiles[2].improvement_pillaged = Some(false);
        assert!(!hidden_improvement.tiles_are_consistent());

        let mut resource_amount_without_identity = projection.clone();
        resource_amount_without_identity.explored_tiles[1].resource_amount = Some(4);
        assert!(!resource_amount_without_identity.tiles_are_consistent());

        let mut reordered_features = projection;
        reordered_features.explored_tiles[0].terrain_features =
            vec!["Marsh".into(), "Forest".into()];
        assert!(!reordered_features.tiles_are_consistent());
    }

    #[test]
    fn movement_metadata_rejects_hidden_unsorted_foreign_and_out_of_turn_options() {
        let fixture = include_str!("../../protocol/player-projection-v60.fixture.json");
        let projection: PlayerProjection = serde_json::from_str(fixture).unwrap();

        let mut hidden = projection.clone();
        hidden.own_units[0].move_destinations[0] = ProjectedMovementDestination { x: 5, y: -1 };
        assert!(!hidden.movement_is_consistent());

        let mut unseen_swap = projection.clone();
        unseen_swap.own_units[0].swap_destinations[0] = ProjectedMovementDestination { x: 9, y: 9 };
        assert!(!unseen_swap.movement_is_consistent());

        let mut duplicate = projection.clone();
        duplicate.own_units[0]
            .move_destinations
            .push(ProjectedMovementDestination { x: 2, y: -1 });
        assert!(!duplicate.movement_is_consistent());

        let mut foreign = projection.clone();
        foreign.visible_foreign_units[0]
            .swap_destinations
            .push(ProjectedMovementDestination { x: 2, y: -1 });
        assert!(!foreign.movement_is_consistent());

        let mut out_of_turn = projection;
        out_of_turn.is_current_turn = false;
        assert!(!out_of_turn.movement_is_consistent());
    }
}
