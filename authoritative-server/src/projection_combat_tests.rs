use crate::projection::{
    PlayerProjection, ProjectedCombatOutcome, ProjectedNuclearEffectDisclosure,
    ProjectedNuclearTarget,
};

#[test]
fn combat_metadata_rejects_hidden_malformed_foreign_and_out_of_turn_values() {
    let fixture = include_str!("../../protocol/player-projection-v46.fixture.json");
    let projection: PlayerProjection = serde_json::from_str(fixture).unwrap();

    let mut hidden_attack = projection.clone();
    hidden_attack.own_units[0].attack_targets[0].x = 5;
    assert!(!hidden_attack.combat_is_consistent());

    let mut hidden_bombard = projection.clone();
    hidden_bombard.own_cities[0].bombard_targets[0].x = 5;
    assert!(!hidden_bombard.combat_is_consistent());

    let mut invalid_preview = projection.clone();
    invalid_preview.own_units[0].attack_targets[0]
        .preview
        .attacker_effective_strength = 0;
    assert!(!invalid_preview.combat_is_consistent());

    let mut oversized_label = projection.clone();
    oversized_label.own_units[0].attack_targets[0]
        .preview
        .attacker_modifiers[0]
        .label = "x".repeat(201);
    assert!(!oversized_label.combat_is_consistent());

    let mut leaked_outcome_damage = projection.clone();
    leaked_outcome_damage.own_units[0].attack_targets[0]
        .preview
        .outcome = Some(ProjectedCombatOutcome::Captured);
    assert!(!leaked_outcome_damage.combat_is_consistent());

    let mut duplicate = projection.clone();
    duplicate.own_units[0]
        .nuclear_target_candidates
        .push(ProjectedNuclearTarget {
            x: 2,
            y: -1,
            blast_radius: 2,
            effect_disclosure: ProjectedNuclearEffectDisclosure::HiddenUntilCommit,
        });
    assert!(!duplicate.combat_is_consistent());

    let mut foreign = projection.clone();
    foreign.visible_foreign_units[0]
        .air_sweep_targets
        .push(projection.own_units[0].air_sweep_targets[0].clone());
    assert!(!foreign.combat_is_consistent());

    let mut leaked_air_sweep = projection.clone();
    leaked_air_sweep.own_units[0].air_sweep_targets[0].attacker_health = 101;
    assert!(!leaked_air_sweep.combat_is_consistent());

    let mut out_of_turn = projection;
    out_of_turn.is_current_turn = false;
    assert!(!out_of_turn.combat_is_consistent());
}
