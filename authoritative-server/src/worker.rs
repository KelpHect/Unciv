//! Private Kotlin worker client. It sends typed intents and accepts only a
//! worker-produced snapshot/hash; it contains no Unciv rules.

use std::{
    net::SocketAddr,
    sync::Arc,
    time::{Duration, SystemTime, UNIX_EPOCH},
};

use thiserror::Error;

use crate::CommitProposal;

mod authentication;
mod capital_project;
mod circuit_breaker;
mod city_disposition;
mod city_economy;
mod city_governance;
mod city_population;
mod city_state;
mod city_tiles;
#[cfg(test)]
mod client_tests;
mod deadlines;
mod diplomacy;
mod espionage;
mod event_choices;
mod game_setup;
mod great_people;
mod instant_improvements;
mod intents;
mod json_limits;
mod legacy_import;
mod lifecycle;
mod major_diplomacy;
mod manifest;
mod protocol;
mod queue;
mod religion;
mod research;
mod response;
mod spectators;
mod trade;
mod transport;
mod unit_actions;
mod unit_gifts;
mod unit_movement;
mod unit_orders;
mod unit_transforms;
mod unit_triggers;
pub use authentication::WorkerIdentityKey;
pub use circuit_breaker::{WorkerCircuitBreakerConfig, WorkerCircuitBreakerConfigError};
pub use city_state::{
    CityStateGoldGiftIntent, CityStateImprovementGiftIntent, CityStateProtectionIntent,
    CityStateTributeIntent,
};
pub use deadlines::{WorkerDeadlineConfigError, WorkerDeadlines};
pub use game_setup::{
    BarbarianMode, GeneratedMapShape, GeneratedMapSize, GeneratedMapType, MapResourceDensity,
    WorkerGameSetup,
};
pub use intents::{
    AcknowledgeResearchCompletionIntent, AddUnitToCapitalProjectIntent, AdoptPolicyIntent,
    AirSweepIntent, AttackWithUnitIntent, BombardWithCityIntent, BuyCityTileBatchIntent,
    BuyCityTileIntent, CancelUnitMovementOrderIntent, CastDiplomaticVoteIntent,
    ChooseFreeTechnologyIntent, ChooseGreatPersonIntent, CreateInstantImprovementIntent,
    DisbandUnitIntent, FoundCityIntent, GiftUnitIntent, LaunchNuclearStrikeIntent,
    ManageConstructionQueuesIntent, ManageResearchQueueIntent, MoveConstructionIntent,
    MoveSpyIntent, MoveUnitIntent, MoveUnitTowardIntent, ParadropUnitIntent, PillageTileIntent,
    PromoteUnitIntent, PurchaseConstructionAtTileIntent, PurchaseConstructionIntent,
    QueueConstructionAtTileIntent, QueueConstructionIntent, RemoveConstructionIntent,
    RenameUnitIntent, ResetCitizensIntent, ResolveCityDispositionIntent, ResolveEventChoiceIntent,
    SellBuildingIntent, SetAvoidGrowthIntent, SetCitizenFocusIntent, SetCityGovernanceIntent,
    SetCityTileAssignmentIntent, SetCityUnitPromotionPreferenceIntent, SetManualSpecialistsIntent,
    SetPerpetualConstructionIntent, SetResearchPathIntent, SetRoadConnectionOrderIntent,
    SetSpecialistCountIntent, SetSpyCoupIntent, SetTileImprovementOrderIntent,
    SetUnitAutomationIntent, SetUnitExplorationIntent, SetUnitPostureIntent, SwapUnitsIntent,
    TransformUnitIntent, TriggerUnitUniqueIntent, UpgradeUnitsIntent, UseGreatPersonUnitIntent,
};
pub use legacy_import::{LegacyImportMetadata, LegacyImportedMember, LegacyPlayerMapping};
pub use major_diplomacy::{
    CityStateProtectionPromptIntent, DiplomacyPartnerIntent, DiplomaticDemandIntent,
    DiplomaticPromptIntent,
};
pub use manifest::{WorkerManifest, WorkerRuleset};
use protocol::{WorkerOperation, WorkerRequest};
pub use queue::{WorkerQueueConfig, WorkerQueueConfigError};
pub use religion::{ChooseReligiousBeliefsIntent, UseReligiousUnitIntent};
use response::WorkerResponse;
pub use response::{
    AssignedPlayer, CreatedGame, ForcedResignation, NormalizedLegacyGame, ProjectedSpectatorState,
    ProjectedState, WorkerCapabilities,
};
pub use trade::{CounterTradeIntent, OfferTradeIntent, TradePartnerIntent, TradeRequestIntent};
#[cfg(test)]
pub(crate) use transport::{read_authenticated_test_frame, write_authenticated_test_frame};

pub const WORKER_PROTOCOL_VERSION: u16 = 3;
const MAX_FRAME_BYTES: usize = 16 * 1024 * 1024;

#[derive(Clone)]
pub struct EngineWorkerClient {
    address: SocketAddr,
    deadlines: WorkerDeadlines,
    circuit_breaker: Arc<circuit_breaker::WorkerCircuitBreaker>,
    queue: Arc<queue::WorkerQueue>,
    identity_key: WorkerIdentityKey,
}

#[derive(Error)]
pub enum WorkerClientError {
    #[error("worker transport failed")]
    Transport,
    #[error("worker connect deadline expired")]
    ConnectTimeout,
    #[error("worker write deadline expired")]
    WriteTimeout,
    #[error("worker read deadline expired")]
    ReadTimeout,
    #[error("worker total deadline expired")]
    TotalTimeout,
    #[error("worker circuit is open")]
    CircuitOpen,
    #[error("worker queue is full")]
    QueueFull,
    #[error("worker queue deadline expired")]
    QueueTimeout,
    #[error("worker frame exceeded its limit")]
    FrameTooLarge,
    #[error("worker returned an incompatible protocol")]
    Protocol,
    #[error("worker identity verification failed")]
    Identity,
    // The private reason may contain rule or state diagnostics. Keep it for
    // internal control flow, but never expose it through Display/logging.
    #[error("worker rejected execution")]
    Rejected(String),
    #[error("worker response was incomplete")]
    Incomplete,
}

impl std::fmt::Debug for WorkerClientError {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        std::fmt::Display::fmt(self, formatter)
    }
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
        server_time_millis: response
            .server_time_millis
            .ok_or(WorkerClientError::Incomplete)?,
        replay_operation: response
            .replay_operation
            .ok_or(WorkerClientError::Incomplete)?,
    })
}

fn server_time_millis() -> Result<i64, WorkerClientError> {
    let millis = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_err(|_| WorkerClientError::Transport)?
        .as_millis();
    i64::try_from(millis).map_err(|_| WorkerClientError::Transport)
}

impl EngineWorkerClient {
    pub fn new(
        address: SocketAddr,
        request_timeout: Duration,
        identity_key: WorkerIdentityKey,
    ) -> Self {
        Self {
            address,
            deadlines: WorkerDeadlines::uniform(request_timeout),
            circuit_breaker: Arc::new(circuit_breaker::WorkerCircuitBreaker::new(
                WorkerCircuitBreakerConfig::default_bounded(),
            )),
            queue: Arc::new(queue::WorkerQueue::new(WorkerQueueConfig::default_bounded())),
            identity_key,
        }
    }

    pub fn with_deadlines(
        address: SocketAddr,
        deadlines: WorkerDeadlines,
        identity_key: WorkerIdentityKey,
    ) -> Self {
        Self {
            address,
            deadlines,
            circuit_breaker: Arc::new(circuit_breaker::WorkerCircuitBreaker::new(
                WorkerCircuitBreakerConfig::default_bounded(),
            )),
            queue: Arc::new(queue::WorkerQueue::new(WorkerQueueConfig::default_bounded())),
            identity_key,
        }
    }

    pub fn with_transport_policy(
        address: SocketAddr,
        deadlines: WorkerDeadlines,
        circuit_breaker: WorkerCircuitBreakerConfig,
        identity_key: WorkerIdentityKey,
    ) -> Self {
        Self::with_runtime_policy(
            address,
            deadlines,
            circuit_breaker,
            WorkerQueueConfig::default_bounded(),
            identity_key,
        )
    }

    pub fn with_runtime_policy(
        address: SocketAddr,
        deadlines: WorkerDeadlines,
        circuit_breaker: WorkerCircuitBreakerConfig,
        queue: WorkerQueueConfig,
        identity_key: WorkerIdentityKey,
    ) -> Self {
        Self {
            address,
            deadlines,
            circuit_breaker: Arc::new(circuit_breaker::WorkerCircuitBreaker::new(circuit_breaker)),
            queue: Arc::new(queue::WorkerQueue::new(queue)),
            identity_key,
        }
    }

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
        commit_proposal(previous_revision, response)
    }

    pub async fn handshake(&self) -> Result<WorkerCapabilities, WorkerClientError> {
        let response = self
            .execute_request(WorkerRequest {
                protocol_version: WORKER_PROTOCOL_VERSION,
                server_time_millis: None,
                actor_id: None,
                ruleset_manifest: None,
                operation: WorkerOperation::Handshake,
            })
            .await?;
        let release_bundle_id = response
            .release_bundle_id
            .ok_or(WorkerClientError::Incomplete)?;
        let engine_build = response.engine_build.ok_or(WorkerClientError::Incomplete)?;
        let installed_rulesets = response
            .installed_rulesets
            .ok_or(WorkerClientError::Incomplete)?;
        if !valid_release_bundle_id(&release_bundle_id)
            || engine_build.is_empty()
            || engine_build.len() > 128
            || engine_build.chars().any(char::is_control)
            || installed_rulesets.len() > 1_024
            || installed_rulesets.iter().any(|ruleset| !ruleset.is_valid())
        {
            return Err(WorkerClientError::Protocol);
        }
        Ok(WorkerCapabilities {
            release_bundle_id,
            engine_build,
            installed_rulesets,
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
        let projection_checks = [
            ("victory", projection.victory_is_consistent()),
            ("research", projection.research.is_consistent()),
            ("tiles", projection.tiles_are_consistent()),
            ("turn_readiness", projection.turn_readiness_is_consistent()),
            ("unit_actions", projection.unit_actions_are_consistent()),
            ("diplomacy", projection.diplomacy_is_consistent()),
            ("movement", projection.movement_is_consistent()),
            ("combat", projection.combat_is_consistent()),
            ("city_economy", projection.city_economy_is_consistent()),
            ("wonder_events", projection.wonder_events_are_consistent()),
        ];
        if projection_checks.iter().any(|(_, valid)| !valid) {
            tracing::warn!(
                failed_projection_checks = ?projection_checks
                    .iter()
                    .filter_map(|(name, valid)| (!valid).then_some(name))
                    .collect::<Vec<_>>(),
                "authoritative worker returned an inconsistent player projection"
            );
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
        civilization_id: &str,
    ) -> Result<AssignedPlayer, WorkerClientError> {
        let response = self
            .execute(
                actor_id,
                manifest,
                WorkerOperation::AssignPlayer {
                    snapshot,
                    civilization_id,
                },
            )
            .await?;
        let civilization_id = response
            .actor_civilization_id
            .clone()
            .ok_or(WorkerClientError::Incomplete)?;
        Ok(AssignedPlayer {
            proposal: commit_proposal(previous_revision, response)?,
            civilization_id,
        })
    }

    pub async fn end_turn(
        &self,
        actor_id: &str,
        manifest: &WorkerManifest,
        previous_revision: u64,
        snapshot: &str,
        actor_civilization_id: &str,
    ) -> Result<CommitProposal, WorkerClientError> {
        let response = self
            .execute(
                actor_id,
                manifest,
                WorkerOperation::EndTurn {
                    snapshot,
                    actor_civilization_id,
                },
            )
            .await?;
        commit_proposal(previous_revision, response)
    }

    /// Asks the Kotlin worker to create revision zero through `GameStarter`.
    /// The seed is generated by the control plane, never supplied by a client.
    /// The worker derives the complete default setup from the pinned manifest.
    pub async fn create_game(
        &self,
        actor_id: &str,
        manifest: &WorkerManifest,
        game_id: &str,
        server_seed: i64,
        setup: &WorkerGameSetup,
    ) -> Result<CreatedGame, WorkerClientError> {
        let response = self
            .execute(
                actor_id,
                manifest,
                WorkerOperation::CreateGame {
                    game_id,
                    server_seed,
                    setup,
                },
            )
            .await?;
        let owner_civilization_id = response
            .actor_civilization_id
            .clone()
            .ok_or(WorkerClientError::Incomplete)?;
        let available_civilization_ids = response
            .available_civilization_ids
            .clone()
            .ok_or(WorkerClientError::Incomplete)?;
        Ok(CreatedGame {
            proposal: commit_proposal(0, response)?,
            owner_civilization_id,
            available_civilization_ids,
        })
    }
}

fn valid_release_bundle_id(value: &str) -> bool {
    value == "dev-unpackaged"
        || (value.len() == 64
            && value
                .bytes()
                .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte)))
}

#[cfg(test)]
mod tests {
    use super::*;

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
