use std::{fs, path::Path};

const ALERTS: &str = include_str!("../observability/prometheus-alerts.yml");
const DASHBOARD: &str = include_str!("../observability/grafana-dashboard.json");
const TELEMETRY: &str = include_str!("../src/telemetry.rs");
const BOOTSTRAP: &str = include_str!("../src/api/bootstrap.rs");
const API_SERVICE: &str = include_str!("../systemd/unciv-authoritative-api.service");
const WORKFLOW: &str = include_str!("../../.github/workflows/authoritativeV3Observability.yml");

#[test]
fn alert_rules_and_dashboard_are_parseable_and_cover_authority_failures() {
    let alerts: serde_yaml_ng::Value = serde_yaml_ng::from_str(ALERTS).unwrap();
    assert!(
        alerts
            .get("groups")
            .and_then(|value| value.as_sequence())
            .is_some()
    );
    let dashboard: serde_json::Value = serde_json::from_str(DASHBOARD).unwrap();
    assert_eq!(
        dashboard.get("uid").and_then(serde_json::Value::as_str),
        Some("unciv-authoritative-v3")
    );
    assert!(
        dashboard
            .get("panels")
            .and_then(serde_json::Value::as_array)
            .is_some_and(|panels| panels.len() >= 8)
    );

    for required in [
        "AuthenticationAbuse",
        "StaleConflict",
        "CommandFailure",
        "CommandLatency",
        "WorkerFailures",
        "DatabaseLock",
        "RevisionGrowth",
        "ProjectionNearLimit",
        "OutboxLag",
        "WebSocketLoad",
        "SlowReaders",
    ] {
        assert!(ALERTS.contains(required), "missing alert class {required}");
    }
    for runbook in extract_values(ALERTS, "runbook: ") {
        let relative = runbook.split('#').next().unwrap();
        assert!(
            Path::new(env!("CARGO_MANIFEST_DIR"))
                .parent()
                .unwrap()
                .join(relative)
                .is_file(),
            "alert runbook does not exist: {relative}"
        );
    }
}

#[test]
fn observability_is_private_bounded_and_release_packaged() {
    assert!(TELEMETRY.contains("if !metrics_address.ip().is_loopback()"));
    assert!(TELEMETRY.contains("add_allowed_address(\"127.0.0.0/8\")"));
    assert!(TELEMETRY.contains("add_allowed_address(\"::1/128\")"));
    assert!(BOOTSTRAP.contains("telemetry::initialize()"));
    assert!(API_SERVICE.contains("IPAddressAllow=localhost"));

    let packaging = fs::read_to_string(
        Path::new(env!("CARGO_MANIFEST_DIR")).join("src/release_bundle/packaging.rs"),
    )
    .unwrap();
    for artifact in ["prometheus-alerts.yml", "grafana-dashboard.json"] {
        assert!(packaging.contains(artifact));
    }
}

#[test]
fn hosted_qualification_is_least_privilege_and_immutable() {
    let workflow = WORKFLOW.replace("\r\n", "\n");
    assert!(workflow.contains("permissions:\n  contents: read"));
    assert!(workflow.contains("rustup toolchain install 1.97.0"));
    assert!(workflow.contains("cargo clippy --all-targets --all-features -- -D warnings"));
    assert!(workflow.contains("cargo test --all-targets --all-features"));
    assert!(workflow.contains(
        "prom/prometheus@sha256:214f8427c8fba80c327bb94a75feb802ae12f2d6ca30812aa6e7d22f09bbea80"
    ));
    for line in workflow
        .lines()
        .filter(|line| line.trim().starts_with("uses:"))
    {
        let reference = line.split_once('@').map(|(_, value)| value.trim()).unwrap();
        let commit = reference.split_whitespace().next().unwrap();
        assert_eq!(commit.len(), 40);
        assert!(commit.bytes().all(|byte| byte.is_ascii_hexdigit()));
    }
}

#[test]
fn metric_and_trace_dimensions_cannot_encode_private_identifiers() {
    let source_root = Path::new(env!("CARGO_MANIFEST_DIR")).join("src");
    for source in rust_sources(&source_root) {
        let text = fs::read_to_string(&source).unwrap();
        for forbidden in [
            "\"account_id\" =>",
            "\"game_id\" =>",
            "\"command_id\" =>",
            "\"session_id\" =>",
            "\"username\" =>",
            "account_id = %",
            "game_id = %",
            "command_id = %",
            "session_id = %",
            "snapshot = %",
            "projection = %",
            "payload = %",
        ] {
            assert!(
                !text.contains(forbidden),
                "{} contains forbidden observability dimension {forbidden}",
                source.display()
            );
        }
    }
}

fn extract_values<'a>(document: &'a str, prefix: &str) -> Vec<&'a str> {
    document
        .lines()
        .filter_map(|line| line.trim().strip_prefix(prefix))
        .collect()
}

fn rust_sources(root: &Path) -> Vec<std::path::PathBuf> {
    let mut pending = vec![root.to_owned()];
    let mut sources = Vec::new();
    while let Some(directory) = pending.pop() {
        for entry in fs::read_dir(directory).unwrap() {
            let path = entry.unwrap().path();
            if path.is_dir() {
                pending.push(path);
            } else if path.extension().is_some_and(|extension| extension == "rs") {
                sources.push(path);
            }
        }
    }
    sources
}
