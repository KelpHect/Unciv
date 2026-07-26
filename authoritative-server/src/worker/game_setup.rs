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
pub struct WorkerGameSetup {
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
    pub minutes_until_skip_turn: u32,
    pub minutes_until_force_resign: u32,
    pub minutes_recovered_per_turn: u32,
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::worker::protocol::WorkerOperation;

    #[test]
    fn create_game_operation_contains_only_typed_setup_and_server_seed() {
        let setup = WorkerGameSetup {
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
            minutes_until_skip_turn: 1_440,
            minutes_until_force_resign: 4_320,
            minutes_recovered_per_turn: 1_440,
        };
        let value = serde_json::to_value(WorkerOperation::CreateGame {
            server_seed: 42,
            setup: &setup,
        })
        .unwrap();

        assert_eq!(value["type"], "create_game");
        assert_eq!(value["serverSeed"], 42);
        assert_eq!(value["setup"]["majorCivilizations"], 4);
        assert_eq!(value["setup"]["mapType"], "pangaea");
        assert!(value["setup"].get("rulesetManifestHash").is_none());
        assert!(value.get("snapshot").is_none());
    }
}
