use super::*;

impl PostgresGameRepository {
    pub async fn execute_sell_building(
        &self,
        worker: &EngineWorkerClient,
        actor_account_id: Uuid,
        envelope: CommandEnvelope,
    ) -> Result<CommandAccepted, CommitError> {
        let (city_id, building_name) = match &envelope.command {
            crate::GameCommand::SellBuilding {
                city_id,
                building_name,
            } => (city_id.clone(), building_name.clone()),
            _ => return Err(CommitError::InvalidCommand),
        };
        if let Some(accepted) = self.committed_command(&envelope, actor_account_id).await? {
            return Ok(accepted);
        }
        let worker_state = self.worker_command_state(envelope.game_id).await?;
        let actor_civilization_id = self
            .actor_civilization_id(envelope.game_id, actor_account_id)
            .await?;
        let proposal = worker
            .sell_building(
                &actor_account_id.to_string(),
                &worker_state.manifest,
                envelope.expected_revision,
                &worker_state.snapshot,
                SellBuildingIntent {
                    actor_civilization_id: &actor_civilization_id,
                    city_id: &city_id,
                    building_name: &building_name,
                },
            )
            .await
            .map_err(|error| match error {
                crate::worker::WorkerClientError::Rejected(reason) => {
                    CommitError::WorkerRejected(reason)
                }
                _ => CommitError::WorkerRevisionMismatch,
            })?;
        self.commit(actor_account_id, envelope, proposal).await
    }
}
