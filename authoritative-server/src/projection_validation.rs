use crate::ConstructionQueueAction;
use crate::projection::{
    PlayerProjection, ProjectedCombatPreview, ProjectedConstructionPurchase,
    ProjectedMovementDestination, ProjectedTargetCoordinate,
};

const MAX_PROJECTED_CHOICES: usize = 10_000;
const MAX_UNIT_ACTION_CHOICES: usize = 256;

fn coordinates_are_sorted(coordinates: &[ProjectedTargetCoordinate]) -> bool {
    coordinates.len() <= MAX_PROJECTED_CHOICES
        && coordinates
            .windows(2)
            .all(|pair| (pair[0].x, pair[0].y) < (pair[1].x, pair[1].y))
}

fn purchases_are_consistent(purchases: &[ProjectedConstructionPurchase]) -> bool {
    purchases.len() <= 32
        && purchases
            .windows(2)
            .all(|pair| pair[0].currency < pair[1].currency)
        && purchases.iter().all(|purchase| {
            !purchase.currency.is_empty()
                && purchase.currency.chars().count() <= 64
                && purchase.cost >= 0
                && purchase.available_amount >= 0
                && coordinates_are_sorted(&purchase.legal_targets)
                && (purchase.requires_tile || purchase.legal_targets.is_empty())
                && (!purchase.allowed
                    || !purchase.requires_tile
                    || !purchase.legal_targets.is_empty())
        })
}

fn combat_modifiers_are_valid(modifiers: &[crate::projection::ProjectedCombatModifier]) -> bool {
    modifiers.len() <= 64
        && modifiers
            .iter()
            .all(|modifier| !modifier.label.is_empty() && modifier.label.chars().count() <= 200)
        && modifiers.windows(2).all(|pair| pair[0] < pair[1])
}

impl ProjectedCombatPreview {
    fn is_consistent(&self) -> bool {
        let health_is_valid =
            |health: i32, maximum: i32| maximum > 0 && (0..=maximum).contains(&health);
        let remaining = [
            self.attacker_min_remaining_health,
            self.attacker_max_remaining_health,
            self.defender_min_remaining_health,
            self.defender_max_remaining_health,
        ];
        let remaining_is_valid = match self.outcome {
            Some(_) => remaining.iter().all(Option::is_none),
            None => {
                remaining.iter().all(Option::is_some)
                    && (0..=self.attacker_health)
                        .contains(&self.attacker_min_remaining_health.unwrap())
                    && (self.attacker_min_remaining_health.unwrap()..=self.attacker_health)
                        .contains(&self.attacker_max_remaining_health.unwrap())
                    && (0..=self.defender_health)
                        .contains(&self.defender_min_remaining_health.unwrap())
                    && (self.defender_min_remaining_health.unwrap()..=self.defender_health)
                        .contains(&self.defender_max_remaining_health.unwrap())
            }
        };
        self.attacker_base_strength >= 0
            && self.defender_base_strength >= 0
            && self.attacker_effective_strength >= 1
            && self.defender_effective_strength >= 1
            && health_is_valid(self.attacker_health, self.attacker_max_health)
            && health_is_valid(self.defender_health, self.defender_max_health)
            && combat_modifiers_are_valid(&self.attacker_modifiers)
            && combat_modifiers_are_valid(&self.defender_modifiers)
            && remaining_is_valid
    }
}

impl PlayerProjection {
    pub fn victory_is_consistent(&self) -> bool {
        self.victory.as_ref().is_none_or(|victory| {
            !self.is_current_turn
                && self.pending_turn_actions.is_empty()
                && !victory.winning_civilization_id.is_empty()
                && victory.winning_civilization_id.chars().count() <= 128
                && !victory.victory_type.is_empty()
                && victory.victory_type.chars().count() <= 128
                && victory.victory_turn >= 0
                && victory.victory_turn <= self.turn
        })
    }

    pub fn tiles_are_consistent(&self) -> bool {
        self.explored_tiles.len() <= 1_000_000
            && self
                .explored_tiles
                .windows(2)
                .all(|pair| (pair[0].x, pair[0].y) < (pair[1].x, pair[1].y))
            && self.explored_tiles.iter().all(|tile| {
                !tile.base_terrain.is_empty()
                    && tile.base_terrain.chars().count() <= 128
                    && tile.terrain_features.len() <= 16
                    && tile
                        .terrain_features
                        .iter()
                        .all(|feature| !feature.is_empty() && feature.chars().count() <= 128)
                    && tile
                        .terrain_features
                        .windows(2)
                        .all(|pair| pair[0] < pair[1])
                    && tile
                        .natural_wonder_name
                        .as_ref()
                        .is_none_or(|name| !name.is_empty() && name.chars().count() <= 128)
                    && tile
                        .resource_name
                        .as_ref()
                        .is_none_or(|name| !name.is_empty() && name.chars().count() <= 128)
                    && (tile.resource_name.is_some() == tile.resource_amount.is_some())
                    && tile.resource_amount.is_none_or(|amount| amount >= 0)
                    && (tile.improvement_name.is_some() == tile.improvement_pillaged.is_some())
                    && (tile.road_status.is_some() == tile.road_pillaged.is_some())
                    && (tile.visible
                        || (tile.improvement_name.is_none()
                            && tile.improvement_pillaged.is_none()
                            && tile.road_status.is_none()
                            && tile.road_pillaged.is_none()))
            })
    }

    /// Fails closed on a worker that discloses rival cities incorrectly.
    ///
    /// A "foreign" city that is really the player's own, or that repeats an
    /// entry of `own_cities`, means the fog gate was bypassed somewhere - so the
    /// whole projection is rejected rather than served.
    pub fn foreign_cities_are_consistent(&self) -> bool {
        let bounded_name = |value: &str| !value.is_empty() && value.chars().count() <= 128;
        self.visible_foreign_cities.len() <= MAX_PROJECTED_CHOICES
            && self.visible_foreign_cities.windows(2).all(|pair| {
                (&pair[0].civilization_id, &pair[0].id) < (&pair[1].civilization_id, &pair[1].id)
            })
            && self.visible_foreign_cities.iter().all(|city| {
                bounded_name(&city.id)
                    && bounded_name(&city.name)
                    && bounded_name(&city.civilization_id)
                    // A foreign city owned by the viewer is a fog-gate bug.
                    && city.civilization_id != self.civilization_id
                    && !self.own_cities.iter().any(|own| own.id == city.id)
                    && coordinates_are_sorted(&city.owned_tiles)
                    // The centre tile is owned by definition, so an empty border
                    // list would mean the worker clipped away a tile it just
                    // disclosed the city on.
                    && city
                        .owned_tiles
                        .iter()
                        .any(|tile| tile.x == city.x && tile.y == city.y)
            })
    }

    pub fn unit_actions_are_consistent(&self) -> bool {
        let valid_project_name = |name: &Option<String>| {
            name.as_ref()
                .is_none_or(|value| !value.is_empty() && value.chars().count() <= 128)
        };
        let valid_instant_actions =
            |actions: &[crate::projection::ProjectedInstantImprovementAction]| {
                actions.len() <= MAX_UNIT_ACTION_CHOICES
                    && actions.iter().enumerate().all(|(index, action)| {
                        action.action_id.len() == 64
                            && action
                                .action_id
                                .bytes()
                                .all(|byte| byte.is_ascii_hexdigit())
                            && !action.title.is_empty()
                            && action.title.chars().count() <= 256
                            && actions[..index]
                                .iter()
                                .all(|prior| prior.action_id != action.action_id)
                    })
            };
        let valid_direct_controls = |unit: &crate::projection::ProjectedUnit| {
            let valid_destination_list =
                |destinations: &[crate::projection::ProjectedMovementDestination],
                 visible_only: bool| {
                    destinations.len() <= MAX_PROJECTED_CHOICES
                        && destinations
                            .windows(2)
                            .all(|pair| (pair[0].x, pair[0].y) < (pair[1].x, pair[1].y))
                        && destinations.iter().all(|destination| {
                            self.explored_tiles.iter().any(|tile| {
                                (!visible_only || tile.visible)
                                    && tile.x == destination.x
                                    && tile.y == destination.y
                            })
                        })
                };
            unit.available_postures.len() <= 6
                && unit
                    .available_postures
                    .windows(2)
                    .all(|pair| pair[0] < pair[1])
                && valid_destination_list(&unit.paradrop_destinations, true)
                && valid_destination_list(&unit.move_toward_destinations, false)
                && valid_destination_list(&unit.available_road_destinations, false)
                && unit.available_upgrade_targets.len() <= 32
                && unit
                    .available_upgrade_targets
                    .windows(2)
                    .all(|pair| pair[0].target_unit_name < pair[1].target_unit_name)
                && unit.available_upgrade_targets.iter().all(|target| {
                    !target.target_unit_name.is_empty()
                        && target.target_unit_name.chars().count() <= 200
                        && target.gold_cost >= 0
                })
                && unit.available_improvement_orders.len() <= MAX_UNIT_ACTION_CHOICES
                && unit.available_improvement_orders.windows(2).all(|pair| {
                    (&pair[0].improvement_name, &pair[0].queued_improvement_name)
                        < (&pair[1].improvement_name, &pair[1].queued_improvement_name)
                })
                && unit.available_improvement_orders.iter().all(|choice| {
                    let valid_name = |name: &Option<String>| {
                        name.as_ref()
                            .is_none_or(|value| !value.is_empty() && value.chars().count() <= 200)
                    };
                    valid_name(&choice.improvement_name)
                        && valid_name(&choice.queued_improvement_name)
                        && (choice.improvement_name.is_some()
                            || choice.queued_improvement_name.is_none())
                })
        };
        self.own_units.iter().all(|unit| {
            unit.current_movement.is_some()
                && valid_project_name(&unit.capital_project_name)
                && valid_instant_actions(&unit.available_instant_improvement_actions)
                && valid_direct_controls(unit)
        }) && self.visible_foreign_units.iter().all(|unit| {
            unit.current_movement.is_none()
                && !unit.can_gift
                && !unit.can_disband
                && !unit.can_pillage
                && !unit.can_found_city
                && !unit.can_rename
                && unit.capital_project_name.is_none()
                && unit.available_postures.is_empty()
                && unit.paradrop_destinations.is_empty()
                && unit.available_upgrade_targets.is_empty()
                && unit.move_toward_destinations.is_empty()
                && unit.available_improvement_orders.is_empty()
                && unit.available_road_destinations.is_empty()
                && unit.available_instant_improvement_actions.is_empty()
                && unit.available_religious_actions.is_empty()
                && unit.available_great_person_actions.is_empty()
                && unit.available_transform_actions.is_empty()
                && unit.available_trigger_actions.is_empty()
        }) && (self.is_current_turn
            || self.own_units.iter().all(|unit| {
                !unit.can_gift
                    && !unit.can_disband
                    && !unit.can_pillage
                    && !unit.can_found_city
                    && !unit.can_rename
                    && unit.capital_project_name.is_none()
                    && unit.available_postures.is_empty()
                    && unit.paradrop_destinations.is_empty()
                    && unit.available_upgrade_targets.is_empty()
                    && unit.move_toward_destinations.is_empty()
                    && unit.available_improvement_orders.is_empty()
                    && unit.available_road_destinations.is_empty()
                    && unit.available_instant_improvement_actions.is_empty()
                    && unit.available_religious_actions.is_empty()
                    && unit.available_great_person_actions.is_empty()
                    && unit.available_transform_actions.is_empty()
                    && unit.available_trigger_actions.is_empty()
            }))
    }

    pub fn turn_readiness_is_consistent(&self) -> bool {
        self.pending_turn_actions.len() <= 9
            && self
                .pending_turn_actions
                .windows(2)
                .all(|pair| pair[0] < pair[1])
            && self.pending_turn_actions.iter().all(|action| match action {
                crate::projection::PendingEndTurnAction::PickConstruction => {
                    self.own_cities.iter().any(|city| {
                        !city.is_puppet
                            && city.construction_queue.is_empty()
                            && city
                                .construction_options
                                .iter()
                                .any(|option| option.queueable)
                    })
                }
                crate::projection::PendingEndTurnAction::PickTechnology => {
                    !self.research.selectable_targets.is_empty()
                        || !self.research.free_technology_choices.is_empty()
                }
                crate::projection::PendingEndTurnAction::PickPolicy => {
                    !self.policies.selectable_policies.is_empty()
                }
                crate::projection::PendingEndTurnAction::MoveSpies => false,
                crate::projection::PendingEndTurnAction::FoundOrExpandPantheon
                | crate::projection::PendingEndTurnAction::FoundReligion
                | crate::projection::PendingEndTurnAction::EnhanceReligion
                | crate::projection::PendingEndTurnAction::ReformReligion => {
                    self.religion_choice.as_ref().is_some_and(|choice| {
                        !choice.required_belief_types.is_empty()
                            && !choice.available_beliefs.is_empty()
                    })
                }
                crate::projection::PendingEndTurnAction::CastDiplomaticVote => true,
                crate::projection::PendingEndTurnAction::PickGreatPerson => {
                    !self.selectable_great_people.is_empty()
                }
            })
    }

    pub fn diplomacy_is_consistent(&self) -> bool {
        self.diplomacy_partners.len() <= MAX_PROJECTED_CHOICES
            && self
                .diplomacy_partners
                .windows(2)
                .all(|pair| pair[0].civilization_id < pair[1].civilization_id)
            && self.diplomacy_partners.iter().all(|partner| {
                !partner.civilization_id.is_empty()
                    && partner.civilization_id.chars().count() <= 128
                    && partner.adopted_policy_branches.len() <= MAX_PROJECTED_CHOICES
                    && partner
                        .adopted_policy_branches
                        .iter()
                        .all(|name| !name.is_empty() && name.chars().count() <= 128)
                    && partner
                        .adopted_policy_branches
                        .windows(2)
                        .all(|pair| pair[0] < pair[1])
            })
    }

    pub fn wonder_events_are_consistent(&self) -> bool {
        self.wonder_events.len() <= MAX_PROJECTED_CHOICES
            && self.wonder_events.windows(2).all(|pair| {
                (pair[0].completion_turn, pair[0].wonder_name.as_str())
                    < (pair[1].completion_turn, pair[1].wonder_name.as_str())
            })
            && self
                .wonder_events
                .iter()
                .all(|event| event.is_consistent(self.turn))
    }

    pub fn city_economy_is_consistent(&self) -> bool {
        let own_city_ids = self
            .own_cities
            .iter()
            .map(|city| city.id.as_str())
            .collect::<std::collections::HashSet<_>>();
        self.own_cities.iter().all(|city| {
            let coordinate_is_in_city_view = |coordinate: &ProjectedTargetCoordinate| {
                city.tile_states
                    .iter()
                    .any(|tile| tile.x == coordinate.x && tile.y == coordinate.y)
            };
            let queue_matches = city.construction_queue.len()
                == city.construction_queue_entries.len()
                && city
                    .construction_queue
                    .iter()
                    .zip(&city.construction_queue_entries)
                    .enumerate()
                    .all(|(index, (name, entry))| {
                        name == &entry.name
                            && entry.stored_production >= 0
                            && entry.production_cost.is_none_or(|cost| cost >= 0)
                            && entry.estimated_turns.is_none_or(|turns| turns >= 0)
                            && entry.production_cost.is_some() == entry.estimated_turns.is_some()
                            && purchases_are_consistent(&entry.purchases)
                            && entry.purchases.iter().all(|purchase| {
                                purchase
                                    .legal_targets
                                    .iter()
                                    .all(coordinate_is_in_city_view)
                            })
                            && entry
                                .available_actions
                                .windows(2)
                                .all(|pair| pair[0] < pair[1])
                            && entry.available_actions.iter().all(|action| match action {
                                ConstructionQueueAction::MoveToTop => index > 0,
                                ConstructionQueueAction::MoveToEnd => {
                                    index + 1 < city.construction_queue_entries.len()
                                }
                                ConstructionQueueAction::RemoveFromAllCities => true,
                                ConstructionQueueAction::AddToAllCities
                                | ConstructionQueueAction::AddOrMoveToTopAllCities => true,
                                _ => false,
                            })
                    });
            let options_sorted = city.construction_options.len() <= MAX_PROJECTED_CHOICES
                && city
                    .construction_options
                    .windows(2)
                    .all(|pair| pair[0].name < pair[1].name);
            let options_valid = city.construction_options.iter().all(|option| {
                let kind_is_consistent = match option.kind {
                    crate::projection_city_economy::ProjectedConstructionKind::Ordinary => true,
                    crate::projection_city_economy::ProjectedConstructionKind::Perpetual => {
                        option.stored_production == 0
                            && option.production_cost.is_none()
                            && option.estimated_turns.is_none()
                            && option.placement_targets.is_empty()
                            && option.purchases.is_empty()
                    }
                };
                !option.name.is_empty()
                    && option.name.chars().count() <= 128
                    && kind_is_consistent
                    && option.stored_production >= 0
                    && option.production_cost.is_none_or(|cost| cost >= 0)
                    && option.estimated_turns.is_none_or(|turns| turns >= 0)
                    && option.production_cost.is_some() == option.estimated_turns.is_some()
                    && coordinates_are_sorted(&option.placement_targets)
                    && purchases_are_consistent(&option.purchases)
                    && option
                        .placement_targets
                        .iter()
                        .all(coordinate_is_in_city_view)
                    && option.purchases.iter().all(|purchase| {
                        purchase
                            .legal_targets
                            .iter()
                            .all(coordinate_is_in_city_view)
                    })
                    && option
                        .available_actions
                        .windows(2)
                        .all(|pair| pair[0] < pair[1])
                    && option.available_actions.iter().all(|action| {
                        matches!(
                            action,
                            ConstructionQueueAction::AddToTop
                                | ConstructionQueueAction::AddToAllCities
                                | ConstructionQueueAction::AddOrMoveToTopAllCities
                        )
                    })
            });
            let queueable_names = city
                .construction_options
                .iter()
                .filter(|option| option.queueable)
                .map(|option| option.name.as_str())
                .collect::<Vec<_>>();
            let advertised_names = city
                .available_constructions
                .iter()
                .map(String::as_str)
                .collect::<Vec<_>>();
            let tile_states_valid = city.tile_states.len() <= MAX_PROJECTED_CHOICES
                && city
                    .tile_states
                    .windows(2)
                    .all(|pair| (pair[0].x, pair[0].y) < (pair[1].x, pair[1].y))
                && city.tile_states.iter().all(|tile| {
                    self.explored_tiles
                        .iter()
                        .any(|known| known.x == tile.x && known.y == tile.y)
                        && (tile.owned_by_actor == tile.owning_city_id.is_some())
                        && (tile.owned_by_actor
                            || (tile.owning_city_id.is_none()
                                && tile.working_city_id.is_none()
                                && !tile.worked
                                && !tile.locked))
                        && tile
                            .owning_city_id
                            .as_deref()
                            .is_none_or(|id| own_city_ids.contains(id))
                        && tile
                            .working_city_id
                            .as_deref()
                            .is_none_or(|id| own_city_ids.contains(id))
                        && (!tile.worked || tile.working_city_id.is_some())
                        && (!tile.locked || tile.worked)
                });
            let tile_purchases_valid = city.tile_purchases.len() <= MAX_PROJECTED_CHOICES
                && city
                    .tile_purchases
                    .windows(2)
                    .all(|pair| (pair[0].x, pair[0].y) < (pair[1].x, pair[1].y))
                && city.tile_purchases.iter().all(|purchase| {
                    purchase.gold_cost >= 0
                        && city.tile_states.iter().any(|tile| {
                            tile.x == purchase.x && tile.y == purchase.y && !tile.owned_by_actor
                        })
                });
            let tile_batch_purchases_valid = city.tile_batch_purchases.len() <= 32
                && city
                    .tile_batch_purchases
                    .windows(2)
                    .all(|pair| pair[0].ring < pair[1].ring)
                && city.tile_batch_purchases.iter().all(|purchase| {
                    (1..=32).contains(&purchase.ring)
                        && purchase.tile_count >= 2
                        && purchase.tile_count as usize <= MAX_PROJECTED_CHOICES
                        && purchase.gold_cost >= 0
                });
            queue_matches
                && options_sorted
                && options_valid
                && queueable_names == advertised_names
                && tile_states_valid
                && tile_purchases_valid
                && tile_batch_purchases_valid
                && city.sellable_buildings.len() <= MAX_PROJECTED_CHOICES
                && city
                    .sellable_buildings
                    .iter()
                    .all(|name| !name.is_empty() && name.chars().count() <= 128)
                && city
                    .sellable_buildings
                    .windows(2)
                    .all(|pair| pair[0] < pair[1])
                && (self.is_current_turn || city.sellable_buildings.is_empty())
                && (!city.is_puppet || city.sellable_buildings.is_empty())
                && city.available_governance_actions.len() <= 3
                && city
                    .available_governance_actions
                    .iter()
                    .enumerate()
                    .all(|(index, action)| {
                        !city.available_governance_actions[index + 1..].contains(action)
                    })
                && (self.is_current_turn || city.available_governance_actions.is_empty())
        })
    }

    pub fn movement_is_consistent(&self) -> bool {
        if self.visible_foreign_units.iter().any(|unit| {
            !unit.move_destinations.is_empty()
                || !unit.swap_destinations.is_empty()
                || unit.movement_escort_unit_id.is_some()
        }) {
            return false;
        }
        if !self.is_current_turn
            && self.own_units.iter().any(|unit| {
                !unit.move_destinations.is_empty() || !unit.swap_destinations.is_empty()
            })
        {
            return false;
        }
        self.own_units.iter().all(|unit| {
            let escort_is_consistent = unit.movement_escort_unit_id.is_none_or(|escort_id| {
                escort_id != unit.id
                    && unit.movement_destination_x.is_some()
                    && unit.movement_destination_y.is_some()
                    && self.own_units.iter().any(|escort| {
                        escort.id == escort_id && escort.x == unit.x && escort.y == unit.y
                    })
                    && self
                        .own_units
                        .iter()
                        .filter(|other| other.movement_escort_unit_id == Some(escort_id))
                        .count()
                        == 1
            });
            let sorted_and_not_current = |destinations: &[ProjectedMovementDestination]| {
                destinations.windows(2).all(|pair| pair[0] < pair[1])
                    && destinations
                        .iter()
                        .all(|destination| destination.x != unit.x || destination.y != unit.y)
            };
            escort_is_consistent
                && sorted_and_not_current(&unit.move_destinations)
                && unit.move_destinations.iter().all(|destination| {
                    !self.explored_tiles.iter().any(|tile| {
                        tile.x == destination.x && tile.y == destination.y && !tile.visible
                    })
                })
                && sorted_and_not_current(&unit.swap_destinations)
                && unit.swap_destinations.iter().all(|destination| {
                    self.explored_tiles.iter().any(|tile| {
                        tile.x == destination.x && tile.y == destination.y && tile.visible
                    })
                })
        })
    }

    pub fn combat_is_consistent(&self) -> bool {
        let coordinate_is_visible = |coordinate: &ProjectedTargetCoordinate| {
            self.explored_tiles
                .iter()
                .any(|tile| tile.x == coordinate.x && tile.y == coordinate.y && tile.visible)
        };
        let coordinate_pairs_are_sorted = |coordinates: &[(i32, i32)]| {
            coordinates.len() <= 10_000 && coordinates.windows(2).all(|pair| pair[0] < pair[1])
        };
        if self.visible_foreign_units.iter().any(|unit| {
            !unit.attack_targets.is_empty()
                || !unit.nuclear_target_candidates.is_empty()
                || !unit.air_sweep_targets.is_empty()
        }) {
            return false;
        }
        if !self.is_current_turn
            && (self.own_units.iter().any(|unit| {
                !unit.attack_targets.is_empty()
                    || !unit.nuclear_target_candidates.is_empty()
                    || !unit.air_sweep_targets.is_empty()
            }) || self
                .own_cities
                .iter()
                .any(|city| !city.bombard_targets.is_empty()))
        {
            return false;
        }
        self.own_cities.iter().all(|city| {
            coordinate_pairs_are_sorted(
                &city
                    .bombard_targets
                    .iter()
                    .map(|target| (target.x, target.y))
                    .collect::<Vec<_>>(),
            ) && city.bombard_targets.iter().all(|target| {
                coordinate_is_visible(&ProjectedTargetCoordinate {
                    x: target.x,
                    y: target.y,
                }) && target.preview.is_consistent()
            })
        }) && self.own_units.iter().all(|unit| {
            unit.attack_targets.len() <= 10_000
                && coordinate_pairs_are_sorted(
                    &unit
                        .attack_targets
                        .iter()
                        .map(|target| (target.x, target.y))
                        .collect::<Vec<_>>(),
                )
                && unit.attack_targets.iter().all(|target| {
                    coordinate_is_visible(&ProjectedTargetCoordinate {
                        x: target.x,
                        y: target.y,
                    }) && coordinate_is_visible(&ProjectedTargetCoordinate {
                        x: target.attack_from_x,
                        y: target.attack_from_y,
                    }) && target.preview.is_consistent()
                })
                && coordinate_pairs_are_sorted(
                    &unit
                        .nuclear_target_candidates
                        .iter()
                        .map(|target| (target.x, target.y))
                        .collect::<Vec<_>>(),
                )
                && unit.nuclear_target_candidates.iter().all(|target| {
                    (target.x != unit.x || target.y != unit.y)
                        && (0..=1_000).contains(&target.blast_radius)
                        && self
                            .explored_tiles
                            .iter()
                            .any(|tile| tile.x == target.x && tile.y == target.y)
                })
                && coordinate_pairs_are_sorted(
                    &unit
                        .air_sweep_targets
                        .iter()
                        .map(|target| (target.x, target.y))
                        .collect::<Vec<_>>(),
                )
                && unit.air_sweep_targets.iter().all(|target| {
                    (target.x != unit.x || target.y != unit.y)
                        && target.attacker_base_strength >= 0
                        && target.attacker_max_health > 0
                        && (0..=target.attacker_max_health).contains(&target.attacker_health)
                        && combat_modifiers_are_valid(&target.attacker_modifiers)
                })
        })
    }
}
