use crate::GameCommand;

#[test]
fn city_state_commands_reject_client_claimed_rules_and_outcomes() {
    let valid = serde_json::json!({
        "type": "gift_city_state_gold",
        "city_state_civilization_id": "Geneva",
        "amount": 250
    });
    assert!(serde_json::from_value::<GameCommand>(valid.clone()).is_ok());
    for field in ["actor", "influence_gained", "treasury_after", "can_execute"] {
        let mut forged = valid.clone();
        forged[field] = serde_json::json!(999);
        assert!(serde_json::from_value::<GameCommand>(forged).is_err());
    }

    let tribute = serde_json::json!({
        "type": "demand_city_state_tribute",
        "city_state_civilization_id": "Geneva",
        "worker": true
    });
    assert!(serde_json::from_value::<GameCommand>(tribute.clone()).is_ok());
    let mut forged = tribute;
    forged["willingness"] = serde_json::json!(1000);
    assert!(serde_json::from_value::<GameCommand>(forged).is_err());

    let improvement = serde_json::json!({
        "type": "gift_city_state_improvement",
        "city_state_civilization_id": "Geneva",
        "x": 2, "y": -1, "improvement_name": "Plantation"
    });
    assert!(serde_json::from_value::<GameCommand>(improvement.clone()).is_ok());
    let mut forged = improvement;
    forged["gold_cost"] = serde_json::json!(0);
    assert!(serde_json::from_value::<GameCommand>(forged).is_err());

    let marriage = serde_json::json!({
        "type": "marry_city_state",
        "city_state_civilization_id": "Geneva"
    });
    assert!(serde_json::from_value::<GameCommand>(marriage.clone()).is_ok());
    for field in ["gold_cost", "captured_city_ids", "annex"] {
        let mut forged = marriage.clone();
        forged[field] = serde_json::json!(0);
        assert!(serde_json::from_value::<GameCommand>(forged).is_err());
    }
}
