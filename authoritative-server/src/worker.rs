//! Private Kotlin worker client. It sends typed intents and accepts only a
//! worker-produced snapshot/hash; it contains no Unciv rules.

use std::{net::SocketAddr, time::Duration};

use thiserror::Error;
use tokio::{
    io::{AsyncReadExt, AsyncWriteExt},
    net::TcpStream,
    time::timeout,
};

use crate::CommitProposal;

mod city_disposition;
mod city_economy;
mod city_governance;
mod city_population;
mod city_state;
mod diplomacy;
mod espionage;
mod event_choices;
mod great_people;
mod intents;
mod lifecycle;
mod major_diplomacy;
mod protocol;
mod religion;
mod research;
mod spectators;
mod trade;
mod unit_actions;
mod unit_gifts;
mod unit_movement;
mod unit_orders;
mod unit_transforms;
mod unit_triggers;
pub use city_state::{
    CityStateGoldGiftIntent, CityStateImprovementGiftIntent, CityStateProtectionIntent,
    CityStateTributeIntent,
};
pub use intents::{
    AcknowledgeResearchCompletionIntent, AdoptPolicyIntent, AirSweepIntent, AttackWithUnitIntent,
    BombardWithCityIntent, BuyCityTileIntent, CancelUnitMovementOrderIntent,
    CastDiplomaticVoteIntent, ChooseFreeTechnologyIntent, ChooseGreatPersonIntent,
    DisbandUnitIntent, FoundCityIntent, GiftUnitIntent, LaunchNuclearStrikeIntent,
    ManageConstructionQueuesIntent, MoveConstructionIntent, MoveSpyIntent, MoveUnitIntent,
    MoveUnitTowardIntent, ParadropUnitIntent, PillageTileIntent, PromoteUnitIntent,
    PurchaseConstructionAtTileIntent, PurchaseConstructionIntent, QueueConstructionAtTileIntent,
    QueueConstructionIntent, RemoveConstructionIntent, RenameUnitIntent, ResetCitizensIntent,
    ResolveCityDispositionIntent, ResolveEventChoiceIntent, SellBuildingIntent,
    SetAvoidGrowthIntent, SetCitizenFocusIntent, SetCityGovernanceIntent,
    SetCityTileAssignmentIntent, SetCityUnitPromotionPreferenceIntent, SetManualSpecialistsIntent,
    SetPerpetualConstructionIntent, SetResearchPathIntent, SetRoadConnectionOrderIntent,
    SetSpecialistCountIntent, SetSpyCoupIntent, SetTileImprovementOrderIntent,
    SetUnitAutomationIntent, SetUnitExplorationIntent, SetUnitPostureIntent, SwapUnitsIntent,
    TransformUnitIntent, TriggerUnitUniqueIntent, UpgradeUnitsIntent, UseGreatPersonUnitIntent,
};
pub use major_diplomacy::{
    CityStateProtectionPromptIntent, DiplomacyPartnerIntent, DiplomaticDemandIntent,
    DiplomaticPromptIntent,
};
pub use protocol::{
    AssignedPlayer, CreatedGame, ForcedResignation, ProjectedSpectatorState, ProjectedState,
    WorkerCapabilities, WorkerManifest, WorkerRuleset,
};
use protocol::{WorkerOperation, WorkerRequest, WorkerResponse};
pub use religion::{ChooseReligiousBeliefsIntent, UseReligiousUnitIntent};
pub use trade::{CounterTradeIntent, OfferTradeIntent, TradePartnerIntent, TradeRequestIntent};

pub const WORKER_PROTOCOL_VERSION: u16 = 1;
const MAX_FRAME_BYTES: usize = 16 * 1024 * 1024;

#[derive(Clone)]
pub struct EngineWorkerClient {
    address: SocketAddr,
    request_timeout: Duration,
}

#[derive(Debug, Error)]
pub enum WorkerClientError {
    #[error("worker transport failed")]
    Transport,
    #[error("worker frame exceeded its limit")]
    FrameTooLarge,
    #[error("worker returned an incompatible protocol")]
    Protocol,
    #[error("worker rejected execution: {0}")]
    Rejected(String),
    #[error("worker response was incomplete")]
    Incomplete,
}

fn commit_proposal(
    previous_revision: u64,
    response: WorkerResponse,
) -> Result<CommitProposal, WorkerClientError> {
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

impl EngineWorkerClient {
    pub async fn purchase_construction_at_tile(
        &self,
        actor_id: &str,
        manifest: &WorkerManifest,
        previous_revision: u64,
        snapshot: &str,
        intent: PurchaseConstructionAtTileIntent<'_>,
    ) -> Result<CommitProposal, WorkerClientError> {
        let response = self
            .execute(
                actor_id,
                manifest,
                WorkerOperation::PurchaseConstructionAtTile {
                    snapshot,
                    actor_civilization_id: intent.actor_civilization_id,
                    city_id: intent.city_id,
                    construction_name: intent.construction_name,
                    currency_name: intent.currency_name,
                    x: intent.x,
                    y: intent.y,
                    queue_index: intent.queue_index,
                },
            )
            .await?;
        commit_proposal(previous_revision, response)
    }

    pub async fn queue_construction_at_tile(
        &self,
        actor_id: &str,
        manifest: &WorkerManifest,
        previous_revision: u64,
        snapshot: &str,
        intent: QueueConstructionAtTileIntent<'_>,
    ) -> Result<CommitProposal, WorkerClientError> {
        let response = self
            .execute(
                actor_id,
                manifest,
                WorkerOperation::QueueConstructionAtTile {
                    snapshot,
                    actor_civilization_id: intent.actor_civilization_id,
                    city_id: intent.city_id,
                    construction_name: intent.construction_name,
                    x: intent.x,
                    y: intent.y,
                },
            )
            .await?;
        commit_proposal(previous_revision, response)
    }

    pub async fn buy_city_tile(
        &self,
        actor_id: &str,
        manifest: &WorkerManifest,
        previous_revision: u64,
        snapshot: &str,
        intent: BuyCityTileIntent<'_>,
    ) -> Result<CommitProposal, WorkerClientError> {
        let response = self
            .execute(
                actor_id,
                manifest,
                WorkerOperation::BuyCityTile {
                    snapshot,
                    actor_civilization_id: intent.actor_civilization_id,
                    city_id: intent.city_id,
                    x: intent.x,
                    y: intent.y,
                },
            )
            .await?;
        commit_proposal(previous_revision, response)
    }

    pub async fn set_perpetual_construction(
        &self,
        actor_id: &str,
        manifest: &WorkerManifest,
        previous_revision: u64,
        snapshot: &str,
        intent: SetPerpetualConstructionIntent<'_>,
    ) -> Result<CommitProposal, WorkerClientError> {
        let response = self
            .execute(
                actor_id,
                manifest,
                WorkerOperation::SetPerpetualConstruction {
                    snapshot,
                    actor_civilization_id: intent.actor_civilization_id,
                    city_id: intent.city_id,
                    construction_name: intent.construction_name,
                },
            )
            .await?;
        commit_proposal(previous_revision, response)
    }

    pub async fn purchase_construction(
        &self,
        actor_id: &str,
        manifest: &WorkerManifest,
        previous_revision: u64,
        snapshot: &str,
        intent: PurchaseConstructionIntent<'_>,
    ) -> Result<CommitProposal, WorkerClientError> {
        let response = self
            .execute(
                actor_id,
                manifest,
                WorkerOperation::PurchaseConstruction {
                    snapshot,
                    actor_civilization_id: intent.actor_civilization_id,
                    city_id: intent.city_id,
                    construction_name: intent.construction_name,
                    currency_name: intent.currency_name,
                    queue_index: intent.queue_index,
                },
            )
            .await?;
        commit_proposal(previous_revision, response)
    }

    pub async fn remove_construction(
        &self,
        actor_id: &str,
        manifest: &WorkerManifest,
        previous_revision: u64,
        snapshot: &str,
        intent: RemoveConstructionIntent<'_>,
    ) -> Result<CommitProposal, WorkerClientError> {
        let response = self
            .execute(
                actor_id,
                manifest,
                WorkerOperation::RemoveConstruction {
                    snapshot,
                    actor_civilization_id: intent.actor_civilization_id,
                    city_id: intent.city_id,
                    queue_index: intent.queue_index,
                    expected_construction_name: intent.expected_construction_name,
                },
            )
            .await?;
        commit_proposal(previous_revision, response)
    }

    pub async fn move_construction(
        &self,
        actor_id: &str,
        manifest: &WorkerManifest,
        previous_revision: u64,
        snapshot: &str,
        intent: MoveConstructionIntent<'_>,
    ) -> Result<CommitProposal, WorkerClientError> {
        let response = self
            .execute(
                actor_id,
                manifest,
                WorkerOperation::MoveConstruction {
                    snapshot,
                    actor_civilization_id: intent.actor_civilization_id,
                    city_id: intent.city_id,
                    from_index: intent.from_index,
                    to_index: intent.to_index,
                    expected_construction_name: intent.expected_construction_name,
                },
            )
            .await?;
        commit_proposal(previous_revision, response)
    }

    pub async fn queue_construction(
        &self,
        actor_id: &str,
        manifest: &WorkerManifest,
        previous_revision: u64,
        snapshot: &str,
        intent: QueueConstructionIntent<'_>,
    ) -> Result<CommitProposal, WorkerClientError> {
        let response = self
            .execute(
                actor_id,
                manifest,
                WorkerOperation::QueueConstruction {
                    snapshot,
                    actor_civilization_id: intent.actor_civilization_id,
                    city_id: intent.city_id,
                    construction_name: intent.construction_name,
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

    pub async fn handshake(&self) -> Result<WorkerCapabilities, WorkerClientError> {
        let response = self
            .execute_request(WorkerRequest {
                protocol_version: WORKER_PROTOCOL_VERSION,
                actor_id: None,
                ruleset_manifest: None,
                operation: WorkerOperation::Handshake,
            })
            .await?;
        Ok(WorkerCapabilities {
            engine_build: response.engine_build.ok_or(WorkerClientError::Incomplete)?,
            installed_rulesets: response
                .installed_rulesets
                .ok_or(WorkerClientError::Incomplete)?,
        })
    }

    pub async fn project_state(
        &self,
        actor_id: &str,
        manifest: &WorkerManifest,
        snapshot: &str,
        actor_civilization_id: &str,
    ) -> Result<ProjectedState, WorkerClientError> {
        let response = self
            .execute(
                actor_id,
                manifest,
                WorkerOperation::ProjectState {
                    snapshot,
                    actor_civilization_id,
                },
            )
            .await?;
        let projection = response
            .player_projection
            .ok_or(WorkerClientError::Incomplete)?;
        let projection: crate::projection::PlayerProjection =
            serde_json::from_value(projection).map_err(|_| WorkerClientError::Protocol)?;
        if !projection.research.is_consistent()
            || !projection.movement_is_consistent()
            || !projection.combat_is_consistent()
            || !projection.city_economy_is_consistent()
            || !projection.wonder_events_are_consistent()
        {
            return Err(WorkerClientError::Protocol);
        }
        Ok(ProjectedState { projection })
    }

    pub async fn assign_player(
        &self,
        actor_id: &str,
        manifest: &WorkerManifest,
        previous_revision: u64,
        snapshot: &str,
    ) -> Result<AssignedPlayer, WorkerClientError> {
        let response = self
            .execute(
                actor_id,
                manifest,
                WorkerOperation::AssignPlayer { snapshot },
            )
            .await?;
        Ok(AssignedPlayer {
            proposal: CommitProposal {
                previous_revision,
                snapshot: response
                    .snapshot
                    .ok_or(WorkerClientError::Incomplete)?
                    .into_bytes(),
                canonical_state_hash: response
                    .canonical_state_hash
                    .ok_or(WorkerClientError::Incomplete)?,
            },
            civilization_id: response
                .actor_civilization_id
                .ok_or(WorkerClientError::Incomplete)?,
        })
    }

    pub fn new(address: SocketAddr, request_timeout: Duration) -> Self {
        Self {
            address,
            request_timeout,
        }
    }

    pub async fn end_turn(
        &self,
        actor_id: &str,
        manifest: &WorkerManifest,
        previous_revision: u64,
        snapshot: &str,
        actor_civilization_id: &str,
    ) -> Result<CommitProposal, WorkerClientError> {
        let request = WorkerRequest {
            protocol_version: WORKER_PROTOCOL_VERSION,
            actor_id: Some(actor_id),
            ruleset_manifest: Some(manifest),
            operation: WorkerOperation::EndTurn {
                snapshot,
                actor_civilization_id,
            },
        };
        let payload = serde_json::to_vec(&request).map_err(|_| WorkerClientError::Transport)?;
        if payload.len() > MAX_FRAME_BYTES {
            return Err(WorkerClientError::FrameTooLarge);
        }
        let response = timeout(self.request_timeout, async {
            let mut stream = TcpStream::connect(self.address)
                .await
                .map_err(|_| WorkerClientError::Transport)?;
            stream
                .write_u32(payload.len() as u32)
                .await
                .map_err(|_| WorkerClientError::Transport)?;
            stream
                .write_all(&payload)
                .await
                .map_err(|_| WorkerClientError::Transport)?;
            stream
                .flush()
                .await
                .map_err(|_| WorkerClientError::Transport)?;
            let size = stream
                .read_u32()
                .await
                .map_err(|_| WorkerClientError::Transport)? as usize;
            if !(1..=MAX_FRAME_BYTES).contains(&size) {
                return Err(WorkerClientError::FrameTooLarge);
            }
            let mut response = vec![0; size];
            stream
                .read_exact(&mut response)
                .await
                .map_err(|_| WorkerClientError::Transport)?;
            serde_json::from_slice::<WorkerResponse>(&response)
                .map_err(|_| WorkerClientError::Transport)
        })
        .await
        .map_err(|_| WorkerClientError::Transport)??;
        if response.protocol_version != WORKER_PROTOCOL_VERSION {
            return Err(WorkerClientError::Protocol);
        }
        if let Some(error) = response.error {
            return Err(WorkerClientError::Rejected(format!(
                "{}: {}",
                error.code, error.message
            )));
        }
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

    /// Asks the Kotlin worker to create revision zero through `GameStarter`.
    /// `setup` is a setup intent, not an uploaded `GameInfo` or save payload.
    pub async fn create_game(
        &self,
        actor_id: &str,
        manifest: &WorkerManifest,
        setup: &str,
    ) -> Result<CreatedGame, WorkerClientError> {
        let response = self
            .execute(actor_id, manifest, WorkerOperation::CreateGame { setup })
            .await?;
        Ok(CreatedGame {
            proposal: CommitProposal {
                previous_revision: 0,
                snapshot: response
                    .snapshot
                    .ok_or(WorkerClientError::Incomplete)?
                    .into_bytes(),
                canonical_state_hash: response
                    .canonical_state_hash
                    .ok_or(WorkerClientError::Incomplete)?,
            },
            owner_civilization_id: response
                .actor_civilization_id
                .ok_or(WorkerClientError::Incomplete)?,
        })
    }

    async fn execute(
        &self,
        actor_id: &str,
        manifest: &WorkerManifest,
        operation: WorkerOperation<'_>,
    ) -> Result<WorkerResponse, WorkerClientError> {
        let request = WorkerRequest {
            protocol_version: WORKER_PROTOCOL_VERSION,
            actor_id: Some(actor_id),
            ruleset_manifest: Some(manifest),
            operation,
        };
        self.execute_request(request).await
    }

    async fn execute_request(
        &self,
        request: WorkerRequest<'_>,
    ) -> Result<WorkerResponse, WorkerClientError> {
        let payload = serde_json::to_vec(&request).map_err(|_| WorkerClientError::Transport)?;
        if payload.len() > MAX_FRAME_BYTES {
            return Err(WorkerClientError::FrameTooLarge);
        }
        let response = timeout(self.request_timeout, async {
            let mut stream = TcpStream::connect(self.address)
                .await
                .map_err(|_| WorkerClientError::Transport)?;
            stream
                .write_u32(payload.len() as u32)
                .await
                .map_err(|_| WorkerClientError::Transport)?;
            stream
                .write_all(&payload)
                .await
                .map_err(|_| WorkerClientError::Transport)?;
            stream
                .flush()
                .await
                .map_err(|_| WorkerClientError::Transport)?;
            let size = stream
                .read_u32()
                .await
                .map_err(|_| WorkerClientError::Transport)? as usize;
            if !(1..=MAX_FRAME_BYTES).contains(&size) {
                return Err(WorkerClientError::FrameTooLarge);
            }
            let mut response = vec![0; size];
            stream
                .read_exact(&mut response)
                .await
                .map_err(|_| WorkerClientError::Transport)?;
            serde_json::from_slice::<WorkerResponse>(&response)
                .map_err(|_| WorkerClientError::Transport)
        })
        .await
        .map_err(|_| WorkerClientError::Transport)??;
        if response.protocol_version != WORKER_PROTOCOL_VERSION {
            return Err(WorkerClientError::Protocol);
        }
        if let Some(error) = response.error {
            return Err(WorkerClientError::Rejected(format!(
                "{}: {}",
                error.code, error.message
            )));
        }
        Ok(response)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::Value;
    use tokio::net::TcpListener;

    #[tokio::test]
    async fn handshake_uses_the_versioned_actorless_contract() {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let address = listener.local_addr().unwrap();
        let server = tokio::spawn(async move {
            let (mut stream, _) = listener.accept().await.unwrap();
            let size = stream.read_u32().await.unwrap() as usize;
            let mut request = vec![0; size];
            stream.read_exact(&mut request).await.unwrap();
            let request: Value = serde_json::from_slice(&request).unwrap();
            assert_eq!(request["protocolVersion"], WORKER_PROTOCOL_VERSION);
            assert_eq!(request["operation"]["type"], "handshake");
            assert!(request.get("actorId").is_none());
            assert!(request.get("rulesetManifest").is_none());

            let response = serde_json::to_vec(&serde_json::json!({
                "protocolVersion": WORKER_PROTOCOL_VERSION,
                "engineBuild": "4.21.1",
                "installedRulesets": [{
                    "name": "Civ V - Vanilla",
                    "sha256": "a".repeat(64),
                }],
            }))
            .unwrap();
            stream.write_u32(response.len() as u32).await.unwrap();
            stream.write_all(&response).await.unwrap();
        });

        let capabilities = EngineWorkerClient::new(address, Duration::from_secs(1))
            .handshake()
            .await
            .unwrap();
        assert_eq!(capabilities.engine_build, "4.21.1");
        assert_eq!(capabilities.installed_rulesets.len(), 1);
        server.await.unwrap();
    }

    #[test]
    fn event_choice_worker_operation_matches_kotlin_wire_names() {
        let value = serde_json::to_value(WorkerOperation::ResolveEventChoice {
            snapshot: "snapshot",
            actor_civilization_id: "Rome",
            prompt_id: "a",
            choice_id: "b",
        })
        .unwrap();
        assert_eq!(value["type"], "resolve_event_choice");
        assert_eq!(value["actorCivilizationId"], "Rome");
        assert_eq!(value["promptId"], "a");
        assert_eq!(value["choiceId"], "b");
        assert!(value.get("actor_civilization_id").is_none());
    }

    #[test]
    fn gift_unit_worker_operation_matches_kotlin_wire_names() {
        let value = serde_json::to_value(WorkerOperation::GiftUnit {
            snapshot: "snapshot",
            actor_civilization_id: "Rome",
            unit_id: 17,
        })
        .unwrap();
        assert_eq!(value["type"], "gift_unit");
        assert_eq!(value["actorCivilizationId"], "Rome");
        assert_eq!(value["unitId"], 17);
        assert!(value.get("actor_civilization_id").is_none());
        assert!(value.get("unit_id").is_none());
    }

    #[test]
    fn escorted_move_worker_operation_matches_kotlin_wire_names() {
        let value = serde_json::to_value(WorkerOperation::MoveUnit {
            snapshot: "snapshot",
            actor_civilization_id: "Rome",
            unit_id: 17,
            destination_x: 2,
            destination_y: -1,
            escort_unit_id: Some(18),
        })
        .unwrap();
        assert_eq!(value["type"], "move_unit");
        assert_eq!(value["actorCivilizationId"], "Rome");
        assert_eq!(value["unitId"], 17);
        assert_eq!(value["destinationX"], 2);
        assert_eq!(value["destinationY"], -1);
        assert_eq!(value["escortUnitId"], 18);
        assert!(value.get("escort_unit_id").is_none());
    }

    #[test]
    fn transform_unit_worker_operation_matches_kotlin_wire_names() {
        let value = serde_json::to_value(WorkerOperation::TransformUnit {
            snapshot: "snapshot",
            actor_civilization_id: "Rome",
            unit_id: 17,
            action_id: "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        })
        .unwrap();
        assert_eq!(value["type"], "transform_unit");
        assert_eq!(value["actorCivilizationId"], "Rome");
        assert_eq!(value["unitId"], 17);
        assert_eq!(
            value["actionId"],
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        );
        assert!(value.get("action_id").is_none());
    }

    #[test]
    fn construction_queue_batch_worker_operation_matches_kotlin_wire_names() {
        let value = serde_json::to_value(WorkerOperation::ManageConstructionQueues {
            snapshot: "snapshot",
            actor_civilization_id: "Rome",
            city_id: "city-1",
            construction_name: "Monument",
            queue_index: Some(1),
            action: crate::ConstructionQueueAction::AddOrMoveToTopAllCities,
        })
        .unwrap();
        assert_eq!(value["type"], "manage_construction_queues");
        assert_eq!(value["actorCivilizationId"], "Rome");
        assert_eq!(value["cityId"], "city-1");
        assert_eq!(value["constructionName"], "Monument");
        assert_eq!(value["queueIndex"], 1);
        assert_eq!(value["action"], "add_or_move_to_top_all_cities");
        assert!(value.get("city_ids").is_none());
    }

    #[test]
    fn trigger_unit_unique_worker_operation_matches_kotlin_wire_names() {
        let value = serde_json::to_value(WorkerOperation::TriggerUnitUnique {
            snapshot: "snapshot",
            actor_civilization_id: "Rome",
            unit_id: 17,
            action_id: "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
        })
        .unwrap();
        assert_eq!(value["type"], "trigger_unit_unique");
        assert_eq!(value["actorCivilizationId"], "Rome");
        assert_eq!(value["unitId"], 17);
        assert_eq!(
            value["actionId"],
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        );
        assert!(value.get("action_id").is_none());
    }

    #[test]
    fn resign_worker_operation_matches_kotlin_wire_names() {
        let value = serde_json::to_value(WorkerOperation::Resign {
            snapshot: "snapshot",
            actor_civilization_id: "Rome",
        })
        .unwrap();
        assert_eq!(value["type"], "resign");
        assert_eq!(value["actorCivilizationId"], "Rome");
        assert!(value.get("actor_civilization_id").is_none());
    }

    #[test]
    fn force_resign_worker_operation_matches_kotlin_wire_names() {
        let value = serde_json::to_value(WorkerOperation::ForceResign {
            snapshot: "snapshot",
            actor_civilization_id: "Rome",
        })
        .unwrap();
        assert_eq!(value["type"], "force_resign");
        assert_eq!(value["actorCivilizationId"], "Rome");
        assert!(value.get("actor_civilization_id").is_none());
    }
}
