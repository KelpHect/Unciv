use std::process::ExitCode;

use uuid::Uuid;

use crate::postgres::{GameStorageBreakdown, PostgresGameRepository};

#[derive(serde::Serialize)]
struct StorageBudgetListReport {
    budget_bytes: u64,
    games_scanned: u64,
    games_over_budget: u64,
    games: Vec<StorageBudgetGame>,
}

#[derive(serde::Serialize)]
struct StorageBudgetGame {
    game_id: Uuid,
    postgres_bytes: u64,
    archive_bytes: u64,
    total_bytes: u64,
    over_budget: bool,
}

pub async fn run_storage_cli() -> ExitCode {
    let mut budget_bytes = None;
    let mut limit = 100_u64;
    let mut list_all = false;
    let mut args = std::env::args().skip(1);
    while let Some(argument) = args.next() {
        match argument.as_str() {
            "--all" => list_all = true,
            "--budget" => {
                let Some(value) = args.next().and_then(|value| value.parse::<u64>().ok()) else {
                    return usage();
                };
                if value == 0 {
                    return usage();
                }
                budget_bytes = Some(value);
            }
            "--limit" => {
                let Some(value) = args.next().and_then(|value| value.parse::<u64>().ok()) else {
                    return usage();
                };
                if !(1..=10_000).contains(&value) {
                    return usage();
                }
                limit = value;
            }
            _ => return usage(),
        }
    }
    if budget_bytes.is_none() {
        budget_bytes = std::env::var("UNCIV_V3_SNAPSHOT_GAME_BUDGET_BYTES")
            .ok()
            .and_then(|value| value.parse::<u64>().ok())
            .filter(|value| *value != 0);
    }
    let Some(budget_bytes) = budget_bytes else {
        eprintln!(
            "a per-match storage budget is required: pass --budget <bytes> or set UNCIV_V3_SNAPSHOT_GAME_BUDGET_BYTES"
        );
        return usage();
    };
    let Ok(database_url) = std::env::var("UNCIV_V3_DATABASE_URL") else {
        eprintln!("UNCIV_V3_DATABASE_URL is required for storage inspection");
        return ExitCode::FAILURE;
    };
    let repository = match PostgresGameRepository::connect(&database_url).await {
        Ok(repository) => repository,
        Err(error) => {
            eprintln!("failed to connect to the authoritative database: {error}");
            return ExitCode::FAILURE;
        }
    };
    let breakdowns = match repository.list_game_storage(limit).await {
        Ok(breakdowns) => breakdowns,
        Err(error) => {
            eprintln!("storage inspection failed closed: {error}");
            return ExitCode::FAILURE;
        }
    };
    let report = build_report(budget_bytes, breakdowns, list_all);
    println!(
        "{}",
        serde_json::to_string_pretty(&report).expect("storage report is serializable")
    );
    if report.games_over_budget > 0 {
        ExitCode::from(2)
    } else {
        ExitCode::SUCCESS
    }
}

fn build_report(
    budget_bytes: u64,
    breakdowns: Vec<GameStorageBreakdown>,
    list_all: bool,
) -> StorageBudgetListReport {
    let games_scanned = breakdowns.len() as u64;
    let mut games = Vec::new();
    let mut games_over_budget = 0_u64;
    for breakdown in breakdowns {
        let over_budget = breakdown.total_bytes > budget_bytes;
        if over_budget {
            games_over_budget += 1;
        }
        if list_all || over_budget {
            games.push(StorageBudgetGame {
                game_id: breakdown.game_id,
                postgres_bytes: breakdown.postgres_bytes,
                archive_bytes: breakdown.archive_bytes,
                total_bytes: breakdown.total_bytes,
                over_budget,
            });
        }
    }
    StorageBudgetListReport {
        budget_bytes,
        games_scanned,
        games_over_budget,
        games,
    }
}

fn usage() -> ExitCode {
    eprintln!(
        "usage: unciv-v3-storage [--budget <bytes>] [--limit <count>] [--all]\n\
         \n\
         Lists games over the per-match storage budget (retained PostgreSQL\n\
         payloads plus verified Lockwell archive objects) with byte breakdowns.\n\
         --budget  overrides UNCIV_V3_SNAPSHOT_GAME_BUDGET_BYTES\n\
         --limit   maximum games to scan (default 100, max 10000)\n\
         --all     list every game with a breakdown, not just over-budget games"
    );
    ExitCode::FAILURE
}
