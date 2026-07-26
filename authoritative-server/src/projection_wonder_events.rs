use serde::{Deserialize, Serialize};
use utoipa::ToSchema;

#[derive(Clone, Debug, Deserialize, Serialize, ToSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ProjectedWonderEvent {
    pub completion_turn: i32,
    pub wonder_name: String,
    pub effect_summary: String,
    pub builder_civilization_id: Option<String>,
    pub city_id: Option<String>,
    pub city_name: Option<String>,
    pub x: Option<i32>,
    pub y: Option<i32>,
}

impl ProjectedWonderEvent {
    pub(super) fn is_consistent(&self, current_turn: i32) -> bool {
        let location_fields = [
            self.city_id.is_some(),
            self.city_name.is_some(),
            self.x.is_some(),
            self.y.is_some(),
        ];
        (0..=current_turn).contains(&self.completion_turn)
            && !self.wonder_name.is_empty()
            && self.wonder_name.chars().count() <= 128
            && self.effect_summary.chars().count() <= 4_096
            && self
                .builder_civilization_id
                .as_ref()
                .is_none_or(|id| !id.is_empty() && id.chars().count() <= 128)
            && (location_fields.iter().all(|present| *present)
                || location_fields.iter().all(|present| !present))
            && self
                .city_id
                .as_ref()
                .is_none_or(|id| !id.is_empty() && id.chars().count() <= 128)
            && self
                .city_name
                .as_ref()
                .is_none_or(|name| !name.is_empty() && name.chars().count() <= 128)
    }
}
