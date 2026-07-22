use super::*;

impl EngineWorkerClient {
    pub async fn manage_construction_queues(
        &self,
        actor_id: &str,
        manifest: &WorkerManifest,
        previous_revision: u64,
        snapshot: &str,
        intent: ManageConstructionQueuesIntent<'_>,
    ) -> Result<CommitProposal, WorkerClientError> {
        let response = self
            .execute(
                actor_id,
                manifest,
                WorkerOperation::ManageConstructionQueues {
                    snapshot,
                    actor_civilization_id: intent.actor_civilization_id,
                    city_id: intent.city_id,
                    construction_name: intent.construction_name,
                    queue_index: intent.queue_index,
                    action: intent.action,
                },
            )
            .await?;
        commit_proposal(previous_revision, response)
    }

    pub async fn sell_building(
        &self,
        actor_id: &str,
        manifest: &WorkerManifest,
        previous_revision: u64,
        snapshot: &str,
        intent: SellBuildingIntent<'_>,
    ) -> Result<CommitProposal, WorkerClientError> {
        let response = self
            .execute(
                actor_id,
                manifest,
                WorkerOperation::SellBuilding {
                    snapshot,
                    actor_civilization_id: intent.actor_civilization_id,
                    city_id: intent.city_id,
                    building_name: intent.building_name,
                },
            )
            .await?;
        commit_proposal(previous_revision, response)
    }
}
