use super::*;

impl EngineWorkerClient {
    pub async fn resolve_city_disposition(
        &self,
        actor_id: &str,
        manifest: &WorkerManifest,
        previous_revision: u64,
        snapshot: &str,
        intent: ResolveCityDispositionIntent<'_>,
    ) -> Result<CommitProposal, WorkerClientError> {
        let response = self
            .execute(
                actor_id,
                manifest,
                WorkerOperation::ResolveCityDisposition {
                    snapshot,
                    actor_civilization_id: intent.actor_civilization_id,
                    city_id: intent.city_id,
                    action: intent.action,
                },
            )
            .await?;
        commit_proposal(previous_revision, response)
    }
}
