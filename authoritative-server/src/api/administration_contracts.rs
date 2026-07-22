use super::*;

#[derive(Deserialize, ToSchema)]
#[serde(deny_unknown_fields)]
pub(super) struct TransferOwnershipRequest {
    pub(super) operation_id: uuid::Uuid,
    pub(super) username: String,
}

#[derive(Deserialize, ToSchema)]
#[serde(deny_unknown_fields)]
pub(super) struct GameAdminOperationRequest {
    pub(super) operation_id: uuid::Uuid,
}
