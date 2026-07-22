use super::commands::WorkerCommandState;
use super::*;
use crate::worker::{MoveSpyIntent, SetSpyCoupIntent};

impl PostgresGameRepository {
    async fn execute_espionage<F, Fut>(
        &self,
        actor: Uuid,
        envelope: CommandEnvelope,
        execute: F,
    ) -> Result<CommandAccepted, CommitError>
    where
        F: FnOnce(WorkerCommandState, String) -> Fut,
        Fut: std::future::Future<Output = Result<CommitProposal, crate::worker::WorkerClientError>>,
    {
        if let Some(accepted) = self
            .committed_command(envelope.game_id, envelope.command_id, actor)
            .await?
        {
            return Ok(accepted);
        }
        let state = self.worker_command_state(envelope.game_id).await?;
        let civilization = self.actor_civilization_id(envelope.game_id, actor).await?;
        let proposal = execute(state, civilization)
            .await
            .map_err(|error| match error {
                crate::worker::WorkerClientError::Rejected(reason) => {
                    CommitError::WorkerRejected(reason)
                }
                other => {
                    eprintln!("authoritative worker espionage transport/protocol failure: {other}");
                    CommitError::WorkerRevisionMismatch
                }
            })?;
        self.commit(actor, envelope, proposal).await
    }

    pub async fn execute_move_spy(
        &self,
        worker: &EngineWorkerClient,
        actor: Uuid,
        envelope: CommandEnvelope,
    ) -> Result<CommandAccepted, CommitError> {
        let (spy_name, city_id) = match &envelope.command {
            crate::GameCommand::MoveSpy { spy_name, city_id } => {
                (spy_name.clone(), city_id.clone())
            }
            _ => return Err(CommitError::InvalidCommand),
        };
        let revision = envelope.expected_revision;
        self.execute_espionage(actor, envelope, |state, civilization| async move {
            worker
                .move_spy(
                    &actor.to_string(),
                    &state.manifest,
                    revision,
                    &state.snapshot,
                    MoveSpyIntent {
                        actor_civilization_id: &civilization,
                        spy_name: &spy_name,
                        city_id: city_id.as_deref(),
                    },
                )
                .await
        })
        .await
    }

    pub async fn execute_set_spy_coup(
        &self,
        worker: &EngineWorkerClient,
        actor: Uuid,
        envelope: CommandEnvelope,
    ) -> Result<CommandAccepted, CommitError> {
        let (spy_name, enabled) = match &envelope.command {
            crate::GameCommand::SetSpyCoup { spy_name, enabled } => (spy_name.clone(), *enabled),
            _ => return Err(CommitError::InvalidCommand),
        };
        let revision = envelope.expected_revision;
        self.execute_espionage(actor, envelope, |state, civilization| async move {
            worker
                .set_spy_coup(
                    &actor.to_string(),
                    &state.manifest,
                    revision,
                    &state.snapshot,
                    SetSpyCoupIntent {
                        actor_civilization_id: &civilization,
                        spy_name: &spy_name,
                        enabled,
                    },
                )
                .await
        })
        .await
    }
}
