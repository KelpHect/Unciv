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
        Ok(CommitProposal {
            previous_revision,
            snapshot: response
                .snapshot
                .ok_or(WorkerClientError::Incomplete)?
                .into_bytes(),
            canonical_state_hash: response
                .canonical_state_hash
                .ok_or(WorkerClientError::Incomplete)?,
        })
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
        Ok(CommitProposal {
            previous_revision,
            snapshot: response
                .snapshot
                .ok_or(WorkerClientError::Incomplete)?
                .into_bytes(),
            canonical_state_hash: response
                .canonical_state_hash
                .ok_or(WorkerClientError::Incomplete)?,
        })
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
        Ok(CommitProposal {
            previous_revision,
            snapshot: response
                .snapshot
                .ok_or(WorkerClientError::Incomplete)?
                .into_bytes(),
            canonical_state_hash: response
                .canonical_state_hash
                .ok_or(WorkerClientError::Incomplete)?,
        })
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
        Ok(CommitProposal {
            previous_revision,
            snapshot: response
                .snapshot
                .ok_or(WorkerClientError::Incomplete)?
                .into_bytes(),
            canonical_state_hash: response
                .canonical_state_hash
                .ok_or(WorkerClientError::Incomplete)?,
        })
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
}
