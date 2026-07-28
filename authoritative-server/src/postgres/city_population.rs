use super::*;

impl PostgresGameRepository {
    pub async fn execute_set_avoid_growth(
        &self,
        worker: &EngineWorkerClient,
        actor_account_id: Uuid,
        envelope: CommandEnvelope,
    ) -> Result<CommandAccepted, CommitError> {
        let (city_id, enabled) = match &envelope.command {
            crate::GameCommand::SetAvoidGrowth { city_id, enabled } => (city_id.clone(), *enabled),
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
            .set_avoid_growth(
                &actor_account_id.to_string(),
                &worker_state.manifest,
                envelope.expected_revision,
                &worker_state.snapshot,
                SetAvoidGrowthIntent {
                    actor_civilization_id: &actor_civilization_id,
                    city_id: &city_id,
                    enabled,
                },
            )
            .await
            .map_err(|error| match error {
                crate::worker::WorkerClientError::Rejected(reason) => {
                    CommitError::WorkerRejected(reason)
                }
                other => {
                    eprintln!(
                        "authoritative worker SetAvoidGrowth transport/protocol failure: {other}"
                    );
                    CommitError::WorkerRevisionMismatch
                }
            })?;
        self.commit(actor_account_id, envelope, proposal).await
    }

    pub async fn execute_set_citizen_focus(
        &self,
        worker: &EngineWorkerClient,
        actor_account_id: Uuid,
        envelope: CommandEnvelope,
    ) -> Result<CommandAccepted, CommitError> {
        let (city_id, focus) = match &envelope.command {
            crate::GameCommand::SetCitizenFocus { city_id, focus } => (city_id.clone(), *focus),
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
            .set_citizen_focus(
                &actor_account_id.to_string(),
                &worker_state.manifest,
                envelope.expected_revision,
                &worker_state.snapshot,
                SetCitizenFocusIntent {
                    actor_civilization_id: &actor_civilization_id,
                    city_id: &city_id,
                    focus,
                },
            )
            .await
            .map_err(|error| match error {
                crate::worker::WorkerClientError::Rejected(reason) => {
                    CommitError::WorkerRejected(reason)
                }
                other => {
                    eprintln!(
                        "authoritative worker SetCitizenFocus transport/protocol failure: {other}"
                    );
                    CommitError::WorkerRevisionMismatch
                }
            })?;
        self.commit(actor_account_id, envelope, proposal).await
    }

    pub async fn execute_reset_citizens(
        &self,
        worker: &EngineWorkerClient,
        actor_account_id: Uuid,
        envelope: CommandEnvelope,
    ) -> Result<CommandAccepted, CommitError> {
        let city_id = match &envelope.command {
            crate::GameCommand::ResetCitizens { city_id } => city_id.clone(),
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
            .reset_citizens(
                &actor_account_id.to_string(),
                &worker_state.manifest,
                envelope.expected_revision,
                &worker_state.snapshot,
                ResetCitizensIntent {
                    actor_civilization_id: &actor_civilization_id,
                    city_id: &city_id,
                },
            )
            .await
            .map_err(|error| match error {
                crate::worker::WorkerClientError::Rejected(reason) => {
                    CommitError::WorkerRejected(reason)
                }
                other => {
                    eprintln!(
                        "authoritative worker ResetCitizens transport/protocol failure: {other}"
                    );
                    CommitError::WorkerRevisionMismatch
                }
            })?;
        self.commit(actor_account_id, envelope, proposal).await
    }

    pub async fn execute_set_manual_specialists(
        &self,
        worker: &EngineWorkerClient,
        actor_account_id: Uuid,
        envelope: CommandEnvelope,
    ) -> Result<CommandAccepted, CommitError> {
        let (city_id, enabled) = match &envelope.command {
            crate::GameCommand::SetManualSpecialists { city_id, enabled } => {
                (city_id.clone(), *enabled)
            }
            _ => return Err(CommitError::InvalidCommand),
        };
        if let Some(accepted) = self.committed_command(&envelope, actor_account_id).await? {
            return Ok(accepted);
        }
        let worker_state = self.worker_command_state(envelope.game_id).await?;
        let actor_civilization_id = self
            .actor_civilization_id(envelope.game_id, actor_account_id)
            .await?;
        let proposal = worker.set_manual_specialists(
            &actor_account_id.to_string(), &worker_state.manifest,
            envelope.expected_revision, &worker_state.snapshot,
            SetManualSpecialistsIntent {
                actor_civilization_id: &actor_civilization_id,
                city_id: &city_id,
                enabled,
            },
        ).await.map_err(|error| match error {
            crate::worker::WorkerClientError::Rejected(reason) => CommitError::WorkerRejected(reason),
            other => {
                eprintln!("authoritative worker SetManualSpecialists transport/protocol failure: {other}");
                CommitError::WorkerRevisionMismatch
            }
        })?;
        self.commit(actor_account_id, envelope, proposal).await
    }

    pub async fn execute_set_specialist_count(
        &self,
        worker: &EngineWorkerClient,
        actor_account_id: Uuid,
        envelope: CommandEnvelope,
    ) -> Result<CommandAccepted, CommitError> {
        let (city_id, specialist_name, count) = match &envelope.command {
            crate::GameCommand::SetSpecialistCount {
                city_id,
                specialist_name,
                count,
            } => (city_id.clone(), specialist_name.clone(), *count),
            _ => return Err(CommitError::InvalidCommand),
        };
        if let Some(accepted) = self.committed_command(&envelope, actor_account_id).await? {
            return Ok(accepted);
        }
        let worker_state = self.worker_command_state(envelope.game_id).await?;
        let actor_civilization_id = self
            .actor_civilization_id(envelope.game_id, actor_account_id)
            .await?;
        let proposal = worker.set_specialist_count(
            &actor_account_id.to_string(), &worker_state.manifest,
            envelope.expected_revision, &worker_state.snapshot,
            SetSpecialistCountIntent {
                actor_civilization_id: &actor_civilization_id,
                city_id: &city_id,
                specialist_name: &specialist_name,
                count,
            },
        ).await.map_err(|error| match error {
            crate::worker::WorkerClientError::Rejected(reason) => CommitError::WorkerRejected(reason),
            other => {
                eprintln!("authoritative worker SetSpecialistCount transport/protocol failure: {other}");
                CommitError::WorkerRevisionMismatch
            }
        })?;
        self.commit(actor_account_id, envelope, proposal).await
    }

    pub async fn execute_set_city_tile_assignment(
        &self,
        worker: &EngineWorkerClient,
        actor_account_id: Uuid,
        envelope: CommandEnvelope,
    ) -> Result<CommandAccepted, CommitError> {
        let (city_id, x, y, assignment) = match &envelope.command {
            crate::GameCommand::SetCityTileAssignment {
                city_id,
                x,
                y,
                assignment,
            } => (city_id.clone(), *x, *y, *assignment),
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
            .set_city_tile_assignment(
                &actor_account_id.to_string(),
                &worker_state.manifest,
                envelope.expected_revision,
                &worker_state.snapshot,
                SetCityTileAssignmentIntent {
                    actor_civilization_id: &actor_civilization_id,
                    city_id: &city_id,
                    x,
                    y,
                    assignment,
                },
            )
            .await
            .map_err(|error| match error {
                crate::worker::WorkerClientError::Rejected(reason) =>
                    CommitError::WorkerRejected(reason),
                other => {
                    eprintln!("authoritative worker SetCityTileAssignment transport/protocol failure: {other}");
                    CommitError::WorkerRevisionMismatch
                }
            })?;
        self.commit(actor_account_id, envelope, proposal).await
    }
}
