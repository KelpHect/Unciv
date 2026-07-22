use crate::*;

#[test]
fn partner_diplomacy_commands_exclude_actor_rules_and_outcomes() {
    for command_type in ["declare_war", "denounce_civilization", "offer_friendship"] {
        let value = serde_json::json!({ "type": command_type, "other_civilization_id": "Greece" });
        assert!(serde_json::from_value::<GameCommand>(value.clone()).is_ok());
        for field in ["actor", "can_execute", "diplomatic_status", "outcome"] {
            let mut forged = value.clone();
            forged[field] = serde_json::json!(true);
            assert!(serde_json::from_value::<GameCommand>(forged).is_err());
        }
    }
}

#[test]
fn demands_and_responses_use_closed_types_and_projected_prompt_ids() {
    let demand: GameCommand = serde_json::from_value(serde_json::json!({
        "type": "make_diplomatic_demand", "other_civilization_id": "Greece",
        "demand": "do_not_settle_near_us"
    }))
    .unwrap();
    assert!(matches!(
        demand,
        GameCommand::MakeDiplomaticDemand {
            demand: crate::projection::DiplomaticDemand::DoNotSettleNearUs,
            ..
        }
    ));

    let response: GameCommand = serde_json::from_value(serde_json::json!({
        "type": "respond_to_diplomatic_prompt", "prompt_id": "a".repeat(64), "accept": true
    }))
    .unwrap();
    assert!(matches!(
        response,
        GameCommand::RespondToDiplomaticPrompt { accept: true, .. }
    ));
    let mut forged = serde_json::to_value(response).unwrap();
    forged["diplomatic_modifier"] = serde_json::json!(-35);
    assert!(serde_json::from_value::<GameCommand>(forged).is_err());
}
