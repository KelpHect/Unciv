use super::*;

#[test]
fn move_spy_contract_contains_only_destination_intent() {
    let move_to_city: GameCommand = serde_json::from_value(serde_json::json!({
        "type": "move_spy",
        "spy_name": "Agent 1",
        "city_id": "city-1"
    }))
    .unwrap();
    assert_eq!(
        move_to_city,
        GameCommand::MoveSpy {
            spy_name: "Agent 1".to_owned(),
            city_id: Some("city-1".to_owned()),
        }
    );

    let hideout: GameCommand = serde_json::from_value(serde_json::json!({
        "type": "move_spy",
        "spy_name": "Agent 1",
        "city_id": null
    }))
    .unwrap();
    assert_eq!(
        hideout,
        GameCommand::MoveSpy {
            spy_name: "Agent 1".to_owned(),
            city_id: None,
        }
    );

    for untrusted in ["actor_id", "target_civilization_id", "turns", "outcome"] {
        let mut value = serde_json::json!({
            "type": "move_spy", "spy_name": "Agent 1", "city_id": "city-1"
        });
        value
            .as_object_mut()
            .unwrap()
            .insert(untrusted.to_owned(), serde_json::json!(1));
        assert!(serde_json::from_value::<GameCommand>(value).is_err());
    }
}

#[test]
fn spy_coup_contract_contains_only_the_player_choice() {
    let command: GameCommand = serde_json::from_value(serde_json::json!({
        "type": "set_spy_coup",
        "spy_name": "Agent 1",
        "enabled": true
    }))
    .unwrap();
    assert_eq!(
        command,
        GameCommand::SetSpyCoup {
            spy_name: "Agent 1".to_owned(),
            enabled: true,
        }
    );

    for untrusted in ["success_chance", "influence", "random_seed", "outcome"] {
        let mut value = serde_json::json!({
            "type": "set_spy_coup", "spy_name": "Agent 1", "enabled": true
        });
        value
            .as_object_mut()
            .unwrap()
            .insert(untrusted.to_owned(), serde_json::json!(1));
        assert!(serde_json::from_value::<GameCommand>(value).is_err());
    }
}
