use std::{collections::HashMap, path::PathBuf};

const SERVICE: &str = include_str!("../systemd/unciv-authoritative-worker.service");
const ENVIRONMENT_EXAMPLE: &str = include_str!("../systemd/worker.env.example");

#[test]
fn worker_service_enforces_recycling_isolation_and_resource_caps() {
    let directives = directives(SERVICE);
    assert_eq!(directives["User"], "unciv-worker");
    assert_eq!(directives["Group"], "unciv-authoritative");
    assert_eq!(directives["Restart"], "always");
    assert_eq!(directives["RestartSec"], "2s");
    assert_eq!(directives["RuntimeMaxSec"], "6h");
    assert_eq!(directives["CPUQuota"], "80%");
    assert_eq!(directives["MemoryHigh"], "448M");
    assert_eq!(directives["MemoryMax"], "512M");
    assert_eq!(directives["MemorySwapMax"], "0");
    assert_eq!(directives["TasksMax"], "64");
    assert_eq!(directives["OOMPolicy"], "stop");
    assert_eq!(directives["NoNewPrivileges"], "yes");
    assert_eq!(directives["PrivateTmp"], "yes");
    assert_eq!(directives["PrivateDevices"], "yes");
    assert_eq!(directives["ProtectSystem"], "strict");
    assert_eq!(directives["ProtectHome"], "yes");
    assert_eq!(directives["CapabilityBoundingSet"], "");
    assert_eq!(directives["IPAddressDeny"], "any");
    assert_eq!(directives["IPAddressAllow"], "localhost");

    let command = directives["ExecStart"];
    assert!(command.starts_with("/usr/bin/java "));
    for argument in [
        "-Djava.awt.headless=true",
        "-Xms64m",
        "-Xmx384m",
        "-XX:MaxMetaspaceSize=96m",
        "-XX:MaxDirectMemorySize=32m",
        "-XX:+ExitOnOutOfMemoryError",
        "/opt/unciv-authoritative/releases/current/worker/UncivAuthoritativeWorker.jar",
    ] {
        assert!(
            command.contains(argument),
            "missing JVM boundary: {argument}"
        );
    }
    assert!(!SERVICE.contains("UNCIV_ENGINE_WORKER_SECRET="));
    assert!(!SERVICE.contains("UNCIV_V3_UNPACKAGED_DEV"));
    assert_eq!(
        directives["EnvironmentFile"],
        "/etc/unciv-authoritative/worker.env",
    );
}

#[test]
fn environment_template_contains_no_usable_secret() {
    assert!(!ENVIRONMENT_EXAMPLE.contains("UNCIV_V3_UNPACKAGED_DEV"));
    let values = directives(ENVIRONMENT_EXAMPLE);
    assert_eq!(values["UNCIV_ENGINE_WORKER_PORT"], "43170");
    assert_eq!(values["UNCIV_ENGINE_WORKER_SOCKET_TIMEOUT_MS"], "5000");
    assert_eq!(values["UNCIV_ENGINE_WORKER_COMMAND_TIMEOUT_MS"], "30000");
    let bundle_id = &values["UNCIV_V3_RELEASE_BUNDLE_ID"];
    assert_ne!(bundle_id.len(), 64);
    assert!(
        !bundle_id
            .chars()
            .all(|character| character.is_ascii_hexdigit())
    );
    let secret = &values["UNCIV_ENGINE_WORKER_SECRET"];
    assert_ne!(secret.len(), 64);
    assert!(
        !secret
            .chars()
            .all(|character| character.is_ascii_hexdigit())
    );
}

#[test]
fn packaged_worker_and_assets_are_read_only_inputs() {
    let directives = directives(SERVICE);
    assert_eq!(
        directives["WorkingDirectory"],
        "/opt/unciv-authoritative/rulesets/active",
    );
    assert_eq!(directives["ProtectSystem"], "strict");
    assert!(
        !SERVICE
            .lines()
            .any(|line| line.starts_with("ReadWritePaths="))
    );

    let manifest = PathBuf::from(env!("CARGO_MANIFEST_DIR"));
    assert!(manifest.join("../server/build.gradle.kts").is_file());
}

fn directives(source: &str) -> HashMap<&str, &str> {
    source
        .lines()
        .filter_map(|line| {
            let line = line.trim();
            if line.is_empty() || line.starts_with('#') || line.starts_with('[') {
                return None;
            }
            line.split_once('=')
        })
        .collect()
}
