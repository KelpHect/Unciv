use super::*;

pub struct DiplomacyPartnerIntent<'a> {
    pub actor_civilization_id: &'a str,
    pub other_civilization_id: &'a str,
}

pub struct DiplomaticDemandIntent<'a> {
    pub actor_civilization_id: &'a str,
    pub other_civilization_id: &'a str,
    pub demand: crate::projection::DiplomaticDemand,
}

pub struct DiplomaticPromptIntent<'a> {
    pub actor_civilization_id: &'a str,
    pub prompt_id: &'a str,
    pub accept: bool,
}
pub struct CityStateProtectionPromptIntent<'a> {
    pub actor_civilization_id: &'a str,
    pub prompt_id: &'a str,
    pub response: crate::projection::CityStateProtectionResponse,
}

enum PartnerDiplomacyOperation {
    DeclareWar,
    Denounce,
    OfferFriendship,
}

impl EngineWorkerClient {
    async fn diplomacy_partner_operation(
        &self,
        actor_id: &str,
        manifest: &WorkerManifest,
        previous_revision: u64,
        snapshot: &str,
        intent: DiplomacyPartnerIntent<'_>,
        operation: PartnerDiplomacyOperation,
    ) -> Result<CommitProposal, WorkerClientError> {
        let operation = match operation {
            PartnerDiplomacyOperation::DeclareWar => WorkerOperation::DeclareWar {
                snapshot,
                actor_civilization_id: intent.actor_civilization_id,
                other_civilization_id: intent.other_civilization_id,
            },
            PartnerDiplomacyOperation::Denounce => WorkerOperation::DenounceCivilization {
                snapshot,
                actor_civilization_id: intent.actor_civilization_id,
                other_civilization_id: intent.other_civilization_id,
            },
            PartnerDiplomacyOperation::OfferFriendship => WorkerOperation::OfferFriendship {
                snapshot,
                actor_civilization_id: intent.actor_civilization_id,
                other_civilization_id: intent.other_civilization_id,
            },
        };
        commit_proposal(
            previous_revision,
            self.execute(actor_id, manifest, operation).await?,
        )
    }

    pub async fn declare_war(
        &self,
        actor_id: &str,
        manifest: &WorkerManifest,
        revision: u64,
        snapshot: &str,
        intent: DiplomacyPartnerIntent<'_>,
    ) -> Result<CommitProposal, WorkerClientError> {
        self.diplomacy_partner_operation(
            actor_id,
            manifest,
            revision,
            snapshot,
            intent,
            PartnerDiplomacyOperation::DeclareWar,
        )
        .await
    }
    pub async fn denounce_civilization(
        &self,
        actor_id: &str,
        manifest: &WorkerManifest,
        revision: u64,
        snapshot: &str,
        intent: DiplomacyPartnerIntent<'_>,
    ) -> Result<CommitProposal, WorkerClientError> {
        self.diplomacy_partner_operation(
            actor_id,
            manifest,
            revision,
            snapshot,
            intent,
            PartnerDiplomacyOperation::Denounce,
        )
        .await
    }
    pub async fn offer_friendship(
        &self,
        actor_id: &str,
        manifest: &WorkerManifest,
        revision: u64,
        snapshot: &str,
        intent: DiplomacyPartnerIntent<'_>,
    ) -> Result<CommitProposal, WorkerClientError> {
        self.diplomacy_partner_operation(
            actor_id,
            manifest,
            revision,
            snapshot,
            intent,
            PartnerDiplomacyOperation::OfferFriendship,
        )
        .await
    }
    pub async fn make_diplomatic_demand(
        &self,
        actor_id: &str,
        manifest: &WorkerManifest,
        revision: u64,
        snapshot: &str,
        intent: DiplomaticDemandIntent<'_>,
    ) -> Result<CommitProposal, WorkerClientError> {
        let response = self
            .execute(
                actor_id,
                manifest,
                WorkerOperation::MakeDiplomaticDemand {
                    snapshot,
                    actor_civilization_id: intent.actor_civilization_id,
                    other_civilization_id: intent.other_civilization_id,
                    demand: intent.demand,
                },
            )
            .await?;
        commit_proposal(revision, response)
    }
    pub async fn respond_to_diplomatic_prompt(
        &self,
        actor_id: &str,
        manifest: &WorkerManifest,
        revision: u64,
        snapshot: &str,
        intent: DiplomaticPromptIntent<'_>,
    ) -> Result<CommitProposal, WorkerClientError> {
        let response = self
            .execute(
                actor_id,
                manifest,
                WorkerOperation::RespondToDiplomaticPrompt {
                    snapshot,
                    actor_civilization_id: intent.actor_civilization_id,
                    prompt_id: intent.prompt_id,
                    accept: intent.accept,
                },
            )
            .await?;
        commit_proposal(revision, response)
    }
    pub async fn respond_to_city_state_protection_prompt(
        &self,
        actor_id: &str,
        manifest: &WorkerManifest,
        revision: u64,
        snapshot: &str,
        intent: CityStateProtectionPromptIntent<'_>,
    ) -> Result<CommitProposal, WorkerClientError> {
        let response = self
            .execute(
                actor_id,
                manifest,
                WorkerOperation::RespondToCityStateProtectionPrompt {
                    snapshot,
                    actor_civilization_id: intent.actor_civilization_id,
                    prompt_id: intent.prompt_id,
                    response: intent.response,
                },
            )
            .await?;
        commit_proposal(revision, response)
    }
}
