use super::*;

impl PostgresGameRepository {
    pub async fn execute_resign(
        &self,
        worker: &EngineWorkerClient,
        actor: Uuid,
        envelope: CommandEnvelope,
    ) -> Result<CommandAccepted, CommitError> {
        if !matches!(envelope.command, crate::GameCommand::Resign {}) {
            return Err(CommitError::InvalidCommand);
        }
        if let Some(accepted) = self
            .committed_command(envelope.game_id, envelope.command_id, actor)
            .await?
        {
            return Ok(accepted);
        }
        let state = self.worker_command_state(envelope.game_id).await?;
        let civilization = self.actor_civilization_id(envelope.game_id, actor).await?;
        let proposal = worker
            .resign(
                &actor.to_string(),
                &state.manifest,
                envelope.expected_revision,
                &state.snapshot,
                &civilization,
            )
            .await
            .map_err(|error| match error {
                crate::worker::WorkerClientError::Rejected(reason) => {
                    CommitError::WorkerRejected(reason)
                }
                other => {
                    eprintln!("authoritative worker resign transport/protocol failure: {other}");
                    CommitError::WorkerRevisionMismatch
                }
            })?;
        self.commit_resignation(actor, envelope, proposal).await
    }
}
