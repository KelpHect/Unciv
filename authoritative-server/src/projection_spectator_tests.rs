use crate::projection::ProjectedVictory;
use crate::projection::{SpectatorCivilization, SpectatorProjection};

#[test]
fn spectator_projection_is_a_closed_public_summary() {
    let projection = SpectatorProjection {
        protocol_version: 3,
        turn: 7,
        current_player_civilization_id: "Rome".to_owned(),
        active_player_civilization_ids: vec!["Rome".to_owned()],
        victory: None,
        major_civilizations: vec![SpectatorCivilization {
            civilization_id: "Rome".to_owned(),
            display_name: "Rome".to_owned(),
            human_controlled: true,
            defeated: false,
        }],
    };
    let value = serde_json::to_value(&projection).unwrap();
    let encoded = value.to_string();
    for forbidden in ["gold", "unit", "city", "tile", "research", "notification"] {
        assert!(!encoded.to_lowercase().contains(forbidden));
    }
    let mut malicious = value;
    malicious["canonicalGameInfo"] = serde_json::json!({"secret": true});
    assert!(serde_json::from_value::<SpectatorProjection>(malicious).is_err());

    let mut terminal = projection;
    terminal.victory = Some(ProjectedVictory {
        winning_civilization_id: "Rome".to_owned(),
        victory_type: "Domination".to_owned(),
        victory_turn: 7,
    });
    assert!(terminal.victory_is_consistent());
    terminal.victory.as_mut().unwrap().winning_civilization_id = "Hidden".to_owned();
    assert!(!terminal.victory_is_consistent());
}
