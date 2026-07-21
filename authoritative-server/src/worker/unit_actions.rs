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

    pub async fn upgrade_units(
        &self,
        actor_id: &str,
        manifest: &WorkerManifest,
        previous_revision: u64,
        snapshot: &str,
        intent: UpgradeUnitsIntent<'_>,
    ) -> Result<CommitProposal, WorkerClientError> {
        let response = self
            .execute(
                actor_id,
                manifest,
                WorkerOperation::UpgradeUnits {
                    snapshot,
                    actor_civilization_id: intent.actor_civilization_id,
                    unit_ids: intent.unit_ids,
                    target_unit_name: intent.target_unit_name,
                },
            )
            .await?;
        commit_proposal(previous_revision, response)
    }

    pub async fn promote_unit(
        &self,
        actor_id: &str,
        manifest: &WorkerManifest,
        previous_revision: u64,
        snapshot: &str,
        intent: PromoteUnitIntent<'_>,
    ) -> Result<CommitProposal, WorkerClientError> {
        let response = self
            .execute(
                actor_id,
                manifest,
                WorkerOperation::PromoteUnit {
                    snapshot,
                    actor_civilization_id: intent.actor_civilization_id,
                    unit_id: intent.unit_id,
                    promotion_names: intent.promotion_names,
                },
            )
            .await?;
        commit_proposal(previous_revision, response)
    }
}
