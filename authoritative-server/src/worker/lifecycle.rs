use super::*;

impl EngineWorkerClient {
    pub async fn resign(
        &self,
        actor_id: &str,
        manifest: &WorkerManifest,
        previous_revision: u64,
        snapshot: &str,
        actor_civilization_id: &str,
    ) -> Result<CommitProposal, WorkerClientError> {
        let response = self
            .execute(
                actor_id,
                manifest,
                WorkerOperation::Resign {
                    snapshot,
                    actor_civilization_id,
                },
            )
            .await?;
        commit_proposal(previous_revision, response)
    }

    pub async fn force_resign(
        &self,
        actor_id: &str,
        manifest: &WorkerManifest,
        previous_revision: u64,
        snapshot: &str,
        actor_civilization_id: &str,
    ) -> Result<ForcedResignation, WorkerClientError> {
        let response = self
            .execute(
                actor_id,
                manifest,
                WorkerOperation::ForceResign {
                    snapshot,
                    actor_civilization_id,
                },
            )
            .await?;
        let civilization_id = response
            .actor_civilization_id
            .clone()
            .ok_or(WorkerClientError::Incomplete)?;
        Ok(ForcedResignation {
            proposal: commit_proposal(previous_revision, response)?,
            civilization_id,
        })
    }

    pub async fn kick_player(
        &self,
        actor_id: &str,
        manifest: &WorkerManifest,
        previous_revision: u64,
        snapshot: &str,
        actor_civilization_id: &str,
        target_civilization_id: &str,
    ) -> Result<CommitProposal, WorkerClientError> {
        let response = self
            .execute(
                actor_id,
                manifest,
                WorkerOperation::KickPlayer {
                    snapshot,
                    actor_civilization_id,
                    target_civilization_id,
                },
            )
            .await?;
        commit_proposal(previous_revision, response)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn kick_player_operation_matches_kotlin_wire_names() {
        let value = serde_json::to_value(WorkerOperation::KickPlayer {
            snapshot: "snapshot",
            actor_civilization_id: "Rome",
            target_civilization_id: "Greece",
        })
        .unwrap();
        assert_eq!(value["type"], "kick_player");
        assert_eq!(value["actorCivilizationId"], "Rome");
        assert_eq!(value["targetCivilizationId"], "Greece");
        assert!(value.get("actor_civilization_id").is_none());
        assert!(value.get("target_civilization_id").is_none());
    }
}
