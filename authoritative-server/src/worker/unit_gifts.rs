use super::*;

impl EngineWorkerClient {
    pub async fn gift_unit(
        &self,
        actor_id: &str,
        manifest: &WorkerManifest,
        previous_revision: u64,
        snapshot: &str,
        intent: GiftUnitIntent<'_>,
    ) -> Result<CommitProposal, WorkerClientError> {
        let response = self
            .execute(
                actor_id,
                manifest,
                WorkerOperation::GiftUnit {
                    snapshot,
                    actor_civilization_id: intent.actor_civilization_id,
                    unit_id: intent.unit_id,
                },
            )
            .await?;
        commit_proposal(previous_revision, response)
    }
}
