use serde::{Deserialize, Serialize};

#[derive(Clone, Copy, Debug, Deserialize, PartialEq, Eq, Serialize, utoipa::ToSchema)]
#[serde(rename_all = "snake_case")]
pub enum GeneratedMapType {
    Pangaea,
    SmallContinents,
    Perlin,
    Fractal,
    ContinentAndIslands,
    Archipelago,
    TwoContinents,
    ThreeContinents,
    InnerSea,
    Lakes,
    FourCorners,
    Spiral,
    Boreal,
}

#[derive(Clone, Copy, Debug, Deserialize, PartialEq, Eq, Serialize, utoipa::ToSchema)]
#[serde(rename_all = "snake_case")]
pub enum GeneratedMapShape {
    Rectangular,
    Hexagonal,
    FlatEarth,
}

#[derive(Clone, Copy, Debug, Default, Deserialize, PartialEq, Eq, Serialize, utoipa::ToSchema)]
#[serde(rename_all = "snake_case")]
pub enum MirroringType {
    #[default]
    None,
    TopBottom,
    LeftRight,
    AroundCenterTile,
    FourWay,
}

fn default_tiles_per_biome_area() -> u8 {
    6
}

fn default_max_coast_extension() -> u8 {
    2
}

fn default_elevation_exponent() -> f32 {
    0.7
}

fn default_temperature_intensity() -> f32 {
    0.6
}

fn default_vegetation_richness() -> f32 {
    0.4
}

fn default_rare_features_richness() -> f32 {
    0.05
}

fn default_resource_richness() -> f32 {
    0.1
}

#[derive(Clone, Copy, Debug, Deserialize, PartialEq, Eq, Serialize, utoipa::ToSchema)]
#[serde(rename_all = "snake_case")]
pub enum GeneratedMapSize {
    Tiny,
    Small,
    Medium,
    Large,
    Huge,
}

#[derive(Clone, Copy, Debug, Deserialize, PartialEq, Eq, Serialize, utoipa::ToSchema)]
#[serde(rename_all = "snake_case")]
pub enum MapResourceDensity {
    Sparse,
    Default,
    Abundant,
}

#[derive(Clone, Copy, Debug, Deserialize, PartialEq, Eq, Serialize, utoipa::ToSchema)]
#[serde(rename_all = "snake_case")]
pub enum BarbarianMode {
    Disabled,
    Normal,
    Raging,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct WorkerLobbyParticipant {
    pub account_id: String,
    pub civilization_id: String,
}

pub struct WorkerLobbyReconfiguration<'a> {
    pub game_id: &'a str,
    pub server_seed: i64,
    pub setup: &'a WorkerGameSetup,
    pub participants: &'a [WorkerLobbyParticipant],
}

#[derive(Clone, Debug, Deserialize, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct WorkerGameSetup {
    pub owner_civilization_id: String,
    pub difficulty: String,
    pub speed: String,
    pub starting_era: String,
    pub victory_types: Vec<String>,
    pub major_civilizations: u8,
    pub city_states: u8,
    pub max_turns: u16,
    pub map_type: GeneratedMapType,
    pub map_shape: GeneratedMapShape,
    pub map_size: GeneratedMapSize,
    pub map_resources: MapResourceDensity,
    pub barbarians: BarbarianMode,
    pub one_city_challenge: bool,
    pub nuclear_weapons_enabled: bool,
    pub espionage_enabled: bool,
    pub no_start_bias: bool,
    pub shuffle_player_order: bool,
    pub no_city_razing: bool,
    pub world_wrap: bool,
    pub strategic_balance: bool,
    pub legendary_start: bool,
    pub no_ruins: bool,
    pub no_natural_wonders: bool,
    #[serde(default)]
    pub map_seed: Option<i64>,
    #[serde(default)]
    pub mirroring: MirroringType,
    #[serde(default = "default_tiles_per_biome_area")]
    pub tiles_per_biome_area: u8,
    #[serde(default = "default_max_coast_extension")]
    pub max_coast_extension: u8,
    #[serde(default = "default_elevation_exponent")]
    pub elevation_exponent: f32,
    #[serde(default = "default_temperature_intensity")]
    pub temperature_intensity: f32,
    #[serde(default)]
    pub temperature_shift: f32,
    #[serde(default = "default_vegetation_richness")]
    pub vegetation_richness: f32,
    #[serde(default = "default_rare_features_richness")]
    pub rare_features_richness: f32,
    #[serde(default = "default_resource_richness")]
    pub resource_richness: f32,
    #[serde(default)]
    pub water_threshold: f32,
}

impl Default for WorkerGameSetup {
    fn default() -> Self {
        Self {
            owner_civilization_id: String::new(),
            difficulty: String::new(),
            speed: String::new(),
            starting_era: String::new(),
            victory_types: Vec::new(),
            major_civilizations: 2,
            city_states: 0,
            max_turns: 500,
            map_type: GeneratedMapType::Pangaea,
            map_shape: GeneratedMapShape::Rectangular,
            map_size: GeneratedMapSize::Tiny,
            map_resources: MapResourceDensity::Default,
            barbarians: BarbarianMode::Normal,
            one_city_challenge: false,
            nuclear_weapons_enabled: true,
            espionage_enabled: true,
            no_start_bias: false,
            shuffle_player_order: false,
            no_city_razing: false,
            world_wrap: false,
            strategic_balance: false,
            legendary_start: false,
            no_ruins: false,
            no_natural_wonders: false,
            map_seed: None,
            mirroring: MirroringType::None,
            tiles_per_biome_area: default_tiles_per_biome_area(),
            max_coast_extension: default_max_coast_extension(),
            elevation_exponent: default_elevation_exponent(),
            temperature_intensity: default_temperature_intensity(),
            temperature_shift: 0.0,
            vegetation_richness: default_vegetation_richness(),
            rare_features_richness: default_rare_features_richness(),
            resource_richness: default_resource_richness(),
            water_threshold: 0.0,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::worker::protocol::WorkerOperation;

    #[test]
    fn create_game_operation_contains_only_typed_setup_and_server_seed() {
        let setup = WorkerGameSetup {
            owner_civilization_id: "Rome".to_owned(),
            difficulty: "Prince".to_owned(),
            speed: "Standard".to_owned(),
            starting_era: "Ancient era".to_owned(),
            victory_types: vec!["Domination".to_owned()],
            major_civilizations: 4,
            city_states: 6,
            max_turns: 500,
            map_type: GeneratedMapType::Pangaea,
            map_shape: GeneratedMapShape::Hexagonal,
            map_size: GeneratedMapSize::Medium,
            map_resources: MapResourceDensity::Default,
            barbarians: BarbarianMode::Normal,
            one_city_challenge: false,
            nuclear_weapons_enabled: true,
            espionage_enabled: true,
            no_start_bias: false,
            shuffle_player_order: false,
            no_city_razing: false,
            world_wrap: false,
            strategic_balance: false,
            legendary_start: false,
            no_ruins: false,
            no_natural_wonders: false,
            map_seed: Some(1234),
            mirroring: MirroringType::None,
            tiles_per_biome_area: 6,
            max_coast_extension: 2,
            elevation_exponent: 0.7,
            temperature_intensity: 0.6,
            temperature_shift: 0.0,
            vegetation_richness: 0.4,
            rare_features_richness: 0.05,
            resource_richness: 0.1,
            water_threshold: 0.0,
        };
        let value = serde_json::to_value(WorkerOperation::CreateGame {
            game_id: "00000000-0000-4000-8000-000000000001",
            server_seed: 42,
            setup: &setup,
        })
        .unwrap();

        assert_eq!(value["type"], "create_game");
        assert_eq!(value["gameId"], "00000000-0000-4000-8000-000000000001");
        assert_eq!(value["serverSeed"], 42);
        assert_eq!(value["setup"]["majorCivilizations"], 4);
        assert_eq!(value["setup"]["ownerCivilizationId"], "Rome");
        assert_eq!(value["setup"]["mapType"], "pangaea");
        assert_eq!(value["setup"]["mapSeed"], 1234);
        assert!(value["setup"].get("rulesetManifestHash").is_none());
        assert!(value.get("snapshot").is_none());
    }
}
