use super::*;

pub struct UseReligiousUnitIntent<'a> {
    pub actor_civilization_id: &'a str,
    pub unit_id: i32,
    pub action: crate::ReligiousUnitAction,
}

pub struct ChooseReligiousBeliefsIntent<'a> {
    pub actor_civilization_id: &'a str,
    pub belief_names: &'a [String],
    pub religion_icon_name: Option<&'a str>,
    pub religion_display_name: Option<&'a str>,
}

impl EngineWorkerClient {
    pub async fn choose_religious_beliefs(
        &self,
        actor_id: &str,
        manifest: &WorkerManifest,
        previous_revision: u64,
        snapshot: &str,
        intent: ChooseReligiousBeliefsIntent<'_>,
    ) -> Result<CommitProposal, WorkerClientError> {
        let response = self
            .execute(
                actor_id,
                manifest,
                WorkerOperation::ChooseReligiousBeliefs {
                    snapshot,
                    actor_civilization_id: intent.actor_civilization_id,
                    belief_names: intent.belief_names,
                    religion_icon_name: intent.religion_icon_name,
                    religion_display_name: intent.religion_display_name,
                },
            )
            .await?;
        commit_proposal(previous_revision, response)
    }

    pub async fn use_religious_unit(
        &self,
        actor_id: &str,
        manifest: &WorkerManifest,
        previous_revision: u64,
        snapshot: &str,
        intent: UseReligiousUnitIntent<'_>,
    ) -> Result<CommitProposal, WorkerClientError> {
        let response = self
            .execute(
                actor_id,
                manifest,
                WorkerOperation::UseReligiousUnit {
                    snapshot,
                    actor_civilization_id: intent.actor_civilization_id,
                    unit_id: intent.unit_id,
                    action: intent.action,
                },
            )
            .await?;
        commit_proposal(previous_revision, response)
    }
}
