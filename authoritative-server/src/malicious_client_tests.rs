use uuid::Uuid;

use crate::{
    CommandEnvelope, CommitError, CommitProposal, GameCommand, InMemoryGameRepository,
    PROTOCOL_VERSION, state_hash,
};

fn command(game_id: Uuid, command_id: Uuid, expected_revision: u64) -> CommandEnvelope {
    CommandEnvelope {
        protocol_version: PROTOCOL_VERSION,
        game_id,
        command_id,
        expected_revision,
        client_observed_state_hash: None,
        command: GameCommand::EndTurn {},
    }
}

fn proposal(previous_revision: u64, snapshot: &[u8]) -> CommitProposal {
    CommitProposal {
        previous_revision,
        snapshot: snapshot.to_vec(),
        canonical_state_hash: state_hash(snapshot),
        server_time_millis: 0,
        replay_operation: serde_json::json!({"type": "malicious_client_test"}),
    }
}

#[tokio::test]
async fn changed_command_identity_cannot_reuse_a_committed_id() {
    let repository = InMemoryGameRepository::default();
    let game = Uuid::new_v4();
    let command_id = Uuid::new_v4();
    repository
        .create_game(game, b"revision-0".to_vec())
        .await
        .unwrap();
    repository
        .commit(command(game, command_id, 0), proposal(0, b"revision-1"))
        .await
        .unwrap();

    let changed = CommandEnvelope {
        command: GameCommand::Resign {},
        ..command(game, command_id, 0)
    };
    let error = repository
        .commit(changed, proposal(0, b"malicious-replacement"))
        .await
        .unwrap_err();

    assert_eq!(error, CommitError::IdempotencyConflict);
}

#[tokio::test]
async fn diagnostic_observed_hash_does_not_change_idempotency_identity() {
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

    let retry = CommandEnvelope {
        client_observed_state_hash: Some("diagnostic-only".to_owned()),
        ..command(game, command_id, 0)
    };
    let accepted = repository
        .commit(retry, proposal(0, b"must-not-replace"))
        .await
        .unwrap();

    assert_eq!(accepted, first);
}

#[test]
fn client_cannot_inject_actor_civilization_into_the_command_envelope() {
    let game = Uuid::new_v4();
    let command_id = Uuid::new_v4();
    let malicious = serde_json::json!({
        "protocol_version": PROTOCOL_VERSION,
        "game_id": game,
        "command_id": command_id,
        "expected_revision": 0,
        "client_observed_state_hash": null,
        "actor_civilization_id": "other-player",
        "command": {"type": "end_turn"}
    });

    assert!(serde_json::from_value::<CommandEnvelope>(malicious).is_err());
}
