use crate::projection::PlayerProjection;

fn fixture() -> PlayerProjection {
    serde_json::from_str(include_str!(
        "../../protocol/player-projection-v59.fixture.json"
    ))
    .expect("projection-v51 fixture should deserialize")
}

#[test]
fn accepts_consistent_city_economy_projection() {
    assert!(fixture().city_economy_is_consistent());
}

#[test]
fn rejects_queue_metadata_that_does_not_match_queue() {
    let mut projection = fixture();
    projection.own_cities[0].construction_queue_entries[0].name = "Granary".into();
    assert!(!projection.city_economy_is_consistent());
}

#[test]
fn rejects_unadvertised_queueability_and_duplicate_targets() {
    let mut projection = fixture();
    projection.own_cities[0].available_constructions.clear();
    assert!(!projection.city_economy_is_consistent());

    let mut projection = fixture();
    let batch = projection.own_cities[0].tile_batch_purchases[0].clone();
    projection.own_cities[0].tile_batch_purchases.push(batch);
    assert!(!projection.city_economy_is_consistent());

    let mut projection = fixture();
    projection.own_cities[0].tile_batch_purchases[0].tile_count = 1;
    assert!(!projection.city_economy_is_consistent());

    let mut projection = fixture();
    let target = projection.own_cities[0].tile_purchases[0].clone();
    projection.own_cities[0].tile_purchases.push(target);
    assert!(!projection.city_economy_is_consistent());

    let mut projection = fixture();
    projection.own_cities[0].construction_options[0]
        .available_actions
        .push(crate::ConstructionQueueAction::AddToTop);
    assert!(!projection.city_economy_is_consistent());
}

#[test]
fn rejects_foreign_city_identifiers_and_unexplored_tiles() {
    let mut projection = fixture();
    projection.own_cities[0].tile_states[0].working_city_id = Some("foreign-secret".into());
    assert!(!projection.city_economy_is_consistent());

    let mut projection = fixture();
    projection.own_cities[0].tile_states[1].x = 999;
    assert!(!projection.city_economy_is_consistent());
}

#[test]
fn rejects_illegal_purchase_disclosure_shapes() {
    let mut projection = fixture();
    let purchase = &mut projection.own_cities[0].construction_options[0].purchases[0];
    purchase.requires_tile = false;
    purchase
        .legal_targets
        .push(crate::projection::ProjectedTargetCoordinate { x: 2, y: -1 });
    assert!(!projection.city_economy_is_consistent());

    let mut projection = fixture();
    projection.own_cities[0].construction_options[0]
        .placement_targets
        .push(crate::projection::ProjectedTargetCoordinate { x: 999, y: 999 });
    assert!(!projection.city_economy_is_consistent());
}

#[test]
fn rejects_perpetual_construction_with_ordinary_state() {
    let mut projection = fixture();
    let perpetual = projection.own_cities[0]
        .construction_options
        .iter_mut()
        .find(|option| option.name == "Nothing")
        .expect("fixture perpetual construction");
    perpetual.stored_production = 1;
    assert!(!projection.city_economy_is_consistent());

    let mut projection = fixture();
    let perpetual = projection.own_cities[0]
        .construction_options
        .iter_mut()
        .find(|option| option.name == "Nothing")
        .expect("fixture perpetual construction");
    perpetual.production_cost = Some(1);
    perpetual.estimated_turns = Some(1);
    assert!(!projection.city_economy_is_consistent());
}

#[test]
fn rejects_malformed_sellable_building_allowlists() {
    let mut projection = fixture();
    projection.own_cities[0]
        .sellable_buildings
        .push("Monument".into());
    assert!(!projection.city_economy_is_consistent());

    let mut projection = fixture();
    projection.own_cities[0].sellable_buildings[0].clear();
    assert!(!projection.city_economy_is_consistent());

    let mut projection = fixture();
    projection.is_current_turn = false;
    projection.own_cities[0]
        .available_governance_actions
        .clear();
    assert!(!projection.city_economy_is_consistent());

    let mut projection = fixture();
    projection.is_current_turn = false;
    projection.own_cities[0].sellable_buildings.clear();
    assert!(!projection.city_economy_is_consistent());
}
