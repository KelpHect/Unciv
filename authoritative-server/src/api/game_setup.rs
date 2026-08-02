use super::*;
use unciv_authoritative_server::worker::{
    AiSlot, BarbarianMode, GeneratedMapShape, GeneratedMapSize, GeneratedMapType,
    MapResourceDensity, MirroringType, WorkerGameSetup,
};

/// Wire form of one host-authored AI seat. Bounds are checked in
/// [`CreateGameSetupRequest::validate`]; the worker re-checks every name against
/// the pinned ruleset.
#[derive(Deserialize, ToSchema)]
#[serde(deny_unknown_fields)]
pub(super) struct AiSlotRequest {
    #[serde(default)]
    civilization_id: String,
    #[serde(default)]
    difficulty: String,
    #[serde(default)]
    personality: String,
}

impl AiSlotRequest {
    fn is_bounded(&self) -> bool {
        [&self.civilization_id, &self.difficulty, &self.personality]
            .into_iter()
            .all(|name| name.is_empty() || bounded_name(name))
    }
}

impl From<AiSlotRequest> for AiSlot {
    fn from(request: AiSlotRequest) -> Self {
        Self {
            civilization_id: request.civilization_id,
            difficulty: request.difficulty,
            personality: request.personality,
        }
    }
}

#[derive(Deserialize, ToSchema)]
#[serde(deny_unknown_fields)]
pub(super) struct CreateGameSetupRequest {
    owner_civilization_id: String,
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
    #[serde(default)]
    custom_map_radius: Option<u16>,
    #[serde(default)]
    custom_map_width: Option<u16>,
    #[serde(default)]
    custom_map_height: Option<u16>,
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
    #[serde(default)]
    map_seed: Option<i64>,
    #[serde(default)]
    mirroring: MirroringType,
    #[serde(default = "default_tiles_per_biome_area")]
    tiles_per_biome_area: u8,
    #[serde(default = "default_max_coast_extension")]
    max_coast_extension: u8,
    #[serde(default = "default_elevation_exponent")]
    elevation_exponent: f32,
    #[serde(default = "default_temperature_intensity")]
    temperature_intensity: f32,
    #[serde(default)]
    temperature_shift: f32,
    #[serde(default = "default_vegetation_richness")]
    vegetation_richness: f32,
    #[serde(default = "default_rare_features_richness")]
    rare_features_richness: f32,
    #[serde(default = "default_resource_richness")]
    resource_richness: f32,
    #[serde(default)]
    water_threshold: f32,
    #[serde(default)]
    ai_civilizations: Option<Vec<AiSlotRequest>>,
}

impl CreateGameSetupRequest {
    pub(super) fn validate(mut self) -> Result<WorkerGameSetup, ApiError> {
        let names_are_bounded = [
            &self.owner_civilization_id,
            &self.difficulty,
            &self.speed,
            &self.starting_era,
        ]
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
        let custom_size_is_valid = match (self.map_size, self.map_shape) {
            (GeneratedMapSize::Custom, GeneratedMapShape::Rectangular) => {
                matches!(
                    (self.custom_map_radius, self.custom_map_width, self.custom_map_height),
                    (None, Some(width), Some(height))
                        if (3..=220).contains(&width)
                            && (3..=220).contains(&height)
                            && width <= height.saturating_mul(16)
                            && height <= width.saturating_mul(16)
                            && (!self.world_wrap || width >= 32 && width % 2 == 0)
                )
            }
            (
                GeneratedMapSize::Custom,
                GeneratedMapShape::Hexagonal | GeneratedMapShape::FlatEarth,
            ) => matches!(
                (self.custom_map_radius, self.custom_map_width, self.custom_map_height),
                (Some(radius), None, None) if (2..=100).contains(&radius)
            ),
            _ => {
                self.custom_map_radius.is_none()
                    && self.custom_map_width.is_none()
                    && self.custom_map_height.is_none()
            }
        };
        // A blank civilization is an AI seat the server draws; a named one is
        // reserved and must never collide with the owner or another slot.
        let ai_roster_is_bounded = match self.ai_civilizations.as_deref() {
            None => true,
            Some(roster) => {
                let pinned: Vec<&String> = roster
                    .iter()
                    .map(|slot| &slot.civilization_id)
                    .filter(|name| !name.is_empty())
                    .collect();
                roster.len() < usize::from(self.major_civilizations)
                    && roster.iter().all(AiSlotRequest::is_bounded)
                    && !pinned
                        .iter()
                        .any(|name| **name == self.owner_civilization_id)
                    && pinned
                        .iter()
                        .collect::<std::collections::HashSet<_>>()
                        .len()
                        == pinned.len()
            }
        };
        if !names_are_bounded
            || !victories_are_bounded
            || !custom_size_is_valid
            || !ai_roster_is_bounded
            || !(2..=16).contains(&self.major_civilizations)
            || self.city_states > 64
            || !(100..=1500).contains(&self.max_turns)
            || !(1..=15).contains(&self.tiles_per_biome_area)
            || !(1..=5).contains(&self.max_coast_extension)
            || !finite_range(self.elevation_exponent, 0.6, 0.8)
            || !finite_range(self.temperature_intensity, 0.4, 0.8)
            || !finite_range(self.temperature_shift, -0.4, 0.4)
            || !finite_range(self.vegetation_richness, 0.0, 1.0)
            || !finite_range(self.rare_features_richness, 0.0, 0.5)
            || !finite_range(self.resource_richness, 0.0, 0.5)
            || !finite_range(self.water_threshold, -0.1, 0.1)
        {
            return Err(ApiError::bad_request("invalid_game_setup"));
        }
        self.victory_types.sort();
        Ok(WorkerGameSetup {
            owner_civilization_id: self.owner_civilization_id,
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
            custom_map_radius: self.custom_map_radius,
            custom_map_width: self.custom_map_width,
            custom_map_height: self.custom_map_height,
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
            map_seed: self.map_seed,
            mirroring: self.mirroring,
            tiles_per_biome_area: self.tiles_per_biome_area,
            max_coast_extension: self.max_coast_extension,
            elevation_exponent: self.elevation_exponent,
            temperature_intensity: self.temperature_intensity,
            temperature_shift: self.temperature_shift,
            vegetation_richness: self.vegetation_richness,
            rare_features_richness: self.rare_features_richness,
            resource_richness: self.resource_richness,
            water_threshold: self.water_threshold,
            ai_civilizations: self
                .ai_civilizations
                .map(|roster| roster.into_iter().map(AiSlot::from).collect()),
        })
    }
}

/// A pinned AI roster must account for exactly the non-human major
/// civilizations, so the room's "N AI + M human" arithmetic can never drift
/// from what the engine will actually create.
pub(super) fn ai_roster_matches_human_slots(setup: &WorkerGameSetup, human_slots: u8) -> bool {
    match setup.ai_civilizations.as_deref() {
        None => true,
        Some(roster) => {
            roster.len() + usize::from(human_slots) == usize::from(setup.major_civilizations)
        }
    }
}

fn finite_range(value: f32, minimum: f32, maximum: f32) -> bool {
    value.is_finite() && (minimum..=maximum).contains(&value)
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
        unknown["client_snapshot"] = json!("not allowed");
        assert!(serde_json::from_value::<CreateGameSetupRequest>(unknown).is_err());
    }

    #[test]
    fn custom_world_dimensions_are_shape_specific_and_bounded() {
        let mut rectangular = valid_setup();
        rectangular["map_shape"] = json!("rectangular");
        rectangular["map_size"] = json!("custom");
        rectangular["custom_map_width"] = json!(72);
        rectangular["custom_map_height"] = json!(48);
        let setup = serde_json::from_value::<CreateGameSetupRequest>(rectangular)
            .unwrap()
            .validate()
            .unwrap();
        assert_eq!(setup.map_size, GeneratedMapSize::Custom);
        assert_eq!(setup.custom_map_width, Some(72));
        assert_eq!(setup.custom_map_height, Some(48));

        let mut wrapped = valid_setup();
        wrapped["map_shape"] = json!("rectangular");
        wrapped["map_size"] = json!("custom");
        wrapped["custom_map_width"] = json!(33);
        wrapped["custom_map_height"] = json!(24);
        wrapped["world_wrap"] = json!(true);
        assert!(
            serde_json::from_value::<CreateGameSetupRequest>(wrapped)
                .unwrap()
                .validate()
                .is_err()
        );

        let mut hexagonal = valid_setup();
        hexagonal["map_shape"] = json!("hexagonal");
        hexagonal["map_size"] = json!("custom");
        hexagonal["custom_map_radius"] = json!(28);
        assert!(
            serde_json::from_value::<CreateGameSetupRequest>(hexagonal)
                .unwrap()
                .validate()
                .is_ok()
        );
    }

    #[test]
    fn ai_roster_is_bounded_unique_and_tied_to_the_human_seats() {
        let mut pinned = valid_setup();
        pinned["ai_civilizations"] = json!([
            {"civilization_id": "Greece", "difficulty": "Immortal", "personality": "Aggressive"},
            {"personality": "Expansionist"},
            {}
        ]);
        let setup = serde_json::from_value::<CreateGameSetupRequest>(pinned)
            .unwrap()
            .validate()
            .unwrap();
        let roster = setup.ai_civilizations.as_deref().unwrap();
        assert_eq!(roster[0].civilization_id, "Greece");
        assert_eq!(roster[0].difficulty, "Immortal");
        // A server-drawn nation can still carry a pinned personality.
        assert_eq!(roster[1].civilization_id, "");
        assert_eq!(roster[1].personality, "Expansionist");
        assert_eq!(roster[2], AiSlot::default());
        // 3 AI + 1 human == 4 major civilizations; any other seat count drifts.
        assert!(ai_roster_matches_human_slots(&setup, 1));
        assert!(!ai_roster_matches_human_slots(&setup, 2));

        for rejected in [
            json!([{"civilization_id": "Greece"}, {"civilization_id": "Greece"}]),
            json!([{"civilization_id": "Rome"}]),
            json!([{}, {}, {}, {}]),
            json!([{"unknown_field": "x"}]),
        ] {
            let mut invalid = valid_setup();
            invalid["ai_civilizations"] = rejected;
            // Rejected either by the strict decoder or by the bounds check.
            assert!(
                serde_json::from_value::<CreateGameSetupRequest>(invalid)
                    .map_err(|_| ())
                    .and_then(|request| request.validate().map_err(|_| ()))
                    .is_err()
            );
        }

        // An absent roster keeps the legacy count-only meaning.
        let legacy = serde_json::from_value::<CreateGameSetupRequest>(valid_setup())
            .unwrap()
            .validate()
            .unwrap();
        assert!(legacy.ai_civilizations.is_none());
        assert!(ai_roster_matches_human_slots(&legacy, 4));
    }

    fn valid_setup() -> Value {
        json!({
            "owner_civilization_id": "Rome",
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
            "no_natural_wonders": false
            ,"map_seed": 1234
            ,"mirroring": "none"
            ,"tiles_per_biome_area": 6
            ,"max_coast_extension": 2
            ,"elevation_exponent": 0.7
            ,"temperature_intensity": 0.6
            ,"temperature_shift": 0.0
            ,"vegetation_richness": 0.4
            ,"rare_features_richness": 0.05
            ,"resource_richness": 0.1
            ,"water_threshold": 0.0
        })
    }
}
