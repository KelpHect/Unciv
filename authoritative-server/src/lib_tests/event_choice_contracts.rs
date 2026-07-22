use super::*;

#[test]
fn event_choice_contract_accepts_only_opaque_projected_ids() {
    let prompt_id = "a".repeat(64);
    let choice_id = "b".repeat(64);
    let command: GameCommand = serde_json::from_value(serde_json::json!({
        "type": "resolve_event_choice",
        "prompt_id": prompt_id,
        "choice_id": choice_id
    }))
    .unwrap();
    assert_eq!(
        command,
        GameCommand::ResolveEventChoice {
            prompt_id: "a".repeat(64),
            choice_id: "b".repeat(64),
        }
    );

    for untrusted in [
        "actor_id",
        "event_name",
        "unit_id",
        "choice_index",
        "unique_effects",
        "random_seed",
        "outcome",
    ] {
        let mut value = serde_json::json!({
            "type": "resolve_event_choice",
            "prompt_id": "a".repeat(64),
            "choice_id": "b".repeat(64)
        });
        value
            .as_object_mut()
            .unwrap()
            .insert(untrusted.to_owned(), serde_json::json!(1));
        assert!(serde_json::from_value::<GameCommand>(value).is_err());
    }
}
