const COMPOSE: &str = include_str!("../compose.vps.yaml");
const ENVIRONMENT: &str = include_str!("../.env.vps.example");
const ROLE_BOOTSTRAP: &str = include_str!("../docker/init-roles.sh");

#[test]
fn postgres_role_bootstrap_runs_only_through_the_parameterized_script() {
    assert!(COMPOSE.contains("./postgresql/bootstrap-roles.sql:/bootstrap/bootstrap-roles.sql:ro"));
    assert!(
        !COMPOSE.contains("bootstrap-roles.sql:/docker-entrypoint-initdb.d/bootstrap-roles.sql")
    );
    assert!(ROLE_BOOTSTRAP.contains("--file=/bootstrap/bootstrap-roles.sql"));
}

#[test]
fn migration_and_proxy_environment_names_match_the_binaries() {
    assert!(COMPOSE.contains("UNCIV_V3_MIGRATION_DATABASE_URL:"));
    assert!(!COMPOSE.contains("UNCIV_V3_TRUSTED_PROXY_MODE"));
    assert!(COMPOSE.contains("UNCIV_V3_TRUSTED_PROXY: loopback"));
    assert!(!ENVIRONMENT.contains("UNCIV_V3_TRUSTED_PROXY="));
}

#[test]
fn api_uses_host_loopback_so_caddy_can_be_the_only_trusted_proxy() {
    for required in [
        "network_mode: host",
        "UNCIV_V3_BIND: 127.0.0.1:${UNCIV_V3_PUBLIC_PORT:-3000}",
        "127.0.0.1:${UNCIV_V3_POSTGRES_HOST_PORT:-54320}:5432",
        "@127.0.0.1:${UNCIV_V3_POSTGRES_HOST_PORT:-54320}",
        "UNCIV_ENGINE_WORKER_ADDR: 127.0.0.1:43170",
    ] {
        assert!(
            COMPOSE.contains(required),
            "Loopback proxy contract is missing {required}"
        );
    }
    assert!(!COMPOSE.contains("network_mode: service:worker"));
    assert!(!COMPOSE.contains(":43170:43170"));
}

#[test]
fn worker_starts_only_after_an_activated_read_only_ruleset_tree_exists() {
    for required in [
        "rulesets:",
        "/bundle/bin/unciv-v3-rulesets",
        "condition: service_completed_successfully",
        "working_dir: /rulesets/active",
        "${UNCIV_V3_RULESETS_ROOT:?absolute ruleset store path}:/rulesets:ro",
        "--enable-native-access=ALL-UNNAMED",
        "UNCIV_V3_RELEASE_BUNDLE_ROOT: /bundle",
        "UNCIV_ENGINE_WORKER_JAR: /bundle/worker/UncivAuthoritativeWorker.jar",
    ] {
        assert!(
            COMPOSE.contains(required),
            "Compose contract is missing {required}"
        );
    }
}
