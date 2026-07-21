use super::*;

impl EngineWorkerClient {
    pub async fn disband_unit(
        &self,
        actor_id: &str,
        manifest: &WorkerManifest,
        previous_revision: u64,
        snapshot: &str,
        intent: DisbandUnitIntent<'_>,
    ) -> Result<CommitProposal, WorkerClientError> {
        let response = self
            .execute(
                actor_id,
                manifest,
                WorkerOperation::DisbandUnit {
                    snapshot,
                    actor_civilization_id: intent.actor_civilization_id,
                    unit_id: intent.unit_id,
                },
            )
            .await?;
        commit_proposal(previous_revision, response)
    }
}
