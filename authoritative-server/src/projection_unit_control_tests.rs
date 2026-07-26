use crate::UnitPosture;
use crate::projection::{
    PlayerProjection, ProjectedImprovementOrderChoice, ProjectedMovementDestination,
    ProjectedUnitUpgradeTarget,
};

fn fixture() -> PlayerProjection {
    serde_json::from_str(include_str!(
        "../../protocol/player-projection-v58.fixture.json"
    ))
    .unwrap()
}

#[test]
fn direct_unit_controls_are_bounded_and_private() {
    let projection = fixture();
    assert!(projection.unit_actions_are_consistent());
    let unit = &projection.own_units[0];
    assert_eq!(
        unit.available_postures,
        [UnitPosture::Sleep, UnitPosture::Guard]
    );
    assert!(unit.can_disband);
    assert!(unit.can_pillage);
    assert!(unit.can_rename);
    assert_eq!(
        unit.paradrop_destinations,
        [ProjectedMovementDestination { x: 2, y: -1 }]
    );
    assert_eq!(
        unit.available_upgrade_targets,
        [ProjectedUnitUpgradeTarget {
            target_unit_name: "Rifleman".into(),
            gold_cost: 120,
        }]
    );
    assert_eq!(
        unit.move_toward_destinations,
        [
            ProjectedMovementDestination { x: 2, y: -1 },
            ProjectedMovementDestination { x: 5, y: -1 },
        ]
    );
    assert_eq!(
        unit.available_improvement_orders[0],
        ProjectedImprovementOrderChoice {
            improvement_name: None,
            queued_improvement_name: None,
        }
    );
    assert_eq!(
        unit.available_road_destinations,
        [ProjectedMovementDestination { x: 2, y: -1 }]
    );

    let foreign = &projection.visible_foreign_units[0];
    assert!(foreign.available_postures.is_empty());
    assert!(!foreign.can_disband);
    assert!(!foreign.can_pillage);
    assert!(!foreign.can_found_city);
    assert!(!foreign.can_rename);
    assert!(foreign.paradrop_destinations.is_empty());
    assert!(foreign.available_upgrade_targets.is_empty());
    assert!(foreign.move_toward_destinations.is_empty());
    assert!(foreign.available_improvement_orders.is_empty());
    assert!(foreign.available_road_destinations.is_empty());
}

#[test]
fn direct_unit_controls_reject_forged_reordered_and_out_of_turn_choices() {
    let projection = fixture();

    let mut hidden_destination = projection.clone();
    hidden_destination.own_units[0].paradrop_destinations =
        vec![ProjectedMovementDestination { x: 5, y: -1 }];
    assert!(!hidden_destination.unit_actions_are_consistent());

    let mut duplicate_posture = projection.clone();
    duplicate_posture.own_units[0]
        .available_postures
        .push(UnitPosture::Guard);
    assert!(!duplicate_posture.unit_actions_are_consistent());

    let mut invalid_upgrade = projection.clone();
    invalid_upgrade.own_units[0].available_upgrade_targets[0].gold_cost = -1;
    assert!(!invalid_upgrade.unit_actions_are_consistent());

    let mut invented_improvement = projection.clone();
    invented_improvement.own_units[0].available_improvement_orders[1].improvement_name =
        Some(String::new());
    assert!(!invented_improvement.unit_actions_are_consistent());

    let mut hidden_long_route = projection.clone();
    hidden_long_route.own_units[0].move_toward_destinations =
        vec![ProjectedMovementDestination { x: 99, y: 99 }];
    assert!(!hidden_long_route.unit_actions_are_consistent());

    let mut leaked_foreign = projection.clone();
    leaked_foreign.visible_foreign_units[0].can_disband = true;
    assert!(!leaked_foreign.unit_actions_are_consistent());

    let mut out_of_turn = projection;
    out_of_turn.is_current_turn = false;
    assert!(!out_of_turn.unit_actions_are_consistent());
}
