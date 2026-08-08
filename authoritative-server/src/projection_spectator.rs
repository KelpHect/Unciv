use serde::{Deserialize, Serialize};
use utoipa::ToSchema;

#[derive(Clone, Debug, Deserialize, Serialize, ToSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct SpectatorProjection {
    pub protocol_version: u16,
    pub turn: i32,
    pub current_player_civilization_id: String,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub active_player_civilization_ids: Vec<String>,
    pub victory: Option<crate::projection::ProjectedVictory>,
    pub major_civilizations: Vec<SpectatorCivilization>,
}

#[derive(Clone, Debug, Deserialize, Serialize, ToSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct SpectatorCivilization {
    pub civilization_id: String,
    pub display_name: String,
    pub human_controlled: bool,
    pub defeated: bool,
}

impl SpectatorProjection {
    pub fn victory_is_consistent(&self) -> bool {
        self.victory.as_ref().is_none_or(|victory| {
            !victory.winning_civilization_id.is_empty()
                && victory.winning_civilization_id.chars().count() <= 128
                && self.major_civilizations.iter().any(|civilization| {
                    civilization.civilization_id == victory.winning_civilization_id
                })
                && !victory.victory_type.is_empty()
                && victory.victory_type.chars().count() <= 128
                && victory.victory_turn >= 0
                && victory.victory_turn <= self.turn
        })
    }
}
