use super::*;

impl PostgresGameRepository {
    pub async fn execute_disband_unit(
        &self,
        worker: &EngineWorkerClient,
        actor_account_id: Uuid,
        envelope: CommandEnvelope,
    ) -> Result<CommandAccepted, CommitError> {
        let unit_id = match &envelope.command {
            crate::GameCommand::DisbandUnit { unit_id } => *unit_id,
            _ => return Err(CommitError::InvalidCommand),
        };
        if let Some(accepted) = self
            .committed_command(envelope.game_id, envelope.command_id, actor_account_id)
            .await?
        {
            return Ok(accepted);
        }
        let worker_state = self.worker_command_state(envelope.game_id).await?;
        let actor_civilization_id = self
            .actor_civilization_id(envelope.game_id, actor_account_id)
            .await?;
        let proposal = worker
            .disband_unit(
                &actor_account_id.to_string(),
                &worker_state.manifest,
                envelope.expected_revision,
                &worker_state.snapshot,
                DisbandUnitIntent {
                    actor_civilization_id: &actor_civilization_id,
                    unit_id,
                },
            )
            .await
            .map_err(|error| match error {
                crate::worker::WorkerClientError::Rejected(reason) => {
                    CommitError::WorkerRejected(reason)
                }
                other => {
                    eprintln!(
                        "authoritative worker DisbandUnit transport/protocol failure: {other}"
                    );
                    CommitError::WorkerRevisionMismatch
                }
            })?;
        self.commit(actor_account_id, envelope, proposal).await
    }

    pub async fn execute_upgrade_units(
        &self,
        worker: &EngineWorkerClient,
        actor_account_id: Uuid,
        envelope: CommandEnvelope,
    ) -> Result<CommandAccepted, CommitError> {
        let (unit_ids, target_unit_name) = match &envelope.command {
            crate::GameCommand::UpgradeUnits {
                unit_ids,
                target_unit_name,
            } => (unit_ids.clone(), target_unit_name.clone()),
            _ => return Err(CommitError::InvalidCommand),
        };
        if let Some(accepted) = self
            .committed_command(envelope.game_id, envelope.command_id, actor_account_id)
            .await?
        {
            return Ok(accepted);
        }
        let worker_state = self.worker_command_state(envelope.game_id).await?;
        let actor_civilization_id = self
            .actor_civilization_id(envelope.game_id, actor_account_id)
            .await?;
        let proposal = worker
            .upgrade_units(
                &actor_account_id.to_string(),
                &worker_state.manifest,
                envelope.expected_revision,
                &worker_state.snapshot,
                UpgradeUnitsIntent {
                    actor_civilization_id: &actor_civilization_id,
                    unit_ids: &unit_ids,
                    target_unit_name: &target_unit_name,
                },
            )
            .await
            .map_err(|error| match error {
                crate::worker::WorkerClientError::Rejected(reason) => {
                    CommitError::WorkerRejected(reason)
                }
                other => {
                    eprintln!(
                        "authoritative worker UpgradeUnits transport/protocol failure: {other}"
                    );
                    CommitError::WorkerRevisionMismatch
                }
            })?;
        self.commit(actor_account_id, envelope, proposal).await
    }

    pub async fn execute_promote_unit(
        &self,
        worker: &EngineWorkerClient,
        actor_account_id: Uuid,
        envelope: CommandEnvelope,
    ) -> Result<CommandAccepted, CommitError> {
        let (unit_id, promotion_names) = match &envelope.command {
            crate::GameCommand::PromoteUnit {
                unit_id,
                promotion_names,
            } => (*unit_id, promotion_names.clone()),
            _ => return Err(CommitError::InvalidCommand),
        };
        if let Some(accepted) = self
            .committed_command(envelope.game_id, envelope.command_id, actor_account_id)
            .await?
        {
            return Ok(accepted);
        }
        let worker_state = self.worker_command_state(envelope.game_id).await?;
        let actor_civilization_id = self
            .actor_civilization_id(envelope.game_id, actor_account_id)
            .await?;
        let proposal = worker
            .promote_unit(
                &actor_account_id.to_string(),
                &worker_state.manifest,
                envelope.expected_revision,
                &worker_state.snapshot,
                PromoteUnitIntent {
                    actor_civilization_id: &actor_civilization_id,
                    unit_id,
                    promotion_names: &promotion_names,
                },
            )
            .await
            .map_err(|error| match error {
                crate::worker::WorkerClientError::Rejected(reason) => {
                    CommitError::WorkerRejected(reason)
                }
                other => {
                    eprintln!(
                        "authoritative worker PromoteUnit transport/protocol failure: {other}"
                    );
                    CommitError::WorkerRevisionMismatch
                }
            })?;
        self.commit(actor_account_id, envelope, proposal).await
    }
}
