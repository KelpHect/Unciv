use super::*;

impl PostgresGameRepository {
    pub async fn execute_cancel_unit_movement_order(
        &self,
        worker: &EngineWorkerClient,
        actor_account_id: Uuid,
        envelope: CommandEnvelope,
    ) -> Result<CommandAccepted, CommitError> {
        let unit_id = match &envelope.command {
            crate::GameCommand::CancelUnitMovementOrder { unit_id } => *unit_id,
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
            .cancel_unit_movement_order(
                &actor_account_id.to_string(),
                &worker_state.manifest,
                envelope.expected_revision,
                &worker_state.snapshot,
                CancelUnitMovementOrderIntent {
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

    pub async fn execute_move_unit_toward(
        &self,
        worker: &EngineWorkerClient,
        actor_account_id: Uuid,
        envelope: CommandEnvelope,
    ) -> Result<CommandAccepted, CommitError> {
        let (unit_id, destination_x, destination_y, escort_unit_id) = match &envelope.command {
            crate::GameCommand::MoveUnitToward {
                unit_id,
                destination_x,
                destination_y,
                escort_unit_id,
            } => (*unit_id, *destination_x, *destination_y, *escort_unit_id),
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
            .move_unit_toward(
                &actor_account_id.to_string(),
                &worker_state.manifest,
                envelope.expected_revision,
                &worker_state.snapshot,
                MoveUnitTowardIntent {
                    actor_civilization_id: &actor_civilization_id,
                    unit_id,
                    destination_x,
                    destination_y,
                    escort_unit_id,
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

    pub async fn execute_swap_units(
        &self,
        worker: &EngineWorkerClient,
        actor_account_id: Uuid,
        envelope: CommandEnvelope,
    ) -> Result<CommandAccepted, CommitError> {
        let (unit_id, destination_x, destination_y) = match &envelope.command {
            crate::GameCommand::SwapUnits {
                unit_id,
                destination_x,
                destination_y,
            } => (*unit_id, *destination_x, *destination_y),
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
            .swap_units(
                &actor_account_id.to_string(),
                &worker_state.manifest,
                envelope.expected_revision,
                &worker_state.snapshot,
                SwapUnitsIntent {
                    actor_civilization_id: &actor_civilization_id,
                    unit_id,
                    destination_x,
                    destination_y,
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

    pub async fn execute_move_unit(
        &self,
        worker: &EngineWorkerClient,
        actor_account_id: Uuid,
        envelope: CommandEnvelope,
    ) -> Result<CommandAccepted, CommitError> {
        let (unit_id, destination_x, destination_y, escort_unit_id) = match &envelope.command {
            crate::GameCommand::MoveUnit {
                unit_id,
                destination_x,
                destination_y,
                escort_unit_id,
            } => (*unit_id, *destination_x, *destination_y, *escort_unit_id),
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
            .move_unit(
                &actor_account_id.to_string(),
                &worker_state.manifest,
                envelope.expected_revision,
                &worker_state.snapshot,
                MoveUnitIntent {
                    actor_civilization_id: &actor_civilization_id,
                    unit_id,
                    destination_x,
                    destination_y,
                    escort_unit_id,
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
