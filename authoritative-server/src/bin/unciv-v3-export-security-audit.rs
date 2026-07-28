#[tokio::main]
async fn main() -> std::process::ExitCode {
    unciv_authoritative_server::operations::run_security_audit_export_cli().await
}
