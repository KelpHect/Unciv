use crate::projection::*;
use crate::{
    CityDispositionAction, CityGovernanceAction, ReligiousBeliefType, ReligiousUnitAction,
    UnitPosture,
};

#[test]
fn shared_projection_fixture_is_closed_and_round_trips_semantically() {
    let fixture = include_str!("../../protocol/player-projection-v60.fixture.json");
    let expected: serde_json::Value = serde_json::from_str(fixture).unwrap();
    let projection: PlayerProjection = serde_json::from_value(expected.clone()).unwrap();
    assert_eq!(projection.protocol_version, 3);
    assert_eq!(
        projection.pending_turn_actions,
        [
            PendingEndTurnAction::PickPolicy,
            PendingEndTurnAction::CastDiplomaticVote,
            PendingEndTurnAction::PickGreatPerson,
        ]
    );
    assert_eq!(projection.diplomatic_vote_candidates, ["Greece"]);
    assert_eq!(projection.research.appendable_targets, ["Archery"]);
    assert_eq!(projection.research.queue_entries[0].stored_science, 14);
    assert!(projection.tiles_are_consistent());
    assert_eq!(projection.explored_tiles[0].base_terrain, "Grassland");
    assert_eq!(projection.explored_tiles[0].terrain_features, ["Forest"]);
    assert_eq!(
        projection.explored_tiles[0].resource_name.as_deref(),
        Some("Wheat")
    );
    assert!(
        projection.research.queue_entries[0]
            .available_actions
            .is_empty()
    );
    assert_eq!(
        projection.research.queue_entries[1].available_actions,
        [crate::ResearchQueueAction::Remove]
    );
    assert_eq!(
        projection.research.queue_entries[0].estimated_turns,
        Some(4)
    );
    assert_eq!(projection.research.overflow_science, 3);
    assert_eq!(
        projection.research.completion_prompts[0].technology_name,
        "Mining"
    );
    assert!(projection.research.is_consistent());
    assert_eq!(projection.diplomacy_partners[0].civilization_id, "Greece");
    assert_eq!(
        projection.diplomacy_partners[0].adopted_policy_branches,
        ["Tradition"]
    );
    assert!(projection.diplomacy_is_consistent());
    assert_eq!(
        projection.diplomacy_partners[0].available_demands,
        [DiplomaticDemand::DoNotSettleNearUs]
    );
    assert_eq!(
        projection.diplomacy_prompts[0].r#type,
        DiplomacyPromptType::Friendship
    );
    assert_eq!(projection.city_state_partners[0].civilization_id, "Geneva");
    assert_eq!(
        projection.city_state_partners[0].available_gold_gifts,
        [250]
    );
    assert_eq!(projection.city_state_partners[0].influence, 37);
    assert_eq!(
        projection.city_state_partners[0].influence_level,
        ProjectedCityStateInfluenceLevel::Friend
    );
    assert_eq!(
        projection.selectable_great_people,
        ["Great Engineer", "Great Scientist"]
    );
    let religion_choice = projection.religion_choice.as_ref().unwrap();
    assert_eq!(
        religion_choice.required_belief_types,
        [ReligiousBeliefType::Founder, ReligiousBeliefType::Follower]
    );
    assert!(religion_choice.requires_religion_identity);
    assert_eq!(
        projection.own_units[0].available_religious_actions,
        [ReligiousUnitAction::SpreadReligion]
    );
    assert!(projection.own_units[0].capital_project_name.is_none());
    assert!(projection.unit_actions_are_consistent());
    assert_eq!(projection.own_units[0].current_movement, Some(1.5));
    assert_eq!(projection.visible_foreign_units[0].current_movement, None);
    let mut missing_private_movement = projection.clone();
    missing_private_movement.own_units[0].current_movement = None;
    assert!(!missing_private_movement.unit_actions_are_consistent());
    let mut leaked_private_movement = projection.clone();
    leaked_private_movement.visible_foreign_units[0].current_movement = Some(0.0);
    assert!(!leaked_private_movement.unit_actions_are_consistent());
    assert!(
        projection.visible_foreign_units[0]
            .available_religious_actions
            .is_empty()
    );
    assert_eq!(projection.own_cities[0].construction_queue, ["Monument"]);
    assert!(projection.own_cities[0].assignable_tiles[0].worked);
    assert_eq!(projection.own_cities[0].specialists[0].name, "Scientist");
    assert_eq!(
        projection.own_cities[0].unit_promotion_preferences[0].base_unit_name,
        "Warrior"
    );
    assert_eq!(
        projection.own_cities[0].citizen_focus,
        CitizenFocus::GoldFocus
    );
    assert_eq!(
        projection.own_cities[0].available_governance_actions,
        [CityGovernanceAction::StartRazing]
    );
    assert_eq!(projection.pending_city_dispositions[0].city_name, "Athens");
    assert_eq!(
        projection.pending_city_dispositions[0].available_actions,
        [
            CityDispositionAction::Liberate,
            CityDispositionAction::Annex,
            CityDispositionAction::Puppet,
            CityDispositionAction::Raze,
        ]
    );
    assert_eq!(
        projection.research.current_technology.as_deref(),
        Some("Pottery")
    );
    assert_eq!(projection.policies.selectable_policies, ["Tradition"]);
    assert_eq!(projection.own_units[0].movement_destination_x, Some(7));
    assert_eq!(
        projection.own_units[0].move_destinations,
        [ProjectedMovementDestination { x: 2, y: -1 }]
    );
    assert_eq!(
        projection.own_units[0].swap_destinations,
        [ProjectedMovementDestination { x: 2, y: -1 }]
    );
    assert!(projection.movement_is_consistent());
    let attack = &projection.own_units[0].attack_targets[0];
    assert_eq!(
        (
            attack.x,
            attack.y,
            attack.attack_from_x,
            attack.attack_from_y
        ),
        (2, -1, 3, -1)
    );
    assert_eq!(attack.preview.attacker_effective_strength, 11);
    let bombard = &projection.own_cities[0].bombard_targets[0];
    assert_eq!((bombard.x, bombard.y), (2, -1));
    assert_eq!(bombard.preview.defender_min_remaining_health, Some(70));
    assert!(projection.combat_is_consistent());
    assert!(projection.own_units[0].automated);
    assert!(!projection.own_units[0].exploring);
    assert_eq!(projection.own_units[0].posture, Some(UnitPosture::Fortify));
    assert_eq!(projection.own_units[0].promotions, ["Drill I"]);
    assert_eq!(projection.own_units[0].promotion_xp, Some(12));
    assert_eq!(projection.own_units[0].next_promotion_xp, Some(30));
    assert_eq!(
        projection.own_units[0].instance_name.as_deref(),
        Some("First Mission")
    );
    assert_eq!(projection.own_units[0].improvement_order.len(), 2);
    assert_eq!(
        projection.own_units[0].road_connection_destination_x,
        Some(8)
    );
    assert_eq!(projection.own_units[0].road_connection_path.len(), 4);
    assert_eq!(
        projection.visible_foreign_units[0].promotions,
        Vec::<String>::new()
    );
    assert_eq!(projection.visible_foreign_units[0].promotion_xp, None);
    assert_eq!(projection.visible_foreign_units[0].instance_name, None);
    assert!(
        projection.visible_foreign_units[0]
            .improvement_order
            .is_empty()
    );
    assert!(
        projection.visible_foreign_units[0]
            .road_connection_path
            .is_empty()
    );
    assert_eq!(
        projection.visible_foreign_units[0].movement_destination_x,
        None
    );
    assert!(!projection.visible_foreign_units[0].automated);
    assert!(!projection.visible_foreign_units[0].exploring);
    assert_eq!(projection.visible_foreign_units[0].posture, None);
    assert!(
        projection.visible_foreign_units[0]
            .move_destinations
            .is_empty()
    );
    assert!(
        projection.visible_foreign_units[0]
            .swap_destinations
            .is_empty()
    );
    assert!(
        projection.visible_foreign_units[0]
            .attack_targets
            .is_empty()
    );
    assert!(
        projection.visible_foreign_units[0]
            .nuclear_target_candidates
            .is_empty()
    );
    assert!(
        projection.visible_foreign_units[0]
            .air_sweep_targets
            .is_empty()
    );
    assert_eq!(serde_json::to_value(projection).unwrap(), expected);

    let mut unknown = expected;
    unknown["canonicalGameInfo"] = serde_json::json!({"secret": true});
    assert!(serde_json::from_value::<PlayerProjection>(unknown).is_err());

    let unknown_action = serde_json::from_str::<serde_json::Value>(fixture)
        .unwrap()
        .to_string()
        .replace("pick_policy", "replace_canonical_state");
    assert!(serde_json::from_str::<PlayerProjection>(&unknown_action).is_err());
}

#[test]
fn terminal_victory_is_bounded_and_disables_turn_actions() {
    let fixture = include_str!("../../protocol/player-projection-v60.fixture.json");
    let mut projection: PlayerProjection = serde_json::from_str(fixture).unwrap();
    projection.is_current_turn = false;
    projection.pending_turn_actions.clear();
    projection.victory = Some(ProjectedVictory {
        winning_civilization_id: "Rome".to_owned(),
        victory_type: "Domination".to_owned(),
        victory_turn: projection.turn,
    });
    assert!(projection.victory_is_consistent());

    projection.is_current_turn = true;
    assert!(!projection.victory_is_consistent());
    projection.is_current_turn = false;
    projection.victory.as_mut().unwrap().victory_type = String::new();
    assert!(!projection.victory_is_consistent());
}

#[test]
fn inconsistent_research_queue_metadata_fails_semantic_validation() {
    let fixture = include_str!("../../protocol/player-projection-v60.fixture.json");
    let mut projection: PlayerProjection = serde_json::from_str(fixture).unwrap();
    projection.research.queue_entries[0].technology_name = "Writing".into();
    assert!(!projection.research.is_consistent());
    projection.research.queue_entries[0].technology_name = "Pottery".into();
    projection.research.queue_entries[0].cost = -1;
    assert!(!projection.research.is_consistent());
    projection.research.queue_entries[0].cost = 35;
    projection.research.researched_technologies.reverse();
    assert!(!projection.research.is_consistent());
}

#[test]
fn tile_metadata_rejects_hidden_mutations_and_incoherent_resources() {
    let fixture = include_str!("../../protocol/player-projection-v60.fixture.json");
    let projection: PlayerProjection = serde_json::from_str(fixture).unwrap();

    let mut hidden_improvement = projection.clone();
    hidden_improvement.explored_tiles[2].improvement_name = Some("Secret Mine".into());
    hidden_improvement.explored_tiles[2].improvement_pillaged = Some(false);
    assert!(!hidden_improvement.tiles_are_consistent());

    let mut resource_amount_without_identity = projection.clone();
    resource_amount_without_identity.explored_tiles[1].resource_amount = Some(4);
    assert!(!resource_amount_without_identity.tiles_are_consistent());

    let mut reordered_features = projection;
    reordered_features.explored_tiles[0].terrain_features = vec!["Marsh".into(), "Forest".into()];
    assert!(!reordered_features.tiles_are_consistent());
}

#[test]
fn movement_metadata_rejects_hidden_unsorted_foreign_and_out_of_turn_options() {
    let fixture = include_str!("../../protocol/player-projection-v60.fixture.json");
    let projection: PlayerProjection = serde_json::from_str(fixture).unwrap();

    let mut hidden = projection.clone();
    hidden.own_units[0].move_destinations[0] = ProjectedMovementDestination { x: 5, y: -1 };
    assert!(!hidden.movement_is_consistent());

    let mut unseen_swap = projection.clone();
    unseen_swap.own_units[0].swap_destinations[0] = ProjectedMovementDestination { x: 9, y: 9 };
    assert!(!unseen_swap.movement_is_consistent());

    let mut duplicate = projection.clone();
    duplicate.own_units[0]
        .move_destinations
        .push(ProjectedMovementDestination { x: 2, y: -1 });
    assert!(!duplicate.movement_is_consistent());

    let mut foreign = projection.clone();
    foreign.visible_foreign_units[0]
        .swap_destinations
        .push(ProjectedMovementDestination { x: 2, y: -1 });
    assert!(!foreign.movement_is_consistent());

    let mut out_of_turn = projection;
    out_of_turn.is_current_turn = false;
    assert!(!out_of_turn.movement_is_consistent());
}
#[test]
fn city_state_quest_and_influence_fields_default_for_older_projections() {
    // Simulates a worker that predates the quest/influence projection: the new
    // fields are absent, so the control plane must fail closed to defaults
    // rather than rejecting the payload.
    let legacy_partner = serde_json::json!({
        "civilizationId": "Geneva",
        "availableGoldGifts": [250],
        "canPledgeProtection": true,
        "canRevokeProtection": false,
        "tributeGoldAmount": 75,
        "canDemandWorker": false,
        "improvementGifts": [],
        "canNegotiatePeace": false,
        "canDeclareWar": true,
        "diplomaticMarriageCost": 500,
    });
    let partner: ProjectedCityStatePartner = serde_json::from_value(legacy_partner).unwrap();
    assert_eq!(partner.influence, 0);
    assert_eq!(
        partner.influence_level,
        ProjectedCityStateInfluenceLevel::Neutral
    );
    assert!(partner.quests.is_empty());
}

#[test]
fn city_state_quest_round_trips_with_camel_case_and_snake_case_enum() {
    let quest = ProjectedCityStateQuest {
        quest_name: "Clear Barbarian Camp".into(),
        data1: Some("3".into()),
        data2: Some("-4".into()),
        influence: 50,
        remaining_turns: Some(12),
        is_global: true,
    };
    let partner = ProjectedCityStatePartner {
        civilization_id: "Geneva".into(),
        available_gold_gifts: vec![250],
        can_pledge_protection: true,
        can_revoke_protection: false,
        tribute_gold_amount: None,
        can_demand_worker: false,
        improvement_gifts: Vec::new(),
        can_negotiate_peace: false,
        can_declare_war: true,
        diplomatic_marriage_cost: None,
        influence: 37,
        influence_level: ProjectedCityStateInfluenceLevel::Friend,
        quests: vec![quest],
    };
    let encoded = serde_json::to_value(&partner).unwrap();
    assert_eq!(encoded["influence"], 37);
    assert_eq!(encoded["influenceLevel"], "friend");
    assert_eq!(encoded["quests"][0]["questName"], "Clear Barbarian Camp");
    assert_eq!(encoded["quests"][0]["data1"], "3");
    assert_eq!(encoded["quests"][0]["data2"], "-4");
    assert_eq!(encoded["quests"][0]["influence"], 50);
    assert_eq!(encoded["quests"][0]["remainingTurns"], 12);
    assert_eq!(encoded["quests"][0]["isGlobal"], true);
    let decoded: ProjectedCityStatePartner = serde_json::from_value(encoded).unwrap();
    assert_eq!(decoded.influence, 37);
    assert_eq!(
        decoded.influence_level,
        ProjectedCityStateInfluenceLevel::Friend
    );
    assert_eq!(decoded.quests.len(), 1);
    assert_eq!(decoded.quests[0].quest_name, "Clear Barbarian Camp");
}
