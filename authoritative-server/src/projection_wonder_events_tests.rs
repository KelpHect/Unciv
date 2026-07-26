use crate::projection::PlayerProjection;

fn fixture() -> PlayerProjection {
    serde_json::from_str(include_str!(
        "../../protocol/player-projection-v53.fixture.json"
    ))
    .unwrap()
}

#[test]
fn wonder_event_fixture_is_bounded_sorted_and_visibility_coherent() {
    let projection = fixture();
    assert!(projection.wonder_events_are_consistent());
    assert!(projection.wonder_events[0].city_id.is_some());
    assert!(
        projection.wonder_events[1]
            .builder_civilization_id
            .is_none()
    );
    assert!(projection.wonder_events[1].city_id.is_none());
}

#[test]
fn wonder_event_rejects_partial_location_disclosure() {
    let mut projection = fixture();
    projection.wonder_events[1].city_id = Some("hidden-city".to_owned());
    assert!(!projection.wonder_events_are_consistent());
}

#[test]
fn wonder_event_rejects_future_or_reordered_events() {
    let mut future = fixture();
    future.wonder_events[0].completion_turn = future.turn + 1;
    assert!(!future.wonder_events_are_consistent());

    let mut reordered = fixture();
    reordered.wonder_events.reverse();
    assert!(!reordered.wonder_events_are_consistent());
}
