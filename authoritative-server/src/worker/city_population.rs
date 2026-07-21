use super::*;

impl EngineWorkerClient {
    pub async fn set_avoid_growth(
        &self,
        actor_id: &str,
        manifest: &WorkerManifest,
        previous_revision: u64,
        snapshot: &str,
        intent: SetAvoidGrowthIntent<'_>,
    ) -> Result<CommitProposal, WorkerClientError> {
        let response = self
            .execute(
                actor_id,
                manifest,
                WorkerOperation::SetAvoidGrowth {
                    snapshot,
                    actor_civilization_id: intent.actor_civilization_id,
                    city_id: intent.city_id,
                    enabled: intent.enabled,
                },
            )
            .await?;
        commit_proposal(previous_revision, response)
    }

    pub async fn set_citizen_focus(
        &self,
        actor_id: &str,
        manifest: &WorkerManifest,
        previous_revision: u64,
        snapshot: &str,
        intent: SetCitizenFocusIntent<'_>,
    ) -> Result<CommitProposal, WorkerClientError> {
        let response = self
            .execute(
                actor_id,
                manifest,
                WorkerOperation::SetCitizenFocus {
                    snapshot,
                    actor_civilization_id: intent.actor_civilization_id,
                    city_id: intent.city_id,
                    focus: intent.focus,
                },
            )
            .await?;
        commit_proposal(previous_revision, response)
    }

    pub async fn reset_citizens(
        &self,
        actor_id: &str,
        manifest: &WorkerManifest,
        previous_revision: u64,
        snapshot: &str,
        intent: ResetCitizensIntent<'_>,
    ) -> Result<CommitProposal, WorkerClientError> {
        let response = self
            .execute(
                actor_id,
                manifest,
                WorkerOperation::ResetCitizens {
                    snapshot,
                    actor_civilization_id: intent.actor_civilization_id,
                    city_id: intent.city_id,
                },
            )
            .await?;
        commit_proposal(previous_revision, response)
    }

    pub async fn set_manual_specialists(
        &self,
        actor_id: &str,
        manifest: &WorkerManifest,
        previous_revision: u64,
        snapshot: &str,
        intent: SetManualSpecialistsIntent<'_>,
    ) -> Result<CommitProposal, WorkerClientError> {
        let response = self
            .execute(
                actor_id,
                manifest,
                WorkerOperation::SetManualSpecialists {
                    snapshot,
                    actor_civilization_id: intent.actor_civilization_id,
                    city_id: intent.city_id,
                    enabled: intent.enabled,
                },
            )
            .await?;
        commit_proposal(previous_revision, response)
    }

    pub async fn set_specialist_count(
        &self,
        actor_id: &str,
        manifest: &WorkerManifest,
        previous_revision: u64,
        snapshot: &str,
        intent: SetSpecialistCountIntent<'_>,
    ) -> Result<CommitProposal, WorkerClientError> {
        let response = self
            .execute(
                actor_id,
                manifest,
                WorkerOperation::SetSpecialistCount {
                    snapshot,
                    actor_civilization_id: intent.actor_civilization_id,
                    city_id: intent.city_id,
                    specialist_name: intent.specialist_name,
                    count: intent.count,
                },
            )
            .await?;
        commit_proposal(previous_revision, response)
    }

    pub async fn set_city_tile_assignment(
        &self,
        actor_id: &str,
        manifest: &WorkerManifest,
        previous_revision: u64,
        snapshot: &str,
        intent: SetCityTileAssignmentIntent<'_>,
    ) -> Result<CommitProposal, WorkerClientError> {
        let response = self
            .execute(
                actor_id,
                manifest,
                WorkerOperation::SetCityTileAssignment {
                    snapshot,
                    actor_civilization_id: intent.actor_civilization_id,
                    city_id: intent.city_id,
                    x: intent.x,
                    y: intent.y,
                    assignment: intent.assignment,
                },
            )
            .await?;
        commit_proposal(previous_revision, response)
    }
}
