use super::commands::WorkerCommandState;
use super::*;
use crate::worker::CityStateProtectionPromptIntent;

impl PostgresGameRepository {
    async fn execute_major_diplomacy<F, Fut>(
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
                    eprintln!("authoritative worker diplomacy transport/protocol failure: {other}");
                    CommitError::WorkerRevisionMismatch
                }
            })?;
        self.commit(actor, envelope, proposal).await
    }

    pub async fn execute_diplomacy_partner_command(
        &self,
        worker: &EngineWorkerClient,
        actor: Uuid,
        envelope: CommandEnvelope,
    ) -> Result<CommandAccepted, CommitError> {
        enum Action {
            War,
            Denounce,
            Friendship,
        }
        let (other, action) = match &envelope.command {
            crate::GameCommand::DeclareWar {
                other_civilization_id,
            } => (other_civilization_id.clone(), Action::War),
            crate::GameCommand::DenounceCivilization {
                other_civilization_id,
            } => (other_civilization_id.clone(), Action::Denounce),
            crate::GameCommand::OfferFriendship {
                other_civilization_id,
            } => (other_civilization_id.clone(), Action::Friendship),
            _ => return Err(CommitError::InvalidCommand),
        };
        let revision = envelope.expected_revision;
        self.execute_major_diplomacy(actor, envelope, |state, civilization| async move {
            let intent = DiplomacyPartnerIntent {
                actor_civilization_id: &civilization,
                other_civilization_id: &other,
            };
            match action {
                Action::War => {
                    worker
                        .declare_war(
                            &actor.to_string(),
                            &state.manifest,
                            revision,
                            &state.snapshot,
                            intent,
                        )
                        .await
                }
                Action::Denounce => {
                    worker
                        .denounce_civilization(
                            &actor.to_string(),
                            &state.manifest,
                            revision,
                            &state.snapshot,
                            intent,
                        )
                        .await
                }
                Action::Friendship => {
                    worker
                        .offer_friendship(
                            &actor.to_string(),
                            &state.manifest,
                            revision,
                            &state.snapshot,
                            intent,
                        )
                        .await
                }
            }
        })
        .await
    }

    pub async fn execute_make_diplomatic_demand(
        &self,
        worker: &EngineWorkerClient,
        actor: Uuid,
        envelope: CommandEnvelope,
    ) -> Result<CommandAccepted, CommitError> {
        let (other, demand) = match &envelope.command {
            crate::GameCommand::MakeDiplomaticDemand {
                other_civilization_id,
                demand,
            } => (other_civilization_id.clone(), *demand),
            _ => return Err(CommitError::InvalidCommand),
        };
        let revision = envelope.expected_revision;
        self.execute_major_diplomacy(actor, envelope, |state, civilization| async move {
            worker
                .make_diplomatic_demand(
                    &actor.to_string(),
                    &state.manifest,
                    revision,
                    &state.snapshot,
                    DiplomaticDemandIntent {
                        actor_civilization_id: &civilization,
                        other_civilization_id: &other,
                        demand,
                    },
                )
                .await
        })
        .await
    }

    pub async fn execute_respond_to_diplomatic_prompt(
        &self,
        worker: &EngineWorkerClient,
        actor: Uuid,
        envelope: CommandEnvelope,
    ) -> Result<CommandAccepted, CommitError> {
        let (prompt_id, accept) = match &envelope.command {
            crate::GameCommand::RespondToDiplomaticPrompt { prompt_id, accept } => {
                (prompt_id.clone(), *accept)
            }
            _ => return Err(CommitError::InvalidCommand),
        };
        let revision = envelope.expected_revision;
        self.execute_major_diplomacy(actor, envelope, |state, civilization| async move {
            worker
                .respond_to_diplomatic_prompt(
                    &actor.to_string(),
                    &state.manifest,
                    revision,
                    &state.snapshot,
                    DiplomaticPromptIntent {
                        actor_civilization_id: &civilization,
                        prompt_id: &prompt_id,
                        accept,
                    },
                )
                .await
        })
        .await
    }

    pub async fn execute_respond_to_city_state_protection_prompt(
        &self,
        worker: &EngineWorkerClient,
        actor: Uuid,
        envelope: CommandEnvelope,
    ) -> Result<CommandAccepted, CommitError> {
        let (prompt_id, response) = match &envelope.command {
            crate::GameCommand::RespondToCityStateProtectionPrompt {
                prompt_id,
                response,
            } => (prompt_id.clone(), *response),
            _ => return Err(CommitError::InvalidCommand),
        };
        let revision = envelope.expected_revision;
        self.execute_major_diplomacy(actor, envelope, |state, civilization| async move {
            worker
                .respond_to_city_state_protection_prompt(
                    &actor.to_string(),
                    &state.manifest,
                    revision,
                    &state.snapshot,
                    CityStateProtectionPromptIntent {
                        actor_civilization_id: &civilization,
                        prompt_id: &prompt_id,
                        response,
                    },
                )
                .await
        })
        .await
    }
}
