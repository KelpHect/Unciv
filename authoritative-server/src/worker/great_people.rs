use super::*;

impl EngineWorkerClient {
    pub async fn choose_great_person(
        &self,
        actor_id: &str,
        manifest: &WorkerManifest,
        previous_revision: u64,
        snapshot: &str,
        intent: ChooseGreatPersonIntent<'_>,
    ) -> Result<CommitProposal, WorkerClientError> {
        let response = self
            .execute(
                actor_id,
                manifest,
                WorkerOperation::ChooseGreatPerson {
                    snapshot,
                    actor_civilization_id: intent.actor_civilization_id,
                    unit_name: intent.unit_name,
                },
            )
            .await?;
        commit_proposal(previous_revision, response)
    }

    pub async fn use_great_person_unit(
        &self,
        actor_id: &str,
        manifest: &WorkerManifest,
        previous_revision: u64,
        snapshot: &str,
        intent: UseGreatPersonUnitIntent<'_>,
    ) -> Result<CommitProposal, WorkerClientError> {
        let response = self
            .execute(
                actor_id,
                manifest,
                WorkerOperation::UseGreatPersonUnit {
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
