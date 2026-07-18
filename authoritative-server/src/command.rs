use serde::{Deserialize, Serialize};
use uuid::Uuid;

#[derive(Clone, Debug, Deserialize, Serialize, PartialEq, Eq)]
#[serde(tag = "type", rename_all = "snake_case", deny_unknown_fields)]
pub enum GameCommand {
    JoinGame,
    EndTurn,
    MoveUnit {
        unit_id: i32,
        destination_x: i32,
        destination_y: i32,
    },
    QueueConstruction {
        city_id: String,
        construction_name: String,
    },
    SetPerpetualConstruction {
        city_id: String,
        construction_name: String,
    },
    RemoveConstruction {
        city_id: String,
        queue_index: u32,
        expected_construction_name: String,
    },
    MoveConstruction {
        city_id: String,
        from_index: u32,
        to_index: u32,
        expected_construction_name: String,
    },
    PurchaseConstruction {
        city_id: String,
        construction_name: String,
        currency_name: String,
        queue_index: Option<u32>,
    },
    SetResearchPath {
        technology_name: String,
    },
    AdoptPolicy {
        policy_name: String,
    },
    ChooseFreeTechnology {
        technology_name: String,
    },
}

#[derive(Clone, Debug, Deserialize, Serialize, PartialEq, Eq)]
pub struct CommandEnvelope {
    pub protocol_version: u16,
    pub game_id: Uuid,
    pub command_id: Uuid,
    pub expected_revision: u64,
    pub client_observed_state_hash: Option<String>,
    pub command: GameCommand,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct CommitProposal {
    pub previous_revision: u64,
    pub snapshot: Vec<u8>,
    pub canonical_state_hash: String,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, utoipa::ToSchema)]
pub struct CommandAccepted {
    pub game_id: Uuid,
    pub command_id: Uuid,
    pub previous_revision: u64,
    pub committed_revision: u64,
    pub canonical_state_hash: String,
}
