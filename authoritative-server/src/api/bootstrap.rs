use super::*;

pub(crate) async fn run() {
    if std::env::args().any(|argument| argument == "--write-openapi") {
        let target = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("openapi")
            .join("api-v3.json");
        std::fs::create_dir_all(target.parent().expect("OpenAPI target has a parent"))
            .expect("failed to create OpenAPI output directory");
        std::fs::write(
            &target,
            format!(
                "{}\n",
                serde_json::to_string_pretty(&ApiDoc::openapi())
                    .expect("OpenAPI document is serializable")
            ),
        )
        .expect("failed to write generated OpenAPI document");
        eprintln!("wrote {}", target.display());
        return;
    }
    let address = std::env::var("UNCIV_V3_BIND")
        .unwrap_or_else(|_| "127.0.0.1:3000".to_owned())
        .parse::<SocketAddr>()
        .expect("UNCIV_V3_BIND must be a socket address");
    let database_url = std::env::var("UNCIV_V3_DATABASE_URL")
        .expect("UNCIV_V3_DATABASE_URL is required for the authoritative API");
    let repository = PostgresGameRepository::connect(&database_url)
        .await
        .expect("failed to connect to UNCIV_V3_DATABASE_URL");
    repository
        .migrate()
        .await
        .expect("failed to migrate authoritative database");
    let worker_address = std::env::var("UNCIV_ENGINE_WORKER_ADDR")
        .unwrap_or_else(|_| "127.0.0.1:43170".to_owned())
        .parse::<SocketAddr>()
        .expect("UNCIV_ENGINE_WORKER_ADDR must be a socket address");
    let worker = EngineWorkerClient::new(worker_address, Duration::from_secs(30));
    let worker_capabilities = worker
        .handshake()
        .await
        .expect("authoritative engine worker handshake failed");
    eprintln!(
        "authoritative engine worker ready: protocol={}, engine_build={}, rulesets={}",
        unciv_authoritative_server::worker::WORKER_PROTOCOL_VERSION,
        worker_capabilities.engine_build,
        worker_capabilities.installed_rulesets.len(),
    );
    let notifications = NotificationHub::default();
    tokio::spawn(run_outbox_dispatcher(
        repository.clone(),
        notifications.clone(),
    ));
    let app = Router::new()
        .route("/healthz", get(health))
        .route("/api/v3/capabilities", get(capabilities))
        .route("/api/v3/openapi.json", get(openapi_document))
        .route("/api/v3/notifications", get(websocket_notifications))
        .route("/api/v3/auth/register", post(register))
        .route("/api/v3/auth/login", post(login))
        .route("/api/v3/auth/refresh", post(refresh_session))
        .route("/api/v3/auth/logout", post(logout))
        .route("/api/v3/account/password", post(change_password))
        .route("/api/v3/account/disable", post(disable_account))
        .route("/api/v3/account", delete(delete_account))
        .route("/api/v3/games", get(list_games).post(create_game))
        .route("/api/v3/games/{game_id}", get(game_metadata))
        .route("/api/v3/games/{game_id}/projection", get(game_projection))
        .route("/api/v3/games/{game_id}/join", post(join_game))
        .route("/api/v3/games/{game_id}/commands/end-turn", post(end_turn))
        .route(
            "/api/v3/games/{game_id}/commands/move-unit",
            post(move_unit),
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
            "/api/v3/games/{game_id}/commands/adopt-policy",
            post(adopt_policy),
        )
        .route(
            "/api/v3/games/{game_id}/commands/choose-free-technology",
            post(choose_free_technology),
        )
        .layer(DefaultBodyLimit::max(8 * 1024))
        .with_state(AppState {
            repository,
            worker,
            notifications,
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
