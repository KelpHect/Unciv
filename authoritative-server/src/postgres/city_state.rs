use super::commands::WorkerCommandState;
use super::*;
use crate::worker::{
    CityStateGoldGiftIntent, CityStateImprovementGiftIntent, CityStateProtectionIntent,
    CityStateTributeIntent,
};

impl PostgresGameRepository {
    async fn execute_city_state<F, Fut>(
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
                    eprintln!(
                        "authoritative worker city-state transport/protocol failure: {other}"
                    );
                    CommitError::WorkerRevisionMismatch
                }
            })?;
        self.commit(actor, envelope, proposal).await
    }

    pub async fn execute_city_state_command(
        &self,
        worker: &EngineWorkerClient,
        actor: Uuid,
        envelope: CommandEnvelope,
    ) -> Result<CommandAccepted, CommitError> {
        enum Action {
            Gift(u32),
            Protection(bool),
            Tribute(bool),
            Improvement { x: i32, y: i32, name: String },
            Peace,
        }
        let (city_state, action) = match &envelope.command {
            crate::GameCommand::GiftCityStateGold {
                city_state_civilization_id,
                amount,
            } => (city_state_civilization_id.clone(), Action::Gift(*amount)),
            crate::GameCommand::SetCityStateProtection {
                city_state_civilization_id,
                protect,
            } => (
                city_state_civilization_id.clone(),
                Action::Protection(*protect),
            ),
            crate::GameCommand::DemandCityStateTribute {
                city_state_civilization_id,
                worker,
            } => (city_state_civilization_id.clone(), Action::Tribute(*worker)),
            crate::GameCommand::GiftCityStateImprovement {
                city_state_civilization_id,
                x,
                y,
                improvement_name,
            } => (
                city_state_civilization_id.clone(),
                Action::Improvement {
                    x: *x,
                    y: *y,
                    name: improvement_name.clone(),
                },
            ),
            crate::GameCommand::NegotiateCityStatePeace {
                city_state_civilization_id,
            } => (city_state_civilization_id.clone(), Action::Peace),
            _ => return Err(CommitError::InvalidCommand),
        };
        let revision = envelope.expected_revision;
        self.execute_city_state(actor, envelope, |state, civilization| async move {
            match action {
                Action::Gift(amount) => {
                    worker
                        .gift_city_state_gold(
                            &actor.to_string(),
                            &state.manifest,
                            revision,
                            &state.snapshot,
                            CityStateGoldGiftIntent {
                                actor_civilization_id: &civilization,
                                city_state_civilization_id: &city_state,
                                amount,
                            },
                        )
                        .await
                }
                Action::Protection(protect) => {
                    worker
                        .set_city_state_protection(
                            &actor.to_string(),
                            &state.manifest,
                            revision,
                            &state.snapshot,
                            CityStateProtectionIntent {
                                actor_civilization_id: &civilization,
                                city_state_civilization_id: &city_state,
                                protect,
                            },
                        )
                        .await
                }
                Action::Tribute(worker_tribute) => {
                    worker
                        .demand_city_state_tribute(
                            &actor.to_string(),
                            &state.manifest,
                            revision,
                            &state.snapshot,
                            CityStateTributeIntent {
                                actor_civilization_id: &civilization,
                                city_state_civilization_id: &city_state,
                                worker: worker_tribute,
                            },
                        )
                        .await
                }
                Action::Improvement { x, y, name } => {
                    worker
                        .gift_city_state_improvement(
                            &actor.to_string(),
                            &state.manifest,
                            revision,
                            &state.snapshot,
                            CityStateImprovementGiftIntent {
                                actor_civilization_id: &civilization,
                                city_state_civilization_id: &city_state,
                                x,
                                y,
                                improvement_name: &name,
                            },
                        )
                        .await
                }
                Action::Peace => {
                    worker
                        .negotiate_city_state_peace(
                            &actor.to_string(),
                            &state.manifest,
                            revision,
                            &state.snapshot,
                            &civilization,
                            &city_state,
                        )
                        .await
                }
            }
        })
        .await
    }
}
