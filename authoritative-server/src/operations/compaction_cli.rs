use std::process::ExitCode;

use uuid::Uuid;

use crate::postgres::{PostgresGameRepository, SnapshotRetentionPolicy};

pub async fn run_compaction_cli() -> ExitCode {
    let mut game_id = None;
    let mut policy = SnapshotRetentionPolicy::default();
    let mut apply = false;
    let mut args = std::env::args().skip(1);
    while let Some(argument) = args.next() {
        match argument.as_str() {
            "--apply" => apply = true,
            "--recent" => {
                let Some(value) = args.next().and_then(|value| value.parse::<u64>().ok()) else {
                    return usage();
                };
                policy.recent_revisions = value;
            }
            "--long-term-interval" => {
                let Some(value) = args.next().and_then(|value| value.parse::<u64>().ok()) else {
                    return usage();
                };
                policy.long_term_interval = value;
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
        eprintln!("UNCIV_V3_DATABASE_URL is required for snapshot compaction");
        return ExitCode::FAILURE;
    };
    let Ok(repository) = PostgresGameRepository::connect(&database_url).await else {
        eprintln!("failed to connect to the authoritative database");
        return ExitCode::FAILURE;
    };
    let Ok(report) = repository
        .compact_snapshot_payloads(game_id, policy, !apply)
        .await
    else {
        eprintln!("snapshot compaction failed closed");
        return ExitCode::FAILURE;
    };
    println!(
        "{}",
        serde_json::to_string_pretty(&report).expect("compaction report is serializable")
    );
    ExitCode::SUCCESS
}

fn usage() -> ExitCode {
    eprintln!(
        "usage: unciv-v3-compact <game-uuid> [--recent <count>] \
         [--long-term-interval <count>] [--apply]"
    );
    ExitCode::FAILURE
}
