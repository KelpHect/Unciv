use serde::{Deserialize, Serialize};
use utoipa::ToSchema;

/// The widest custom world the public setup contract admits is 220 x 220.
const MAX_BOUNDING_BOX_SIDE: u32 = 256;
const MAX_TERRAIN_NAMES: usize = 512;
const MAX_START_POSITIONS: usize = 16;

/// Pregame terrain of a lobby's committed map.
///
/// This is the only pregame map disclosure and it is deliberately not a
/// gameplay projection: no units, cities, resources, improvements, natural
/// wonders, tile ownership, civilization identities, or turn state. Start
/// positions are unlabeled, so which civilization holds which start stays
/// private until the match begins.
#[derive(Clone, Debug, PartialEq, Deserialize, Serialize, ToSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct LobbyTerrainProjection {
    pub world_wrap: bool,
    pub min_x: i32,
    pub min_y: i32,
    pub width: u32,
    pub height: u32,
    /// Sorted, deduplicated base terrain names that `tiles` indexes into.
    pub terrain_names: Vec<String>,
    /// Row-major over the bounding box; `-1` marks a coordinate off the map.
    pub tiles: Vec<i32>,
    /// Flat `x, y` pairs.
    pub start_positions: Vec<i32>,
}

impl LobbyTerrainProjection {
    /// Fail-closed shape check mirroring the Kotlin worker's own assertion.
    /// A worker that returns anything malformed is treated as a protocol fault.
    pub fn is_consistent(&self) -> bool {
        self.dimensions_are_bounded()
            && self.palette_is_canonical()
            && self.tiles_are_bounded()
            && self.start_positions_are_on_the_map()
    }

    fn dimensions_are_bounded(&self) -> bool {
        (1..=MAX_BOUNDING_BOX_SIDE).contains(&self.width)
            && (1..=MAX_BOUNDING_BOX_SIDE).contains(&self.height)
    }

    fn palette_is_canonical(&self) -> bool {
        let mut sorted = self.terrain_names.clone();
        sorted.sort();
        sorted.dedup();
        !self.terrain_names.is_empty()
            && self.terrain_names.len() <= MAX_TERRAIN_NAMES
            && self
                .terrain_names
                .iter()
                .all(|name| !name.trim().is_empty() && name.len() <= 128)
            && sorted == self.terrain_names
    }

    fn tiles_are_bounded(&self) -> bool {
        let expected = usize::try_from(self.width)
            .ok()
            .zip(usize::try_from(self.height).ok())
            .and_then(|(width, height)| width.checked_mul(height));
        let palette = i32::try_from(self.terrain_names.len()).unwrap_or(i32::MAX);
        expected == Some(self.tiles.len())
            && self.tiles.iter().all(|tile| (-1..palette).contains(tile))
            && self.tiles.iter().any(|tile| *tile >= 0)
    }

    fn start_positions_are_on_the_map(&self) -> bool {
        self.start_positions.len().is_multiple_of(2)
            && self.start_positions.len() <= 2 * MAX_START_POSITIONS
            && self
                .start_positions
                .chunks_exact(2)
                .all(|pair| self.terrain_index_at(pair[0], pair[1]).is_some())
    }

    fn terrain_index_at(&self, x: i32, y: i32) -> Option<i32> {
        let column = usize::try_from(x.checked_sub(self.min_x)?).ok()?;
        let row = usize::try_from(y.checked_sub(self.min_y)?).ok()?;
        let width = usize::try_from(self.width).ok()?;
        if column >= width || row >= usize::try_from(self.height).ok()? {
            return None;
        }
        self.tiles
            .get(row * width + column)
            .copied()
            .filter(|t| *t >= 0)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn valid() -> LobbyTerrainProjection {
        LobbyTerrainProjection {
            world_wrap: false,
            min_x: -1,
            min_y: -1,
            width: 2,
            height: 2,
            terrain_names: vec!["Grassland".to_string(), "Ocean".to_string()],
            tiles: vec![0, 1, 1, 0],
            start_positions: vec![-1, -1],
        }
    }

    #[test]
    fn a_well_formed_projection_is_consistent() {
        assert!(valid().is_consistent());
    }

    #[test]
    fn an_unsorted_or_duplicated_palette_is_rejected() {
        let mut unsorted = valid();
        unsorted.terrain_names = vec!["Ocean".to_string(), "Grassland".to_string()];
        assert!(!unsorted.is_consistent());

        let mut duplicated = valid();
        duplicated.terrain_names = vec!["Grassland".to_string(), "Grassland".to_string()];
        assert!(!duplicated.is_consistent());
    }

    #[test]
    fn tile_length_and_palette_indices_are_enforced() {
        let mut short = valid();
        short.tiles = vec![0, 1, 1];
        assert!(!short.is_consistent());

        let mut out_of_range = valid();
        out_of_range.tiles = vec![0, 1, 2, 0];
        assert!(!out_of_range.is_consistent());

        let mut all_off_map = valid();
        all_off_map.tiles = vec![-1, -1, -1, -1];
        assert!(!all_off_map.is_consistent());
    }

    #[test]
    fn start_positions_must_be_paired_and_land_on_the_map() {
        let mut odd = valid();
        odd.start_positions = vec![0];
        assert!(!odd.is_consistent());

        let mut off_map = valid();
        off_map.start_positions = vec![99, 99];
        assert!(!off_map.is_consistent());

        let mut off_map_hole = valid();
        off_map_hole.tiles = vec![-1, 1, 1, 0];
        off_map_hole.start_positions = vec![-1, -1];
        assert!(!off_map_hole.is_consistent());

        let mut too_many = valid();
        too_many.start_positions = vec![-1; 2 * (MAX_START_POSITIONS + 1)];
        assert!(!too_many.is_consistent());
    }

    #[test]
    fn oversized_dimensions_are_rejected() {
        let mut wide = valid();
        wide.width = MAX_BOUNDING_BOX_SIDE + 1;
        assert!(!wide.is_consistent());
    }
}
