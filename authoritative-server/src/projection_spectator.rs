use serde::{Deserialize, Serialize};
use utoipa::ToSchema;

#[derive(Clone, Debug, Deserialize, Serialize, ToSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct SpectatorProjection {
    pub protocol_version: u16,
    pub turn: i32,
    pub current_player_civilization_id: String,
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
