use crate::projection::{
    PlayerProjection, ProjectedMovementDestination, ProjectedTargetCoordinate,
};

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
        let coordinates_are_sorted = |coordinates: &[ProjectedTargetCoordinate]| {
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
            coordinates_are_sorted(&city.bombard_targets)
                && city.bombard_targets.iter().all(&coordinate_is_visible)
        }) && self.own_units.iter().all(|unit| {
            unit.attack_targets.len() <= 10_000
                && unit.attack_targets.windows(2).all(|pair| pair[0] < pair[1])
                && unit.attack_targets.iter().all(|target| {
                    coordinate_is_visible(&ProjectedTargetCoordinate {
                        x: target.x,
                        y: target.y,
                    }) && coordinate_is_visible(&ProjectedTargetCoordinate {
                        x: target.attack_from_x,
                        y: target.attack_from_y,
                    })
                })
                && coordinates_are_sorted(&unit.nuclear_target_candidates)
                && unit.nuclear_target_candidates.iter().all(|target| {
                    (target.x != unit.x || target.y != unit.y)
                        && self
                            .explored_tiles
                            .iter()
                            .any(|tile| tile.x == target.x && tile.y == target.y)
                })
                && coordinates_are_sorted(&unit.air_sweep_targets)
                && unit
                    .air_sweep_targets
                    .iter()
                    .all(|target| target.x != unit.x || target.y != unit.y)
        })
    }
}
