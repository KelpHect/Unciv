use serde::{Deserialize, Serialize};
use utoipa::ToSchema;

use crate::projection::ProjectedVictory;

/// Full no-fog-of-war projection of the entire game state for match replay.
/// All civilizations' data is visible — no fog-of-war filtering.
#[derive(Clone, Debug, Deserialize, Serialize, ToSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ReplayProjection {
    pub protocol_version: u16,
    pub turn: i32,
    pub current_player_civilization_id: String,
    pub victory: Option<ProjectedVictory>,
    pub major_civilizations: Vec<ReplayCivilization>,
    pub map: ReplayMap,
}

/// Per-civilization summary for replay, with stats visible to all.
#[derive(Clone, Debug, Deserialize, Serialize, ToSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ReplayCivilization {
    pub civilization_id: String,
    pub display_name: String,
    pub human_controlled: bool,
    pub defeated: bool,
    pub gold: i32,
    pub city_count: u32,
    pub unit_count: u32,
    pub population: u32,
    pub technologies_researched: u32,
    pub policies_adopted: u32,
    pub stats_history: Vec<ReplayStatsEntry>,
}

/// One turn's stats for a civilization in the replay.
#[derive(Clone, Debug, Deserialize, Serialize, ToSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ReplayStatsEntry {
    pub turn: i32,
    pub score: i32,
    pub population: i32,
    pub growth: i32,
    pub production: i32,
    pub gold: i32,
    pub territory: i32,
    pub force: i32,
    pub happiness: i32,
    pub technologies: i32,
    pub culture: i32,
}

/// Minimal map data for replay rendering.
#[derive(Clone, Debug, Deserialize, Serialize, ToSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ReplayMap {
    pub world_wrap: bool,
    pub tiles: Vec<ReplayTile>,
}

/// A tile in the replay map, with terrain and optional owner.
#[derive(Clone, Debug, Deserialize, Serialize, ToSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ReplayTile {
    pub x: i32,
    pub y: i32,
    pub base_terrain: String,
    pub terrain_features: Vec<String>,
    pub natural_wonder_name: Option<String>,
    pub owner_civilization_id: Option<String>,
}

impl ReplayProjection {
    pub fn victory_is_consistent(&self) -> bool {
        self.victory.as_ref().is_none_or(|victory| {
            !victory.winning_civilization_id.is_empty()
                && victory.winning_civilization_id.len() <= 128
                && self
                    .major_civilizations
                    .iter()
                    .any(|civ| civ.civilization_id == victory.winning_civilization_id)
                && !victory.victory_type.is_empty()
                && victory.victory_type.len() <= 128
                && victory.victory_turn >= 0
                && victory.victory_turn <= self.turn
        })
    }
}
