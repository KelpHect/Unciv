use crate::projection::{PlayerProjection, ProjectedInstantImprovementAction};

fn fixture() -> PlayerProjection {
    serde_json::from_str(include_str!(
        "../../protocol/player-projection-v54.fixture.json"
    ))
    .unwrap()
}

#[test]
fn private_capital_project_action_is_bounded_and_never_foreign_or_out_of_turn() {
    let mut own = fixture();
    own.own_units[0].capital_project_name = Some("The Spaceship".to_owned());
    assert!(own.unit_actions_are_consistent());

    let mut empty = fixture();
    empty.own_units[0].capital_project_name = Some(String::new());
    assert!(!empty.unit_actions_are_consistent());

    let mut oversized = fixture();
    oversized.own_units[0].capital_project_name = Some("x".repeat(129));
    assert!(!oversized.unit_actions_are_consistent());

    let mut foreign = fixture();
    foreign.visible_foreign_units[0].capital_project_name = Some("Secret Project".to_owned());
    assert!(!foreign.unit_actions_are_consistent());

    let mut out_of_turn = own;
    out_of_turn.is_current_turn = false;
    assert!(!out_of_turn.unit_actions_are_consistent());
}

#[test]
fn instant_improvement_actions_are_closed_bounded_and_private() {
    let action = ProjectedInstantImprovementAction {
        action_id: "a".repeat(64),
        title: "Create Fishing Boats".to_owned(),
    };
    let mut own = fixture();
    own.own_units[0]
        .available_instant_improvement_actions
        .push(action.clone());
    assert!(own.unit_actions_are_consistent());

    let mut malformed = fixture();
    malformed.own_units[0]
        .available_instant_improvement_actions
        .push(ProjectedInstantImprovementAction {
            action_id: "not-an-opaque-id".to_owned(),
            title: String::new(),
        });
    assert!(!malformed.unit_actions_are_consistent());

    let mut duplicate = own.clone();
    duplicate.own_units[0]
        .available_instant_improvement_actions
        .push(action.clone());
    assert!(!duplicate.unit_actions_are_consistent());

    let mut oversized = fixture();
    oversized.own_units[0].available_instant_improvement_actions = (0..257)
        .map(|index| ProjectedInstantImprovementAction {
            action_id: format!("{index:064x}"),
            title: format!("Create option {index}"),
        })
        .collect();
    assert!(!oversized.unit_actions_are_consistent());

    let mut foreign = fixture();
    foreign.visible_foreign_units[0]
        .available_instant_improvement_actions
        .push(action);
    assert!(!foreign.unit_actions_are_consistent());

    let mut out_of_turn = own;
    out_of_turn.is_current_turn = false;
    assert!(!out_of_turn.unit_actions_are_consistent());
}
