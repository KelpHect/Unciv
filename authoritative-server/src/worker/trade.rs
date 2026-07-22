use super::*;

pub struct OfferTradeIntent<'a> {
    pub actor_civilization_id: &'a str,
    pub other_civilization_id: &'a str,
    pub trade: &'a crate::projection::ProjectedTrade,
}

pub struct TradePartnerIntent<'a> {
    pub actor_civilization_id: &'a str,
    pub other_civilization_id: &'a str,
}

pub struct TradeRequestIntent<'a> {
    pub actor_civilization_id: &'a str,
    pub request_id: &'a str,
}

pub struct CounterTradeIntent<'a> {
    pub actor_civilization_id: &'a str,
    pub request_id: &'a str,
    pub trade: &'a crate::projection::ProjectedTrade,
}

impl EngineWorkerClient {
    pub async fn offer_trade(
        &self,
        actor_id: &str,
        manifest: &WorkerManifest,
        previous_revision: u64,
        snapshot: &str,
        intent: OfferTradeIntent<'_>,
    ) -> Result<CommitProposal, WorkerClientError> {
        let response = self
            .execute(
                actor_id,
                manifest,
                WorkerOperation::OfferTrade {
                    snapshot,
                    actor_civilization_id: intent.actor_civilization_id,
                    other_civilization_id: intent.other_civilization_id,
                    trade: intent.trade,
                },
            )
            .await?;
        commit_proposal(previous_revision, response)
    }

    pub async fn retract_trade_offer(
        &self,
        actor_id: &str,
        manifest: &WorkerManifest,
        previous_revision: u64,
        snapshot: &str,
        intent: TradePartnerIntent<'_>,
    ) -> Result<CommitProposal, WorkerClientError> {
        let response = self
            .execute(
                actor_id,
                manifest,
                WorkerOperation::RetractTradeOffer {
                    snapshot,
                    actor_civilization_id: intent.actor_civilization_id,
                    other_civilization_id: intent.other_civilization_id,
                },
            )
            .await?;
        commit_proposal(previous_revision, response)
    }

    pub async fn accept_trade(
        &self,
        actor_id: &str,
        manifest: &WorkerManifest,
        previous_revision: u64,
        snapshot: &str,
        intent: TradeRequestIntent<'_>,
    ) -> Result<CommitProposal, WorkerClientError> {
        let response = self
            .execute(
                actor_id,
                manifest,
                WorkerOperation::AcceptTrade {
                    snapshot,
                    actor_civilization_id: intent.actor_civilization_id,
                    request_id: intent.request_id,
                },
            )
            .await?;
        commit_proposal(previous_revision, response)
    }

    pub async fn decline_trade(
        &self,
        actor_id: &str,
        manifest: &WorkerManifest,
        previous_revision: u64,
        snapshot: &str,
        intent: TradeRequestIntent<'_>,
    ) -> Result<CommitProposal, WorkerClientError> {
        let response = self
            .execute(
                actor_id,
                manifest,
                WorkerOperation::DeclineTrade {
                    snapshot,
                    actor_civilization_id: intent.actor_civilization_id,
                    request_id: intent.request_id,
                },
            )
            .await?;
        commit_proposal(previous_revision, response)
    }

    pub async fn counter_trade(
        &self,
        actor_id: &str,
        manifest: &WorkerManifest,
        previous_revision: u64,
        snapshot: &str,
        intent: CounterTradeIntent<'_>,
    ) -> Result<CommitProposal, WorkerClientError> {
        let response = self
            .execute(
                actor_id,
                manifest,
                WorkerOperation::CounterTrade {
                    snapshot,
                    actor_civilization_id: intent.actor_civilization_id,
                    request_id: intent.request_id,
                    trade: intent.trade,
                },
            )
            .await?;
        commit_proposal(previous_revision, response)
    }
}
