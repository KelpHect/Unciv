use std::process::ExitCode;

use uuid::Uuid;

use crate::postgres::PostgresGameRepository;

pub async fn run_repair_cli() -> ExitCode {
    let mut game_id = None;
    let mut apply = false;
    for argument in std::env::args().skip(1) {
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
        eprintln!("UNCIV_V3_DATABASE_URL is required for repair");
        return ExitCode::FAILURE;
    };
    let Ok(repository) = PostgresGameRepository::connect(&database_url).await else {
        eprintln!("failed to connect to the authoritative database");
        return ExitCode::FAILURE;
    };
    let Ok(report) = repository.repair_authoritative_game(game_id, !apply).await else {
        eprintln!("authoritative repair failed closed");
        return ExitCode::FAILURE;
    };
    println!(
        "{}",
        serde_json::to_string_pretty(&report).expect("repair report is serializable")
    );
    ExitCode::SUCCESS
}

fn usage() -> ExitCode {
    eprintln!("usage: unciv-v3-repair <game-uuid> [--apply]");
    ExitCode::FAILURE
}
