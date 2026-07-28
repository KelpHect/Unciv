use super::*;
use crate::worker::AddUnitToCapitalProjectIntent;

impl PostgresGameRepository {
    pub async fn execute_add_unit_to_capital_project(
        &self,
        worker: &EngineWorkerClient,
        actor_account_id: Uuid,
        envelope: CommandEnvelope,
    ) -> Result<CommandAccepted, CommitError> {
        let unit_id = match envelope.command {
            crate::GameCommand::AddUnitToCapitalProject { unit_id } => unit_id,
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
            .add_unit_to_capital_project(
                &actor_account_id.to_string(),
                &worker_state.manifest,
                envelope.expected_revision,
                &worker_state.snapshot,
                AddUnitToCapitalProjectIntent {
                    actor_civilization_id: &actor_civilization_id,
                    unit_id,
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
