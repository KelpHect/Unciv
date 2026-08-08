use std::process::ExitCode;

use uuid::Uuid;

use crate::postgres::PostgresGameRepository;

pub async fn run_reencode_cli() -> ExitCode {
    let mut game_id = None;
    let mut apply = false;
    let args = std::env::args().skip(1);
    for argument in args {
        match argument.as_str() {
            "--apply" => apply = true,
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
        eprintln!("UNCIV_V3_DATABASE_URL is required for snapshot re-encoding");
        return ExitCode::FAILURE;
    };
    let repository = match PostgresGameRepository::connect(&database_url).await {
        Ok(repository) => repository,
        Err(error) => {
            eprintln!("failed to connect to the authoritative database: {error}");
            return ExitCode::FAILURE;
        }
    };
    let report = match repository.reencode_snapshot_payloads(game_id, !apply).await {
        Ok(report) => report,
        Err(error) => {
            eprintln!("snapshot re-encoding failed closed: {error}");
            return ExitCode::FAILURE;
        }
    };
    println!(
        "{}",
        serde_json::to_string_pretty(&report).expect("re-encode report is serializable")
    );
    ExitCode::SUCCESS
}

fn usage() -> ExitCode {
    eprintln!("usage: unciv-v3-reencode <game-uuid> [--apply]");
    ExitCode::FAILURE
}
