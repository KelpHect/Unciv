//! Rust API-v3 control-plane primitives. Rule execution is intentionally absent:
//! a private Kotlin worker produces a validated [`CommitProposal`].

use std::{collections::HashMap, sync::Arc};

use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use thiserror::Error;
use tokio::sync::Mutex;
use uuid::Uuid;

pub mod auth;
pub mod notifications;
pub mod postgres;
pub mod worker;

pub const PROTOCOL_VERSION: u16 = 3;
pub const PROJECTION_VERSION: u16 = 2;

#[derive(Clone, Debug, Deserialize, Serialize, PartialEq, Eq)]
#[serde(tag = "type", rename_all = "snake_case", deny_unknown_fields)]
pub enum GameCommand {
    JoinGame,
    EndTurn,
    MoveUnit {
        unit_id: i32,
        destination_x: i32,
        destination_y: i32,
    },
    QueueConstruction {
        city_id: String,
        construction_name: String,
    },
}

#[derive(Clone, Debug, Deserialize, Serialize, PartialEq, Eq)]
pub struct CommandEnvelope {
    pub protocol_version: u16,
    pub game_id: Uuid,
    pub command_id: Uuid,
    pub expected_revision: u64,
    pub client_observed_state_hash: Option<String>,
    pub command: GameCommand,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct CommitProposal {
    pub previous_revision: u64,
    pub snapshot: Vec<u8>,
    pub canonical_state_hash: String,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
pub struct CommandAccepted {
    pub game_id: Uuid,
    pub command_id: Uuid,
    pub previous_revision: u64,
    pub committed_revision: u64,
    pub canonical_state_hash: String,
}

#[derive(Clone, Debug)]
struct GameHead {
    revision: u64,
    snapshot: Vec<u8>,
    canonical_state_hash: String,
    committed_commands: HashMap<Uuid, CommandAccepted>,
}

#[derive(Clone, Default)]
pub struct InMemoryGameRepository {
    games: Arc<Mutex<HashMap<Uuid, GameHead>>>,
}

#[derive(Debug, Error, PartialEq, Eq)]
pub enum CommitError {
    #[error("unsupported protocol version {0}")]
    UnsupportedProtocol(u16),
    #[error("game was not found")]
    NotFound,
    #[error("stale revision: expected {expected}, canonical head is {actual}")]
    Stale { expected: u64, actual: u64 },
    #[error("worker proposal did not start from the canonical head")]
    WorkerRevisionMismatch,
    #[error("worker rejected the command")]
    WorkerRejected(String),
    #[error("snapshot hash does not match its payload")]
    InvalidSnapshotHash,
    #[error("authenticated account is not allowed to mutate this game")]
    Unauthorized,
    #[error("command is not valid for the canonical game state")]
    InvalidCommand,
    #[error("database storage failure")]
    Storage,
}

impl CommitError {
    fn storage(_: sqlx::Error) -> Self {
        Self::Storage
    }
}

impl InMemoryGameRepository {
    pub async fn create_game(&self, game_id: Uuid, snapshot: Vec<u8>) -> Result<(), CommitError> {
        let hash = state_hash(&snapshot);
        let mut games = self.games.lock().await;
        games.insert(
            game_id,
            GameHead {
                revision: 0,
                snapshot,
                canonical_state_hash: hash,
                committed_commands: HashMap::new(),
            },
        );
        Ok(())
    }

    /// Serializes each game's commit path. PostgreSQL row locking/CAS replaces
    /// this process-local lock in production; this implementation defines the
    /// behavior covered by the same regression tests.
    pub async fn commit(
        &self,
        envelope: CommandEnvelope,
        proposal: CommitProposal,
    ) -> Result<CommandAccepted, CommitError> {
        if envelope.protocol_version != PROTOCOL_VERSION {
            return Err(CommitError::UnsupportedProtocol(envelope.protocol_version));
        }
        if state_hash(&proposal.snapshot) != proposal.canonical_state_hash {
            return Err(CommitError::InvalidSnapshotHash);
        }

        let mut games = self.games.lock().await;
        let head = games
            .get_mut(&envelope.game_id)
            .ok_or(CommitError::NotFound)?;
        if let Some(previous) = head.committed_commands.get(&envelope.command_id) {
            return Ok(previous.clone());
        }
        if envelope.expected_revision != head.revision {
            return Err(CommitError::Stale {
                expected: envelope.expected_revision,
                actual: head.revision,
            });
        }
        if proposal.previous_revision != head.revision {
            return Err(CommitError::WorkerRevisionMismatch);
        }

        let accepted = CommandAccepted {
            game_id: envelope.game_id,
            command_id: envelope.command_id,
            previous_revision: head.revision,
            committed_revision: head.revision + 1,
            canonical_state_hash: proposal.canonical_state_hash.clone(),
        };
        head.revision = accepted.committed_revision;
        head.snapshot = proposal.snapshot;
        head.canonical_state_hash = proposal.canonical_state_hash;
        head.committed_commands
            .insert(envelope.command_id, accepted.clone());
        Ok(accepted)
    }
}

pub fn state_hash(snapshot: &[u8]) -> String {
    Sha256::digest(snapshot)
        .iter()
        .map(|byte| format!("{byte:02x}"))
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

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
}
