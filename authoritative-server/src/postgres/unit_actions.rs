use super::*;

impl PostgresGameRepository {
    pub async fn execute_air_sweep(
        &self,
        worker: &EngineWorkerClient,
        actor_account_id: Uuid,
        envelope: CommandEnvelope,
    ) -> Result<CommandAccepted, CommitError> {
        let (unit_id, target_x, target_y) = match &envelope.command {
            crate::GameCommand::AirSweep {
                unit_id,
                target_x,
                target_y,
            } => (*unit_id, *target_x, *target_y),
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
            .air_sweep(
                &actor_account_id.to_string(),
                &worker_state.manifest,
                envelope.expected_revision,
                &worker_state.snapshot,
                AirSweepIntent {
                    actor_civilization_id: &actor_civilization_id,
                    unit_id,
                    target_x,
                    target_y,
                },
            )
            .await
            .map_err(|error| match error {
                crate::worker::WorkerClientError::Rejected(reason) => {
                    CommitError::WorkerRejected(reason)
                }
                other => {
                    eprintln!("authoritative worker AirSweep transport/protocol failure: {other}");
                    CommitError::WorkerRevisionMismatch
                }
            })?;
        self.commit(actor_account_id, envelope, proposal).await
    }

    pub async fn execute_launch_nuclear_strike(
        &self,
        worker: &EngineWorkerClient,
        actor_account_id: Uuid,
        envelope: CommandEnvelope,
    ) -> Result<CommandAccepted, CommitError> {
        let (unit_id, target_x, target_y) = match &envelope.command {
            crate::GameCommand::LaunchNuclearStrike {
                unit_id,
                target_x,
                target_y,
            } => (*unit_id, *target_x, *target_y),
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
            .launch_nuclear_strike(
                &actor_account_id.to_string(),
                &worker_state.manifest,
                envelope.expected_revision,
                &worker_state.snapshot,
                LaunchNuclearStrikeIntent {
                    actor_civilization_id: &actor_civilization_id,
                    unit_id,
                    target_x,
                    target_y,
                },
            )
            .await
            .map_err(|error| match error {
                crate::worker::WorkerClientError::Rejected(reason) => {
                    CommitError::WorkerRejected(reason)
                }
                other => {
                    eprintln!(
                        "authoritative worker LaunchNuclearStrike transport/protocol failure: {other}"
                    );
                    CommitError::WorkerRevisionMismatch
                }
            })?;
        self.commit(actor_account_id, envelope, proposal).await
    }

    pub async fn execute_bombard_with_city(
        &self,
        worker: &EngineWorkerClient,
        actor_account_id: Uuid,
        envelope: CommandEnvelope,
    ) -> Result<CommandAccepted, CommitError> {
        let (city_id, target_x, target_y) = match &envelope.command {
            crate::GameCommand::BombardWithCity {
                city_id,
                target_x,
                target_y,
            } => (city_id.as_str(), *target_x, *target_y),
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
            .bombard_with_city(
                &actor_account_id.to_string(),
                &worker_state.manifest,
                envelope.expected_revision,
                &worker_state.snapshot,
                BombardWithCityIntent {
                    actor_civilization_id: &actor_civilization_id,
                    city_id,
                    target_x,
                    target_y,
                },
            )
            .await
            .map_err(|error| match error {
                crate::worker::WorkerClientError::Rejected(reason) => {
                    CommitError::WorkerRejected(reason)
                }
                other => {
                    eprintln!(
                        "authoritative worker BombardWithCity transport/protocol failure: {other}"
                    );
                    CommitError::WorkerRevisionMismatch
                }
            })?;
        self.commit(actor_account_id, envelope, proposal).await
    }

    pub async fn execute_attack_with_unit(
        &self,
        worker: &EngineWorkerClient,
        actor_account_id: Uuid,
        envelope: CommandEnvelope,
    ) -> Result<CommandAccepted, CommitError> {
        let (unit_id, target_x, target_y) = match &envelope.command {
            crate::GameCommand::AttackWithUnit {
                unit_id,
                target_x,
                target_y,
            } => (*unit_id, *target_x, *target_y),
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
            .attack_with_unit(
                &actor_account_id.to_string(),
                &worker_state.manifest,
                envelope.expected_revision,
                &worker_state.snapshot,
                AttackWithUnitIntent {
                    actor_civilization_id: &actor_civilization_id,
                    unit_id,
                    target_x,
                    target_y,
                },
            )
            .await
            .map_err(|error| match error {
                crate::worker::WorkerClientError::Rejected(reason) => {
                    CommitError::WorkerRejected(reason)
                }
                other => {
                    eprintln!(
                        "authoritative worker AttackWithUnit transport/protocol failure: {other}"
                    );
                    CommitError::WorkerRevisionMismatch
                }
            })?;
        self.commit(actor_account_id, envelope, proposal).await
    }

    pub async fn execute_paradrop_unit(
        &self,
        worker: &EngineWorkerClient,
        actor_account_id: Uuid,
        envelope: CommandEnvelope,
    ) -> Result<CommandAccepted, CommitError> {
        let (unit_id, destination_x, destination_y) = match &envelope.command {
            crate::GameCommand::ParadropUnit {
                unit_id,
                destination_x,
                destination_y,
            } => (*unit_id, *destination_x, *destination_y),
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
            .paradrop_unit(
                &actor_account_id.to_string(),
                &worker_state.manifest,
                envelope.expected_revision,
                &worker_state.snapshot,
                ParadropUnitIntent {
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
                other => {
                    eprintln!(
                        "authoritative worker ParadropUnit transport/protocol failure: {other}"
                    );
                    CommitError::WorkerRevisionMismatch
                }
            })?;
        self.commit(actor_account_id, envelope, proposal).await
    }

    pub async fn execute_found_city(
        &self,
        worker: &EngineWorkerClient,
        actor_account_id: Uuid,
        envelope: CommandEnvelope,
    ) -> Result<CommandAccepted, CommitError> {
        let unit_id = match &envelope.command {
            crate::GameCommand::FoundCity { unit_id } => *unit_id,
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
            .found_city(
                &actor_account_id.to_string(),
                &worker_state.manifest,
                envelope.expected_revision,
                &worker_state.snapshot,
                FoundCityIntent {
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
                    eprintln!("authoritative worker FoundCity transport/protocol failure: {other}");
                    CommitError::WorkerRevisionMismatch
                }
            })?;
        self.commit(actor_account_id, envelope, proposal).await
    }

    pub async fn execute_pillage_tile(
        &self,
        worker: &EngineWorkerClient,
        actor_account_id: Uuid,
        envelope: CommandEnvelope,
    ) -> Result<CommandAccepted, CommitError> {
        let unit_id = match &envelope.command {
            crate::GameCommand::PillageTile { unit_id } => *unit_id,
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
            .pillage_tile(
                &actor_account_id.to_string(),
                &worker_state.manifest,
                envelope.expected_revision,
                &worker_state.snapshot,
                PillageTileIntent {
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
                        "authoritative worker PillageTile transport/protocol failure: {other}"
                    );
                    CommitError::WorkerRevisionMismatch
                }
            })?;
        self.commit(actor_account_id, envelope, proposal).await
    }

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
        let (unit_id, promotion_names, save_as_city_default) = match &envelope.command {
            crate::GameCommand::PromoteUnit {
                unit_id,
                promotion_names,
                save_as_city_default,
            } => (*unit_id, promotion_names.clone(), *save_as_city_default),
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
                    save_as_city_default,
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

    pub async fn execute_set_city_unit_promotion_preference(
        &self,
        worker: &EngineWorkerClient,
        actor_account_id: Uuid,
        envelope: CommandEnvelope,
    ) -> Result<CommandAccepted, CommitError> {
        let (city_id, base_unit_name, enabled) = match &envelope.command {
            crate::GameCommand::SetCityUnitPromotionPreference {
                city_id,
                base_unit_name,
                enabled,
            } => (city_id.clone(), base_unit_name.clone(), *enabled),
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
            .set_city_unit_promotion_preference(
                &actor_account_id.to_string(),
                &worker_state.manifest,
                envelope.expected_revision,
                &worker_state.snapshot,
                SetCityUnitPromotionPreferenceIntent {
                    actor_civilization_id: &actor_civilization_id,
                    city_id: &city_id,
                    base_unit_name: &base_unit_name,
                    enabled,
                },
            )
            .await
            .map_err(|error| match error {
                crate::worker::WorkerClientError::Rejected(reason) => {
                    CommitError::WorkerRejected(reason)
                }
                other => {
                    eprintln!("authoritative worker city promotion preference failure: {other}");
                    CommitError::WorkerRevisionMismatch
                }
            })?;
        self.commit(actor_account_id, envelope, proposal).await
    }

    pub async fn execute_rename_unit(
        &self,
        worker: &EngineWorkerClient,
        actor_account_id: Uuid,
        envelope: CommandEnvelope,
    ) -> Result<CommandAccepted, CommitError> {
        let (unit_id, instance_name) = match &envelope.command {
            crate::GameCommand::RenameUnit {
                unit_id,
                instance_name,
            } => (*unit_id, instance_name.clone()),
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
            .rename_unit(
                &actor_account_id.to_string(),
                &worker_state.manifest,
                envelope.expected_revision,
                &worker_state.snapshot,
                RenameUnitIntent {
                    actor_civilization_id: &actor_civilization_id,
                    unit_id,
                    instance_name: instance_name.as_deref(),
                },
            )
            .await
            .map_err(|error| match error {
                crate::worker::WorkerClientError::Rejected(reason) => {
                    CommitError::WorkerRejected(reason)
                }
                other => {
                    eprintln!(
                        "authoritative worker RenameUnit transport/protocol failure: {other}"
                    );
                    CommitError::WorkerRevisionMismatch
                }
            })?;
        self.commit(actor_account_id, envelope, proposal).await
    }

    pub async fn execute_set_tile_improvement_order(
        &self,
        worker: &EngineWorkerClient,
        actor_account_id: Uuid,
        envelope: CommandEnvelope,
    ) -> Result<CommandAccepted, CommitError> {
        let (unit_id, improvement_name, queued_improvement_name) = match &envelope.command {
            crate::GameCommand::SetTileImprovementOrder {
                unit_id,
                improvement_name,
                queued_improvement_name,
            } => (
                *unit_id,
                improvement_name.clone(),
                queued_improvement_name.clone(),
            ),
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
            .set_tile_improvement_order(
                &actor_account_id.to_string(),
                &worker_state.manifest,
                envelope.expected_revision,
                &worker_state.snapshot,
                SetTileImprovementOrderIntent {
                    actor_civilization_id: &actor_civilization_id,
                    unit_id,
                    improvement_name: improvement_name.as_deref(),
                    queued_improvement_name: queued_improvement_name.as_deref(),
                },
            )
            .await
            .map_err(|error| match error {
                crate::worker::WorkerClientError::Rejected(reason) => {
                    CommitError::WorkerRejected(reason)
                }
                other => {
                    eprintln!("authoritative worker tile improvement order failure: {other}");
                    CommitError::WorkerRevisionMismatch
                }
            })?;
        self.commit(actor_account_id, envelope, proposal).await
    }

    pub async fn execute_set_road_connection_order(
        &self,
        worker: &EngineWorkerClient,
        actor_account_id: Uuid,
        envelope: CommandEnvelope,
    ) -> Result<CommandAccepted, CommitError> {
        let (unit_id, destination_x, destination_y) = match &envelope.command {
            crate::GameCommand::SetRoadConnectionOrder {
                unit_id,
                destination_x,
                destination_y,
            } => (*unit_id, *destination_x, *destination_y),
            _ => return Err(CommitError::InvalidCommand),
        };
        if destination_x.is_some() != destination_y.is_some() {
            return Err(CommitError::InvalidCommand);
        }
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
            .set_road_connection_order(
                &actor_account_id.to_string(),
                &worker_state.manifest,
                envelope.expected_revision,
                &worker_state.snapshot,
                SetRoadConnectionOrderIntent {
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
                other => {
                    eprintln!("authoritative worker road connection order failure: {other}");
                    CommitError::WorkerRevisionMismatch
                }
            })?;
        self.commit(actor_account_id, envelope, proposal).await
    }
}
