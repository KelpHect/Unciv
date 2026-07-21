use super::*;

impl PostgresGameRepository {
    pub async fn execute_set_unit_exploration(
        &self,
        worker: &EngineWorkerClient,
        actor_account_id: Uuid,
        envelope: CommandEnvelope,
    ) -> Result<CommandAccepted, CommitError> {
        let (unit_id, enabled) = match &envelope.command {
            crate::GameCommand::SetUnitExploration { unit_id, enabled } => (*unit_id, *enabled),
            _ => return Err(CommitError::InvalidCommand),
        };
        self.execute_unit_order(
            worker,
            actor_account_id,
            envelope,
            unit_id,
            enabled,
            UnitOrderKind::Exploration,
        )
        .await
    }

    pub async fn execute_set_unit_automation(
        &self,
        worker: &EngineWorkerClient,
        actor_account_id: Uuid,
        envelope: CommandEnvelope,
    ) -> Result<CommandAccepted, CommitError> {
        let (unit_id, enabled) = match &envelope.command {
            crate::GameCommand::SetUnitAutomation { unit_id, enabled } => (*unit_id, *enabled),
            _ => return Err(CommitError::InvalidCommand),
        };
        self.execute_unit_order(
            worker,
            actor_account_id,
            envelope,
            unit_id,
            enabled,
            UnitOrderKind::Automation,
        )
        .await
    }

    async fn execute_unit_order(
        &self,
        worker: &EngineWorkerClient,
        actor_account_id: Uuid,
        envelope: CommandEnvelope,
        unit_id: i32,
        enabled: bool,
        kind: UnitOrderKind,
    ) -> Result<CommandAccepted, CommitError> {
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
        let proposal = match kind {
            UnitOrderKind::Exploration => {
                worker
                    .set_unit_exploration(
                        &actor_account_id.to_string(),
                        &worker_state.manifest,
                        envelope.expected_revision,
                        &worker_state.snapshot,
                        SetUnitExplorationIntent {
                            actor_civilization_id: &actor_civilization_id,
                            unit_id,
                            enabled,
                        },
                    )
                    .await
            }
            UnitOrderKind::Automation => {
                worker
                    .set_unit_automation(
                        &actor_account_id.to_string(),
                        &worker_state.manifest,
                        envelope.expected_revision,
                        &worker_state.snapshot,
                        SetUnitAutomationIntent {
                            actor_civilization_id: &actor_civilization_id,
                            unit_id,
                            enabled,
                        },
                    )
                    .await
            }
        }
        .map_err(|error| match error {
            crate::worker::WorkerClientError::Rejected(reason) => {
                CommitError::WorkerRejected(reason)
            }
            other => {
                eprintln!("authoritative worker unit-order transport/protocol failure: {other}");
                CommitError::WorkerRevisionMismatch
            }
        })?;
        self.commit(actor_account_id, envelope, proposal).await
    }
}

#[derive(Clone, Copy)]
enum UnitOrderKind {
    Exploration,
    Automation,
}
