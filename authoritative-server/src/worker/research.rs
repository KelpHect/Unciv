use super::*;

impl EngineWorkerClient {
    pub async fn set_research_path(
        &self,
        actor_id: &str,
        manifest: &WorkerManifest,
        previous_revision: u64,
        snapshot: &str,
        intent: SetResearchPathIntent<'_>,
    ) -> Result<CommitProposal, WorkerClientError> {
        let response = self
            .execute(
                actor_id,
                manifest,
                WorkerOperation::SetResearchPath {
                    snapshot,
                    actor_civilization_id: intent.actor_civilization_id,
                    technology_name: intent.technology_name,
                    append: intent.append,
                },
            )
            .await?;
        commit_proposal(previous_revision, response)
    }

    pub async fn manage_research_queue(
        &self,
        actor_id: &str,
        manifest: &WorkerManifest,
        previous_revision: u64,
        snapshot: &str,
        intent: ManageResearchQueueIntent<'_>,
    ) -> Result<CommitProposal, WorkerClientError> {
        let response = self
            .execute(
                actor_id,
                manifest,
                WorkerOperation::ManageResearchQueue {
                    snapshot,
                    actor_civilization_id: intent.actor_civilization_id,
                    technology_name: intent.technology_name,
                    queue_index: intent.queue_index,
                    action: intent.action,
                },
            )
            .await?;
        commit_proposal(previous_revision, response)
    }

    pub async fn adopt_policy(
        &self,
        actor_id: &str,
        manifest: &WorkerManifest,
        previous_revision: u64,
        snapshot: &str,
        intent: AdoptPolicyIntent<'_>,
    ) -> Result<CommitProposal, WorkerClientError> {
        let response = self
            .execute(
                actor_id,
                manifest,
                WorkerOperation::AdoptPolicy {
                    snapshot,
                    actor_civilization_id: intent.actor_civilization_id,
                    policy_name: intent.policy_name,
                },
            )
            .await?;
        commit_proposal(previous_revision, response)
    }

    pub async fn choose_free_technology(
        &self,
        actor_id: &str,
        manifest: &WorkerManifest,
        previous_revision: u64,
        snapshot: &str,
        intent: ChooseFreeTechnologyIntent<'_>,
    ) -> Result<CommitProposal, WorkerClientError> {
        let response = self
            .execute(
                actor_id,
                manifest,
                WorkerOperation::ChooseFreeTechnology {
                    snapshot,
                    actor_civilization_id: intent.actor_civilization_id,
                    technology_name: intent.technology_name,
                },
            )
            .await?;
        commit_proposal(previous_revision, response)
    }

    pub async fn acknowledge_research_completion(
        &self,
        actor_id: &str,
        manifest: &WorkerManifest,
        previous_revision: u64,
        snapshot: &str,
        intent: AcknowledgeResearchCompletionIntent<'_>,
    ) -> Result<CommitProposal, WorkerClientError> {
        let response = self
            .execute(
                actor_id,
                manifest,
                WorkerOperation::AcknowledgeResearchCompletion {
                    snapshot,
                    actor_civilization_id: intent.actor_civilization_id,
                    prompt_id: intent.prompt_id,
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
    fn research_completion_operation_matches_kotlin_wire_names() {
        let value = serde_json::to_value(WorkerOperation::AcknowledgeResearchCompletion {
            snapshot: "snapshot",
            actor_civilization_id: "Rome",
            prompt_id: "a-prompt",
        })
        .unwrap();

        assert_eq!(value["type"], "acknowledge_research_completion");
        assert_eq!(value["actorCivilizationId"], "Rome");
        assert_eq!(value["promptId"], "a-prompt");
        assert!(value.get("technologyName").is_none());
    }

    #[test]
    fn research_queue_operation_matches_kotlin_wire_names() {
        let value = serde_json::to_value(WorkerOperation::ManageResearchQueue {
            snapshot: "snapshot",
            actor_civilization_id: "Rome",
            technology_name: "Writing",
            queue_index: 1,
            action: crate::ResearchQueueAction::Remove,
        })
        .unwrap();

        assert_eq!(value["type"], "manage_research_queue");
        assert_eq!(value["actorCivilizationId"], "Rome");
        assert_eq!(value["technologyName"], "Writing");
        assert_eq!(value["queueIndex"], 1);
        assert_eq!(value["action"], "remove");
        assert!(value.get("queue").is_none());
    }
}
