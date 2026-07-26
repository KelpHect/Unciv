use serde::Serialize;
use tokio::{
    io::{AsyncReadExt, AsyncWriteExt},
    net::TcpStream,
    time::timeout,
};

use super::*;

impl EngineWorkerClient {
    pub(crate) async fn replay_operation(
        &self,
        previous_revision: u64,
        actor_id: &str,
        manifest: &WorkerManifest,
        server_time_millis: i64,
        snapshot: &str,
        replay_operation: serde_json::Value,
    ) -> Result<CommitProposal, WorkerClientError> {
        if server_time_millis < 0 {
            return Err(WorkerClientError::Protocol);
        }
        let mut operation = replay_operation.clone();
        let fields = operation
            .as_object_mut()
            .ok_or(WorkerClientError::Protocol)?;
        if fields
            .get("type")
            .and_then(serde_json::Value::as_str)
            .is_none()
            || fields.contains_key("snapshot")
        {
            return Err(WorkerClientError::Protocol);
        }
        fields.insert(
            "snapshot".to_owned(),
            serde_json::Value::String(snapshot.to_owned()),
        );
        let request = serde_json::json!({
            "protocolVersion": WORKER_PROTOCOL_VERSION,
            "serverTimeMillis": server_time_millis,
            "actorId": actor_id,
            "rulesetManifest": manifest,
            "operation": operation,
        });
        let mut response = self.execute_request(request).await?;
        if response.server_time_millis != Some(server_time_millis) {
            return Err(WorkerClientError::Protocol);
        }
        response.replay_operation = Some(replay_operation);
        commit_proposal(previous_revision, response)
    }

    pub(super) async fn execute(
        &self,
        actor_id: &str,
        manifest: &WorkerManifest,
        operation: WorkerOperation<'_>,
    ) -> Result<WorkerResponse, WorkerClientError> {
        let server_time_millis = server_time_millis()?;
        let mut replay_operation =
            serde_json::to_value(&operation).map_err(|_| WorkerClientError::Transport)?;
        replay_operation
            .as_object_mut()
            .ok_or(WorkerClientError::Protocol)?
            .remove("snapshot");
        let request = WorkerRequest {
            protocol_version: WORKER_PROTOCOL_VERSION,
            server_time_millis: Some(server_time_millis),
            actor_id: Some(actor_id),
            ruleset_manifest: Some(manifest),
            operation,
        };
        let mut response = self.execute_request(request).await?;
        if response.server_time_millis != Some(server_time_millis) {
            return Err(WorkerClientError::Protocol);
        }
        response.replay_operation = Some(replay_operation);
        Ok(response)
    }

    pub(super) async fn execute_request(
        &self,
        request: impl Serialize,
    ) -> Result<WorkerResponse, WorkerClientError> {
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
        Ok(response)
    }
}
