use std::{fs, path::Path};

use serde_json::json;
use uuid::Uuid;

use crate::{CommitError, notifications::RevisionNotification, worker::WorkerClientError};

const PRIVATE_SENTINEL: &str = "PRIVATE_CANONICAL_SNAPSHOT_hidden-unit_secret-diplomacy_server-rng";

#[test]
fn private_worker_diagnostics_are_redacted_from_normal_error_formatting() {
    let worker = WorkerClientError::Rejected(PRIVATE_SENTINEL.to_owned());
    let commit = CommitError::WorkerRejected(PRIVATE_SENTINEL.to_owned());

    for formatted in [
        worker.to_string(),
        format!("{worker:?}"),
        commit.to_string(),
        format!("{commit:?}"),
    ] {
        assert!(!formatted.contains(PRIVATE_SENTINEL));
        assert!(!formatted.contains("snapshot"));
        assert!(!formatted.contains("hidden-unit"));
    }
}

#[test]
fn websocket_revision_frames_have_one_exact_public_shape() {
    let notification = RevisionNotification {
        event_type: "revision_committed",
        protocol_version: crate::PROTOCOL_VERSION,
        game_id: Uuid::nil(),
        committed_revision: 9,
        canonical_state_hash: "a".repeat(64),
    };
    let value = serde_json::to_value(notification).unwrap();

    assert_eq!(
        value,
        json!({
            "type": "revision_committed",
            "protocol_version": crate::PROTOCOL_VERSION,
            "game_id": Uuid::nil(),
            "committed_revision": 9,
            "canonical_state_hash": "a".repeat(64),
        })
    );
    assert!(!value.to_string().contains(PRIVATE_SENTINEL));
}

#[test]
fn observability_dependencies_and_sensitive_log_interpolation_fail_closed() {
    let manifest = include_str!("../Cargo.toml").to_ascii_lowercase();
    for dependency in [
        "\nlog ",
        "\nlog=",
        "\ntracing ",
        "\ntracing=",
        "\nmetrics ",
        "\nmetrics=",
        "\nprometheus ",
        "\nprometheus=",
        "\nopentelemetry ",
        "\nopentelemetry=",
    ] {
        assert!(
            !manifest.contains(dependency),
            "observability dependency {dependency:?} requires an explicit disclosure policy"
        );
    }

    let source_root = Path::new(env!("CARGO_MANIFEST_DIR")).join("src");
    let mut sources = Vec::new();
    collect_rust_sources(&source_root, &mut sources);
    for source in sources {
        let file_name = source
            .file_name()
            .and_then(|name| name.to_str())
            .unwrap_or("");
        if source
            .components()
            .any(|part| part.as_os_str() == "operations")
            || file_name == "tests.rs"
            || file_name == "lib_tests.rs"
            || file_name.ends_with("_tests.rs")
        {
            continue;
        }
        let text = fs::read_to_string(&source).unwrap();
        for log_call in macro_calls(&text, "eprintln!") {
            for private_capture in [
                "{snapshot}",
                "{payload}",
                "{projection}",
                "{reason}",
                "{request}",
                "{response}",
                "{credential}",
                "{token}",
                "{password}",
                "{recovery_code}",
                "{identity}",
                "{:?}",
            ] {
                assert!(
                    !log_call.contains(private_capture),
                    "{} contains ungoverned private log interpolation {private_capture}",
                    source.display()
                );
            }
        }
    }
}

#[test]
fn account_secrets_have_no_worker_or_projection_schema_path() {
    for relative in [
        "src/worker.rs",
        "src/worker/protocol.rs",
        "src/projection.rs",
        "src/projection_validation.rs",
    ] {
        let source = fs::read_to_string(Path::new(env!("CARGO_MANIFEST_DIR")).join(relative))
            .expect("disclosure-boundary source exists");
        for secret_field in ["password", "recovery_code", "session_token"] {
            assert!(
                !source.contains(secret_field),
                "{relative} exposes account secret field {secret_field}"
            );
        }
    }
}

fn macro_calls<'a>(source: &'a str, name: &str) -> Vec<&'a str> {
    let mut calls = Vec::new();
    let mut remainder = source;
    while let Some(start) = remainder.find(name) {
        let call = &remainder[start..];
        let end = call.find(");").map_or(call.len(), |index| index + 2);
        calls.push(&call[..end]);
        remainder = &call[end..];
    }
    calls
}

fn collect_rust_sources(directory: &Path, sources: &mut Vec<std::path::PathBuf>) {
    for entry in fs::read_dir(directory).unwrap() {
        let path = entry.unwrap().path();
        if path.is_dir() {
            collect_rust_sources(&path, sources);
        } else if path.extension().is_some_and(|extension| extension == "rs") {
            sources.push(path);
        }
    }
}
