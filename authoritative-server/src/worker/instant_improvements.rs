use super::*;

impl EngineWorkerClient {
    pub async fn create_instant_improvement(
        &self,
        actor_id: &str,
        manifest: &WorkerManifest,
        previous_revision: u64,
        snapshot: &str,
        intent: CreateInstantImprovementIntent<'_>,
    ) -> Result<CommitProposal, WorkerClientError> {
        let response = self
            .execute(
                actor_id,
                manifest,
                WorkerOperation::CreateInstantImprovement {
                    snapshot,
                    actor_civilization_id: intent.actor_civilization_id,
                    unit_id: intent.unit_id,
                    action_id: intent.action_id,
                },
            )
            .await?;
        commit_proposal(previous_revision, response)
    }
}

#[cfg(test)]
mod tests {
    use super::WorkerOperation;

    #[test]
    fn instant_improvement_operation_matches_kotlin_wire_names() {
        let value = serde_json::to_value(WorkerOperation::CreateInstantImprovement {
            snapshot: "snapshot",
            actor_civilization_id: "Rome",
            unit_id: 17,
            action_id: "a",
        })
        .unwrap();
        assert_eq!(value["type"], "create_instant_improvement");
        assert_eq!(value["actorCivilizationId"], "Rome");
        assert_eq!(value["unitId"], 17);
        assert_eq!(value["actionId"], "a");
    }
}
