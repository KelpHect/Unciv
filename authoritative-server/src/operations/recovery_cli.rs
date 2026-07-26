use std::{net::SocketAddr, process::ExitCode, time::Duration};

use uuid::Uuid;

use crate::{
    postgres::PostgresGameRepository,
    worker::{EngineWorkerClient, WorkerIdentityKey},
};

const DEFAULT_MAX_TAIL: u64 = 128;

pub async fn run_recovery_cli() -> ExitCode {
    let mut game_id = None;
    let mut max_tail = DEFAULT_MAX_TAIL;
    let mut apply = false;
    let mut args = std::env::args().skip(1);
    while let Some(argument) = args.next() {
        match argument.as_str() {
            "--apply" => apply = true,
            "--max-tail" => {
                let Some(value) = args.next() else {
                    return usage();
                };
                let Ok(value) = value.parse::<u64>() else {
                    return usage();
                };
                max_tail = value;
            }
            _ if game_id.is_none() => {
                let Ok(value) = Uuid::parse_str(&argument) else {
                    return usage();
                };
                game_id = Some(value);
            }
            _ => return usage(),
        }
    }
    let Some(game_id) = game_id else {
        return usage();
    };
    let Ok(database_url) = std::env::var("UNCIV_V3_DATABASE_URL") else {
        eprintln!("UNCIV_V3_DATABASE_URL is required for recovery");
        return ExitCode::FAILURE;
    };
    let Ok(worker_address) = std::env::var("UNCIV_ENGINE_WORKER_ADDR")
        .unwrap_or_else(|_| "127.0.0.1:39031".to_owned())
        .parse::<SocketAddr>()
    else {
        eprintln!("UNCIV_ENGINE_WORKER_ADDR must be a valid socket address");
        return ExitCode::FAILURE;
    };
    let Ok(repository) = PostgresGameRepository::connect(&database_url).await else {
        eprintln!("failed to connect to the authoritative database");
        return ExitCode::FAILURE;
    };
    let Ok(worker_identity) = std::env::var("UNCIV_ENGINE_WORKER_SECRET")
        .map_err(|_| ())
        .and_then(|value| WorkerIdentityKey::from_hex(&value).map_err(|_| ()))
    else {
        eprintln!(
            "UNCIV_ENGINE_WORKER_SECRET must be exactly 32 bytes encoded as 64 hexadecimal characters"
        );
        return ExitCode::FAILURE;
    };
    let worker = EngineWorkerClient::new(worker_address, Duration::from_secs(30), worker_identity);
    let Ok(recovered) = repository
        .reconstruct_head(&worker, game_id, max_tail)
        .await
    else {
        eprintln!("bounded recovery reconstruction failed closed");
        return ExitCode::FAILURE;
    };
    let mut report = serde_json::json!({
        "mode": if apply { "apply" } else { "dry_run" },
        "game_id": recovered.game_id,
        "source_revision": recovered.source_revision,
        "recovered_head_revision": recovered.head_revision,
        "commands_replayed": recovered.commands_replayed,
        "canonical_state_hash": recovered.canonical_state_hash,
    });
    if apply {
        let Ok(revision) = repository.publish_recovered_head(&recovered).await else {
            eprintln!("atomic recovery publication failed closed");
            return ExitCode::FAILURE;
        };
        report["published_revision"] = serde_json::json!(revision);
    }
    println!(
        "{}",
        serde_json::to_string_pretty(&report).expect("recovery report is serializable")
    );
    ExitCode::SUCCESS
}

fn usage() -> ExitCode {
    eprintln!("usage: unciv-v3-recover <game-uuid> [--max-tail <count>] [--apply]");
    ExitCode::FAILURE
}
