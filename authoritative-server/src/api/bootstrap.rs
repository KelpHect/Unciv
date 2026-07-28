use super::*;

pub(crate) async fn run() {
    if std::env::args().any(|argument| argument == "--write-openapi") {
        let contract_directory = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("openapi");
        std::fs::create_dir_all(&contract_directory)
            .expect("failed to create OpenAPI output directory");
        for (name, document) in [
            (
                "api-v3.json",
                serde_json::to_string_pretty(&ApiDoc::openapi())
                    .expect("OpenAPI document is serializable"),
            ),
            (
                "notifications-v3.json",
                serde_json::to_string_pretty(&asyncapi_document_value())
                    .expect("AsyncAPI document is serializable"),
            ),
        ] {
            let target = contract_directory.join(name);
            std::fs::write(&target, format!("{document}\n"))
                .expect("failed to write generated API contract document");
            eprintln!("wrote {}", target.display());
        }
        return;
    }
    let release_bundle = unciv_authoritative_server::release_bundle::verify_runtime_environment()
        .expect("authoritative API release bundle must be exact and complete");
    let address = std::env::var("UNCIV_V3_BIND")
        .unwrap_or_else(|_| "127.0.0.1:3000".to_owned())
        .parse::<SocketAddr>()
        .expect("UNCIV_V3_BIND must be a socket address");
    let database_url = std::env::var("UNCIV_V3_DATABASE_URL")
        .expect("UNCIV_V3_DATABASE_URL is required for the authoritative API");
    let database_config =
        unciv_authoritative_server::postgres::PostgresRuntimeConfig::from_environment()
            .expect("authoritative PostgreSQL runtime configuration must be valid");
    let repository = PostgresGameRepository::connect_with_config(&database_url, database_config)
        .await
        .expect("failed to connect to UNCIV_V3_DATABASE_URL");
    repository
        .verify_schema_compatibility()
        .await
        .expect("authoritative database schema must exactly match this release; run unciv-v3-migrate with migration credentials");
    let worker_address = std::env::var("UNCIV_ENGINE_WORKER_ADDR")
        .unwrap_or_else(|_| "127.0.0.1:43170".to_owned())
        .parse::<SocketAddr>()
        .expect("UNCIV_ENGINE_WORKER_ADDR must be a socket address");
    let worker_identity = std::env::var("UNCIV_ENGINE_WORKER_SECRET")
        .ok()
        .and_then(|value| unciv_authoritative_server::worker::WorkerIdentityKey::from_hex(&value).ok())
        .expect("UNCIV_ENGINE_WORKER_SECRET must be exactly 32 bytes encoded as 64 hexadecimal characters");
    let worker_deadlines = unciv_authoritative_server::worker::WorkerDeadlines::from_environment()
        .expect("authoritative engine worker deadlines must be valid");
    let worker_circuit_breaker =
        unciv_authoritative_server::worker::WorkerCircuitBreakerConfig::from_environment()
            .expect("authoritative engine worker circuit breaker must be valid");
    let worker_queue = unciv_authoritative_server::worker::WorkerQueueConfig::from_environment()
        .expect("authoritative engine worker queue must be valid");
    let worker = EngineWorkerClient::with_runtime_policy(
        worker_address,
        worker_deadlines,
        worker_circuit_breaker,
        worker_queue,
        worker_identity,
    );
    let worker_capabilities = worker
        .handshake()
        .await
        .expect("authoritative engine worker handshake failed");
    if worker_capabilities.release_bundle_id != release_bundle.bundle_id {
        panic!("authoritative engine worker release bundle identity mismatch");
    }
    let bundled_rulesets = std::iter::once(&release_bundle.ruleset_manifest.base_ruleset)
        .chain(release_bundle.ruleset_manifest.mods.iter());
    if release_bundle.bundle_id != "dev-unpackaged"
        && (worker_capabilities.engine_build != release_bundle.ruleset_manifest.engine_build
            || bundled_rulesets
                .into_iter()
                .any(|expected| !worker_capabilities.installed_rulesets.contains(expected)))
    {
        panic!("authoritative engine worker ruleset catalog does not match the release bundle");
    }
    eprintln!(
        "authoritative engine worker ready: protocol={}, engine_build={}, rulesets={}",
        unciv_authoritative_server::worker::WORKER_PROTOCOL_VERSION,
        worker_capabilities.engine_build,
        worker_capabilities.installed_rulesets.len(),
    );
    let websocket_policy = WebSocketRuntimePolicy::from_environment()
        .expect("authoritative WebSocket runtime policy must be valid");
    let notifications = NotificationHub::with_connection_limits(
        websocket_policy.global_connection_limit,
        websocket_policy.account_connection_limit,
    );
    let outbox_policy =
        unciv_authoritative_server::postgres::OutboxRuntimePolicy::from_environment()
            .expect("authoritative outbox runtime policy must be valid");
    start_notification_runtime(repository.clone(), notifications.clone(), outbox_policy)
        .await
        .expect("authoritative shared notification runtime failed to start");
    let http_security = HttpSecurityConfig::from_environment()
        .expect("authoritative API HTTP security policy must be valid");
    let app = Router::new()
        .route("/healthz", get(health))
        .route("/readyz", get(readiness))
        .route("/api/v3/capabilities", get(capabilities))
        .route("/api/v3/openapi.json", get(openapi_document))
        .route("/api/v3/asyncapi.json", get(asyncapi_document))
        .route("/api/v3/notifications", get(websocket_notifications))
        .route("/api/v3/auth/register", post(register))
        .route("/api/v3/auth/login", post(login))
        .route("/api/v3/auth/refresh", post(refresh_session))
        .route("/api/v3/auth/logout", post(logout))
        .route("/api/v3/account/password", post(change_password))
        .route("/api/v3/account/disable", post(disable_account))
        .route("/api/v3/account", delete(delete_account))
        .route("/api/v3/ruleset-manifests", get(list_ruleset_manifests))
        .route("/api/v3/games", get(list_games).post(create_game))
        .route("/api/v3/games/{game_id}", get(game_metadata))
        .route("/api/v3/games/{game_id}/owner", put(transfer_ownership))
        .route(
            "/api/v3/games/{game_id}/player-invitations",
            put(invite_player),
        )
        .route("/api/v3/player-invitations", get(list_player_invitations))
        .route("/api/v3/games/{game_id}/close", post(close_game_admin))
        .route("/api/v3/games/{game_id}/archive", post(archive_game_admin))
        .route("/api/v3/games/{game_id}/projection", get(game_projection))
        .route(
            "/api/v3/games/{game_id}/projection/delta",
            get(game_projection_delta),
        )
        .route(
            "/api/v3/games/{game_id}/spectator-projection",
            get(spectator_projection),
        )
        .route(
            "/api/v3/games/{game_id}/spectators",
            put(add_spectator).delete(leave_spectator),
        )
        .route(
            "/api/v3/games/{game_id}/spectator-revocations",
            post(revoke_spectator),
        )
        .route("/api/v3/games/{game_id}/join", post(join_game))
        .route("/api/v3/games/{game_id}/commands/end-turn", post(end_turn))
        .route("/api/v3/games/{game_id}/commands/resign", post(resign))
        .route(
            "/api/v3/games/{game_id}/commands/force-resign",
            post(force_resign),
        )
        .route(
            "/api/v3/games/{game_id}/commands/kick-member",
            post(kick_member),
        )
        .route(
            "/api/v3/games/{game_id}/commands/move-unit",
            post(move_unit),
        )
        .route(
            "/api/v3/games/{game_id}/commands/move-unit-toward",
            post(move_unit_toward),
        )
        .route(
            "/api/v3/games/{game_id}/commands/cancel-unit-movement-order",
            post(cancel_unit_movement_order),
        )
        .route(
            "/api/v3/games/{game_id}/commands/set-unit-exploration",
            post(set_unit_exploration),
        )
        .route(
            "/api/v3/games/{game_id}/commands/set-unit-automation",
            post(set_unit_automation),
        )
        .route(
            "/api/v3/games/{game_id}/commands/set-unit-posture",
            post(set_unit_posture),
        )
        .route(
            "/api/v3/games/{game_id}/commands/disband-unit",
            post(disband_unit),
        )
        .route(
            "/api/v3/games/{game_id}/commands/pillage-tile",
            post(pillage_tile),
        )
        .route(
            "/api/v3/games/{game_id}/commands/found-city",
            post(found_city),
        )
        .route(
            "/api/v3/games/{game_id}/commands/paradrop-unit",
            post(paradrop_unit),
        )
        .route(
            "/api/v3/games/{game_id}/commands/attack-with-unit",
            post(attack_with_unit),
        )
        .route(
            "/api/v3/games/{game_id}/commands/bombard-with-city",
            post(bombard_with_city),
        )
        .route(
            "/api/v3/games/{game_id}/commands/launch-nuclear-strike",
            post(launch_nuclear_strike),
        )
        .route(
            "/api/v3/games/{game_id}/commands/air-sweep",
            post(air_sweep),
        )
        .route(
            "/api/v3/games/{game_id}/commands/upgrade-units",
            post(upgrade_units),
        )
        .route(
            "/api/v3/games/{game_id}/commands/promote-unit",
            post(promote_unit),
        )
        .route(
            "/api/v3/games/{game_id}/commands/set-city-unit-promotion-preference",
            post(set_city_unit_promotion_preference),
        )
        .route(
            "/api/v3/games/{game_id}/commands/rename-unit",
            post(rename_unit),
        )
        .route(
            "/api/v3/games/{game_id}/commands/set-tile-improvement-order",
            post(set_tile_improvement_order),
        )
        .route(
            "/api/v3/games/{game_id}/commands/set-road-connection-order",
            post(set_road_connection_order),
        )
        .route(
            "/api/v3/games/{game_id}/commands/swap-units",
            post(swap_units),
        )
        .route(
            "/api/v3/games/{game_id}/commands/queue-construction",
            post(queue_construction),
        )
        .route(
            "/api/v3/games/{game_id}/commands/queue-construction-at-tile",
            post(queue_construction_at_tile),
        )
        .route(
            "/api/v3/games/{game_id}/commands/set-perpetual-construction",
            post(set_perpetual_construction),
        )
        .route(
            "/api/v3/games/{game_id}/commands/remove-construction",
            post(remove_construction),
        )
        .route(
            "/api/v3/games/{game_id}/commands/move-construction",
            post(move_construction),
        )
        .route(
            "/api/v3/games/{game_id}/commands/manage-construction-queues",
            post(manage_construction_queues),
        )
        .route(
            "/api/v3/games/{game_id}/commands/purchase-construction",
            post(purchase_construction),
        )
        .route(
            "/api/v3/games/{game_id}/commands/purchase-construction-at-tile",
            post(purchase_construction_at_tile),
        )
        .route(
            "/api/v3/games/{game_id}/commands/buy-city-tile",
            post(buy_city_tile),
        )
        .route(
            "/api/v3/games/{game_id}/commands/buy-city-tile-batch",
            post(buy_city_tile_batch),
        )
        .route(
            "/api/v3/games/{game_id}/commands/sell-building",
            post(sell_building),
        )
        .route(
            "/api/v3/games/{game_id}/commands/set-city-governance",
            post(set_city_governance),
        )
        .route(
            "/api/v3/games/{game_id}/commands/resolve-city-disposition",
            post(resolve_city_disposition),
        )
        .route(
            "/api/v3/games/{game_id}/commands/cast-diplomatic-vote",
            post(cast_diplomatic_vote),
        )
        .route(
            "/api/v3/games/{game_id}/commands/choose-great-person",
            post(choose_great_person),
        )
        .route(
            "/api/v3/games/{game_id}/commands/use-great-person-unit",
            post(use_great_person_unit),
        )
        .route(
            "/api/v3/games/{game_id}/commands/gift-unit",
            post(gift_unit),
        )
        .route(
            "/api/v3/games/{game_id}/commands/add-unit-to-capital-project",
            post(add_unit_to_capital_project),
        )
        .route(
            "/api/v3/games/{game_id}/commands/transform-unit",
            post(transform_unit),
        )
        .route(
            "/api/v3/games/{game_id}/commands/trigger-unit-unique",
            post(trigger_unit_unique),
        )
        .route(
            "/api/v3/games/{game_id}/commands/create-instant-improvement",
            post(create_instant_improvement),
        )
        .route(
            "/api/v3/games/{game_id}/commands/use-religious-unit",
            post(use_religious_unit),
        )
        .route(
            "/api/v3/games/{game_id}/commands/choose-religious-beliefs",
            post(choose_religious_beliefs),
        )
        .route(
            "/api/v3/games/{game_id}/commands/offer-trade",
            post(offer_trade),
        )
        .route(
            "/api/v3/games/{game_id}/commands/retract-trade-offer",
            post(retract_trade_offer),
        )
        .route(
            "/api/v3/games/{game_id}/commands/accept-trade",
            post(accept_trade),
        )
        .route(
            "/api/v3/games/{game_id}/commands/decline-trade",
            post(decline_trade),
        )
        .route(
            "/api/v3/games/{game_id}/commands/counter-trade",
            post(counter_trade),
        )
        .route(
            "/api/v3/games/{game_id}/commands/declare-war",
            post(declare_war),
        )
        .route(
            "/api/v3/games/{game_id}/commands/denounce-civilization",
            post(denounce_civilization),
        )
        .route(
            "/api/v3/games/{game_id}/commands/offer-friendship",
            post(offer_friendship),
        )
        .route(
            "/api/v3/games/{game_id}/commands/make-diplomatic-demand",
            post(make_diplomatic_demand),
        )
        .route(
            "/api/v3/games/{game_id}/commands/respond-to-diplomatic-prompt",
            post(respond_to_diplomatic_prompt),
        )
        .route(
            "/api/v3/games/{game_id}/commands/respond-to-city-state-protection-prompt",
            post(respond_to_city_state_protection_prompt),
        )
        .route(
            "/api/v3/games/{game_id}/commands/gift-city-state-gold",
            post(gift_city_state_gold),
        )
        .route(
            "/api/v3/games/{game_id}/commands/set-city-state-protection",
            post(set_city_state_protection),
        )
        .route(
            "/api/v3/games/{game_id}/commands/demand-city-state-tribute",
            post(demand_city_state_tribute),
        )
        .route(
            "/api/v3/games/{game_id}/commands/gift-city-state-improvement",
            post(gift_city_state_improvement),
        )
        .route(
            "/api/v3/games/{game_id}/commands/negotiate-city-state-peace",
            post(negotiate_city_state_peace),
        )
        .route(
            "/api/v3/games/{game_id}/commands/marry-city-state",
            post(marry_city_state),
        )
        .route("/api/v3/games/{game_id}/commands/move-spy", post(move_spy))
        .route(
            "/api/v3/games/{game_id}/commands/set-spy-coup",
            post(set_spy_coup),
        )
        .route(
            "/api/v3/games/{game_id}/commands/resolve-event-choice",
            post(resolve_event_choice),
        )
        .route(
            "/api/v3/games/{game_id}/commands/set-city-tile-assignment",
            post(set_city_tile_assignment),
        )
        .route(
            "/api/v3/games/{game_id}/commands/set-specialist-count",
            post(set_specialist_count),
        )
        .route(
            "/api/v3/games/{game_id}/commands/set-manual-specialists",
            post(set_manual_specialists),
        )
        .route(
            "/api/v3/games/{game_id}/commands/reset-citizens",
            post(reset_citizens),
        )
        .route(
            "/api/v3/games/{game_id}/commands/set-avoid-growth",
            post(set_avoid_growth),
        )
        .route(
            "/api/v3/games/{game_id}/commands/set-citizen-focus",
            post(set_citizen_focus),
        )
        .route(
            "/api/v3/games/{game_id}/commands/set-research-path",
            post(set_research_path),
        )
        .route(
            "/api/v3/games/{game_id}/commands/manage-research-queue",
            post(manage_research_queue),
        )
        .route(
            "/api/v3/games/{game_id}/commands/adopt-policy",
            post(adopt_policy),
        )
        .route(
            "/api/v3/games/{game_id}/commands/choose-free-technology",
            post(choose_free_technology),
        )
        .route(
            "/api/v3/games/{game_id}/commands/acknowledge-research-completion",
            post(acknowledge_research_completion),
        )
        .layer(axum::middleware::from_fn(enforce_response_limits))
        .layer(axum::middleware::from_fn(enforce_request_limits))
        .layer(axum::middleware::from_fn(request_deadline))
        .layer(DefaultBodyLimit::max(MAX_REQUEST_BODY_BYTES))
        .layer(http_security.cors_layer())
        .layer(axum::middleware::from_fn_with_state(
            http_security.origin_policy(),
            enforce_origin,
        ))
        .layer(axum::middleware::from_fn(set_security_headers))
        .with_state(AppState {
            repository,
            worker,
            notifications,
            websocket_policy,
        });
    let listener = tokio::net::TcpListener::bind(address)
        .await
        .expect("failed to bind UNCIV_V3_BIND");
    axum::serve(
        listener,
        app.into_make_service_with_connect_info::<SocketAddr>(),
    )
    .await
    .expect("authoritative API server failed");
}
