use serde::{Deserialize, Serialize};
use utoipa::ToSchema;

#[derive(Clone, Debug, Deserialize, Serialize, ToSchema, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ProjectedNuclearTarget {
    pub x: i32,
    pub y: i32,
    pub blast_radius: i32,
    pub effect_disclosure: ProjectedNuclearEffectDisclosure,
}

#[derive(Clone, Copy, Debug, Deserialize, Serialize, ToSchema, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum ProjectedNuclearEffectDisclosure {
    HiddenUntilCommit,
}

#[derive(Clone, Debug, Deserialize, Serialize, ToSchema, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ProjectedAirSweepTarget {
    pub x: i32,
    pub y: i32,
    pub attacker_base_strength: i32,
    pub attacker_modifiers: Vec<ProjectedCombatModifier>,
    pub attacker_health: i32,
    pub attacker_max_health: i32,
    pub interceptor_disclosure: ProjectedAirSweepInterceptorDisclosure,
}

#[derive(Clone, Copy, Debug, Deserialize, Serialize, ToSchema, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum ProjectedAirSweepInterceptorDisclosure {
    HiddenUntilCommit,
}

#[derive(Clone, Debug, Deserialize, Serialize, ToSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ProjectedAttackTarget {
    pub x: i32,
    pub y: i32,
    pub attack_from_x: i32,
    pub attack_from_y: i32,
    pub preview: ProjectedCombatPreview,
}

#[derive(Clone, Debug, Deserialize, Serialize, ToSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ProjectedBombardTarget {
    pub x: i32,
    pub y: i32,
    pub preview: ProjectedCombatPreview,
}

#[derive(Clone, Debug, Deserialize, Serialize, ToSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ProjectedCombatPreview {
    pub attacker_base_strength: i32,
    pub defender_base_strength: i32,
    pub attacker_effective_strength: i32,
    pub defender_effective_strength: i32,
    pub attacker_modifiers: Vec<ProjectedCombatModifier>,
    pub defender_modifiers: Vec<ProjectedCombatModifier>,
    pub attacker_health: i32,
    pub attacker_max_health: i32,
    pub defender_health: i32,
    pub defender_max_health: i32,
    pub attacker_min_remaining_health: Option<i32>,
    pub attacker_max_remaining_health: Option<i32>,
    pub defender_min_remaining_health: Option<i32>,
    pub defender_max_remaining_health: Option<i32>,
    pub outcome: Option<ProjectedCombatOutcome>,
}

#[derive(Clone, Debug, Deserialize, Serialize, ToSchema, PartialEq, Eq, PartialOrd, Ord)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ProjectedCombatModifier {
    pub label: String,
    pub percent: i32,
}

#[derive(Clone, Copy, Debug, Deserialize, Serialize, ToSchema)]
#[serde(rename_all = "snake_case")]
pub enum ProjectedCombatOutcome {
    Captured,
    Occupied,
    NoEstimate,
}
