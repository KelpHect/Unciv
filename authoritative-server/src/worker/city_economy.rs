use super::*;

impl EngineWorkerClient {
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
