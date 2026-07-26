use super::*;

impl EngineWorkerClient {
    pub async fn add_unit_to_capital_project(
        &self,
        actor_id: &str,
        manifest: &WorkerManifest,
        previous_revision: u64,
        snapshot: &str,
        intent: AddUnitToCapitalProjectIntent<'_>,
    ) -> Result<CommitProposal, WorkerClientError> {
        let response = self
            .execute(
                actor_id,
                manifest,
                WorkerOperation::AddUnitToCapitalProject {
                    snapshot,
                    actor_civilization_id: intent.actor_civilization_id,
                    unit_id: intent.unit_id,
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
    fn capital_project_operation_matches_kotlin_wire_names() {
        let value = serde_json::to_value(WorkerOperation::AddUnitToCapitalProject {
            snapshot: "snapshot",
            actor_civilization_id: "Rome",
            unit_id: 17,
        })
        .unwrap();
        assert_eq!(value["type"], "add_unit_to_capital_project");
        assert_eq!(value["actorCivilizationId"], "Rome");
        assert_eq!(value["unitId"], 17);
    }
}
