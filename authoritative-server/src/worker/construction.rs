use super::*;

impl EngineWorkerClient {
    pub async fn purchase_construction_at_tile(
        &self,
        actor_id: &str,
        manifest: &WorkerManifest,
        previous_revision: u64,
        snapshot: &str,
        intent: PurchaseConstructionAtTileIntent<'_>,
    ) -> Result<CommitProposal, WorkerClientError> {
        let response = self
            .execute(
                actor_id,
                manifest,
                WorkerOperation::PurchaseConstructionAtTile {
                    snapshot,
                    actor_civilization_id: intent.actor_civilization_id,
                    city_id: intent.city_id,
                    construction_name: intent.construction_name,
                    currency_name: intent.currency_name,
                    x: intent.x,
                    y: intent.y,
                    queue_index: intent.queue_index,
                },
            )
            .await?;
        commit_proposal(previous_revision, response)
    }

    pub async fn queue_construction_at_tile(
        &self,
        actor_id: &str,
        manifest: &WorkerManifest,
        previous_revision: u64,
        snapshot: &str,
        intent: QueueConstructionAtTileIntent<'_>,
    ) -> Result<CommitProposal, WorkerClientError> {
        let response = self
            .execute(
                actor_id,
                manifest,
                WorkerOperation::QueueConstructionAtTile {
                    snapshot,
                    actor_civilization_id: intent.actor_civilization_id,
                    city_id: intent.city_id,
                    construction_name: intent.construction_name,
                    x: intent.x,
                    y: intent.y,
                },
            )
            .await?;
        commit_proposal(previous_revision, response)
    }

    pub async fn buy_city_tile(
        &self,
        actor_id: &str,
        manifest: &WorkerManifest,
        previous_revision: u64,
        snapshot: &str,
        intent: BuyCityTileIntent<'_>,
    ) -> Result<CommitProposal, WorkerClientError> {
        let response = self
            .execute(
                actor_id,
                manifest,
                WorkerOperation::BuyCityTile {
                    snapshot,
                    actor_civilization_id: intent.actor_civilization_id,
                    city_id: intent.city_id,
                    x: intent.x,
                    y: intent.y,
                },
            )
            .await?;
        commit_proposal(previous_revision, response)
    }

    pub async fn set_perpetual_construction(
        &self,
        actor_id: &str,
        manifest: &WorkerManifest,
        previous_revision: u64,
        snapshot: &str,
        intent: SetPerpetualConstructionIntent<'_>,
    ) -> Result<CommitProposal, WorkerClientError> {
        let response = self
            .execute(
                actor_id,
                manifest,
                WorkerOperation::SetPerpetualConstruction {
                    snapshot,
                    actor_civilization_id: intent.actor_civilization_id,
                    city_id: intent.city_id,
                    construction_name: intent.construction_name,
                },
            )
            .await?;
        commit_proposal(previous_revision, response)
    }

    pub async fn purchase_construction(
        &self,
        actor_id: &str,
        manifest: &WorkerManifest,
        previous_revision: u64,
        snapshot: &str,
        intent: PurchaseConstructionIntent<'_>,
    ) -> Result<CommitProposal, WorkerClientError> {
        let response = self
            .execute(
                actor_id,
                manifest,
                WorkerOperation::PurchaseConstruction {
                    snapshot,
                    actor_civilization_id: intent.actor_civilization_id,
                    city_id: intent.city_id,
                    construction_name: intent.construction_name,
                    currency_name: intent.currency_name,
                    queue_index: intent.queue_index,
                },
            )
            .await?;
        commit_proposal(previous_revision, response)
    }

    pub async fn remove_construction(
        &self,
        actor_id: &str,
        manifest: &WorkerManifest,
        previous_revision: u64,
        snapshot: &str,
        intent: RemoveConstructionIntent<'_>,
    ) -> Result<CommitProposal, WorkerClientError> {
        let response = self
            .execute(
                actor_id,
                manifest,
                WorkerOperation::RemoveConstruction {
                    snapshot,
                    actor_civilization_id: intent.actor_civilization_id,
                    city_id: intent.city_id,
                    queue_index: intent.queue_index,
                    expected_construction_name: intent.expected_construction_name,
                },
            )
            .await?;
        commit_proposal(previous_revision, response)
    }

    pub async fn move_construction(
        &self,
        actor_id: &str,
        manifest: &WorkerManifest,
        previous_revision: u64,
        snapshot: &str,
        intent: MoveConstructionIntent<'_>,
    ) -> Result<CommitProposal, WorkerClientError> {
        let response = self
            .execute(
                actor_id,
                manifest,
                WorkerOperation::MoveConstruction {
                    snapshot,
                    actor_civilization_id: intent.actor_civilization_id,
                    city_id: intent.city_id,
                    from_index: intent.from_index,
                    to_index: intent.to_index,
                    expected_construction_name: intent.expected_construction_name,
                },
            )
            .await?;
        commit_proposal(previous_revision, response)
    }

    pub async fn queue_construction(
        &self,
        actor_id: &str,
        manifest: &WorkerManifest,
        previous_revision: u64,
        snapshot: &str,
        intent: QueueConstructionIntent<'_>,
    ) -> Result<CommitProposal, WorkerClientError> {
        let response = self
            .execute(
                actor_id,
                manifest,
                WorkerOperation::QueueConstruction {
                    snapshot,
                    actor_civilization_id: intent.actor_civilization_id,
                    city_id: intent.city_id,
                    construction_name: intent.construction_name,
                },
            )
            .await?;
        commit_proposal(previous_revision, response)
    }
}
