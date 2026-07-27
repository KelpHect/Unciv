use std::process::ExitCode;

use crate::postgres::{OutboxRuntimePolicy, PostgresGameRepository};

pub async fn run_outbox_cli() -> ExitCode {
    let arguments = std::env::args().skip(1).collect::<Vec<_>>();
    let Ok(database_url) = std::env::var("UNCIV_V3_DATABASE_URL") else {
        eprintln!("UNCIV_V3_DATABASE_URL is required for outbox operations");
        return ExitCode::FAILURE;
    };
    let Ok(repository) = PostgresGameRepository::connect(&database_url).await else {
        eprintln!("failed to connect to the authoritative database");
        return ExitCode::FAILURE;
    };
    match arguments.first().map(String::as_str) {
        Some("status") if arguments.len() == 1 => status(&repository).await,
        Some("compact") => compact(&repository, &arguments[1..]).await,
        Some("requeue") => requeue(&repository, &arguments[1..]).await,
        _ => usage(),
    }
}

async fn status(repository: &PostgresGameRepository) -> ExitCode {
    let Ok(policy) = OutboxRuntimePolicy::from_environment() else {
        eprintln!("authoritative outbox runtime policy is invalid");
        return ExitCode::FAILURE;
    };
    let Ok(report) = repository.outbox_health(policy.lag_alert_after).await else {
        eprintln!("authoritative outbox status failed closed");
        return ExitCode::FAILURE;
    };
    print_report(&report);
    if report.alert {
        ExitCode::from(2)
    } else {
        ExitCode::SUCCESS
    }
}

async fn compact(repository: &PostgresGameRepository, arguments: &[String]) -> ExitCode {
    let mut older_than_days = 30_u32;
    let mut limit = 1_000_i64;
    let mut apply = false;
    let mut index = 0;
    while index < arguments.len() {
        match arguments[index].as_str() {
            "--apply" => apply = true,
            "--older-than-days" if index + 1 < arguments.len() => {
                index += 1;
                let Ok(value) = arguments[index].parse() else {
                    return usage();
                };
                older_than_days = value;
            }
            "--limit" if index + 1 < arguments.len() => {
                index += 1;
                let Ok(value) = arguments[index].parse() else {
                    return usage();
                };
                limit = value;
            }
            _ => return usage(),
        }
        index += 1;
    }
    if !(1..=3_650).contains(&older_than_days) || !(1..=10_000).contains(&limit) {
        return usage();
    }
    let Ok(report) = repository
        .compact_delivered_outbox(older_than_days, limit, !apply)
        .await
    else {
        eprintln!("authoritative outbox compaction failed closed");
        return ExitCode::FAILURE;
    };
    print_report(&report);
    ExitCode::SUCCESS
}

async fn requeue(repository: &PostgresGameRepository, arguments: &[String]) -> ExitCode {
    let Some(id) = arguments
        .first()
        .and_then(|value| value.parse::<i64>().ok())
    else {
        return usage();
    };
    if id <= 0 || arguments.len() > 2 || (arguments.len() == 2 && arguments[1] != "--apply") {
        return usage();
    }
    let apply = arguments.get(1).is_some_and(|value| value == "--apply");
    let Ok(report) = repository.requeue_dead_letter(id, !apply).await else {
        eprintln!("authoritative outbox requeue failed closed");
        return ExitCode::FAILURE;
    };
    print_report(&report);
    if !report.dry_run && !report.requeued {
        ExitCode::from(2)
    } else {
        ExitCode::SUCCESS
    }
}

fn print_report(report: &impl serde::Serialize) {
    println!(
        "{}",
        serde_json::to_string_pretty(report).expect("outbox report is serializable")
    );
}

fn usage() -> ExitCode {
    eprintln!(
        "usage: unciv-v3-outbox status | compact [--older-than-days 30] [--limit 1000] [--apply] | requeue <outbox-id> [--apply]"
    );
    ExitCode::FAILURE
}
