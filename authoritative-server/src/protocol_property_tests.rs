use std::collections::HashMap;

use proptest::prelude::*;
use proptest::test_runner::RngSeed;
use serde_json::{Value, json};
use uuid::Uuid;

use crate::{
    CommandAccepted, CommandEnvelope, CommitError, CommitProposal, GameCommand,
    InMemoryGameRepository, PROTOCOL_VERSION, projection::PlayerProjection, state_hash,
};

fn command_strategy() -> impl Strategy<Value = GameCommand> {
    let name = "[A-Za-z0-9 _-]{0,32}";
    prop_oneof![
        Just(GameCommand::JoinGame {
            civilization_id: "Rome".to_owned(),
        }),
        Just(GameCommand::EndTurn {}),
        Just(GameCommand::Resign {}),
        (any::<i32>(), any::<i32>(), any::<i32>()).prop_map(
            |(unit_id, destination_x, destination_y)| GameCommand::MoveUnit {
                unit_id,
                destination_x,
                destination_y,
                escort_unit_id: None,
            }
        ),
        (name, any::<i32>(), any::<i32>()).prop_map(|(city_id, target_x, target_y)| {
            GameCommand::BombardWithCity {
                city_id,
                target_x,
                target_y,
            }
        }),
        (name, name).prop_map(|(city_id, construction_name)| {
            GameCommand::QueueConstruction {
                city_id,
                construction_name,
            }
        }),
        (name, any::<bool>())
            .prop_map(|(city_id, enabled)| GameCommand::SetAvoidGrowth { city_id, enabled }),
        name.prop_map(|technology_name| GameCommand::SetResearchPath {
            technology_name,
            append: false,
        }),
    ]
}

fn uuid_strategy() -> impl Strategy<Value = Uuid> {
    any::<[u8; 16]>().prop_map(Uuid::from_bytes)
}

proptest! {
    #![proptest_config(ProptestConfig {
        cases: 256,
        rng_seed: RngSeed::Fixed(0x554E_4349_5650_3301),
        ..ProptestConfig::default()
    })]

    #[test]
    fn command_envelopes_round_trip_and_reject_unknown_fields(
        game_id in uuid_strategy(),
        command_id in uuid_strategy(),
        expected_revision in any::<u64>(),
        observed_hash in prop::option::of("[0-9a-f]{64}"),
        command in command_strategy(),
        unknown_suffix in "[a-z]{1,16}",
    ) {
        let envelope = CommandEnvelope {
            protocol_version: PROTOCOL_VERSION,
            game_id,
            command_id,
            expected_revision,
            client_observed_state_hash: observed_hash,
            command,
        };
        let encoded = serde_json::to_value(&envelope).unwrap();
        let decoded: CommandEnvelope = serde_json::from_value(encoded.clone()).unwrap();
        prop_assert_eq!(&decoded, &envelope);

        let mut unknown = encoded;
        unknown
            .as_object_mut()
            .unwrap()
            .insert(format!("unknown_{unknown_suffix}"), Value::Null);
        prop_assert!(serde_json::from_value::<CommandEnvelope>(unknown).is_err());
    }

    #[test]
    fn unknown_command_variants_and_fields_are_always_rejected(
        unknown_type in "[a-z]{1,32}",
        unknown_field in "[a-z]{1,32}",
        value in any::<i64>(),
    ) {
        let unknown_variant = json!({"type": format!("unknown_{unknown_type}")});
        prop_assert!(serde_json::from_value::<GameCommand>(unknown_variant).is_err());

        let unknown_payload = json!({
            "type": "end_turn",
            format!("unknown_{unknown_field}"): value,
        });
        prop_assert!(serde_json::from_value::<GameCommand>(unknown_payload).is_err());
    }

    #[test]
    fn player_projection_serialization_is_closed_and_stable(
        turn in any::<i32>(),
        gold in any::<i32>(),
        unknown_suffix in "[a-z]{1,16}",
    ) {
        let mut fixture: Value = serde_json::from_str(include_str!(
            "../../protocol/player-projection-v60.fixture.json"
        ))
        .unwrap();
        fixture["turn"] = json!(turn);
        fixture["gold"] = json!(gold);
        let projection: PlayerProjection = serde_json::from_value(fixture).unwrap();
        let encoded = serde_json::to_value(&projection).unwrap();
        let decoded: PlayerProjection = serde_json::from_value(encoded.clone()).unwrap();
        prop_assert_eq!(
            serde_json::to_value(decoded).unwrap(),
            encoded.clone()
        );

        let mut unknown = encoded;
        unknown
            .as_object_mut()
            .unwrap()
            .insert(format!("unknown{unknown_suffix}"), Value::Null);
        prop_assert!(serde_json::from_value::<PlayerProjection>(unknown).is_err());
    }

    #[test]
    fn revision_and_idempotency_transitions_match_the_reference_model(
        operations in prop::collection::vec((0_u8..8, any::<bool>()), 1..64),
    ) {
        let runtime = tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .unwrap();
        runtime.block_on(async {
            let repository = InMemoryGameRepository::default();
            let game_id = Uuid::from_u128(1);
            repository.create_game(game_id, b"genesis".to_vec()).await.unwrap();
            let command_ids = (0_u128..8)
                .map(|index| Uuid::from_u128(index + 10))
                .collect::<Vec<_>>();
            let mut model_revision = 0_u64;
            let mut committed: HashMap<Uuid, (u64, CommandAccepted)> = HashMap::new();

            for (step, (slot, use_current_revision)) in operations.iter().enumerate() {
                let command_id = command_ids[*slot as usize];
                let expected_revision = if *use_current_revision {
                    model_revision
                } else {
                    model_revision.saturating_add(1)
                };
                let snapshot = format!("snapshot-{step}-{slot}").into_bytes();
                let result = repository
                    .commit(
                        CommandEnvelope {
                            protocol_version: PROTOCOL_VERSION,
                            game_id,
                            command_id,
                            expected_revision,
                            client_observed_state_hash: None,
                            command: GameCommand::EndTurn {},
                        },
                        CommitProposal {
                            previous_revision: expected_revision,
                            canonical_state_hash: state_hash(&snapshot),
                            snapshot,
                            server_time_millis: step as i64,
                            replay_operation: json!({"type": "end_turn"}),
                        },
                    )
                    .await;

                if let Some((original_expected_revision, original)) = committed.get(&command_id) {
                    if expected_revision == *original_expected_revision {
                        prop_assert_eq!(result.unwrap(), original.clone());
                    } else {
                        prop_assert_eq!(result, Err(CommitError::IdempotencyConflict));
                    }
                } else if *use_current_revision {
                    let accepted = result.unwrap();
                    prop_assert_eq!(accepted.previous_revision, model_revision);
                    prop_assert_eq!(accepted.committed_revision, model_revision + 1);
                    model_revision += 1;
                    committed.insert(command_id, (expected_revision, accepted));
                } else {
                    prop_assert_eq!(
                        result,
                        Err(CommitError::Stale {
                            expected: expected_revision,
                            actual: model_revision,
                        })
                    );
                }
            }
            Ok(())
        })?;
    }
}
