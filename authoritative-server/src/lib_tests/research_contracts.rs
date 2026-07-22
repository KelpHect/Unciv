use super::*;

#[test]
fn set_research_path_contract_is_typed_and_closed() {
    let command: GameCommand = serde_json::from_value(serde_json::json!({
        "type": "set_research_path",
        "technology_name": "Writing"
    }))
    .unwrap();
    assert_eq!(
        command,
        GameCommand::SetResearchPath {
            technology_name: "Writing".to_owned(),
            append: false,
        }
    );
    assert_eq!(
        serde_json::from_value::<GameCommand>(serde_json::json!({
            "type": "set_research_path",
            "technology_name": "Writing",
            "append": true
        }))
        .unwrap(),
        GameCommand::SetResearchPath {
            technology_name: "Writing".to_owned(),
            append: true,
        }
    );
    assert!(
        serde_json::from_value::<GameCommand>(serde_json::json!({
            "type": "set_research_path",
            "technology_name": "Writing",
            "research_queue": ["Pottery", "Writing"]
        }))
        .is_err()
    );
}

#[test]
fn choose_free_technology_contract_is_typed_and_closed() {
    let command: GameCommand = serde_json::from_value(serde_json::json!({
        "type": "choose_free_technology",
        "technology_name": "Writing"
    }))
    .unwrap();
    assert_eq!(
        command,
        GameCommand::ChooseFreeTechnology {
            technology_name: "Writing".to_owned(),
        }
    );
    assert!(
        serde_json::from_value::<GameCommand>(serde_json::json!({
            "type": "choose_free_technology",
            "technology_name": "Writing",
            "free_techs": 99
        }))
        .is_err()
    );
}

#[test]
fn research_completion_acknowledgment_is_opaque_and_closed() {
    let prompt_id = "a".repeat(64);
    let command: GameCommand = serde_json::from_value(serde_json::json!({
        "type": "acknowledge_research_completion",
        "prompt_id": prompt_id,
    }))
    .unwrap();
    assert_eq!(
        command,
        GameCommand::AcknowledgeResearchCompletion {
            prompt_id: "a".repeat(64),
        }
    );
    assert!(
        serde_json::from_value::<GameCommand>(serde_json::json!({
            "type": "acknowledge_research_completion",
            "prompt_id": "a".repeat(64),
            "technology_name": "Mining"
        }))
        .is_err()
    );
}
