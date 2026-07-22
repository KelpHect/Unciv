use serde::{Deserialize, Serialize};
use utoipa::ToSchema;

use crate::{
    CityDispositionAction, CityGovernanceAction, ReligiousBeliefType, ReligiousUnitAction,
    UnitPosture,
};

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
    pub is_current_turn: bool,
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
    pub queue: Vec<String>,
    pub selectable_targets: Vec<String>,
    pub free_technology_choices: Vec<String>,
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

#[derive(Clone, Debug, Deserialize, Serialize, ToSchema, PartialEq, Eq)]
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
    pub current_movement: f32,
    pub movement_destination_x: Option<i32>,
    pub movement_destination_y: Option<i32>,
    pub automated: bool,
    pub exploring: bool,
    pub posture: Option<UnitPosture>,
    pub promotions: Vec<String>,
    pub promotion_xp: Option<i32>,
    pub next_promotion_xp: Option<i32>,
    pub available_promotions: Vec<String>,
    pub instance_name: Option<String>,
    pub improvement_order: Vec<ProjectedImprovementOrderEntry>,
    pub road_connection_destination_x: Option<i32>,
    pub road_connection_destination_y: Option<i32>,
    pub road_connection_path: Vec<ProjectedRoadPathTile>,
    pub available_religious_actions: Vec<ReligiousUnitAction>,
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

#[derive(Clone, Debug, Deserialize, Serialize, ToSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ProjectedTileVisibility {
    pub x: i32,
    pub y: i32,
    pub visible: bool,
    pub improvement_name: Option<String>,
    pub improvement_pillaged: Option<bool>,
    pub road_status: Option<String>,
    pub road_pillaged: Option<bool>,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn shared_projection_fixture_is_closed_and_round_trips_semantically() {
        let fixture = include_str!("../../protocol/player-projection-v30.fixture.json");
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
        assert_eq!(projection.diplomacy_partners[0].civilization_id, "Greece");
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
}
