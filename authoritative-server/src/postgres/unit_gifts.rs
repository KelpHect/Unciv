use super::*;

impl PostgresGameRepository {
    pub async fn execute_gift_unit(
        &self,
        worker: &EngineWorkerClient,
        actor: Uuid,
        envelope: CommandEnvelope,
    ) -> Result<CommandAccepted, CommitError> {
        if let Some(accepted) = self.committed_command(&envelope, actor).await? {
            return Ok(accepted);
        }
        let unit_id = match &envelope.command {
            crate::GameCommand::GiftUnit { unit_id } => *unit_id,
            _ => return Err(CommitError::InvalidCommand),
        };
        let state = self.worker_command_state(envelope.game_id).await?;
        let civilization = self.actor_civilization_id(envelope.game_id, actor).await?;
        let proposal = worker
            .gift_unit(
                &actor.to_string(),
                &state.manifest,
                envelope.expected_revision,
                &state.snapshot,
                GiftUnitIntent {
                    actor_civilization_id: &civilization,
                    unit_id,
                },
            )
            .await
            .map_err(|error| match error {
                crate::worker::WorkerClientError::Rejected(reason) => {
                    CommitError::WorkerRejected(reason)
                }
                other => {
                    eprintln!("authoritative worker unit-gift transport/protocol failure: {other}");
                    CommitError::WorkerRevisionMismatch
                }
            })?;
        self.commit(actor, envelope, proposal).await
    }
}
