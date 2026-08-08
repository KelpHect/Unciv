use super::*;
use crate::projection::ReplayProjection;

impl EngineWorkerClient {
    pub async fn project_replay_state(
        &self,
        actor_id: &str,
        manifest: &WorkerManifest,
        snapshot: &str,
    ) -> Result<ReplayProjection, WorkerClientError> {
        let response = self
            .execute(
                actor_id,
                manifest,
                WorkerOperation::ProjectReplayState { snapshot },
            )
            .await?;
        let projection = response
            .replay_projection
            .ok_or(WorkerClientError::Incomplete)?;
        let projection: ReplayProjection =
            serde_json::from_value(projection).map_err(|_| WorkerClientError::Protocol)?;
        if !projection.victory_is_consistent() {
            return Err(WorkerClientError::Protocol);
        }
        Ok(projection)
    }
}
