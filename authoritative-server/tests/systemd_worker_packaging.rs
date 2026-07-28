use std::{collections::HashMap, path::PathBuf};

const SERVICE: &str = include_str!("../systemd/unciv-authoritative-worker.service");
const ENVIRONMENT_EXAMPLE: &str = include_str!("../systemd/worker.env.example");
const QUALIFICATION_DOCKERFILE: &str = include_str!("../systemd/qualification/Dockerfile");
const QUALIFICATION_SCRIPT: &str = include_str!("../systemd/qualification/qualify-worker.sh");
const QUALIFICATION_RUNNER: &str =
    include_str!("../systemd/qualification/run-linux-worker-qualification.ps1");

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
    assert_eq!(directives["RuntimeDirectory"], "unciv-worker");
    assert_eq!(directives["RuntimeDirectoryMode"], "0700");
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
        "-Djava.io.tmpdir=/run/unciv-worker",
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

#[test]
fn live_linux_qualification_is_pinned_bounded_and_cleans_up() {
    assert!(QUALIFICATION_DOCKERFILE.contains(
        "ubuntu@sha256:4fbb8e6a8395de5a7550b33509421a2bafbc0aab6c06ba2cef9ebffbc7092d90"
    ));
    assert!(QUALIFICATION_DOCKERFILE.contains(r#"CMD ["/usr/lib/systemd/systemd"]"#));
    assert!(!QUALIFICATION_DOCKERFILE.contains("ubuntu:latest"));
    assert!(!QUALIFICATION_DOCKERFILE.contains("UNCIV_V3_UNPACKAGED_DEV"));

    for evidence in [
        "systemd-analyze verify",
        "MemorySwapMax",
        "systemctl kill --signal=SIGKILL",
        "RuntimeMaxSec=5s",
        "UNCIV_ENGINE_WORKER_COMMAND_TIMEOUT_MS=1000",
        "timed_out_status",
        "OutOfMemoryError",
        "runuser -u nobody",
        "qualification-write",
    ] {
        assert!(
            QUALIFICATION_SCRIPT.contains(evidence),
            "missing live qualification evidence: {evidence}"
        );
    }
    assert!(QUALIFICATION_RUNNER.contains("--privileged"));
    assert!(QUALIFICATION_RUNNER.contains("'/tmp:rw,noexec,nosuid,nodev'"));
    assert!(QUALIFICATION_RUNNER.contains("docker rm --force"));
    assert!(QUALIFICATION_RUNNER.contains("Remove-Item -LiteralPath"));
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
