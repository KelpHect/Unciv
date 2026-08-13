use super::*;

impl PostgresGameRepository {
    pub async fn execute_queue_construction(
        &self,
        worker: &EngineWorkerClient,
        actor_account_id: Uuid,
        envelope: CommandEnvelope,
    ) -> Result<CommandAccepted, CommitError> {
        let (city_id, construction_name) = match &envelope.command {
            crate::GameCommand::QueueConstruction {
                city_id,
                construction_name,
            } => (city_id.clone(), construction_name.clone()),
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
            .queue_construction(
                &actor_account_id.to_string(),
                &worker_state.manifest,
                envelope.expected_revision,
                &worker_state.snapshot,
                QueueConstructionIntent {
                    actor_civilization_id: &actor_civilization_id,
                    city_id: &city_id,
                    construction_name: &construction_name,
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

    pub async fn execute_queue_construction_at_tile(
        &self,
        worker: &EngineWorkerClient,
        actor_account_id: Uuid,
        envelope: CommandEnvelope,
    ) -> Result<CommandAccepted, CommitError> {
        let (city_id, construction_name, x, y) = match &envelope.command {
            crate::GameCommand::QueueConstructionAtTile {
                city_id,
                construction_name,
                x,
                y,
            } => (city_id.clone(), construction_name.clone(), *x, *y),
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
            .queue_construction_at_tile(
                &actor_account_id.to_string(),
                &worker_state.manifest,
                envelope.expected_revision,
                &worker_state.snapshot,
                QueueConstructionAtTileIntent {
                    actor_civilization_id: &actor_civilization_id,
                    city_id: &city_id,
                    construction_name: &construction_name,
                    x,
                    y,
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

    pub async fn execute_set_perpetual_construction(
        &self,
        worker: &EngineWorkerClient,
        actor_account_id: Uuid,
        envelope: CommandEnvelope,
    ) -> Result<CommandAccepted, CommitError> {
        let (city_id, construction_name) = match &envelope.command {
            crate::GameCommand::SetPerpetualConstruction {
                city_id,
                construction_name,
            } => (city_id.clone(), construction_name.clone()),
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
            .set_perpetual_construction(
                &actor_account_id.to_string(),
                &worker_state.manifest,
                envelope.expected_revision,
                &worker_state.snapshot,
                SetPerpetualConstructionIntent {
                    actor_civilization_id: &actor_civilization_id,
                    city_id: &city_id,
                    construction_name: &construction_name,
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

    pub async fn execute_remove_construction(
        &self,
        worker: &EngineWorkerClient,
        actor_account_id: Uuid,
        envelope: CommandEnvelope,
    ) -> Result<CommandAccepted, CommitError> {
        let (city_id, queue_index, expected_construction_name) = match &envelope.command {
            crate::GameCommand::RemoveConstruction {
                city_id,
                queue_index,
                expected_construction_name,
            } => (
                city_id.clone(),
                *queue_index,
                expected_construction_name.clone(),
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
        let proposal = worker
            .remove_construction(
                &actor_account_id.to_string(),
                &worker_state.manifest,
                envelope.expected_revision,
                &worker_state.snapshot,
                RemoveConstructionIntent {
                    actor_civilization_id: &actor_civilization_id,
                    city_id: &city_id,
                    queue_index,
                    expected_construction_name: &expected_construction_name,
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

    pub async fn execute_move_construction(
        &self,
        worker: &EngineWorkerClient,
        actor_account_id: Uuid,
        envelope: CommandEnvelope,
    ) -> Result<CommandAccepted, CommitError> {
        let (city_id, from_index, to_index, expected_construction_name) = match &envelope.command {
            crate::GameCommand::MoveConstruction {
                city_id,
                from_index,
                to_index,
                expected_construction_name,
            } => (
                city_id.clone(),
                *from_index,
                *to_index,
                expected_construction_name.clone(),
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
        let proposal = worker
            .move_construction(
                &actor_account_id.to_string(),
                &worker_state.manifest,
                envelope.expected_revision,
                &worker_state.snapshot,
                MoveConstructionIntent {
                    actor_civilization_id: &actor_civilization_id,
                    city_id: &city_id,
                    from_index,
                    to_index,
                    expected_construction_name: &expected_construction_name,
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

    pub async fn execute_purchase_construction(
        &self,
        worker: &EngineWorkerClient,
        actor_account_id: Uuid,
        envelope: CommandEnvelope,
    ) -> Result<CommandAccepted, CommitError> {
        let (city_id, construction_name, currency_name, queue_index) = match &envelope.command {
            crate::GameCommand::PurchaseConstruction {
                city_id,
                construction_name,
                currency_name,
                queue_index,
            } => (
                city_id.clone(),
                construction_name.clone(),
                currency_name.clone(),
                *queue_index,
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
        let proposal = worker
            .purchase_construction(
                &actor_account_id.to_string(),
                &worker_state.manifest,
                envelope.expected_revision,
                &worker_state.snapshot,
                PurchaseConstructionIntent {
                    actor_civilization_id: &actor_civilization_id,
                    city_id: &city_id,
                    construction_name: &construction_name,
                    currency_name: &currency_name,
                    queue_index,
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

    pub async fn execute_purchase_construction_at_tile(
        &self,
        worker: &EngineWorkerClient,
        actor_account_id: Uuid,
        envelope: CommandEnvelope,
    ) -> Result<CommandAccepted, CommitError> {
        let (city_id, construction_name, currency_name, x, y, queue_index) = match &envelope.command
        {
            crate::GameCommand::PurchaseConstructionAtTile {
                city_id,
                construction_name,
                currency_name,
                x,
                y,
                queue_index,
            } => (
                city_id.clone(),
                construction_name.clone(),
                currency_name.clone(),
                *x,
                *y,
                *queue_index,
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
        let proposal = worker
            .purchase_construction_at_tile(
                &actor_account_id.to_string(),
                &worker_state.manifest,
                envelope.expected_revision,
                &worker_state.snapshot,
                PurchaseConstructionAtTileIntent {
                    actor_civilization_id: &actor_civilization_id,
                    city_id: &city_id,
                    construction_name: &construction_name,
                    currency_name: &currency_name,
                    x,
                    y,
                    queue_index,
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

    pub async fn execute_buy_city_tile(
        &self,
        worker: &EngineWorkerClient,
        actor_account_id: Uuid,
        envelope: CommandEnvelope,
    ) -> Result<CommandAccepted, CommitError> {
        let (city_id, x, y) = match &envelope.command {
            crate::GameCommand::BuyCityTile { city_id, x, y } => (city_id.clone(), *x, *y),
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
            .buy_city_tile(
                &actor_account_id.to_string(),
                &worker_state.manifest,
                envelope.expected_revision,
                &worker_state.snapshot,
                BuyCityTileIntent {
                    actor_civilization_id: &actor_civilization_id,
                    city_id: &city_id,
                    x,
                    y,
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
