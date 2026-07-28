use unciv_authoritative_server::postgres::{PostgresGameRepository, PostgresRuntimeConfig};

#[tokio::main]
async fn main() {
    let database_url = std::env::var("UNCIV_V3_MIGRATION_DATABASE_URL")
        .expect("UNCIV_V3_MIGRATION_DATABASE_URL is required");
    let config = PostgresRuntimeConfig::from_environment()
        .expect("authoritative PostgreSQL runtime configuration must be valid");
    let repository = PostgresGameRepository::connect_with_config(&database_url, config)
        .await
        .expect("failed to connect with authoritative migration credentials");
    repository
        .migrate()
        .await
        .expect("failed to migrate authoritative database");
    repository
        .verify_schema_compatibility()
        .await
        .expect("migrated schema is incompatible with this release");
    eprintln!("authoritative database migration set is exact and current");
}
