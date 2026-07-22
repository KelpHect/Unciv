use super::*;

impl EngineWorkerClient {
    pub async fn trigger_unit_unique(
        &self,
        actor_id: &str,
        manifest: &WorkerManifest,
        previous_revision: u64,
        snapshot: &str,
        intent: TriggerUnitUniqueIntent<'_>,
    ) -> Result<CommitProposal, WorkerClientError> {
        let response = self
            .execute(
                actor_id,
                manifest,
                WorkerOperation::TriggerUnitUnique {
                    snapshot,
                    actor_civilization_id: intent.actor_civilization_id,
                    unit_id: intent.unit_id,
                    action_id: intent.action_id,
                },
            )
            .await?;
        commit_proposal(previous_revision, response)
    }
}
