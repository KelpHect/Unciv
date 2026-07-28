use super::*;

impl PostgresGameRepository {
    pub async fn execute_transform_unit(
        &self,
        worker: &EngineWorkerClient,
        actor: Uuid,
        envelope: CommandEnvelope,
    ) -> Result<CommandAccepted, CommitError> {
        if let Some(accepted) = self.committed_command(&envelope, actor).await? {
            return Ok(accepted);
        }
        let (unit_id, action_id) = match &envelope.command {
            crate::GameCommand::TransformUnit { unit_id, action_id } => {
                (*unit_id, action_id.clone())
            }
            _ => return Err(CommitError::InvalidCommand),
        };
        let state = self.worker_command_state(envelope.game_id).await?;
        let civilization = self.actor_civilization_id(envelope.game_id, actor).await?;
        let proposal = worker
            .transform_unit(
                &actor.to_string(),
                &state.manifest,
                envelope.expected_revision,
                &state.snapshot,
                TransformUnitIntent {
                    actor_civilization_id: &civilization,
                    unit_id,
                    action_id: &action_id,
                },
            )
            .await
            .map_err(|error| match error {
                crate::worker::WorkerClientError::Rejected(reason) => {
                    CommitError::WorkerRejected(reason)
                }
                _ => CommitError::WorkerRevisionMismatch,
            })?;
        self.commit(actor, envelope, proposal).await
    }
}
