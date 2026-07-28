use super::commands::WorkerCommandState;
use super::*;

impl PostgresGameRepository {
    async fn execute_trade_command<F, Fut>(
        &self,
        actor_account_id: Uuid,
        envelope: CommandEnvelope,
        execute: F,
    ) -> Result<CommandAccepted, CommitError>
    where
        F: FnOnce(WorkerCommandState, String) -> Fut,
        Fut: std::future::Future<Output = Result<CommitProposal, crate::worker::WorkerClientError>>,
    {
        if let Some(accepted) = self.committed_command(&envelope, actor_account_id).await? {
            return Ok(accepted);
        }
        let state = self.worker_command_state(envelope.game_id).await?;
        let civilization_id = self
            .actor_civilization_id(envelope.game_id, actor_account_id)
            .await?;
        let proposal = execute(state, civilization_id)
            .await
            .map_err(|error| match error {
                crate::worker::WorkerClientError::Rejected(reason) => {
                    CommitError::WorkerRejected(reason)
                }
                _ => CommitError::WorkerRevisionMismatch,
            })?;
        self.commit(actor_account_id, envelope, proposal).await
    }

    pub async fn execute_offer_trade(
        &self,
        worker: &EngineWorkerClient,
        actor: Uuid,
        envelope: CommandEnvelope,
    ) -> Result<CommandAccepted, CommitError> {
        let (other, trade) = match &envelope.command {
            crate::GameCommand::OfferTrade {
                other_civilization_id,
                trade,
            } => (other_civilization_id.clone(), trade.clone()),
            _ => return Err(CommitError::InvalidCommand),
        };
        let revision = envelope.expected_revision;
        self.execute_trade_command(actor, envelope, |state, civilization| async move {
            worker
                .offer_trade(
                    &actor.to_string(),
                    &state.manifest,
                    revision,
                    &state.snapshot,
                    OfferTradeIntent {
                        actor_civilization_id: &civilization,
                        other_civilization_id: &other,
                        trade: &trade,
                    },
                )
                .await
        })
        .await
    }

    pub async fn execute_retract_trade_offer(
        &self,
        worker: &EngineWorkerClient,
        actor: Uuid,
        envelope: CommandEnvelope,
    ) -> Result<CommandAccepted, CommitError> {
        let other = match &envelope.command {
            crate::GameCommand::RetractTradeOffer {
                other_civilization_id,
            } => other_civilization_id.clone(),
            _ => return Err(CommitError::InvalidCommand),
        };
        let revision = envelope.expected_revision;
        self.execute_trade_command(actor, envelope, |state, civilization| async move {
            worker
                .retract_trade_offer(
                    &actor.to_string(),
                    &state.manifest,
                    revision,
                    &state.snapshot,
                    TradePartnerIntent {
                        actor_civilization_id: &civilization,
                        other_civilization_id: &other,
                    },
                )
                .await
        })
        .await
    }

    pub async fn execute_accept_trade(
        &self,
        worker: &EngineWorkerClient,
        actor: Uuid,
        envelope: CommandEnvelope,
    ) -> Result<CommandAccepted, CommitError> {
        self.execute_trade_request(worker, actor, envelope, true)
            .await
    }

    pub async fn execute_decline_trade(
        &self,
        worker: &EngineWorkerClient,
        actor: Uuid,
        envelope: CommandEnvelope,
    ) -> Result<CommandAccepted, CommitError> {
        self.execute_trade_request(worker, actor, envelope, false)
            .await
    }

    pub async fn execute_counter_trade(
        &self,
        worker: &EngineWorkerClient,
        actor: Uuid,
        envelope: CommandEnvelope,
    ) -> Result<CommandAccepted, CommitError> {
        let (request_id, trade) = match &envelope.command {
            crate::GameCommand::CounterTrade { request_id, trade } => {
                (request_id.clone(), trade.clone())
            }
            _ => return Err(CommitError::InvalidCommand),
        };
        let revision = envelope.expected_revision;
        self.execute_trade_command(actor, envelope, |state, civilization| async move {
            worker
                .counter_trade(
                    &actor.to_string(),
                    &state.manifest,
                    revision,
                    &state.snapshot,
                    CounterTradeIntent {
                        actor_civilization_id: &civilization,
                        request_id: &request_id,
                        trade: &trade,
                    },
                )
                .await
        })
        .await
    }

    async fn execute_trade_request(
        &self,
        worker: &EngineWorkerClient,
        actor: Uuid,
        envelope: CommandEnvelope,
        accept: bool,
    ) -> Result<CommandAccepted, CommitError> {
        let request_id = match &envelope.command {
            crate::GameCommand::AcceptTrade { request_id }
            | crate::GameCommand::DeclineTrade { request_id } => request_id.clone(),
            _ => return Err(CommitError::InvalidCommand),
        };
        let revision = envelope.expected_revision;
        self.execute_trade_command(actor, envelope, |state, civilization| async move {
            let intent = TradeRequestIntent {
                actor_civilization_id: &civilization,
                request_id: &request_id,
            };
            if accept {
                worker
                    .accept_trade(
                        &actor.to_string(),
                        &state.manifest,
                        revision,
                        &state.snapshot,
                        intent,
                    )
                    .await
            } else {
                worker
                    .decline_trade(
                        &actor.to_string(),
                        &state.manifest,
                        revision,
                        &state.snapshot,
                        intent,
                    )
                    .await
            }
        })
        .await
    }
}
