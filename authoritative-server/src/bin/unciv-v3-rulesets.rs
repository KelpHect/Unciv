#[tokio::main]
async fn main() -> std::process::ExitCode {
    unciv_authoritative_server::ruleset_acquisition::run_ruleset_acquisition_cli().await
}
