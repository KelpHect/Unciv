use crate::*;

#[test]
fn trade_offer_contract_contains_only_partner_and_selected_projected_offers() {
    let command: GameCommand = serde_json::from_value(serde_json::json!({
        "type": "offer_trade",
        "other_civilization_id": "Greece",
        "trade": { "ourOffers": [{ "name": "Gold", "type": "Gold", "amount": 50, "duration": 0 }], "theirOffers": [] }
    })).unwrap();
    assert!(
        matches!(command, GameCommand::OfferTrade { other_civilization_id, .. } if other_civilization_id == "Greece")
    );
    for untrusted in [
        "actor_civilization_id",
        "accepted",
        "evaluation",
        "canonical_game",
    ] {
        let mut value = serde_json::json!({
            "type": "offer_trade", "other_civilization_id": "Greece",
            "trade": { "ourOffers": [], "theirOffers": [] }
        });
        value[untrusted] = serde_json::json!(true);
        assert!(serde_json::from_value::<GameCommand>(value).is_err());
    }
}

#[test]
fn trade_decisions_reference_only_server_projected_ids() {
    let request_id = "a".repeat(64);
    let accept: GameCommand = serde_json::from_value(serde_json::json!({
        "type": "accept_trade", "request_id": request_id
    }))
    .unwrap();
    assert!(matches!(accept, GameCommand::AcceptTrade { request_id } if request_id.len() == 64));

    let mut forged = serde_json::json!({ "type": "decline_trade", "request_id": "b".repeat(64) });
    forged["trade"] = serde_json::json!({ "ourOffers": [], "theirOffers": [] });
    assert!(serde_json::from_value::<GameCommand>(forged).is_err());
}

#[test]
fn counteroffer_references_one_request_and_selected_offers() {
    let counter: GameCommand = serde_json::from_value(serde_json::json!({
        "type": "counter_trade", "request_id": "c".repeat(64),
        "trade": { "ourOffers": [], "theirOffers": [{ "name": "Gold", "type": "Gold", "amount": 25, "duration": 0 }] }
    })).unwrap();
    assert!(
        matches!(counter, GameCommand::CounterTrade { request_id, .. } if request_id.len() == 64)
    );
}
