use super::*;

impl EngineWorkerClient {
    pub async fn air_sweep(
        &self,
        actor_id: &str,
        manifest: &WorkerManifest,
        previous_revision: u64,
        snapshot: &str,
        intent: AirSweepIntent<'_>,
    ) -> Result<CommitProposal, WorkerClientError> {
        let response = self
            .execute(
                actor_id,
                manifest,
                WorkerOperation::AirSweep {
                    snapshot,
                    actor_civilization_id: intent.actor_civilization_id,
                    unit_id: intent.unit_id,
                    target_x: intent.target_x,
                    target_y: intent.target_y,
                },
            )
            .await?;
        commit_proposal(previous_revision, response)
    }

    pub async fn launch_nuclear_strike(
        &self,
        actor_id: &str,
        manifest: &WorkerManifest,
        previous_revision: u64,
        snapshot: &str,
        intent: LaunchNuclearStrikeIntent<'_>,
    ) -> Result<CommitProposal, WorkerClientError> {
        let response = self
            .execute(
                actor_id,
                manifest,
                WorkerOperation::LaunchNuclearStrike {
                    snapshot,
                    actor_civilization_id: intent.actor_civilization_id,
                    unit_id: intent.unit_id,
                    target_x: intent.target_x,
                    target_y: intent.target_y,
                },
            )
            .await?;
        commit_proposal(previous_revision, response)
    }

    pub async fn bombard_with_city(
        &self,
        actor_id: &str,
        manifest: &WorkerManifest,
        previous_revision: u64,
        snapshot: &str,
        intent: BombardWithCityIntent<'_>,
    ) -> Result<CommitProposal, WorkerClientError> {
        let response = self
            .execute(
                actor_id,
                manifest,
                WorkerOperation::BombardWithCity {
                    snapshot,
                    actor_civilization_id: intent.actor_civilization_id,
                    city_id: intent.city_id,
                    target_x: intent.target_x,
                    target_y: intent.target_y,
                },
            )
            .await?;
        commit_proposal(previous_revision, response)
    }

    pub async fn attack_with_unit(
        &self,
        actor_id: &str,
        manifest: &WorkerManifest,
        previous_revision: u64,
        snapshot: &str,
        intent: AttackWithUnitIntent<'_>,
    ) -> Result<CommitProposal, WorkerClientError> {
        let response = self
            .execute(
                actor_id,
                manifest,
                WorkerOperation::AttackWithUnit {
                    snapshot,
                    actor_civilization_id: intent.actor_civilization_id,
                    unit_id: intent.unit_id,
                    target_x: intent.target_x,
                    target_y: intent.target_y,
                },
            )
            .await?;
        commit_proposal(previous_revision, response)
    }

    pub async fn paradrop_unit(
        &self,
        actor_id: &str,
        manifest: &WorkerManifest,
        previous_revision: u64,
        snapshot: &str,
        intent: ParadropUnitIntent<'_>,
    ) -> Result<CommitProposal, WorkerClientError> {
        let response = self
            .execute(
                actor_id,
                manifest,
                WorkerOperation::ParadropUnit {
                    snapshot,
                    actor_civilization_id: intent.actor_civilization_id,
                    unit_id: intent.unit_id,
                    destination_x: intent.destination_x,
                    destination_y: intent.destination_y,
                },
            )
            .await?;
        commit_proposal(previous_revision, response)
    }

    pub async fn found_city(
        &self,
        actor_id: &str,
        manifest: &WorkerManifest,
        previous_revision: u64,
        snapshot: &str,
        intent: FoundCityIntent<'_>,
    ) -> Result<CommitProposal, WorkerClientError> {
        let response = self
            .execute(
                actor_id,
                manifest,
                WorkerOperation::FoundCity {
                    snapshot,
                    actor_civilization_id: intent.actor_civilization_id,
                    unit_id: intent.unit_id,
                },
            )
            .await?;
        commit_proposal(previous_revision, response)
    }

    pub async fn pillage_tile(
        &self,
        actor_id: &str,
        manifest: &WorkerManifest,
        previous_revision: u64,
        snapshot: &str,
        intent: PillageTileIntent<'_>,
    ) -> Result<CommitProposal, WorkerClientError> {
        let response = self
            .execute(
                actor_id,
                manifest,
                WorkerOperation::PillageTile {
                    snapshot,
                    actor_civilization_id: intent.actor_civilization_id,
                    unit_id: intent.unit_id,
                },
            )
            .await?;
        commit_proposal(previous_revision, response)
    }

    pub async fn disband_unit(
        &self,
        actor_id: &str,
        manifest: &WorkerManifest,
        previous_revision: u64,
        snapshot: &str,
        intent: DisbandUnitIntent<'_>,
    ) -> Result<CommitProposal, WorkerClientError> {
        let response = self
            .execute(
                actor_id,
                manifest,
                WorkerOperation::DisbandUnit {
                    snapshot,
                    actor_civilization_id: intent.actor_civilization_id,
                    unit_id: intent.unit_id,
                },
            )
            .await?;
        commit_proposal(previous_revision, response)
    }

    pub async fn upgrade_units(
        &self,
        actor_id: &str,
        manifest: &WorkerManifest,
        previous_revision: u64,
        snapshot: &str,
        intent: UpgradeUnitsIntent<'_>,
    ) -> Result<CommitProposal, WorkerClientError> {
        let response = self
            .execute(
                actor_id,
                manifest,
                WorkerOperation::UpgradeUnits {
                    snapshot,
                    actor_civilization_id: intent.actor_civilization_id,
                    unit_ids: intent.unit_ids,
                    target_unit_name: intent.target_unit_name,
                },
            )
            .await?;
        commit_proposal(previous_revision, response)
    }

    pub async fn promote_unit(
        &self,
        actor_id: &str,
        manifest: &WorkerManifest,
        previous_revision: u64,
        snapshot: &str,
        intent: PromoteUnitIntent<'_>,
    ) -> Result<CommitProposal, WorkerClientError> {
        let response = self
            .execute(
                actor_id,
                manifest,
                WorkerOperation::PromoteUnit {
                    snapshot,
                    actor_civilization_id: intent.actor_civilization_id,
                    unit_id: intent.unit_id,
                    promotion_names: intent.promotion_names,
                    save_as_city_default: intent.save_as_city_default,
                },
            )
            .await?;
        commit_proposal(previous_revision, response)
    }

    pub async fn set_city_unit_promotion_preference(
        &self,
        actor_id: &str,
        manifest: &WorkerManifest,
        previous_revision: u64,
        snapshot: &str,
        intent: SetCityUnitPromotionPreferenceIntent<'_>,
    ) -> Result<CommitProposal, WorkerClientError> {
        let response = self
            .execute(
                actor_id,
                manifest,
                WorkerOperation::SetCityUnitPromotionPreference {
                    snapshot,
                    actor_civilization_id: intent.actor_civilization_id,
                    city_id: intent.city_id,
                    base_unit_name: intent.base_unit_name,
                    enabled: intent.enabled,
                },
            )
            .await?;
        commit_proposal(previous_revision, response)
    }

    pub async fn rename_unit(
        &self,
        actor_id: &str,
        manifest: &WorkerManifest,
        previous_revision: u64,
        snapshot: &str,
        intent: RenameUnitIntent<'_>,
    ) -> Result<CommitProposal, WorkerClientError> {
        let response = self
            .execute(
                actor_id,
                manifest,
                WorkerOperation::RenameUnit {
                    snapshot,
                    actor_civilization_id: intent.actor_civilization_id,
                    unit_id: intent.unit_id,
                    instance_name: intent.instance_name,
                },
            )
            .await?;
        commit_proposal(previous_revision, response)
    }

    pub async fn set_tile_improvement_order(
        &self,
        actor_id: &str,
        manifest: &WorkerManifest,
        previous_revision: u64,
        snapshot: &str,
        intent: SetTileImprovementOrderIntent<'_>,
    ) -> Result<CommitProposal, WorkerClientError> {
        let response = self
            .execute(
                actor_id,
                manifest,
                WorkerOperation::SetTileImprovementOrder {
                    snapshot,
                    actor_civilization_id: intent.actor_civilization_id,
                    unit_id: intent.unit_id,
                    improvement_name: intent.improvement_name,
                    queued_improvement_name: intent.queued_improvement_name,
                },
            )
            .await?;
        commit_proposal(previous_revision, response)
    }

    pub async fn set_road_connection_order(
        &self,
        actor_id: &str,
        manifest: &WorkerManifest,
        previous_revision: u64,
        snapshot: &str,
        intent: SetRoadConnectionOrderIntent<'_>,
    ) -> Result<CommitProposal, WorkerClientError> {
        let response = self
            .execute(
                actor_id,
                manifest,
                WorkerOperation::SetRoadConnectionOrder {
                    snapshot,
                    actor_civilization_id: intent.actor_civilization_id,
                    unit_id: intent.unit_id,
                    destination_x: intent.destination_x,
                    destination_y: intent.destination_y,
                },
            )
            .await?;
        commit_proposal(previous_revision, response)
    }
}
