use crate::projection::PlayerProjection;

fn fixture() -> PlayerProjection {
    serde_json::from_str(include_str!(
        "../../protocol/player-projection-v56.fixture.json"
    ))
    .unwrap()
}

#[test]
fn public_policy_branches_are_bounded_sorted_and_unique() {
    let projection = fixture();
    assert!(projection.diplomacy_is_consistent());

    let mut duplicate = fixture();
    duplicate.diplomacy_partners[0]
        .adopted_policy_branches
        .push("Tradition".to_owned());
    assert!(!duplicate.diplomacy_is_consistent());

    let mut oversized_name = fixture();
    oversized_name.diplomacy_partners[0].adopted_policy_branches = vec!["x".repeat(129)];
    assert!(!oversized_name.diplomacy_is_consistent());
}
