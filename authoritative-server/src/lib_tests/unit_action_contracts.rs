use crate::*;

#[test]
fn move_unit_contract_is_typed_and_closed() {
    let command: GameCommand = serde_json::from_value(serde_json::json!({
        "type": "move_unit", "unit_id": 42, "destination_x": -3, "destination_y": 7
    }))
    .unwrap();
    assert_eq!(
        command,
        GameCommand::MoveUnit {
            unit_id: 42,
            destination_x: -3,
            destination_y: 7
        }
    );
    assert!(
        serde_json::from_value::<GameCommand>(serde_json::json!({
            "type": "move_unit", "unit_id": "42", "destination_tile_id": "-3,7"
        }))
        .is_err()
    );
    assert!(
        serde_json::from_value::<GameCommand>(serde_json::json!({
            "type": "move_unit", "unit_id": 42, "destination_x": -3,
            "destination_y": 7, "actor_id": "attacker-controlled"
        }))
        .is_err()
    );
}

#[test]
fn move_unit_toward_contract_excludes_client_path_and_order_state() {
    let command: GameCommand = serde_json::from_value(serde_json::json!({
        "type": "move_unit_toward", "unit_id": 42, "destination_x": 7, "destination_y": 2
    }))
    .unwrap();
    assert_eq!(
        command,
        GameCommand::MoveUnitToward {
            unit_id: 42,
            destination_x: 7,
            destination_y: 2
        }
    );
    assert!(
        serde_json::from_value::<GameCommand>(serde_json::json!({
            "type": "move_unit_toward", "unit_id": 42, "destination_x": 7,
            "destination_y": 2, "path": [[1, 0], [2, 0]]
        }))
        .is_err()
    );
}

#[test]
fn cancel_unit_movement_order_contract_contains_only_the_unit_id() {
    let command: GameCommand = serde_json::from_value(serde_json::json!({
        "type": "cancel_unit_movement_order", "unit_id": 42
    }))
    .unwrap();
    assert_eq!(
        command,
        GameCommand::CancelUnitMovementOrder { unit_id: 42 }
    );
    assert!(
        serde_json::from_value::<GameCommand>(serde_json::json!({
            "type": "cancel_unit_movement_order", "unit_id": 42, "action": null
        }))
        .is_err()
    );
}

#[test]
fn set_unit_exploration_contract_is_typed_and_closed() {
    let command: GameCommand = serde_json::from_value(serde_json::json!({
        "type": "set_unit_exploration", "unit_id": 42, "enabled": true
    }))
    .unwrap();
    assert_eq!(
        command,
        GameCommand::SetUnitExploration {
            unit_id: 42,
            enabled: true
        }
    );
    assert!(
        serde_json::from_value::<GameCommand>(serde_json::json!({
            "type": "set_unit_exploration", "unit_id": 42, "enabled": true, "action": "Explore"
        }))
        .is_err()
    );
}

#[test]
fn set_unit_automation_contract_is_typed_and_closed() {
    let command: GameCommand = serde_json::from_value(serde_json::json!({
        "type": "set_unit_automation", "unit_id": 42, "enabled": true
    }))
    .unwrap();
    assert_eq!(
        command,
        GameCommand::SetUnitAutomation {
            unit_id: 42,
            enabled: true
        }
    );
    assert!(
        serde_json::from_value::<GameCommand>(serde_json::json!({
            "type": "set_unit_automation", "unit_id": 42, "enabled": true, "actor": "Rome"
        }))
        .is_err()
    );
}

#[test]
fn set_unit_posture_contract_uses_a_closed_enum() {
    let command: GameCommand = serde_json::from_value(serde_json::json!({
        "type": "set_unit_posture", "unit_id": 42, "posture": "fortify_until_healed"
    }))
    .unwrap();
    assert_eq!(
        command,
        GameCommand::SetUnitPosture {
            unit_id: 42,
            posture: UnitPosture::FortifyUntilHealed,
        }
    );
    assert!(
        serde_json::from_value::<GameCommand>(serde_json::json!({
            "type": "set_unit_posture", "unit_id": 42, "posture": "arbitrary_action"
        }))
        .is_err()
    );
}

#[test]
fn disband_unit_contract_contains_only_the_stable_unit_id() {
    let serialized = serde_json::to_string(&GameCommand::DisbandUnit { unit_id: 42 }).unwrap();
    assert!(serialized.contains("\"unit_id\":42"));
    assert!(!serialized.contains("gold"));
    assert!(!serialized.contains("actor"));
    assert!(!serialized.contains("civilization"));
}

#[test]
fn pillage_tile_contract_contains_only_the_stable_unit_id() {
    let serialized = serde_json::to_value(GameCommand::PillageTile { unit_id: 42 }).unwrap();
    assert_eq!(
        serialized,
        serde_json::json!({ "type": "pillage_tile", "unit_id": 42 })
    );
}

#[test]
fn found_city_contract_contains_only_the_stable_unit_id() {
    let serialized = serde_json::to_value(GameCommand::FoundCity { unit_id: 42 }).unwrap();
    assert_eq!(
        serialized,
        serde_json::json!({ "type": "found_city", "unit_id": 42 })
    );
}

#[test]
fn paradrop_unit_contract_contains_only_intent_coordinates() {
    let serialized = serde_json::to_value(GameCommand::ParadropUnit {
        unit_id: 42,
        destination_x: 3,
        destination_y: -2,
    })
    .unwrap();
    assert_eq!(
        serialized,
        serde_json::json!({
            "type": "paradrop_unit",
            "unit_id": 42,
            "destination_x": 3,
            "destination_y": -2,
        })
    );
}

#[test]
fn attack_with_unit_contract_excludes_paths_damage_rng_and_actor() {
    let serialized = serde_json::to_value(GameCommand::AttackWithUnit {
        unit_id: 42,
        target_x: 3,
        target_y: -2,
    })
    .unwrap();
    assert_eq!(
        serialized,
        serde_json::json!({
            "type": "attack_with_unit",
            "unit_id": 42,
            "target_x": 3,
            "target_y": -2,
        })
    );
}

#[test]
fn upgrade_units_contract_excludes_cost_resources_and_actor() {
    let command = GameCommand::UpgradeUnits {
        unit_ids: vec![42, 43],
        target_unit_name: "Swordsman".to_owned(),
    };
    let serialized = serde_json::to_string(&command).unwrap();
    assert!(serialized.contains("\"unit_ids\":[42,43]"));
    assert!(serialized.contains("\"target_unit_name\":\"Swordsman\""));
    assert!(!serialized.contains("gold"));
    assert!(!serialized.contains("resources"));
    assert!(!serialized.contains("actor"));
    assert!(
        serde_json::from_value::<GameCommand>(serde_json::json!({
            "type": "upgrade_units", "unit_ids": [42],
            "target_unit_name": "Swordsman", "gold_cost": 5
        }))
        .is_err()
    );
}

#[test]
fn promote_unit_contract_contains_only_unit_and_selected_path() {
    let command = GameCommand::PromoteUnit {
        unit_id: 42,
        promotion_names: vec!["Drill I".to_owned(), "Drill II".to_owned()],
        save_as_city_default: true,
    };
    let serialized = serde_json::to_string(&command).unwrap();
    assert!(serialized.contains("\"unit_id\":42"));
    assert!(serialized.contains("\"promotion_names\":[\"Drill I\",\"Drill II\"]"));
    assert!(!serialized.contains("xp_cost"));
    assert!(!serialized.contains("actor"));
    assert!(
        serde_json::from_value::<GameCommand>(serde_json::json!({
            "type": "promote_unit", "unit_id": 42,
            "promotion_names": ["Drill I"], "xp_cost": 10
        }))
        .is_err()
    );
}

#[test]
fn city_unit_promotion_preference_excludes_saved_promotion_state() {
    let command = GameCommand::SetCityUnitPromotionPreference {
        city_id: "city-1".to_owned(),
        base_unit_name: "Warrior".to_owned(),
        enabled: false,
    };
    let serialized = serde_json::to_string(&command).unwrap();
    assert!(serialized.contains("\"base_unit_name\":\"Warrior\""));
    assert!(!serialized.contains("saved_promotions"));
    assert!(!serialized.contains("actor"));
}

#[test]
fn rename_unit_contract_contains_only_unit_and_optional_name() {
    let command = GameCommand::RenameUnit {
        unit_id: 42,
        instance_name: Some("The First Legion".to_owned()),
    };
    let serialized = serde_json::to_string(&command).unwrap();
    assert!(serialized.contains("\"unit_id\":42"));
    assert!(serialized.contains("\"instance_name\":\"The First Legion\""));
    assert!(!serialized.contains("actor"));
    assert!(
        serde_json::from_value::<GameCommand>(serde_json::json!({
            "type": "rename_unit", "unit_id": 42,
            "instance_name": "Legion", "civilization_id": "Rome"
        }))
        .is_err()
    );
}

#[test]
fn tile_improvement_order_excludes_tile_cost_turn_and_actor_claims() {
    let command = GameCommand::SetTileImprovementOrder {
        unit_id: 42,
        improvement_name: Some("Remove Forest".to_owned()),
        queued_improvement_name: Some("Farm".to_owned()),
    };
    let serialized = serde_json::to_string(&command).unwrap();
    assert!(serialized.contains("\"unit_id\":42"));
    assert!(serialized.contains("\"queued_improvement_name\":\"Farm\""));
    for forbidden in ["actor", "civilization", "turns", "tile_x", "tile_y", "cost"] {
        assert!(!serialized.contains(forbidden));
    }
    assert!(
        serde_json::from_value::<GameCommand>(serde_json::json!({
            "type": "set_tile_improvement_order", "unit_id": 42,
            "improvement_name": "Farm", "queued_improvement_name": null,
            "turns": 1
        }))
        .is_err()
    );
}

#[test]
fn road_connection_order_contains_only_unit_and_optional_destination() {
    let command = GameCommand::SetRoadConnectionOrder {
        unit_id: 42,
        destination_x: Some(7),
        destination_y: Some(-2),
    };
    let serialized = serde_json::to_string(&command).unwrap();
    assert!(serialized.contains("\"destination_x\":7"));
    for forbidden in [
        "actor",
        "civilization",
        "path",
        "road_tier",
        "movement",
        "cost",
    ] {
        assert!(!serialized.contains(forbidden));
    }
    assert!(
        serde_json::from_value::<GameCommand>(serde_json::json!({
            "type": "set_road_connection_order", "unit_id": 42,
            "destination_x": 7, "destination_y": -2,
            "path": [{"x": 1, "y": 0}]
        }))
        .is_err()
    );
}

#[test]
fn swap_units_contract_is_typed_and_closed() {
    let command: GameCommand = serde_json::from_value(serde_json::json!({
        "type": "swap_units", "unit_id": 42, "destination_x": 2, "destination_y": -1
    }))
    .unwrap();
    assert_eq!(
        command,
        GameCommand::SwapUnits {
            unit_id: 42,
            destination_x: 2,
            destination_y: -1
        }
    );
    assert!(
        serde_json::from_value::<GameCommand>(serde_json::json!({
            "type": "swap_units", "unit_id": 42, "destination_x": 2,
            "destination_y": -1, "target_unit_id": 99
        }))
        .is_err()
    );
}
