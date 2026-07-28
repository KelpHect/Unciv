use super::*;

impl PostgresGameRepository {
    pub async fn execute_choose_religious_beliefs(
        &self,
        worker: &EngineWorkerClient,
        actor_account_id: Uuid,
        envelope: CommandEnvelope,
    ) -> Result<CommandAccepted, CommitError> {
        let (belief_names, religion_icon_name, religion_display_name) = match &envelope.command {
            crate::GameCommand::ChooseReligiousBeliefs {
                belief_names,
                religion_icon_name,
                religion_display_name,
            } => (
                belief_names.clone(),
                religion_icon_name.clone(),
                religion_display_name.clone(),
            ),
            _ => return Err(CommitError::InvalidCommand),
        };
        if let Some(accepted) = self.committed_command(&envelope, actor_account_id).await? {
            return Ok(accepted);
        }
        let worker_state = self.worker_command_state(envelope.game_id).await?;
        let actor_civilization_id = self
            .actor_civilization_id(envelope.game_id, actor_account_id)
            .await?;
        let proposal = worker.choose_religious_beliefs(
            &actor_account_id.to_string(), &worker_state.manifest, envelope.expected_revision,
            &worker_state.snapshot, ChooseReligiousBeliefsIntent {
                actor_civilization_id: &actor_civilization_id,
                belief_names: &belief_names,
                religion_icon_name: religion_icon_name.as_deref(),
                religion_display_name: religion_display_name.as_deref(),
            },
        ).await.map_err(|error| match error {
            crate::worker::WorkerClientError::Rejected(reason) => CommitError::WorkerRejected(reason),
            other => {
                eprintln!("authoritative worker ChooseReligiousBeliefs transport/protocol failure: {other}");
                CommitError::WorkerRevisionMismatch
            }
        })?;
        self.commit(actor_account_id, envelope, proposal).await
    }

    pub async fn execute_use_religious_unit(
        &self,
        worker: &EngineWorkerClient,
        actor_account_id: Uuid,
        envelope: CommandEnvelope,
    ) -> Result<CommandAccepted, CommitError> {
        let (unit_id, action) = match &envelope.command {
            crate::GameCommand::UseReligiousUnit { unit_id, action } => (*unit_id, *action),
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
            .use_religious_unit(
                &actor_account_id.to_string(),
                &worker_state.manifest,
                envelope.expected_revision,
                &worker_state.snapshot,
                UseReligiousUnitIntent {
                    actor_civilization_id: &actor_civilization_id,
                    unit_id,
                    action,
                },
            )
            .await
            .map_err(|error| match error {
                crate::worker::WorkerClientError::Rejected(reason) => {
                    CommitError::WorkerRejected(reason)
                }
                other => {
                    eprintln!(
                        "authoritative worker UseReligiousUnit transport/protocol failure: {other}"
                    );
                    CommitError::WorkerRevisionMismatch
                }
            })?;
        self.commit(actor_account_id, envelope, proposal).await
    }
}
