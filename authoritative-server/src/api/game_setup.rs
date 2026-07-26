use super::*;
use unciv_authoritative_server::worker::{
    BarbarianMode, GeneratedMapShape, GeneratedMapSize, GeneratedMapType, MapResourceDensity,
    WorkerGameSetup,
};

#[derive(Deserialize, ToSchema)]
#[serde(deny_unknown_fields)]
pub(super) struct CreateGameSetupRequest {
    difficulty: String,
    speed: String,
    starting_era: String,
    victory_types: Vec<String>,
    major_civilizations: u8,
    city_states: u8,
    max_turns: u16,
    map_type: GeneratedMapType,
    map_shape: GeneratedMapShape,
    map_size: GeneratedMapSize,
    map_resources: MapResourceDensity,
    barbarians: BarbarianMode,
    one_city_challenge: bool,
    nuclear_weapons_enabled: bool,
    espionage_enabled: bool,
    no_start_bias: bool,
    shuffle_player_order: bool,
    no_city_razing: bool,
    world_wrap: bool,
    strategic_balance: bool,
    legendary_start: bool,
    no_ruins: bool,
    no_natural_wonders: bool,
    minutes_until_skip_turn: u32,
    minutes_until_force_resign: u32,
    minutes_recovered_per_turn: u32,
}

impl CreateGameSetupRequest {
    pub(super) fn validate(self) -> Result<WorkerGameSetup, ApiError> {
        let names_are_bounded = [&self.difficulty, &self.speed, &self.starting_era]
            .into_iter()
            .all(|name| bounded_name(name));
        let victories_are_bounded = !self.victory_types.is_empty()
            && self.victory_types.len() <= 16
            && self.victory_types.iter().all(|name| bounded_name(name))
            && self
                .victory_types
                .iter()
                .collect::<std::collections::HashSet<_>>()
                .len()
                == self.victory_types.len();
        if !names_are_bounded
            || !victories_are_bounded
            || !(2..=16).contains(&self.major_civilizations)
            || self.city_states > 64
            || !(100..=1500).contains(&self.max_turns)
            || !(5..=10_080).contains(&self.minutes_until_skip_turn)
            || !(60..=43_200).contains(&self.minutes_until_force_resign)
            || self.minutes_recovered_per_turn > 10_080
        {
            return Err(ApiError::bad_request("invalid_game_setup"));
        }
        Ok(WorkerGameSetup {
            difficulty: self.difficulty,
            speed: self.speed,
            starting_era: self.starting_era,
            victory_types: self.victory_types,
            major_civilizations: self.major_civilizations,
            city_states: self.city_states,
            max_turns: self.max_turns,
            map_type: self.map_type,
            map_shape: self.map_shape,
            map_size: self.map_size,
            map_resources: self.map_resources,
            barbarians: self.barbarians,
            one_city_challenge: self.one_city_challenge,
            nuclear_weapons_enabled: self.nuclear_weapons_enabled,
            espionage_enabled: self.espionage_enabled,
            no_start_bias: self.no_start_bias,
            shuffle_player_order: self.shuffle_player_order,
            no_city_razing: self.no_city_razing,
            world_wrap: self.world_wrap,
            strategic_balance: self.strategic_balance,
            legendary_start: self.legendary_start,
            no_ruins: self.no_ruins,
            no_natural_wonders: self.no_natural_wonders,
            minutes_until_skip_turn: self.minutes_until_skip_turn,
            minutes_until_force_resign: self.minutes_until_force_resign,
            minutes_recovered_per_turn: self.minutes_recovered_per_turn,
        })
    }
}

fn bounded_name(value: &str) -> bool {
    !value.is_empty()
        && value.len() <= 128
        && value.chars().all(|character| !character.is_control())
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::{Value, json};

    #[test]
    fn bounded_setup_accepts_only_unique_closed_server_intent() {
        let setup: CreateGameSetupRequest = serde_json::from_value(valid_setup()).unwrap();
        let setup = setup.validate().unwrap();

        assert_eq!(setup.major_civilizations, 4);
        assert_eq!(setup.map_type, GeneratedMapType::Pangaea);
        assert_eq!(setup.victory_types, ["Domination", "Scientific"]);
    }

    #[test]
    fn bounded_setup_rejects_ambiguous_or_unknown_input() {
        let mut duplicated = valid_setup();
        duplicated["victory_types"] = json!(["Domination", "Domination"]);
        assert!(
            serde_json::from_value::<CreateGameSetupRequest>(duplicated)
                .unwrap()
                .validate()
                .is_err()
        );

        let mut unknown = valid_setup();
        unknown["seed"] = json!(1234);
        assert!(serde_json::from_value::<CreateGameSetupRequest>(unknown).is_err());
    }

    fn valid_setup() -> Value {
        json!({
            "difficulty": "Prince",
            "speed": "Standard",
            "starting_era": "Ancient era",
            "victory_types": ["Domination", "Scientific"],
            "major_civilizations": 4,
            "city_states": 6,
            "max_turns": 500,
            "map_type": "pangaea",
            "map_shape": "hexagonal",
            "map_size": "medium",
            "map_resources": "default",
            "barbarians": "normal",
            "one_city_challenge": false,
            "nuclear_weapons_enabled": true,
            "espionage_enabled": true,
            "no_start_bias": false,
            "shuffle_player_order": false,
            "no_city_razing": false,
            "world_wrap": false,
            "strategic_balance": false,
            "legendary_start": false,
            "no_ruins": false,
            "no_natural_wonders": false,
            "minutes_until_skip_turn": 1440,
            "minutes_until_force_resign": 4320,
            "minutes_recovered_per_turn": 1440
        })
    }
}
