use crate::projection::ProjectedVictory;
use crate::projection::{SpectatorCivilization, SpectatorMapUnit, SpectatorProjection};

#[test]
fn spectator_projection_discloses_the_full_map_and_nothing_owner_private() {
    let projection = SpectatorProjection {
        protocol_version: 4,
        turn: 7,
        current_player_civilization_id: "Rome".to_owned(),
        active_player_civilization_ids: vec!["Rome".to_owned()],
        victory: None,
        major_civilizations: vec![SpectatorCivilization {
            civilization_id: "Rome".to_owned(),
            display_name: "Rome".to_owned(),
            human_controlled: true,
            defeated: false,
        }],
        world_wrap: false,
        map_tiles: vec![crate::projection::ProjectedTileVisibility {
            x: 0,
            y: 0,
            visible: true,
            improvement_name: None,
            improvement_pillaged: None,
            road_status: None,
            road_pillaged: None,
            base_terrain: "Grassland".to_owned(),
            terrain_features: vec![],
            natural_wonder_name: None,
            resource_name: None,
            resource_amount: None,
        }],
        map_cities: vec![crate::projection::ProjectedForeignCity {
            id: "city-rome".to_owned(),
            name: "Rome".to_owned(),
            civilization_id: "Rome".to_owned(),
            x: 0,
            y: 0,
            owned_tiles: vec![crate::projection::ProjectedTargetCoordinate { x: 0, y: 0 }],
        }],
        map_units: vec![SpectatorMapUnit {
            id: 1,
            civilization_id: "Rome".to_owned(),
            name: "Warrior".to_owned(),
            x: 1,
            y: 0,
        }],
    };
    let value = serde_json::to_value(&projection).unwrap();
    let encoded = value.to_string();

    // Full-reveal parity: the map, cities and public unit markers are present.
    for expected in ["mapTiles", "mapCities", "mapUnits", "Grassland", "Warrior"] {
        assert!(encoded.contains(expected), "missing {expected}");
    }

    // Owner-private families stay withheld even though the map is not.
    for forbidden in [
        "gold",
        "research",
        "notification",
        "spies",
        "diplomacy",
        "promotions",
        "moveDestinations",
        "posture",
        "currentMovement",
    ] {
        assert!(
            !encoded.to_lowercase().contains(&forbidden.to_lowercase()),
            "spectator payload leaked {forbidden}"
        );
    }
    let mut malicious = value;
    malicious["canonicalGameInfo"] = serde_json::json!({"secret": true});
    assert!(serde_json::from_value::<SpectatorProjection>(malicious).is_err());

    let mut terminal = projection;
    terminal.victory = Some(ProjectedVictory {
        winning_civilization_id: "Rome".to_owned(),
        victory_type: "Domination".to_owned(),
        victory_turn: 7,
    });
    assert!(terminal.victory_is_consistent());
    terminal.victory.as_mut().unwrap().winning_civilization_id = "Hidden".to_owned();
    assert!(!terminal.victory_is_consistent());
}
