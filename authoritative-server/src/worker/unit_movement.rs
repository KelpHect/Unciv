use super::*;

impl EngineWorkerClient {
    pub async fn cancel_unit_movement_order(
        &self,
        actor_id: &str,
        manifest: &WorkerManifest,
        previous_revision: u64,
        snapshot: &str,
        intent: CancelUnitMovementOrderIntent<'_>,
    ) -> Result<CommitProposal, WorkerClientError> {
        let response = self
            .execute(
                actor_id,
                manifest,
                WorkerOperation::CancelUnitMovementOrder {
                    snapshot,
                    actor_civilization_id: intent.actor_civilization_id,
                    unit_id: intent.unit_id,
                },
            )
            .await?;
        commit_proposal(previous_revision, response)
    }

    pub async fn move_unit_toward(
        &self,
        actor_id: &str,
        manifest: &WorkerManifest,
        previous_revision: u64,
        snapshot: &str,
        intent: MoveUnitTowardIntent<'_>,
    ) -> Result<CommitProposal, WorkerClientError> {
        let response = self
            .execute(
                actor_id,
                manifest,
                WorkerOperation::MoveUnitToward {
                    snapshot,
                    actor_civilization_id: intent.actor_civilization_id,
                    unit_id: intent.unit_id,
                    destination_x: intent.destination_x,
                    destination_y: intent.destination_y,
                },
            )
            .await?;
        commit_proposal(previous_revision, response)
    }

    pub async fn swap_units(
        &self,
        actor_id: &str,
        manifest: &WorkerManifest,
        previous_revision: u64,
        snapshot: &str,
        intent: SwapUnitsIntent<'_>,
    ) -> Result<CommitProposal, WorkerClientError> {
        let response = self
            .execute(
                actor_id,
                manifest,
                WorkerOperation::SwapUnits {
                    snapshot,
                    actor_civilization_id: intent.actor_civilization_id,
                    unit_id: intent.unit_id,
                    destination_x: intent.destination_x,
                    destination_y: intent.destination_y,
                },
            )
            .await?;
        commit_proposal(previous_revision, response)
    }

    pub async fn move_unit(
        &self,
        actor_id: &str,
        manifest: &WorkerManifest,
        previous_revision: u64,
        snapshot: &str,
        intent: MoveUnitIntent<'_>,
    ) -> Result<CommitProposal, WorkerClientError> {
        let response = self
            .execute(
                actor_id,
                manifest,
                WorkerOperation::MoveUnit {
                    snapshot,
                    actor_civilization_id: intent.actor_civilization_id,
                    unit_id: intent.unit_id,
                    destination_x: intent.destination_x,
                    destination_y: intent.destination_y,
                    escort_unit_id: intent.escort_unit_id,
                },
            )
            .await?;
        commit_proposal(previous_revision, response)
    }
}
