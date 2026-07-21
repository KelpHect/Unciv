use super::*;

impl EngineWorkerClient {
    pub async fn set_unit_exploration(
        &self,
        actor_id: &str,
        manifest: &WorkerManifest,
        previous_revision: u64,
        snapshot: &str,
        intent: SetUnitExplorationIntent<'_>,
    ) -> Result<CommitProposal, WorkerClientError> {
        let response = self
            .execute(
                actor_id,
                manifest,
                WorkerOperation::SetUnitExploration {
                    snapshot,
                    actor_civilization_id: intent.actor_civilization_id,
                    unit_id: intent.unit_id,
                    enabled: intent.enabled,
                },
            )
            .await?;
        commit_proposal(previous_revision, response)
    }

    pub async fn set_unit_automation(
        &self,
        actor_id: &str,
        manifest: &WorkerManifest,
        previous_revision: u64,
        snapshot: &str,
        intent: SetUnitAutomationIntent<'_>,
    ) -> Result<CommitProposal, WorkerClientError> {
        let response = self
            .execute(
                actor_id,
                manifest,
                WorkerOperation::SetUnitAutomation {
                    snapshot,
                    actor_civilization_id: intent.actor_civilization_id,
                    unit_id: intent.unit_id,
                    enabled: intent.enabled,
                },
            )
            .await?;
        commit_proposal(previous_revision, response)
    }
}
