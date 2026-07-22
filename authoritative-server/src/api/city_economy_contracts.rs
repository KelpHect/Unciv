use serde::Deserialize;
use utoipa::ToSchema;

#[derive(Deserialize, ToSchema)]
#[serde(deny_unknown_fields)]
pub(super) struct QueueConstructionRequest {
    pub(super) command_id: uuid::Uuid,
    pub(super) expected_revision: u64,
    pub(super) client_observed_state_hash: Option<String>,
    pub(super) city_id: String,
    pub(super) construction_name: String,
}

#[derive(Deserialize, ToSchema)]
#[serde(deny_unknown_fields)]
pub(super) struct QueueConstructionAtTileRequest {
    pub(super) command_id: uuid::Uuid,
    pub(super) expected_revision: u64,
    pub(super) client_observed_state_hash: Option<String>,
    pub(super) city_id: String,
    pub(super) construction_name: String,
    pub(super) x: i32,
    pub(super) y: i32,
}

#[derive(Deserialize, ToSchema)]
#[serde(deny_unknown_fields)]
pub(super) struct SetPerpetualConstructionRequest {
    pub(super) command_id: uuid::Uuid,
    pub(super) expected_revision: u64,
    pub(super) client_observed_state_hash: Option<String>,
    pub(super) city_id: String,
    pub(super) construction_name: String,
}

#[derive(Deserialize, ToSchema)]
#[serde(deny_unknown_fields)]
pub(super) struct RemoveConstructionRequest {
    pub(super) command_id: uuid::Uuid,
    pub(super) expected_revision: u64,
    pub(super) client_observed_state_hash: Option<String>,
    pub(super) city_id: String,
    pub(super) queue_index: u32,
    pub(super) expected_construction_name: String,
}

#[derive(Deserialize, ToSchema)]
#[serde(deny_unknown_fields)]
pub(super) struct MoveConstructionRequest {
    pub(super) command_id: uuid::Uuid,
    pub(super) expected_revision: u64,
    pub(super) client_observed_state_hash: Option<String>,
    pub(super) city_id: String,
    pub(super) from_index: u32,
    pub(super) to_index: u32,
    pub(super) expected_construction_name: String,
}

#[derive(Deserialize, ToSchema)]
#[serde(deny_unknown_fields)]
pub(super) struct ManageConstructionQueuesRequest {
    pub(super) command_id: uuid::Uuid,
    pub(super) expected_revision: u64,
    pub(super) client_observed_state_hash: Option<String>,
    pub(super) city_id: String,
    pub(super) construction_name: String,
    pub(super) queue_index: Option<u32>,
    pub(super) action: unciv_authoritative_server::ConstructionQueueAction,
}

#[derive(Deserialize, ToSchema)]
#[serde(deny_unknown_fields)]
pub(super) struct PurchaseConstructionRequest {
    pub(super) command_id: uuid::Uuid,
    pub(super) expected_revision: u64,
    pub(super) client_observed_state_hash: Option<String>,
    pub(super) city_id: String,
    pub(super) construction_name: String,
    pub(super) currency_name: String,
    pub(super) queue_index: Option<u32>,
}

#[derive(Deserialize, ToSchema)]
#[serde(deny_unknown_fields)]
pub(super) struct PurchaseConstructionAtTileRequest {
    pub(super) command_id: uuid::Uuid,
    pub(super) expected_revision: u64,
    pub(super) client_observed_state_hash: Option<String>,
    pub(super) city_id: String,
    pub(super) construction_name: String,
    pub(super) currency_name: String,
    pub(super) x: i32,
    pub(super) y: i32,
    pub(super) queue_index: Option<u32>,
}

#[derive(Deserialize, ToSchema)]
#[serde(deny_unknown_fields)]
pub(super) struct BuyCityTileRequest {
    pub(super) command_id: uuid::Uuid,
    pub(super) expected_revision: u64,
    pub(super) client_observed_state_hash: Option<String>,
    pub(super) city_id: String,
    pub(super) x: i32,
    pub(super) y: i32,
}

#[derive(Deserialize, ToSchema)]
#[serde(deny_unknown_fields)]
pub(super) struct SellBuildingRequest {
    pub(super) command_id: uuid::Uuid,
    pub(super) expected_revision: u64,
    pub(super) client_observed_state_hash: Option<String>,
    pub(super) city_id: String,
    pub(super) building_name: String,
}
