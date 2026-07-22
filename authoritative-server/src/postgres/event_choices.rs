use super::commands::WorkerCommandState;
use super::*;

impl PostgresGameRepository {
    pub async fn execute_resolve_event_choice(
        &self,
        worker: &EngineWorkerClient,
        actor: Uuid,
        envelope: CommandEnvelope,
    ) -> Result<CommandAccepted, CommitError> {
        if let Some(accepted) = self
            .committed_command(envelope.game_id, envelope.command_id, actor)
            .await?
        {
            return Ok(accepted);
        }
        let (prompt_id, choice_id) = match &envelope.command {
            crate::GameCommand::ResolveEventChoice {
                prompt_id,
                choice_id,
            } => (prompt_id.clone(), choice_id.clone()),
            _ => return Err(CommitError::InvalidCommand),
        };
        let WorkerCommandState { manifest, snapshot } =
            self.worker_command_state(envelope.game_id).await?;
        let civilization = self.actor_civilization_id(envelope.game_id, actor).await?;
        let proposal = worker
            .resolve_event_choice(
                &actor.to_string(),
                &manifest,
                envelope.expected_revision,
                &snapshot,
                ResolveEventChoiceIntent {
                    actor_civilization_id: &civilization,
                    prompt_id: &prompt_id,
                    choice_id: &choice_id,
                },
            )
            .await
            .map_err(|error| match error {
                crate::worker::WorkerClientError::Rejected(reason) => {
                    CommitError::WorkerRejected(reason)
                }
                other => {
                    eprintln!(
                        "authoritative worker event-choice transport/protocol failure: {other}"
                    );
                    CommitError::WorkerRevisionMismatch
                }
            })?;
        self.commit(actor, envelope, proposal).await
    }
}
