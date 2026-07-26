use serde::{Deserialize, Serialize};
use utoipa::ToSchema;

/// Explored, player-scoped map knowledge. Mutable infrastructure is present
/// only while currently visible; terrain and technology-revealed resources are
/// durable player knowledge.
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
    pub base_terrain: String,
    pub terrain_features: Vec<String>,
    pub natural_wonder_name: Option<String>,
    pub resource_name: Option<String>,
    pub resource_amount: Option<i32>,
}
