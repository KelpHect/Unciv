use std::process::ExitCode;

use crate::postgres::PostgresGameRepository;

pub async fn run_reconciliation_cli() -> ExitCode {
    let Ok(database_url) = std::env::var("UNCIV_V3_DATABASE_URL") else {
        eprintln!("UNCIV_V3_DATABASE_URL is required for reconciliation");
        return ExitCode::FAILURE;
    };
    let Ok(repository) = PostgresGameRepository::connect(&database_url).await else {
        eprintln!("failed to connect to the authoritative database");
        return ExitCode::FAILURE;
    };
    let Ok(report) = repository.reconcile_authoritative_state().await else {
        eprintln!("authoritative reconciliation failed");
        return ExitCode::FAILURE;
    };
    println!(
        "{}",
        serde_json::to_string_pretty(&report).expect("reconciliation report is serializable")
    );
    if report.total_findings == 0 {
        ExitCode::SUCCESS
    } else {
        ExitCode::from(2)
    }
}
