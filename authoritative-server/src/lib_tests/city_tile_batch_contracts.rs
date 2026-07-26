use super::*;

#[test]
fn city_tile_batch_contract_contains_only_city_and_bounded_ring_intent() {
    let command: GameCommand = serde_json::from_value(serde_json::json!({
        "type": "buy_city_tile_batch",
        "city_id": "city-1",
        "ring": 2
    }))
    .unwrap();
    assert_eq!(
        command,
        GameCommand::BuyCityTileBatch {
            city_id: "city-1".to_owned(),
            ring: 2,
        }
    );
    for forged in ["tiles", "gold_cost", "actor", "result"] {
        let mut value = serde_json::json!({
            "type": "buy_city_tile_batch",
            "city_id": "city-1",
            "ring": 2
        });
        value[forged] = serde_json::json!([]);
        assert!(serde_json::from_value::<GameCommand>(value).is_err());
    }
}
