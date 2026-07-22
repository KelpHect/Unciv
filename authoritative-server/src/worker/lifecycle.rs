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
}
