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
        if let Some(accepted) = self.committed_command(&envelope, actor).await? {
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
                _ => CommitError::WorkerRevisionMismatch,
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
        if let Some(accepted) = self.committed_command(&envelope, actor).await? {
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
                _ => CommitError::WorkerRevisionMismatch,
            })?;
        self.commit_civilization_removal(actor, envelope, forced.proposal, forced.civilization_id)
            .await
    }

    pub async fn execute_kick_member(
        &self,
        worker: &EngineWorkerClient,
        actor: Uuid,
        envelope: CommandEnvelope,
        username: &str,
    ) -> Result<CommandAccepted, CommitError> {
        if !matches!(envelope.command, crate::GameCommand::KickMember {}) {
            return Err(CommitError::InvalidCommand);
        }
        if let Some(accepted) = self.committed_command(&envelope, actor).await? {
            return Ok(accepted);
        }
        let username = normalize_username(username).map_err(|_| CommitError::InvalidCommand)?;
        let owner_civilization: Option<String> = sqlx::query_scalar(
            "SELECT civilization_id FROM game_members WHERE game_id=$1 AND account_id=$2 AND role='owner'",
        )
        .bind(envelope.game_id)
        .bind(actor)
        .fetch_optional(&self.pool)
        .await
        .map_err(CommitError::storage)?
        .flatten();
        let owner_civilization = owner_civilization.ok_or(CommitError::Unauthorized)?;
        let target_civilization: Option<String> = sqlx::query_scalar(
            "SELECT gm.civilization_id FROM game_members gm JOIN accounts a ON a.id=gm.account_id WHERE gm.game_id=$1 AND a.username_normalized=$2 AND gm.role='player'",
        )
        .bind(envelope.game_id)
        .bind(username)
        .fetch_optional(&self.pool)
        .await
        .map_err(CommitError::storage)?
        .flatten();
        let target_civilization = target_civilization.ok_or(CommitError::NotFound)?;
        let state = self.worker_command_state(envelope.game_id).await?;
        let proposal = worker
            .kick_player(
                &actor.to_string(),
                &state.manifest,
                envelope.expected_revision,
                &state.snapshot,
                &owner_civilization,
                &target_civilization,
            )
            .await
            .map_err(|error| match error {
                crate::worker::WorkerClientError::Rejected(reason) => {
                    CommitError::WorkerRejected(reason)
                }
                _ => CommitError::WorkerRevisionMismatch,
            })?;
        self.commit_civilization_removal(actor, envelope, proposal, target_civilization)
            .await
    }
}
