use super::*;

#[derive(Debug, Deserialize, ToSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(super) struct ProposeRewindRequest {
    pub request_id: uuid::Uuid,
    pub expected_head_revision: u64,
    pub target_revision: u64,
}

#[derive(Debug, Deserialize, ToSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(super) struct VoteRewindRequest {
    pub approved: bool,
}

#[derive(Debug, Deserialize, utoipa::IntoParams)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(super) struct RewindCheckpointQuery {
    pub limit: Option<u32>,
}
