use serde::{Deserialize, Serialize};
use utoipa::ToSchema;

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
}

#[derive(Clone, Debug, Deserialize, Serialize, ToSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ProjectedTileVisibility {
    pub x: i32,
    pub y: i32,
    pub visible: bool,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn shared_projection_fixture_is_closed_and_round_trips_semantically() {
        let fixture = include_str!("../../protocol/player-projection-v5.fixture.json");
        let expected: serde_json::Value = serde_json::from_str(fixture).unwrap();
        let projection: PlayerProjection = serde_json::from_value(expected.clone()).unwrap();
        assert_eq!(projection.protocol_version, 3);
        assert_eq!(
            projection.pending_turn_actions,
            [PendingEndTurnAction::PickPolicy]
        );
        assert_eq!(projection.own_cities[0].construction_queue, ["Monument"]);
        assert_eq!(
            projection.research.current_technology.as_deref(),
            Some("Pottery")
        );
        assert_eq!(projection.policies.selectable_policies, ["Tradition"]);
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
