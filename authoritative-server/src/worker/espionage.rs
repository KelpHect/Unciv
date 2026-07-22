use super::*;

impl EngineWorkerClient {
    pub async fn move_spy(
        &self,
        actor_id: &str,
        manifest: &WorkerManifest,
        revision: u64,
        snapshot: &str,
        intent: MoveSpyIntent<'_>,
    ) -> Result<CommitProposal, WorkerClientError> {
        let response = self
            .execute(
                actor_id,
                manifest,
                WorkerOperation::MoveSpy {
                    snapshot,
                    actor_civilization_id: intent.actor_civilization_id,
                    spy_name: intent.spy_name,
                    city_id: intent.city_id,
                },
            )
            .await?;
        commit_proposal(revision, response)
    }

    pub async fn set_spy_coup(
        &self,
        actor_id: &str,
        manifest: &WorkerManifest,
        revision: u64,
        snapshot: &str,
        intent: SetSpyCoupIntent<'_>,
    ) -> Result<CommitProposal, WorkerClientError> {
        let response = self
            .execute(
                actor_id,
                manifest,
                WorkerOperation::SetSpyCoup {
                    snapshot,
                    actor_civilization_id: intent.actor_civilization_id,
                    spy_name: intent.spy_name,
                    enabled: intent.enabled,
                },
            )
            .await?;
        commit_proposal(revision, response)
    }
}
