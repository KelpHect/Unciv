use super::*;

impl EngineWorkerClient {
    pub async fn set_city_governance(
        &self,
        actor_id: &str,
        manifest: &WorkerManifest,
        previous_revision: u64,
        snapshot: &str,
        intent: SetCityGovernanceIntent<'_>,
    ) -> Result<CommitProposal, WorkerClientError> {
        let response = self
            .execute(
                actor_id,
                manifest,
                WorkerOperation::SetCityGovernance {
                    snapshot,
                    actor_civilization_id: intent.actor_civilization_id,
                    city_id: intent.city_id,
                    action: intent.action,
                },
            )
            .await?;
        commit_proposal(previous_revision, response)
    }
}
