use crate::projection::{PendingEndTurnAction, PlayerProjection};

fn fixture() -> PlayerProjection {
    serde_json::from_str(include_str!(
        "../../protocol/player-projection-v51.fixture.json"
    ))
    .unwrap()
}

#[test]
fn pending_turn_actions_are_closed_sorted_unique_and_resolvable() {
    let projection = fixture();
    assert!(projection.turn_readiness_is_consistent());

    let mut duplicate = fixture();
    duplicate
        .pending_turn_actions
        .insert(1, PendingEndTurnAction::PickPolicy);
    assert!(!duplicate.turn_readiness_is_consistent());

    let mut legacy_spy_reminder = fixture();
    legacy_spy_reminder.pending_turn_actions = vec![PendingEndTurnAction::MoveSpies];
    assert!(!legacy_spy_reminder.turn_readiness_is_consistent());

    let mut missing_policy_choices = fixture();
    missing_policy_choices.policies.selectable_policies.clear();
    assert!(!missing_policy_choices.turn_readiness_is_consistent());

    let mut missing_great_people = fixture();
    missing_great_people.selectable_great_people.clear();
    assert!(!missing_great_people.turn_readiness_is_consistent());
}
