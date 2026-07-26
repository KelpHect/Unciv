use std::{collections::HashMap, sync::Arc};

use thiserror::Error;
use tokio::sync::Mutex;
use uuid::Uuid;

use crate::{
    CommandAccepted, CommandEnvelope, CommitProposal, MAX_SNAPSHOT_BYTES, PROTOCOL_VERSION,
    state_hash,
};

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
    #[error("snapshot exceeded the authoritative size limit")]
    SnapshotTooLarge,
    #[error("game is unavailable pending operator recovery")]
    GameUnavailable,
    #[error("bounded recovery evidence is missing or incomplete")]
    RecoveryEvidenceMissing,
    #[error("recovery journal tail exceeds the configured bound")]
    RecoveryTailTooLong,
    #[error("replayed state diverged from immutable revision history")]
    RecoveryDiverged,
    #[error("authenticated account is not allowed to mutate this game")]
    Unauthorized,
    #[error("command is not valid for the canonical game state")]
    InvalidCommand,
    #[error("database storage failure")]
    Storage,
}

impl CommitError {
    pub(crate) fn storage(_: sqlx::Error) -> Self {
        Self::Storage
    }
}

impl InMemoryGameRepository {
    pub async fn create_game(&self, game_id: Uuid, snapshot: Vec<u8>) -> Result<(), CommitError> {
        if snapshot.is_empty() || snapshot.len() > MAX_SNAPSHOT_BYTES {
            return Err(CommitError::SnapshotTooLarge);
        }
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
        if proposal.snapshot.is_empty() || proposal.snapshot.len() > MAX_SNAPSHOT_BYTES {
            return Err(CommitError::SnapshotTooLarge);
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
