use super::*;

impl EngineWorkerClient {
    pub async fn resign(
        &self,
        actor_id: &str,
        manifest: &WorkerManifest,
        previous_revision: u64,
        snapshot: &str,
        actor_civilization_id: &str,
    ) -> Result<CommitProposal, WorkerClientError> {
        let response = self
            .execute(
                actor_id,
                manifest,
                WorkerOperation::Resign {
                    snapshot,
                    actor_civilization_id,
                },
            )
            .await?;
        commit_proposal(previous_revision, response)
    }

    pub async fn force_resign(
        &self,
        actor_id: &str,
        manifest: &WorkerManifest,
        previous_revision: u64,
        snapshot: &str,
        actor_civilization_id: &str,
    ) -> Result<ForcedResignation, WorkerClientError> {
        let response = self
            .execute(
                actor_id,
                manifest,
                WorkerOperation::ForceResign {
                    snapshot,
                    actor_civilization_id,
                },
            )
            .await?;
        let civilization_id = response
            .actor_civilization_id
            .clone()
            .ok_or(WorkerClientError::Incomplete)?;
        Ok(ForcedResignation {
            proposal: commit_proposal(previous_revision, response)?,
            civilization_id,
        })
    }
}
