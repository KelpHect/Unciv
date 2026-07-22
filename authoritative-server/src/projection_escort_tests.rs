use crate::projection::PlayerProjection;

#[test]
fn movement_metadata_rejects_malformed_persistent_escort_orders() {
    let fixture = include_str!("../../protocol/player-projection-v43.fixture.json");
    let mut projection: PlayerProjection = serde_json::from_str(fixture).unwrap();
    let mut escort = projection.own_units[0].clone();
    escort.id = 43;
    escort.move_destinations.clear();
    escort.swap_destinations.clear();
    escort.attack_targets.clear();
    escort.nuclear_target_candidates.clear();
    escort.air_sweep_targets.clear();
    projection.own_units.push(escort);
    projection.own_units[0].movement_escort_unit_id = Some(43);
    assert!(projection.movement_is_consistent());

    let mut self_reference = projection.clone();
    self_reference.own_units[0].movement_escort_unit_id = Some(self_reference.own_units[0].id);
    assert!(!self_reference.movement_is_consistent());

    let mut missing_destination = projection.clone();
    missing_destination.own_units[0].movement_destination_x = None;
    assert!(!missing_destination.movement_is_consistent());

    let mut missing_escort = projection.clone();
    missing_escort.own_units[0].movement_escort_unit_id = Some(99);
    assert!(!missing_escort.movement_is_consistent());

    let mut foreign = projection;
    foreign.visible_foreign_units[0].movement_escort_unit_id = Some(43);
    assert!(!foreign.movement_is_consistent());
}
