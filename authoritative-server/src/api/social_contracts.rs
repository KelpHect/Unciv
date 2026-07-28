use super::*;

#[derive(Deserialize, ToSchema)]
#[serde(deny_unknown_fields)]
pub(super) struct FriendRequest {
    pub(super) request_id: uuid::Uuid,
    pub(super) username: String,
}
