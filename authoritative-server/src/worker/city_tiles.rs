use super::*;

impl EngineWorkerClient {
    pub async fn buy_city_tile_batch(
        &self,
        actor_id: &str,
        manifest: &WorkerManifest,
        previous_revision: u64,
        snapshot: &str,
        intent: BuyCityTileBatchIntent<'_>,
    ) -> Result<CommitProposal, WorkerClientError> {
        let response = self
            .execute(
                actor_id,
                manifest,
                WorkerOperation::BuyCityTileBatch {
                    snapshot,
                    actor_civilization_id: intent.actor_civilization_id,
                    city_id: intent.city_id,
                    ring: intent.ring,
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
    fn city_tile_batch_operation_matches_kotlin_wire_names() {
        let operation = WorkerOperation::BuyCityTileBatch {
            snapshot: "snapshot",
            actor_civilization_id: "Rome",
            city_id: "city-1",
            ring: 2,
        };
        let value = serde_json::to_value(operation).unwrap();
        assert_eq!(value["type"], "buy_city_tile_batch");
        assert_eq!(value["actorCivilizationId"], "Rome");
        assert_eq!(value["cityId"], "city-1");
        assert_eq!(value["ring"], 2);
    }
}
