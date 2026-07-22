use super::*;

impl PostgresGameRepository {
    pub async fn execute_resign(
        &self,
        worker: &EngineWorkerClient,
        actor: Uuid,
        envelope: CommandEnvelope,
    ) -> Result<CommandAccepted, CommitError> {
        if !matches!(envelope.command, crate::GameCommand::Resign {}) {
            return Err(CommitError::InvalidCommand);
        }
        if let Some(accepted) = self
            .committed_command(envelope.game_id, envelope.command_id, actor)
            .await?
        {
            return Ok(accepted);
        }
        let state = self.worker_command_state(envelope.game_id).await?;
        let civilization = self.actor_civilization_id(envelope.game_id, actor).await?;
        let proposal = worker
            .resign(
                &actor.to_string(),
                &state.manifest,
                envelope.expected_revision,
                &state.snapshot,
                &civilization,
            )
            .await
            .map_err(|error| match error {
                crate::worker::WorkerClientError::Rejected(reason) => {
                    CommitError::WorkerRejected(reason)
                }
                other => {
                    eprintln!("authoritative worker resign transport/protocol failure: {other}");
                    CommitError::WorkerRevisionMismatch
                }
            })?;
        self.commit_resignation(actor, envelope, proposal).await
    }

    pub async fn execute_force_resign(
        &self,
        worker: &EngineWorkerClient,
        actor: Uuid,
        envelope: CommandEnvelope,
    ) -> Result<CommandAccepted, CommitError> {
        if !matches!(envelope.command, crate::GameCommand::ForceResign {}) {
            return Err(CommitError::InvalidCommand);
        }
        if let Some(accepted) = self
            .committed_command(envelope.game_id, envelope.command_id, actor)
            .await?
        {
            return Ok(accepted);
        }
        let actor_civilization: Option<String> = sqlx::query_scalar(
            "SELECT civilization_id FROM game_members WHERE game_id = $1 AND account_id = $2 AND role = 'owner'",
        )
        .bind(envelope.game_id)
        .bind(actor)
        .fetch_optional(&self.pool)
        .await
        .map_err(CommitError::storage)?
        .flatten();
        let actor_civilization = actor_civilization.ok_or(CommitError::Unauthorized)?;
        let state = self.worker_command_state(envelope.game_id).await?;
        let forced = worker
            .force_resign(
                &actor.to_string(),
                &state.manifest,
                envelope.expected_revision,
                &state.snapshot,
                &actor_civilization,
            )
            .await
            .map_err(|error| match error {
                crate::worker::WorkerClientError::Rejected(reason) => {
                    CommitError::WorkerRejected(reason)
                }
                other => {
                    eprintln!(
                        "authoritative worker force-resign transport/protocol failure: {other}"
                    );
                    CommitError::WorkerRevisionMismatch
                }
            })?;
        self.commit_forced_resignation(actor, envelope, forced.proposal, forced.civilization_id)
            .await
    }
}
