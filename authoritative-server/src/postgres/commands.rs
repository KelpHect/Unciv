use super::*;

pub(super) struct WorkerCommandState {
    pub(super) snapshot: String,
    pub(super) manifest: WorkerManifest,
}

impl PostgresGameRepository {
    pub(super) async fn worker_command_state(
        &self,
        game_id: Uuid,
    ) -> Result<WorkerCommandState, CommitError> {
        let row = sqlx::query(
            "SELECT g.unavailable_at IS NOT NULL AS is_unavailable, g.lifecycle_status, r.canonical_state_hash AS revision_state_hash, s.revision AS snapshot_revision, s.payload, s.codec, s.compressed_size, s.uncompressed_size, s.protocol_version AS snapshot_protocol_version, s.validation_status, s.payload_hash, s.canonical_state_hash AS snapshot_state_hash, m.manifest FROM games g JOIN game_revisions r ON r.game_id=g.id AND r.revision=g.head_revision JOIN game_snapshots s ON s.game_id=g.id AND s.revision=g.head_revision JOIN ruleset_manifests m ON m.hash=g.ruleset_manifest_hash WHERE g.id=$1",
        )
        .bind(game_id)
        .fetch_optional(&self.pool)
        .await
        .map_err(CommitError::storage)?
            .ok_or(CommitError::NotFound)?;
        if row.get::<String, _>("lifecycle_status") != "active" {
            return Err(CommitError::InvalidCommand);
        }
        let snapshot = self.validated_snapshot(game_id, &row).await?;
        let manifest = serde_json::from_value::<WorkerManifest>(row.get("manifest"))
            .map_err(|_| CommitError::WorkerRevisionMismatch)?;
        Ok(WorkerCommandState { snapshot, manifest })
    }

    pub(super) async fn actor_civilization_id(
        &self,
        game_id: Uuid,
        actor_account_id: Uuid,
    ) -> Result<String, CommitError> {
        sqlx::query_scalar(
            "SELECT civilization_id FROM game_members WHERE game_id = $1 AND account_id = $2 AND role IN ('owner', 'player')",
        )
        .bind(game_id)
        .bind(actor_account_id)
        .fetch_optional(&self.pool)
        .await
        .map_err(CommitError::storage)?
        .flatten()
        .ok_or(CommitError::Unauthorized)
    }
    /// Loads the canonical head, delegates rule execution to Kotlin, then uses
    /// the normal CAS commit path. A competing commit becomes a stale conflict;
    /// it can never merge or overwrite this result.
    pub async fn execute_end_turn(
        &self,
        worker: &EngineWorkerClient,
        actor_account_id: Uuid,
        envelope: CommandEnvelope,
    ) -> Result<CommandAccepted, CommitError> {
        // A lost response is retried after the head may already have moved.
        // Return its durable original result before contacting the worker, so
        // a duplicate can neither re-run turn processing nor fail as stale.
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
            .end_turn(
                &actor_account_id.to_string(),
                &worker_state.manifest,
                envelope.expected_revision,
                &worker_state.snapshot,
                &actor_civilization_id,
            )
            .await
            .map_err(|error| match error {
                crate::worker::WorkerClientError::Rejected(reason) => {
                    CommitError::WorkerRejected(reason)
                }
                other => {
                    eprintln!("authoritative worker EndTurn transport/protocol failure: {other}");
                    CommitError::WorkerRevisionMismatch
                }
            })?;
        self.commit(actor_account_id, envelope, proposal).await
    }

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
                other => {
                    eprintln!(
                        "authoritative worker QueueConstruction transport/protocol failure: {other}"
                    );
                    CommitError::WorkerRevisionMismatch
                }
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
                other => {
                    eprintln!("authoritative worker QueueConstructionAtTile transport/protocol failure: {other}");
                    CommitError::WorkerRevisionMismatch
                }
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
                crate::worker::WorkerClientError::Rejected(reason) =>
                    CommitError::WorkerRejected(reason),
                other => {
                    eprintln!("authoritative worker SetPerpetualConstruction transport/protocol failure: {other}");
                    CommitError::WorkerRevisionMismatch
                }
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
        let proposal = worker.remove_construction(
            &actor_account_id.to_string(), &worker_state.manifest, envelope.expected_revision, &worker_state.snapshot,
            RemoveConstructionIntent {
                actor_civilization_id: &actor_civilization_id,
                city_id: &city_id,
                queue_index,
                expected_construction_name: &expected_construction_name,
            },
        ).await.map_err(|error| match error {
            crate::worker::WorkerClientError::Rejected(reason) => CommitError::WorkerRejected(reason),
            other => {
                eprintln!("authoritative worker RemoveConstruction transport/protocol failure: {other}");
                CommitError::WorkerRevisionMismatch
            }
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
                other => {
                    eprintln!(
                        "authoritative worker MoveConstruction transport/protocol failure: {other}"
                    );
                    CommitError::WorkerRevisionMismatch
                }
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
                other => {
                    eprintln!(
                        "authoritative worker PurchaseConstruction transport/protocol failure: {other}"
                    );
                    CommitError::WorkerRevisionMismatch
                }
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
                other => {
                    eprintln!("authoritative worker PurchaseConstructionAtTile transport/protocol failure: {other}");
                    CommitError::WorkerRevisionMismatch
                }
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
                other => {
                    eprintln!(
                        "authoritative worker BuyCityTile transport/protocol failure: {other}"
                    );
                    CommitError::WorkerRevisionMismatch
                }
            })?;
        self.commit(actor_account_id, envelope, proposal).await
    }

    pub async fn execute_set_research_path(
        &self,
        worker: &EngineWorkerClient,
        actor_account_id: Uuid,
        envelope: CommandEnvelope,
    ) -> Result<CommandAccepted, CommitError> {
        let technology_name = match &envelope.command {
            crate::GameCommand::SetResearchPath { technology_name } => technology_name.clone(),
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
            .set_research_path(
                &actor_account_id.to_string(),
                &worker_state.manifest,
                envelope.expected_revision,
                &worker_state.snapshot,
                SetResearchPathIntent {
                    actor_civilization_id: &actor_civilization_id,
                    technology_name: &technology_name,
                },
            )
            .await
            .map_err(|error| match error {
                crate::worker::WorkerClientError::Rejected(reason) => {
                    CommitError::WorkerRejected(reason)
                }
                other => {
                    eprintln!(
                        "authoritative worker SetResearchPath transport/protocol failure: {other}"
                    );
                    CommitError::WorkerRevisionMismatch
                }
            })?;
        self.commit(actor_account_id, envelope, proposal).await
    }

    pub async fn execute_adopt_policy(
        &self,
        worker: &EngineWorkerClient,
        actor_account_id: Uuid,
        envelope: CommandEnvelope,
    ) -> Result<CommandAccepted, CommitError> {
        let policy_name = match &envelope.command {
            crate::GameCommand::AdoptPolicy { policy_name } => policy_name.clone(),
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
            .adopt_policy(
                &actor_account_id.to_string(),
                &worker_state.manifest,
                envelope.expected_revision,
                &worker_state.snapshot,
                AdoptPolicyIntent {
                    actor_civilization_id: &actor_civilization_id,
                    policy_name: &policy_name,
                },
            )
            .await
            .map_err(|error| match error {
                crate::worker::WorkerClientError::Rejected(reason) => {
                    CommitError::WorkerRejected(reason)
                }
                other => {
                    eprintln!(
                        "authoritative worker AdoptPolicy transport/protocol failure: {other}"
                    );
                    CommitError::WorkerRevisionMismatch
                }
            })?;
        self.commit(actor_account_id, envelope, proposal).await
    }

    pub async fn execute_choose_free_technology(
        &self,
        worker: &EngineWorkerClient,
        actor_account_id: Uuid,
        envelope: CommandEnvelope,
    ) -> Result<CommandAccepted, CommitError> {
        let technology_name = match &envelope.command {
            crate::GameCommand::ChooseFreeTechnology { technology_name } => technology_name.clone(),
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
            .choose_free_technology(
                &actor_account_id.to_string(),
                &worker_state.manifest,
                envelope.expected_revision,
                &worker_state.snapshot,
                ChooseFreeTechnologyIntent {
                    actor_civilization_id: &actor_civilization_id,
                    technology_name: &technology_name,
                },
            )
            .await
            .map_err(|error| match error {
                crate::worker::WorkerClientError::Rejected(reason) => {
                    CommitError::WorkerRejected(reason)
                }
                other => {
                    eprintln!("authoritative worker ChooseFreeTechnology transport/protocol failure: {other}");
                    CommitError::WorkerRevisionMismatch
                }
            })?;
        self.commit(actor_account_id, envelope, proposal).await
    }

    /// Joins an authenticated account without accepting a civilization choice.
    /// The worker deterministically assigns an unclaimed canonical civilization;
    /// the snapshot revision and membership are committed atomically.
    pub async fn execute_join(
        &self,
        worker: &EngineWorkerClient,
        actor_account_id: Uuid,
        envelope: CommandEnvelope,
    ) -> Result<CommandAccepted, CommitError> {
        if !matches!(&envelope.command, crate::GameCommand::JoinGame) {
            return Err(CommitError::InvalidCommand);
        }
        if let Some(accepted) = self
            .committed_command(envelope.game_id, envelope.command_id, actor_account_id)
            .await?
        {
            return Ok(accepted);
        }
        self.require_pending_player_invitation(envelope.game_id, actor_account_id)
            .await?;
        let worker_state = self.worker_command_state(envelope.game_id).await?;
        let assigned = worker
            .assign_player(
                &actor_account_id.to_string(),
                &worker_state.manifest,
                envelope.expected_revision,
                &worker_state.snapshot,
            )
            .await
            .map_err(|error| match error {
                crate::worker::WorkerClientError::Rejected(reason) => {
                    CommitError::WorkerRejected(reason)
                }
                other => {
                    eprintln!(
                        "authoritative worker AssignPlayer transport/protocol failure: {other}"
                    );
                    CommitError::WorkerRevisionMismatch
                }
            })?;
        self.commit_internal(
            actor_account_id,
            envelope,
            assigned.proposal,
            Some(NewMemberAssignment {
                civilization_id: assigned.civilization_id,
            }),
            None,
        )
        .await
    }

    pub(super) async fn committed_command(
        &self,
        game_id: Uuid,
        command_id: Uuid,
        actor_account_id: Uuid,
    ) -> Result<Option<CommandAccepted>, CommitError> {
        let row = sqlx::query(
            "SELECT c.revision, c.account_id, r.canonical_state_hash FROM game_commands c JOIN game_revisions r ON r.game_id = c.game_id AND r.revision = c.revision WHERE c.game_id = $1 AND c.command_id = $2",
        )
        .bind(game_id)
        .bind(command_id)
        .fetch_optional(&self.pool)
        .await
        .map_err(CommitError::storage)?;
        row.map(|row| {
            if row.get::<Uuid, _>("account_id") != actor_account_id {
                return Err(CommitError::Unauthorized);
            }
            let committed_revision: i64 = row.get("revision");
            Ok(CommandAccepted {
                game_id,
                command_id,
                previous_revision: u64::try_from(committed_revision - 1)
                    .expect("command revisions are positive"),
                committed_revision: u64::try_from(committed_revision)
                    .expect("revision is non-negative"),
                canonical_state_hash: row.get("canonical_state_hash"),
            })
        })
        .transpose()
    }
}
