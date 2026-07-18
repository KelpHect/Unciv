use super::*;
use uuid::Uuid;

fn command(game_id: Uuid, command_id: Uuid, expected_revision: u64) -> CommandEnvelope {
    CommandEnvelope {
        protocol_version: PROTOCOL_VERSION,
        game_id,
        command_id,
        expected_revision,
        client_observed_state_hash: None,
        command: GameCommand::EndTurn,
    }
}

fn proposal(previous_revision: u64, snapshot: &[u8]) -> CommitProposal {
    CommitProposal {
        previous_revision,
        snapshot: snapshot.to_vec(),
        canonical_state_hash: state_hash(snapshot),
    }
}

#[test]
fn move_unit_contract_is_typed_and_closed() {
    let command: GameCommand = serde_json::from_value(serde_json::json!({
        "type": "move_unit",
        "unit_id": 42,
        "destination_x": -3,
        "destination_y": 7
    }))
    .unwrap();
    assert_eq!(
        command,
        GameCommand::MoveUnit {
            unit_id: 42,
            destination_x: -3,
            destination_y: 7,
        }
    );
    assert!(
        serde_json::from_value::<GameCommand>(serde_json::json!({
            "type": "move_unit",
            "unit_id": "42",
            "destination_tile_id": "-3,7"
        }))
        .is_err()
    );
    assert!(
        serde_json::from_value::<GameCommand>(serde_json::json!({
            "type": "move_unit",
            "unit_id": 42,
            "destination_x": -3,
            "destination_y": 7,
            "actor_id": "attacker-controlled"
        }))
        .is_err()
    );
}

#[test]
fn queue_construction_contract_is_typed_and_closed() {
    let command: GameCommand = serde_json::from_value(serde_json::json!({
        "type": "queue_construction",
        "city_id": "city-1",
        "construction_name": "Monument"
    }))
    .unwrap();
    assert_eq!(
        command,
        GameCommand::QueueConstruction {
            city_id: "city-1".to_owned(),
            construction_name: "Monument".to_owned(),
        }
    );
    assert!(
        serde_json::from_value::<GameCommand>(serde_json::json!({
            "type": "queue_construction",
            "city_id": "city-1",
            "construction_name": "Monument",
            "gold_cost": 1,
        }))
        .is_err()
    );
}

#[test]
fn perpetual_construction_contract_is_typed_and_closed() {
    let command: GameCommand = serde_json::from_value(serde_json::json!({
        "type": "set_perpetual_construction",
        "city_id": "city-1",
        "construction_name": "Nothing"
    }))
    .unwrap();
    assert_eq!(
        command,
        GameCommand::SetPerpetualConstruction {
            city_id: "city-1".to_owned(),
            construction_name: "Nothing".to_owned(),
        }
    );
    assert!(
        serde_json::from_value::<GameCommand>(serde_json::json!({
            "type": "set_perpetual_construction",
            "city_id": "city-1",
            "construction_name": "Nothing",
            "actor_id": "client-controlled"
        }))
        .is_err()
    );
}

#[test]
fn construction_queue_mutations_are_typed_and_closed() {
    let remove: GameCommand = serde_json::from_value(serde_json::json!({
        "type": "remove_construction",
        "city_id": "city-1",
        "queue_index": 2,
        "expected_construction_name": "Warrior"
    }))
    .unwrap();
    assert_eq!(
        remove,
        GameCommand::RemoveConstruction {
            city_id: "city-1".to_owned(),
            queue_index: 2,
            expected_construction_name: "Warrior".to_owned(),
        }
    );
    let move_command: GameCommand = serde_json::from_value(serde_json::json!({
        "type": "move_construction",
        "city_id": "city-1",
        "from_index": 2,
        "to_index": 1,
        "expected_construction_name": "Warrior"
    }))
    .unwrap();
    assert_eq!(
        move_command,
        GameCommand::MoveConstruction {
            city_id: "city-1".to_owned(),
            from_index: 2,
            to_index: 1,
            expected_construction_name: "Warrior".to_owned(),
        }
    );
    assert!(
        serde_json::from_value::<GameCommand>(serde_json::json!({
            "type": "remove_construction",
            "city_id": "city-1",
            "queue_index": 2,
            "expected_construction_name": "Warrior",
            "actor_id": "client-controlled"
        }))
        .is_err()
    );
}

#[test]
fn purchase_construction_contract_excludes_actor_and_price() {
    let command: GameCommand = serde_json::from_value(serde_json::json!({
        "type": "purchase_construction",
        "city_id": "city-1",
        "construction_name": "Monument",
        "currency_name": "Gold",
        "queue_index": 0
    }))
    .unwrap();
    assert_eq!(
        command,
        GameCommand::PurchaseConstruction {
            city_id: "city-1".to_owned(),
            construction_name: "Monument".to_owned(),
            currency_name: "Gold".to_owned(),
            queue_index: Some(0),
        }
    );
    assert!(
        serde_json::from_value::<GameCommand>(serde_json::json!({
            "type": "purchase_construction",
            "city_id": "city-1",
            "construction_name": "Monument",
            "currency_name": "Gold",
            "queue_index": 0,
            "price": 1
        }))
        .is_err()
    );
    assert!(
        serde_json::from_value::<GameCommand>(serde_json::json!({
            "type": "purchase_construction",
            "city_id": "city-1",
            "construction_name": "Monument",
            "currency_name": "Gold",
            "actor_id": "client-controlled"
        }))
        .is_err()
    );
}

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
fn adopt_policy_contract_is_typed_and_closed() {
    let command: GameCommand = serde_json::from_value(serde_json::json!({
        "type": "adopt_policy",
        "policy_name": "Tradition"
    }))
    .unwrap();
    assert_eq!(
        command,
        GameCommand::AdoptPolicy {
            policy_name: "Tradition".to_owned(),
        }
    );
    assert!(
        serde_json::from_value::<GameCommand>(serde_json::json!({
            "type": "adopt_policy",
            "policy_name": "Tradition",
            "free": true
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

#[tokio::test]
async fn oversized_snapshot_is_rejected_before_commit() {
    let repository = InMemoryGameRepository::default();
    let game = Uuid::new_v4();
    repository
        .create_game(game, b"revision-0".to_vec())
        .await
        .unwrap();
    let oversized = vec![b'x'; MAX_SNAPSHOT_BYTES + 1];
    let result = repository
        .commit(
            command(game, Uuid::new_v4(), 0),
            CommitProposal {
                previous_revision: 0,
                canonical_state_hash: String::new(),
                snapshot: oversized,
            },
        )
        .await;

    assert_eq!(result, Err(CommitError::SnapshotTooLarge));
}

#[tokio::test]
async fn duplicate_command_is_idempotent() {
    let repository = InMemoryGameRepository::default();
    let game = Uuid::new_v4();
    let command_id = Uuid::new_v4();
    repository
        .create_game(game, b"revision-0".to_vec())
        .await
        .unwrap();

    let first = repository
        .commit(command(game, command_id, 0), proposal(0, b"revision-1"))
        .await
        .unwrap();
    let retried = repository
        .commit(
            command(game, command_id, 0),
            proposal(0, b"malicious-replacement"),
        )
        .await
        .unwrap();

    assert_eq!(first, retried);
    assert_eq!(first.committed_revision, 1);
}

#[tokio::test]
async fn stale_command_cannot_replace_the_head() {
    let repository = InMemoryGameRepository::default();
    let game = Uuid::new_v4();
    repository
        .create_game(game, b"revision-0".to_vec())
        .await
        .unwrap();
    repository
        .commit(command(game, Uuid::new_v4(), 0), proposal(0, b"revision-1"))
        .await
        .unwrap();

    let error = repository
        .commit(
            command(game, Uuid::new_v4(), 0),
            proposal(0, b"replacement"),
        )
        .await
        .unwrap_err();

    assert_eq!(
        error,
        CommitError::Stale {
            expected: 0,
            actual: 1
        }
    );
}

#[tokio::test]
async fn concurrent_commands_have_one_canonical_commit() {
    let repository = InMemoryGameRepository::default();
    let game = Uuid::new_v4();
    repository
        .create_game(game, b"revision-0".to_vec())
        .await
        .unwrap();
    let first = repository.commit(
        command(game, Uuid::new_v4(), 0),
        proposal(0, b"revision-1a"),
    );
    let second = repository.commit(
        command(game, Uuid::new_v4(), 0),
        proposal(0, b"revision-1b"),
    );
    let (first, second) = tokio::join!(first, second);

    assert!(first.is_ok() ^ second.is_ok());
}
