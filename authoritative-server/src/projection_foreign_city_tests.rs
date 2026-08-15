use crate::projection::*;

fn fixture() -> PlayerProjection {
    let fixture = include_str!("../../protocol/player-projection-v60.fixture.json");
    serde_json::from_str(fixture).unwrap()
}

fn city(id: &str, civilization_id: &str, x: i32, y: i32) -> ProjectedForeignCity {
    ProjectedForeignCity {
        id: id.to_owned(),
        name: "Rival".to_owned(),
        civilization_id: civilization_id.to_owned(),
        x,
        y,
        owned_tiles: vec![ProjectedTargetCoordinate { x, y }],
    }
}

/// A worker predating projection 63 omits the field entirely. That must still
/// parse and simply disclose no rival cities, rather than failing the request.
#[test]
fn a_projection_predating_foreign_cities_still_parses_and_discloses_none() {
    let mut raw: serde_json::Value = serde_json::from_str(include_str!(
        "../../protocol/player-projection-v60.fixture.json"
    ))
    .unwrap();
    raw.as_object_mut().unwrap().remove("visibleForeignCities");

    let projection: PlayerProjection = serde_json::from_value(raw).unwrap();

    assert!(projection.visible_foreign_cities.is_empty());
    assert!(projection.foreign_cities_are_consistent());

    // Re-serializing always emits the key, so Rust and Kotlin agree on the wire
    // shape even when there is nothing to disclose.
    let encoded = serde_json::to_value(&projection).unwrap();
    assert_eq!(
        encoded.get("visibleForeignCities"),
        Some(&serde_json::json!([]))
    );
}

#[test]
fn a_well_formed_visible_rival_city_round_trips() {
    let mut projection = fixture();
    projection.visible_foreign_cities = vec![city("city-a", "Greece", 3, 4)];

    assert!(projection.foreign_cities_are_consistent());
    let encoded = serde_json::to_value(&projection).unwrap();
    let decoded: PlayerProjection = serde_json::from_value(encoded).unwrap();
    assert_eq!(decoded.visible_foreign_cities.len(), 1);
    assert_eq!(decoded.visible_foreign_cities[0].civilization_id, "Greece");
    assert_eq!(decoded.visible_foreign_cities[0].owned_tiles.len(), 1);
}

/// The fog gate lives in the worker, so the Rust boundary fails closed on the
/// shapes that would mean it was bypassed.
#[test]
fn a_foreign_city_owned_by_the_viewer_is_rejected() {
    let mut projection = fixture();
    let own = projection.civilization_id.clone();
    projection.visible_foreign_cities = vec![city("city-a", &own, 3, 4)];

    assert!(!projection.foreign_cities_are_consistent());
}

#[test]
fn a_foreign_city_repeating_an_own_city_is_rejected() {
    let mut projection = fixture();
    let own_id = projection.own_cities[0].id.clone();
    projection.visible_foreign_cities = vec![city(&own_id, "Greece", 3, 4)];

    assert!(!projection.foreign_cities_are_consistent());
}

#[test]
fn a_border_that_omits_the_disclosed_centre_tile_is_rejected() {
    let mut projection = fixture();
    let mut disclosed = city("city-a", "Greece", 3, 4);
    disclosed.owned_tiles = vec![ProjectedTargetCoordinate { x: 9, y: 9 }];
    projection.visible_foreign_cities = vec![disclosed];

    assert!(!projection.foreign_cities_are_consistent());
}

#[test]
fn unsorted_or_unbounded_foreign_cities_are_rejected() {
    let mut unsorted = fixture();
    unsorted.visible_foreign_cities = vec![
        city("city-b", "Greece", 3, 4),
        city("city-a", "Greece", 5, 6),
    ];
    assert!(!unsorted.foreign_cities_are_consistent());

    let mut unsorted_tiles = fixture();
    let mut disclosed = city("city-a", "Greece", 3, 4);
    disclosed.owned_tiles = vec![
        ProjectedTargetCoordinate { x: 3, y: 4 },
        ProjectedTargetCoordinate { x: 1, y: 1 },
    ];
    unsorted_tiles.visible_foreign_cities = vec![disclosed];
    assert!(!unsorted_tiles.foreign_cities_are_consistent());

    let mut empty_name = fixture();
    let mut nameless = city("city-a", "Greece", 3, 4);
    nameless.name = String::new();
    empty_name.visible_foreign_cities = vec![nameless];
    assert!(!empty_name.foreign_cities_are_consistent());
}

/// The type is the boundary: a rival city must not gain interior state without
/// an explicit confidentiality review.
#[test]
fn the_foreign_city_payload_has_one_exact_public_shape() {
    let encoded = serde_json::to_value(city("city-a", "Greece", 3, 4)).unwrap();
    let object = encoded.as_object().unwrap();
    let mut keys: Vec<&str> = object.keys().map(String::as_str).collect();
    keys.sort_unstable();

    assert_eq!(
        keys,
        ["civilizationId", "id", "name", "ownedTiles", "x", "y"]
    );
    for forbidden in [
        "population",
        "health",
        "constructionQueue",
        "specialists",
        "tileStates",
    ] {
        assert!(!object.contains_key(forbidden));
    }
}
