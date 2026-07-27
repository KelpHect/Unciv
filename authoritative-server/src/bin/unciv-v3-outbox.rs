#[tokio::main]
async fn main() -> std::process::ExitCode {
    unciv_authoritative_server::operations::run_outbox_cli().await
}
