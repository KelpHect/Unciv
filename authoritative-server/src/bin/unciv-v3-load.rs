use std::process::ExitCode;

#[path = "unciv-v3-load/runner.rs"]
mod runner;

#[tokio::main]
async fn main() -> ExitCode {
    match runner::run().await {
        Ok(report) => {
            println!(
                "{}",
                serde_json::to_string_pretty(&report).expect("load report is serializable")
            );
            ExitCode::SUCCESS
        }
        Err(error) => {
            eprintln!("{error}");
            ExitCode::FAILURE
        }
    }
}
