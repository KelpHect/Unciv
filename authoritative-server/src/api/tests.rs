use super::*;

#[test]
fn generated_openapi_matches_checked_in_contract() {
    let generated = format!(
        "{}\n",
        serde_json::to_string_pretty(&ApiDoc::openapi()).unwrap()
    );
    assert_eq!(generated, include_str!("../../openapi/api-v3.json"));
}

#[test]
fn generated_asyncapi_matches_checked_in_contract_and_runtime_shapes() {
    let document = asyncapi_document_value();
    let generated = format!("{}\n", serde_json::to_string_pretty(&document).unwrap());
    assert_eq!(
        generated,
        include_str!("../../openapi/notifications-v3.json")
    );
    assert_eq!(document["asyncapi"], "3.1.0");
    assert_eq!(
        document["channels"]["revisionHints"]["address"],
        "/api/v3/notifications"
    );
    assert_eq!(
        document["channels"]["revisionHints"]["bindings"]["ws"]["method"],
        "GET"
    );
    assert_eq!(
        document["components"]["securitySchemes"]["opaqueBearerSession"]["scheme"],
        "bearer"
    );
    for schema in ["revisionCommittedPayload", "resyncRequiredPayload"] {
        assert_eq!(
            document["components"]["schemas"][schema]["additionalProperties"],
            false
        );
    }

    let revision = unciv_authoritative_server::notifications::RevisionNotification {
        event_type: "revision_committed",
        protocol_version: PROTOCOL_VERSION,
        game_id: uuid::Uuid::new_v4(),
        committed_revision: 7,
        canonical_state_hash: "ab".repeat(32),
    };
    let revision_value = serde_json::to_value(revision).unwrap();
    assert_message_matches_closed_schema(
        &revision_value,
        &document["components"]["schemas"]["revisionCommittedPayload"],
    );
    let resync = serde_json::to_value(
        unciv_authoritative_server::notifications::ResyncRequiredNotification::default(),
    )
    .unwrap();
    assert_message_matches_closed_schema(
        &resync,
        &document["components"]["schemas"]["resyncRequiredPayload"],
    );

    let serialized = serde_json::to_string(&document).unwrap();
    for forbidden in [
        "snapshot",
        "GameInfo",
        "worker_request",
        "worker_response",
        "rng_state",
        "account_id",
    ] {
        assert!(
            !serialized.contains(forbidden),
            "public AsyncAPI exposes private term {forbidden}"
        );
    }
}

fn assert_message_matches_closed_schema(message: &serde_json::Value, schema: &serde_json::Value) {
    let object = message.as_object().unwrap();
    let properties = schema["properties"].as_object().unwrap();
    let required = schema["required"]
        .as_array()
        .unwrap()
        .iter()
        .map(|value| value.as_str().unwrap())
        .collect::<std::collections::BTreeSet<_>>();
    assert_eq!(
        object
            .keys()
            .map(String::as_str)
            .collect::<std::collections::BTreeSet<_>>(),
        required
    );
    assert!(object.keys().all(|key| properties.contains_key(key)));
    for (key, property) in properties {
        if let Some(expected) = property.get("const") {
            assert_eq!(&object[key], expected, "{key}");
        }
    }
}

#[tokio::test]
async fn capabilities_advertise_every_public_gameplay_command_route() {
    let document = serde_json::to_value(ApiDoc::openapi()).unwrap();
    let mut expected = document["paths"]
        .as_object()
        .unwrap()
        .keys()
        .filter_map(|path| path.split("/commands/").nth(1))
        .map(|command| command.replace('-', "_"))
        .collect::<std::collections::BTreeSet<_>>();
    expected.insert("join_game".to_owned());
    let advertised = capabilities()
        .await
        .0
        .commands
        .into_iter()
        .map(str::to_owned)
        .collect::<std::collections::BTreeSet<_>>();
    assert_eq!(advertised, expected);
}

#[test]
fn openapi_covers_routes_security_and_closed_command_shapes() {
    let document = serde_json::to_value(ApiDoc::openapi()).unwrap();
    let paths = document["paths"].as_object().unwrap();
    let expected_paths = [
        "/healthz",
        "/api/v3/capabilities",
        "/api/v3/openapi.json",
        "/api/v3/asyncapi.json",
        "/api/v3/notifications",
        "/api/v3/auth/register",
        "/api/v3/auth/login",
        "/api/v3/auth/refresh",
        "/api/v3/auth/logout",
        "/api/v3/account/password",
        "/api/v3/account/disable",
        "/api/v3/account",
        "/api/v3/ruleset-manifests",
        "/api/v3/games",
        "/api/v3/games/{game_id}",
        "/api/v3/games/{game_id}/owner",
        "/api/v3/games/{game_id}/player-invitations",
        "/api/v3/player-invitations",
        "/api/v3/games/{game_id}/close",
        "/api/v3/games/{game_id}/archive",
        "/api/v3/games/{game_id}/projection",
        "/api/v3/games/{game_id}/projection/delta",
        "/api/v3/games/{game_id}/spectator-projection",
        "/api/v3/games/{game_id}/spectators",
        "/api/v3/games/{game_id}/spectator-revocations",
        "/api/v3/games/{game_id}/join",
        "/api/v3/games/{game_id}/commands/end-turn",
        "/api/v3/games/{game_id}/commands/resign",
        "/api/v3/games/{game_id}/commands/force-resign",
        "/api/v3/games/{game_id}/commands/kick-member",
        "/api/v3/games/{game_id}/commands/move-unit",
        "/api/v3/games/{game_id}/commands/move-unit-toward",
        "/api/v3/games/{game_id}/commands/cancel-unit-movement-order",
        "/api/v3/games/{game_id}/commands/set-unit-exploration",
        "/api/v3/games/{game_id}/commands/set-unit-automation",
        "/api/v3/games/{game_id}/commands/set-unit-posture",
        "/api/v3/games/{game_id}/commands/disband-unit",
        "/api/v3/games/{game_id}/commands/pillage-tile",
        "/api/v3/games/{game_id}/commands/found-city",
        "/api/v3/games/{game_id}/commands/paradrop-unit",
        "/api/v3/games/{game_id}/commands/attack-with-unit",
        "/api/v3/games/{game_id}/commands/bombard-with-city",
        "/api/v3/games/{game_id}/commands/launch-nuclear-strike",
        "/api/v3/games/{game_id}/commands/air-sweep",
        "/api/v3/games/{game_id}/commands/upgrade-units",
        "/api/v3/games/{game_id}/commands/promote-unit",
        "/api/v3/games/{game_id}/commands/set-city-unit-promotion-preference",
        "/api/v3/games/{game_id}/commands/rename-unit",
        "/api/v3/games/{game_id}/commands/set-tile-improvement-order",
        "/api/v3/games/{game_id}/commands/set-road-connection-order",
        "/api/v3/games/{game_id}/commands/swap-units",
        "/api/v3/games/{game_id}/commands/queue-construction",
        "/api/v3/games/{game_id}/commands/queue-construction-at-tile",
        "/api/v3/games/{game_id}/commands/set-perpetual-construction",
        "/api/v3/games/{game_id}/commands/remove-construction",
        "/api/v3/games/{game_id}/commands/move-construction",
        "/api/v3/games/{game_id}/commands/manage-construction-queues",
        "/api/v3/games/{game_id}/commands/purchase-construction",
        "/api/v3/games/{game_id}/commands/purchase-construction-at-tile",
        "/api/v3/games/{game_id}/commands/buy-city-tile",
        "/api/v3/games/{game_id}/commands/buy-city-tile-batch",
        "/api/v3/games/{game_id}/commands/sell-building",
        "/api/v3/games/{game_id}/commands/set-city-governance",
        "/api/v3/games/{game_id}/commands/resolve-city-disposition",
        "/api/v3/games/{game_id}/commands/cast-diplomatic-vote",
        "/api/v3/games/{game_id}/commands/choose-great-person",
        "/api/v3/games/{game_id}/commands/use-great-person-unit",
        "/api/v3/games/{game_id}/commands/gift-unit",
        "/api/v3/games/{game_id}/commands/add-unit-to-capital-project",
        "/api/v3/games/{game_id}/commands/transform-unit",
        "/api/v3/games/{game_id}/commands/trigger-unit-unique",
        "/api/v3/games/{game_id}/commands/create-instant-improvement",
        "/api/v3/games/{game_id}/commands/use-religious-unit",
        "/api/v3/games/{game_id}/commands/choose-religious-beliefs",
        "/api/v3/games/{game_id}/commands/offer-trade",
        "/api/v3/games/{game_id}/commands/retract-trade-offer",
        "/api/v3/games/{game_id}/commands/accept-trade",
        "/api/v3/games/{game_id}/commands/decline-trade",
        "/api/v3/games/{game_id}/commands/counter-trade",
        "/api/v3/games/{game_id}/commands/declare-war",
        "/api/v3/games/{game_id}/commands/denounce-civilization",
        "/api/v3/games/{game_id}/commands/offer-friendship",
        "/api/v3/games/{game_id}/commands/make-diplomatic-demand",
        "/api/v3/games/{game_id}/commands/respond-to-diplomatic-prompt",
        "/api/v3/games/{game_id}/commands/respond-to-city-state-protection-prompt",
        "/api/v3/games/{game_id}/commands/gift-city-state-gold",
        "/api/v3/games/{game_id}/commands/set-city-state-protection",
        "/api/v3/games/{game_id}/commands/demand-city-state-tribute",
        "/api/v3/games/{game_id}/commands/gift-city-state-improvement",
        "/api/v3/games/{game_id}/commands/negotiate-city-state-peace",
        "/api/v3/games/{game_id}/commands/marry-city-state",
        "/api/v3/games/{game_id}/commands/move-spy",
        "/api/v3/games/{game_id}/commands/set-spy-coup",
        "/api/v3/games/{game_id}/commands/resolve-event-choice",
        "/api/v3/games/{game_id}/commands/set-city-tile-assignment",
        "/api/v3/games/{game_id}/commands/set-specialist-count",
        "/api/v3/games/{game_id}/commands/set-manual-specialists",
        "/api/v3/games/{game_id}/commands/reset-citizens",
        "/api/v3/games/{game_id}/commands/set-avoid-growth",
        "/api/v3/games/{game_id}/commands/set-citizen-focus",
        "/api/v3/games/{game_id}/commands/set-research-path",
        "/api/v3/games/{game_id}/commands/manage-research-queue",
        "/api/v3/games/{game_id}/commands/adopt-policy",
        "/api/v3/games/{game_id}/commands/choose-free-technology",
        "/api/v3/games/{game_id}/commands/acknowledge-research-completion",
    ];
    assert_eq!(paths.len(), expected_paths.len());
    for path in expected_paths {
        assert!(paths.contains_key(path), "missing OpenAPI path {path}");
    }
    assert_eq!(
        document["components"]["securitySchemes"]["bearer_auth"]["scheme"],
        "bearer"
    );
    for (path, methods) in paths {
        for operation in methods.as_object().unwrap().values() {
            let public = matches!(
                path.as_str(),
                "/healthz"
                    | "/api/v3/capabilities"
                    | "/api/v3/openapi.json"
                    | "/api/v3/asyncapi.json"
                    | "/api/v3/auth/register"
                    | "/api/v3/auth/login"
            );
            assert_eq!(
                operation.get("security").is_some(),
                !public,
                "incorrect security declaration for {path}"
            );
        }
    }
    for schema in [
        "TransferOwnershipRequest",
        "InvitePlayerRequest",
        "GameAdminOperationRequest",
        "AddSpectatorRequest",
        "EndTurnRequest",
        "ResignRequest",
        "ForceResignRequest",
        "KickMemberRequest",
        "JoinGameRequest",
        "MoveUnitRequest",
        "MoveUnitTowardRequest",
        "CancelUnitMovementOrderRequest",
        "SetUnitExplorationRequest",
        "SetUnitAutomationRequest",
        "SetUnitPostureRequest",
        "DisbandUnitRequest",
        "PillageTileRequest",
        "FoundCityRequest",
        "ParadropUnitRequest",
        "AttackWithUnitRequest",
        "BombardWithCityRequest",
        "LaunchNuclearStrikeRequest",
        "AirSweepRequest",
        "UpgradeUnitsRequest",
        "PromoteUnitRequest",
        "SetCityUnitPromotionPreferenceRequest",
        "RenameUnitRequest",
        "SetTileImprovementOrderRequest",
        "SetRoadConnectionOrderRequest",
        "SwapUnitsRequest",
        "QueueConstructionRequest",
        "QueueConstructionAtTileRequest",
        "SetPerpetualConstructionRequest",
        "RemoveConstructionRequest",
        "MoveConstructionRequest",
        "ManageConstructionQueuesRequest",
        "PurchaseConstructionRequest",
        "PurchaseConstructionAtTileRequest",
        "BuyCityTileRequest",
        "SellBuildingRequest",
        "SetCityGovernanceRequest",
        "ResolveCityDispositionRequest",
        "CastDiplomaticVoteRequest",
        "ChooseGreatPersonRequest",
        "UseGreatPersonUnitRequest",
        "GiftUnitRequest",
        "AddUnitToCapitalProjectRequest",
        "TransformUnitRequest",
        "TriggerUnitUniqueRequest",
        "CreateInstantImprovementRequest",
        "UseReligiousUnitRequest",
        "ChooseReligiousBeliefsRequest",
        "OfferTradeRequest",
        "RetractTradeOfferRequest",
        "TradeRequestDecisionRequest",
        "CounterTradeRequest",
        "DiplomacyPartnerRequest",
        "DiplomaticDemandRequest",
        "DiplomaticPromptResponseRequest",
        "CityStateProtectionPromptResponseRequest",
        "CityStateGoldGiftRequest",
        "CityStateProtectionRequest",
        "CityStateTributeRequest",
        "CityStateImprovementGiftRequest",
        "CityStatePeaceRequest",
        "CityStateMarriageRequest",
        "MoveSpyRequest",
        "SetSpyCoupRequest",
        "ResolveEventChoiceRequest",
        "SetCityTileAssignmentRequest",
        "SetSpecialistCountRequest",
        "SetManualSpecialistsRequest",
        "ResetCitizensRequest",
        "SetAvoidGrowthRequest",
        "SetCitizenFocusRequest",
    ] {
        assert_eq!(
            document["components"]["schemas"][schema]["additionalProperties"], false,
            "{schema} must remain a closed request object"
        );
    }
    assert_eq!(
        document["components"]["schemas"]["ConstructionQueueAction"]["enum"],
        serde_json::json!([
            "move_to_top",
            "move_to_end",
            "add_to_top",
            "add_to_all_cities",
            "add_or_move_to_top_all_cities",
            "remove_from_all_cities"
        ]),
        "ConstructionQueueAction must remain a closed enum"
    );
    assert!(
        document["components"]["schemas"]
            .as_object()
            .unwrap()
            .contains_key("PlayerInvitation")
    );
    assert!(
        document["components"]["schemas"]["GameProjection"]["properties"]["projection"]["$ref"]
            .as_str()
            .unwrap()
            .ends_with("/PlayerProjection")
    );
    let serialized = serde_json::to_string(&document).unwrap();
    assert!(!serialized.contains("GameInfo"));
    assert!(!serialized.contains("snapshot"));
}

#[test]
fn stale_errors_expose_the_canonical_revision() {
    let error = game_error(CommitError::Stale {
        expected: 3,
        actual: 5,
    });
    assert_eq!(error.status, StatusCode::CONFLICT);
    assert_eq!(error.code, "stale_revision");
    assert_eq!(error.current_revision, Some(5));
}

#[tokio::test]
async fn capabilities_forbid_whole_state_uploads() {
    let response = capabilities().await;
    assert_eq!(response.0.protocol_version, PROTOCOL_VERSION);
    assert_eq!(response.0.projection_version, PROJECTION_VERSION);
    assert!(!response.0.whole_state_upload);
    assert!(response.0.commands.contains(&"move_unit"));
    assert!(response.0.commands.contains(&"queue_construction"));
    assert!(response.0.commands.contains(&"queue_construction_at_tile"));
    assert!(response.0.commands.contains(&"set_perpetual_construction"));
    assert!(response.0.commands.contains(&"remove_construction"));
    assert!(response.0.commands.contains(&"move_construction"));
    assert!(response.0.commands.contains(&"manage_construction_queues"));
    assert!(response.0.commands.contains(&"purchase_construction"));
    assert!(
        response
            .0
            .commands
            .contains(&"purchase_construction_at_tile")
    );
    assert!(response.0.commands.contains(&"buy_city_tile"));
    assert!(response.0.commands.contains(&"buy_city_tile_batch"));
    assert!(response.0.commands.contains(&"disband_unit"));
    assert!(response.0.commands.contains(&"pillage_tile"));
    assert!(response.0.commands.contains(&"found_city"));
    assert!(response.0.commands.contains(&"upgrade_units"));
    assert!(response.0.commands.contains(&"promote_unit"));
    assert!(
        response
            .0
            .commands
            .contains(&"set_city_unit_promotion_preference")
    );
    assert!(response.0.commands.contains(&"rename_unit"));
    assert!(response.0.commands.contains(&"set_tile_improvement_order"));
    assert!(response.0.commands.contains(&"set_road_connection_order"));
    assert!(response.0.commands.contains(&"set_research_path"));
    assert!(response.0.commands.contains(&"manage_research_queue"));
    assert!(response.0.commands.contains(&"adopt_policy"));
    assert!(response.0.commands.contains(&"choose_free_technology"));
    assert!(
        response
            .0
            .commands
            .contains(&"acknowledge_research_completion")
    );
}

#[test]
fn rate_limit_response_and_network_prefixes_are_stable() {
    let response = ApiError::rate_limited(900).into_response();
    assert_eq!(response.status(), StatusCode::TOO_MANY_REQUESTS);
    assert_eq!(response.headers()[header::RETRY_AFTER], "900");
    assert_eq!(source_prefix("192.0.2.99".parse().unwrap()), "192.0.2.0/24");
    assert_eq!(
        source_prefix("2001:db8:abcd:1234:ffff::1".parse().unwrap()),
        "2001:db8:abcd:1234::/64"
    );
}

#[test]
fn corrupt_games_fail_closed_with_stable_unavailable_semantics() {
    let error = game_error(CommitError::GameUnavailable);
    assert_eq!(error.status, StatusCode::SERVICE_UNAVAILABLE);
    assert_eq!(error.code, "game_unavailable");
    assert_eq!(error.current_revision, None);
}

#[test]
fn game_discovery_page_limits_are_bounded_and_stable() {
    assert_eq!(game_page_limit(None).unwrap(), 50);
    assert_eq!(game_page_limit(Some(100)).unwrap(), 100);
    for invalid in [0, 101, u32::MAX] {
        let error = game_page_limit(Some(invalid)).unwrap_err();
        assert_eq!(error.status, StatusCode::BAD_REQUEST);
        assert_eq!(error.code, "invalid_page_limit");
    }
    assert_eq!(game_page_cursor(None).unwrap(), None);
    assert!(game_page_cursor(Some("00000000-0000-0000-0000-000000000001")).is_ok());
    let error = game_page_cursor(Some("not-a-uuid")).unwrap_err();
    assert_eq!(error.status, StatusCode::BAD_REQUEST);
    assert_eq!(error.code, "invalid_page_cursor");
}

#[test]
fn public_openapi_contains_no_canonical_or_worker_private_fields() {
    let document = serde_json::to_value(ApiDoc::openapi()).unwrap();
    let mut property_names = std::collections::BTreeSet::new();
    collect_openapi_property_names(&document, &mut property_names);
    for forbidden in [
        "snapshot",
        "canonical_snapshot",
        "canonical_game_info",
        "canonicalGameInfo",
        "game_info",
        "gameInfo",
        "replay_operation",
        "server_time_millis",
        "worker_request",
        "worker_response",
        "rng_state",
        "random_seed",
    ] {
        assert!(
            !property_names.contains(forbidden),
            "public OpenAPI exposes private field {forbidden}"
        );
    }
}

#[tokio::test]
async fn worker_rejection_details_never_cross_http_errors() {
    let sentinel = "PRIVATE_CANONICAL_SNAPSHOT_hidden-unit_secret-diplomacy";
    let response = game_error(CommitError::WorkerRejected(sentinel.to_owned())).into_response();
    let status = response.status();
    let body = axum::body::to_bytes(response.into_body(), 1024)
        .await
        .unwrap();
    let value: serde_json::Value = serde_json::from_slice(&body).unwrap();

    assert_eq!(status, StatusCode::UNPROCESSABLE_ENTITY);
    assert_eq!(value, serde_json::json!({"code": "invalid_command"}));
    assert!(!String::from_utf8_lossy(&body).contains(sentinel));
}

fn collect_openapi_property_names(
    value: &serde_json::Value,
    names: &mut std::collections::BTreeSet<String>,
) {
    match value {
        serde_json::Value::Object(object) => {
            if let Some(properties) = object.get("properties").and_then(|value| value.as_object()) {
                names.extend(properties.keys().cloned());
            }
            for nested in object.values() {
                collect_openapi_property_names(nested, names);
            }
        }
        serde_json::Value::Array(array) => {
            for nested in array {
                collect_openapi_property_names(nested, names);
            }
        }
        _ => {}
    }
}
