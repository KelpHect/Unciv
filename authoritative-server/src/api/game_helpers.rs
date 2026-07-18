use super::*;

pub(super) fn game_metadata_response(metadata: GameMetadata) -> GameMetadataResponse {
    GameMetadataResponse {
        game_id: metadata.game_id,
        committed_revision: metadata.committed_revision,
        canonical_state_hash: metadata.canonical_state_hash,
        role: metadata.role,
        civilization_id: metadata.civilization_id,
    }
}
