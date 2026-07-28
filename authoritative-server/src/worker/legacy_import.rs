use serde::{Deserialize, Serialize};

use crate::state_hash;

use super::{
    EngineWorkerClient, NormalizedLegacyGame, WorkerClientError, WorkerManifest, WorkerOperation,
};

#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct LegacyPlayerMapping {
    pub legacy_player_id: String,
    pub account_id: String,
}

#[derive(Clone, Debug, Deserialize, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct LegacyImportedMember {
    pub legacy_player_id: String,
    pub account_id: String,
    pub civilization_id: String,
    pub spectator: bool,
}

#[derive(Clone, Debug, Deserialize, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct LegacyImportMetadata {
    pub legacy_game_id: String,
    pub canonical_game_id: String,
    pub serialization_version: i32,
    pub created_with: String,
    pub turns: i32,
    pub current_player: String,
    pub base_ruleset: String,
    pub mods: Vec<String>,
    pub members: Vec<LegacyImportedMember>,
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
        let snapshot = response.snapshot.ok_or(WorkerClientError::Incomplete)?;
        let canonical_state_hash = response
            .canonical_state_hash
            .ok_or(WorkerClientError::Incomplete)?;
        let owner_civilization_id = response
            .actor_civilization_id
            .ok_or(WorkerClientError::Incomplete)?;
        let metadata = response
            .legacy_import
            .ok_or(WorkerClientError::Incomplete)?;
        let mapping_pairs = player_mappings
            .iter()
            .map(|mapping| (&mapping.legacy_player_id, &mapping.account_id))
            .collect::<std::collections::BTreeSet<_>>();
        let metadata_pairs = metadata
            .members
            .iter()
            .map(|member| (&member.legacy_player_id, &member.account_id))
            .collect::<std::collections::BTreeSet<_>>();
        let owner_matches = metadata
            .members
            .iter()
            .filter(|member| {
                member.account_id == owner_account_id
                    && member.civilization_id == owner_civilization_id
                    && !member.spectator
            })
            .count()
            == 1;
        if metadata.legacy_game_id != expected_legacy_game_id
            || metadata.canonical_game_id != canonical_game_id
            || mapping_pairs != metadata_pairs
            || !owner_matches
            || state_hash(snapshot.as_bytes()) != canonical_state_hash
        {
            return Err(WorkerClientError::Protocol);
        }
        Ok(NormalizedLegacyGame {
            snapshot,
            canonical_state_hash,
            owner_civilization_id,
            metadata,
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

    #[test]
    fn legacy_metadata_rejects_unknown_wire_fields() {
        let value = serde_json::json!({
            "legacyGameId": "legacy",
            "canonicalGameId": "00000000-0000-4000-8000-000000000001",
            "serializationVersion": 4,
            "createdWith": "4.15.0 (Build 1000)",
            "turns": 12,
            "currentPlayer": "Rome",
            "baseRuleset": "Civ V - Vanilla",
            "mods": [],
            "members": [],
            "unexpected": true,
        });
        assert!(serde_json::from_value::<LegacyImportMetadata>(value).is_err());
    }
}
