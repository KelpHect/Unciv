use serde::{Deserialize, Serialize};
use utoipa::ToSchema;

use crate::{ConstructionQueueAction, projection::ProjectedTargetCoordinate};

#[derive(Clone, Debug, Deserialize, Serialize, ToSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ProjectedConstructionQueueEntry {
    pub name: String,
    pub stored_production: i32,
    pub production_cost: Option<i32>,
    pub estimated_turns: Option<i32>,
    pub purchases: Vec<ProjectedConstructionPurchase>,
    pub available_actions: Vec<ConstructionQueueAction>,
}

#[derive(Clone, Debug, Deserialize, Serialize, ToSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ProjectedConstructionOption {
    pub name: String,
    pub queueable: bool,
    pub stored_production: i32,
    pub production_cost: Option<i32>,
    pub estimated_turns: Option<i32>,
    pub placement_targets: Vec<ProjectedTargetCoordinate>,
    pub purchases: Vec<ProjectedConstructionPurchase>,
    pub available_actions: Vec<ConstructionQueueAction>,
}

#[derive(Clone, Debug, Deserialize, Serialize, ToSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ProjectedConstructionPurchase {
    pub currency: String,
    pub cost: i32,
    pub available_amount: i32,
    pub allowed: bool,
    pub requires_tile: bool,
    pub legal_targets: Vec<ProjectedTargetCoordinate>,
}

#[derive(Clone, Debug, Deserialize, Serialize, ToSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ProjectedCityTileState {
    pub x: i32,
    pub y: i32,
    pub owned_by_actor: bool,
    pub owning_city_id: Option<String>,
    pub working_city_id: Option<String>,
    pub worked: bool,
    pub locked: bool,
}

#[derive(Clone, Debug, Deserialize, Serialize, ToSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ProjectedCityTilePurchase {
    pub x: i32,
    pub y: i32,
    pub gold_cost: i32,
    pub affordable: bool,
}
