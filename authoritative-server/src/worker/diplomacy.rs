use super::*;

impl EngineWorkerClient {
    pub async fn cast_diplomatic_vote(
        &self,
        actor_id: &str,
        manifest: &WorkerManifest,
        previous_revision: u64,
        snapshot: &str,
        intent: CastDiplomaticVoteIntent<'_>,
    ) -> Result<CommitProposal, WorkerClientError> {
        let response = self
            .execute(
                actor_id,
                manifest,
                WorkerOperation::CastDiplomaticVote {
                    snapshot,
                    actor_civilization_id: intent.actor_civilization_id,
                    candidate_civilization_id: intent.candidate_civilization_id,
                },
            )
            .await?;
        commit_proposal(previous_revision, response)
    }
}
