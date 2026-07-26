use crate::projection::PlayerProjection;

fn fixture() -> PlayerProjection {
    serde_json::from_str(include_str!(
        "../../protocol/player-projection-v52.fixture.json"
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
