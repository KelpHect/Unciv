use super::*;
use crate::worker::BuyCityTileBatchIntent;

impl PostgresGameRepository {
    pub async fn execute_buy_city_tile_batch(
        &self,
        worker: &EngineWorkerClient,
        actor_account_id: Uuid,
        envelope: CommandEnvelope,
    ) -> Result<CommandAccepted, CommitError> {
        let (city_id, ring) = match &envelope.command {
            crate::GameCommand::BuyCityTileBatch { city_id, ring } => (city_id.clone(), *ring),
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
            .buy_city_tile_batch(
                &actor_account_id.to_string(),
                &worker_state.manifest,
                envelope.expected_revision,
                &worker_state.snapshot,
                BuyCityTileBatchIntent {
                    actor_civilization_id: &actor_civilization_id,
                    city_id: &city_id,
                    ring,
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
