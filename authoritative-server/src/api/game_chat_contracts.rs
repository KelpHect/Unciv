use super::*;

#[derive(Deserialize, utoipa::IntoParams)]
#[serde(deny_unknown_fields)]
pub(super) struct GameChatQuery {
    pub(super) before: Option<uuid::Uuid>,
    pub(super) limit: Option<u32>,
}

#[derive(Deserialize, ToSchema)]
#[serde(deny_unknown_fields)]
pub(super) struct PostGameChatRequest {
    pub(super) message_id: uuid::Uuid,
    pub(super) body: String,
}
