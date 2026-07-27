use serde::Serialize;

use super::{
    EngineWorkerClient, NormalizedLegacyGame, WorkerClientError, WorkerManifest, WorkerOperation,
};

#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct LegacyPlayerMapping {
    pub legacy_player_id: String,
    pub account_id: String,
}

impl EngineWorkerClient {
    /// Validates and normalizes a legacy save in the private Kotlin engine.
    /// Persistence is intentionally a separate, operator-controlled step.
    pub async fn normalize_legacy_game(
        &self,
        owner_account_id: &str,
        manifest: &WorkerManifest,
        snapshot: &str,
        expected_legacy_game_id: &str,
        canonical_game_id: &str,
        player_mappings: &[LegacyPlayerMapping],
    ) -> Result<NormalizedLegacyGame, WorkerClientError> {
        let response = self
            .execute(
                owner_account_id,
                manifest,
                WorkerOperation::NormalizeLegacyGame {
                    snapshot,
                    expected_legacy_game_id,
                    canonical_game_id,
                    player_mappings,
                },
            )
            .await?;
        Ok(NormalizedLegacyGame {
            snapshot: response.snapshot.ok_or(WorkerClientError::Incomplete)?,
            canonical_state_hash: response
                .canonical_state_hash
                .ok_or(WorkerClientError::Incomplete)?,
            owner_civilization_id: response
                .actor_civilization_id
                .ok_or(WorkerClientError::Incomplete)?,
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn normalization_operation_contains_explicit_identity_mapping() {
        let mappings = [LegacyPlayerMapping {
            legacy_player_id: "legacy-player".to_owned(),
            account_id: "00000000-0000-4000-8000-000000000002".to_owned(),
        }];
        let value = serde_json::to_value(WorkerOperation::NormalizeLegacyGame {
            snapshot: "legacy-save",
            expected_legacy_game_id: "legacy-game",
            canonical_game_id: "00000000-0000-4000-8000-000000000001",
            player_mappings: &mappings,
        })
        .unwrap();

        assert_eq!(value["type"], "normalize_legacy_game");
        assert_eq!(value["expectedLegacyGameId"], "legacy-game");
        assert_eq!(
            value["canonicalGameId"],
            "00000000-0000-4000-8000-000000000001"
        );
        assert_eq!(
            value["playerMappings"][0]["legacyPlayerId"],
            "legacy-player"
        );
    }
}
