use super::*;

pub struct CityStateGoldGiftIntent<'a> {
    pub actor_civilization_id: &'a str,
    pub city_state_civilization_id: &'a str,
    pub amount: u32,
}
pub struct CityStateProtectionIntent<'a> {
    pub actor_civilization_id: &'a str,
    pub city_state_civilization_id: &'a str,
    pub protect: bool,
}
pub struct CityStateTributeIntent<'a> {
    pub actor_civilization_id: &'a str,
    pub city_state_civilization_id: &'a str,
    pub worker: bool,
}
pub struct CityStateImprovementGiftIntent<'a> {
    pub actor_civilization_id: &'a str,
    pub city_state_civilization_id: &'a str,
    pub x: i32,
    pub y: i32,
    pub improvement_name: &'a str,
}

impl EngineWorkerClient {
    pub async fn gift_city_state_gold(
        &self,
        actor_id: &str,
        manifest: &WorkerManifest,
        revision: u64,
        snapshot: &str,
        intent: CityStateGoldGiftIntent<'_>,
    ) -> Result<CommitProposal, WorkerClientError> {
        let response = self
            .execute(
                actor_id,
                manifest,
                WorkerOperation::GiftCityStateGold {
                    snapshot,
                    actor_civilization_id: intent.actor_civilization_id,
                    city_state_civilization_id: intent.city_state_civilization_id,
                    amount: intent.amount,
                },
            )
            .await?;
        commit_proposal(revision, response)
    }
    pub async fn set_city_state_protection(
        &self,
        actor_id: &str,
        manifest: &WorkerManifest,
        revision: u64,
        snapshot: &str,
        intent: CityStateProtectionIntent<'_>,
    ) -> Result<CommitProposal, WorkerClientError> {
        let response = self
            .execute(
                actor_id,
                manifest,
                WorkerOperation::SetCityStateProtection {
                    snapshot,
                    actor_civilization_id: intent.actor_civilization_id,
                    city_state_civilization_id: intent.city_state_civilization_id,
                    protect: intent.protect,
                },
            )
            .await?;
        commit_proposal(revision, response)
    }
    pub async fn demand_city_state_tribute(
        &self,
        actor_id: &str,
        manifest: &WorkerManifest,
        revision: u64,
        snapshot: &str,
        intent: CityStateTributeIntent<'_>,
    ) -> Result<CommitProposal, WorkerClientError> {
        let response = self
            .execute(
                actor_id,
                manifest,
                WorkerOperation::DemandCityStateTribute {
                    snapshot,
                    actor_civilization_id: intent.actor_civilization_id,
                    city_state_civilization_id: intent.city_state_civilization_id,
                    worker: intent.worker,
                },
            )
            .await?;
        commit_proposal(revision, response)
    }
    pub async fn gift_city_state_improvement(
        &self,
        actor_id: &str,
        manifest: &WorkerManifest,
        revision: u64,
        snapshot: &str,
        intent: CityStateImprovementGiftIntent<'_>,
    ) -> Result<CommitProposal, WorkerClientError> {
        let response = self
            .execute(
                actor_id,
                manifest,
                WorkerOperation::GiftCityStateImprovement {
                    snapshot,
                    actor_civilization_id: intent.actor_civilization_id,
                    city_state_civilization_id: intent.city_state_civilization_id,
                    x: intent.x,
                    y: intent.y,
                    improvement_name: intent.improvement_name,
                },
            )
            .await?;
        commit_proposal(revision, response)
    }
    pub async fn negotiate_city_state_peace(
        &self,
        actor_id: &str,
        manifest: &WorkerManifest,
        revision: u64,
        snapshot: &str,
        actor_civilization_id: &str,
        city_state_civilization_id: &str,
    ) -> Result<CommitProposal, WorkerClientError> {
        let response = self
            .execute(
                actor_id,
                manifest,
                WorkerOperation::NegotiateCityStatePeace {
                    snapshot,
                    actor_civilization_id,
                    city_state_civilization_id,
                },
            )
            .await?;
        commit_proposal(revision, response)
    }
}
