use super::*;

impl EngineWorkerClient {
    pub async fn resolve_event_choice(
        &self,
        actor_id: &str,
        manifest: &WorkerManifest,
        revision: u64,
        snapshot: &str,
        intent: ResolveEventChoiceIntent<'_>,
    ) -> Result<CommitProposal, WorkerClientError> {
        let response = self
            .execute(
                actor_id,
                manifest,
                WorkerOperation::ResolveEventChoice {
                    snapshot,
                    actor_civilization_id: intent.actor_civilization_id,
                    prompt_id: intent.prompt_id,
                    choice_id: intent.choice_id,
                },
            )
            .await?;
        commit_proposal(revision, response)
    }
}
