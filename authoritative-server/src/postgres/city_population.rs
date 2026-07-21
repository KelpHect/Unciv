use super::*;

impl PostgresGameRepository {
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
