use super::*;
use crate::projection::SpectatorProjection;

impl EngineWorkerClient {
    pub async fn project_spectator_state(
        &self,
        actor_id: &str,
        manifest: &WorkerManifest,
        snapshot: &str,
    ) -> Result<ProjectedSpectatorState, WorkerClientError> {
        let response = self
            .execute(
                actor_id,
                manifest,
                WorkerOperation::ProjectSpectatorState { snapshot },
            )
            .await?;
        let projection = response
            .spectator_projection
            .ok_or(WorkerClientError::Incomplete)?;
        let projection: SpectatorProjection =
            serde_json::from_value(projection).map_err(|_| WorkerClientError::Protocol)?;
        if !projection.victory_is_consistent() {
            return Err(WorkerClientError::Protocol);
        }
        Ok(ProjectedSpectatorState { projection })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn spectator_projection_operation_matches_kotlin_wire_names() {
        let value = serde_json::to_value(WorkerOperation::ProjectSpectatorState {
            snapshot: "snapshot",
        })
        .unwrap();
        assert_eq!(value["type"], "project_spectator_state");
        assert_eq!(value["snapshot"], "snapshot");
        assert_eq!(value.as_object().unwrap().len(), 2);
    }
}
