//! Private Kotlin worker client. It sends typed intents and accepts only a
//! worker-produced snapshot/hash; it contains no Unciv rules.

use std::{net::SocketAddr, time::Duration};

use serde::{Deserialize, Serialize};
use thiserror::Error;
use tokio::{
    io::{AsyncReadExt, AsyncWriteExt},
    net::TcpStream,
    time::timeout,
};

use crate::CommitProposal;

pub const WORKER_PROTOCOL_VERSION: u16 = 1;
const MAX_FRAME_BYTES: usize = 16 * 1024 * 1024;

#[derive(Clone)]
pub struct EngineWorkerClient {
    address: SocketAddr,
    request_timeout: Duration,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct WorkerManifest {
    pub engine_build: String,
    pub base_ruleset: WorkerRuleset,
    pub mods: Vec<WorkerRuleset>,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
pub struct WorkerRuleset {
    pub name: String,
    pub sha256: String,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct WorkerRequest<'a> {
    protocol_version: u16,
    actor_id: &'a str,
    ruleset_manifest: &'a WorkerManifest,
    operation: WorkerOperation<'a>,
}

#[derive(Serialize)]
#[serde(tag = "type", rename_all = "snake_case")]
enum WorkerOperation<'a> {
    CreateGame { setup: &'a str },
    EndTurn { snapshot: &'a str },
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct WorkerResponse {
    protocol_version: u16,
    snapshot: Option<String>,
    canonical_state_hash: Option<String>,
    error: Option<WorkerError>,
}

#[derive(Deserialize)]
struct WorkerError {
    code: String,
    message: String,
}

#[derive(Debug, Error)]
pub enum WorkerClientError {
    #[error("worker transport failed")]
    Transport,
    #[error("worker frame exceeded its limit")]
    FrameTooLarge,
    #[error("worker returned an incompatible protocol")]
    Protocol,
    #[error("worker rejected execution: {0}")]
    Rejected(String),
    #[error("worker response was incomplete")]
    Incomplete,
}

impl EngineWorkerClient {
    pub fn new(address: SocketAddr, request_timeout: Duration) -> Self {
        Self {
            address,
            request_timeout,
        }
    }

    pub async fn end_turn(
        &self,
        actor_id: &str,
        manifest: &WorkerManifest,
        previous_revision: u64,
        snapshot: &str,
    ) -> Result<CommitProposal, WorkerClientError> {
        let request = WorkerRequest {
            protocol_version: WORKER_PROTOCOL_VERSION,
            actor_id,
            ruleset_manifest: manifest,
            operation: WorkerOperation::EndTurn { snapshot },
        };
        let payload = serde_json::to_vec(&request).map_err(|_| WorkerClientError::Transport)?;
        if payload.len() > MAX_FRAME_BYTES {
            return Err(WorkerClientError::FrameTooLarge);
        }
        let response = timeout(self.request_timeout, async {
            let mut stream = TcpStream::connect(self.address)
                .await
                .map_err(|_| WorkerClientError::Transport)?;
            stream
                .write_u32(payload.len() as u32)
                .await
                .map_err(|_| WorkerClientError::Transport)?;
            stream
                .write_all(&payload)
                .await
                .map_err(|_| WorkerClientError::Transport)?;
            stream
                .flush()
                .await
                .map_err(|_| WorkerClientError::Transport)?;
            let size = stream
                .read_u32()
                .await
                .map_err(|_| WorkerClientError::Transport)? as usize;
            if !(1..=MAX_FRAME_BYTES).contains(&size) {
                return Err(WorkerClientError::FrameTooLarge);
            }
            let mut response = vec![0; size];
            stream
                .read_exact(&mut response)
                .await
                .map_err(|_| WorkerClientError::Transport)?;
            serde_json::from_slice::<WorkerResponse>(&response)
                .map_err(|_| WorkerClientError::Transport)
        })
        .await
        .map_err(|_| WorkerClientError::Transport)??;
        if response.protocol_version != WORKER_PROTOCOL_VERSION {
            return Err(WorkerClientError::Protocol);
        }
        if let Some(error) = response.error {
            return Err(WorkerClientError::Rejected(format!(
                "{}: {}",
                error.code, error.message
            )));
        }
        Ok(CommitProposal {
            previous_revision,
            snapshot: response
                .snapshot
                .ok_or(WorkerClientError::Incomplete)?
                .into_bytes(),
            canonical_state_hash: response
                .canonical_state_hash
                .ok_or(WorkerClientError::Incomplete)?,
        })
    }

    /// Asks the Kotlin worker to create revision zero through `GameStarter`.
    /// `setup` is a setup intent, not an uploaded `GameInfo` or save payload.
    pub async fn create_game(
        &self,
        actor_id: &str,
        manifest: &WorkerManifest,
        setup: &str,
    ) -> Result<CommitProposal, WorkerClientError> {
        self.execute(actor_id, manifest, WorkerOperation::CreateGame { setup }, 0)
            .await
    }

    async fn execute(
        &self,
        actor_id: &str,
        manifest: &WorkerManifest,
        operation: WorkerOperation<'_>,
        previous_revision: u64,
    ) -> Result<CommitProposal, WorkerClientError> {
        let request = WorkerRequest {
            protocol_version: WORKER_PROTOCOL_VERSION,
            actor_id,
            ruleset_manifest: manifest,
            operation,
        };
        let payload = serde_json::to_vec(&request).map_err(|_| WorkerClientError::Transport)?;
        if payload.len() > MAX_FRAME_BYTES {
            return Err(WorkerClientError::FrameTooLarge);
        }
        let response = timeout(self.request_timeout, async {
            let mut stream = TcpStream::connect(self.address)
                .await
                .map_err(|_| WorkerClientError::Transport)?;
            stream
                .write_u32(payload.len() as u32)
                .await
                .map_err(|_| WorkerClientError::Transport)?;
            stream
                .write_all(&payload)
                .await
                .map_err(|_| WorkerClientError::Transport)?;
            stream
                .flush()
                .await
                .map_err(|_| WorkerClientError::Transport)?;
            let size = stream
                .read_u32()
                .await
                .map_err(|_| WorkerClientError::Transport)? as usize;
            if !(1..=MAX_FRAME_BYTES).contains(&size) {
                return Err(WorkerClientError::FrameTooLarge);
            }
            let mut response = vec![0; size];
            stream
                .read_exact(&mut response)
                .await
                .map_err(|_| WorkerClientError::Transport)?;
            serde_json::from_slice::<WorkerResponse>(&response)
                .map_err(|_| WorkerClientError::Transport)
        })
        .await
        .map_err(|_| WorkerClientError::Transport)??;
        if response.protocol_version != WORKER_PROTOCOL_VERSION {
            return Err(WorkerClientError::Protocol);
        }
        if let Some(error) = response.error {
            return Err(WorkerClientError::Rejected(format!(
                "{}: {}",
                error.code, error.message
            )));
        }
        Ok(CommitProposal {
            previous_revision,
            snapshot: response
                .snapshot
                .ok_or(WorkerClientError::Incomplete)?
                .into_bytes(),
            canonical_state_hash: response
                .canonical_state_hash
                .ok_or(WorkerClientError::Incomplete)?,
        })
    }
}
