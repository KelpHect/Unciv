use super::*;

#[derive(Deserialize, ToSchema)]
#[serde(deny_unknown_fields)]
pub(super) struct EndTurnRequest {
    pub(super) command_id: uuid::Uuid,
    pub(super) expected_revision: u64,
    pub(super) client_observed_state_hash: Option<String>,
}

#[derive(Deserialize, ToSchema)]
#[serde(deny_unknown_fields)]
pub(super) struct AdvanceAiTurnRequest {
    pub(super) command_id: uuid::Uuid,
    pub(super) expected_revision: u64,
    pub(super) client_observed_state_hash: Option<String>,
}

#[derive(Deserialize, ToSchema)]
#[serde(deny_unknown_fields)]
pub(super) struct ResignRequest {
    pub(super) command_id: uuid::Uuid,
    pub(super) expected_revision: u64,
    pub(super) client_observed_state_hash: Option<String>,
}

#[derive(Deserialize, ToSchema)]
#[serde(deny_unknown_fields)]
pub(super) struct ForceResignRequest {
    pub(super) command_id: uuid::Uuid,
    pub(super) expected_revision: u64,
    pub(super) client_observed_state_hash: Option<String>,
}

#[derive(Deserialize, ToSchema)]
#[serde(deny_unknown_fields)]
pub(super) struct KickMemberRequest {
    pub(super) command_id: uuid::Uuid,
    pub(super) expected_revision: u64,
    pub(super) client_observed_state_hash: Option<String>,
    pub(super) username: String,
}
