use crate::projection::{
    PlayerProjection, ProjectedCombatPreview, ProjectedMovementDestination,
    ProjectedTargetCoordinate,
};

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
