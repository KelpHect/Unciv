use super::*;
use sqlx::postgres::PgPoolOptions;
use std::{collections::HashMap, time::Duration};

pub static MIGRATOR: sqlx::migrate::Migrator = sqlx::migrate!("./migrations");

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct PostgresRuntimeConfig {
    pub max_connections: u32,
    pub min_connections: u32,
    pub acquire_timeout: Duration,
    pub idle_timeout: Duration,
    pub max_lifetime: Duration,
    pub statement_timeout_ms: u64,
    pub lock_timeout_ms: u64,
}

#[derive(Debug, thiserror::Error)]
pub enum PostgresConfigurationError {
    #[error("{0} must be an integer")]
    InvalidInteger(&'static str),
    #[error("{0} must be between {1} and {2}")]
    OutOfRange(&'static str, u64, u64),
    #[error("UNCIV_V3_DB_POOL_MIN must not exceed UNCIV_V3_DB_POOL_MAX")]
    InvalidPoolBounds,
}

#[derive(Debug, thiserror::Error)]
pub enum SchemaCompatibilityError {
    #[error("failed to inspect authoritative database schema")]
    Database(#[source] sqlx::Error),
    #[error("authoritative database migration history is incomplete or has extra versions")]
    VersionSet,
    #[error("authoritative database migration {0} was not applied successfully")]
    FailedMigration(i64),
    #[error("authoritative database migration {0} checksum differs from this release")]
    ChecksumMismatch(i64),
}

impl Default for PostgresRuntimeConfig {
    fn default() -> Self {
        Self {
            max_connections: 10,
            min_connections: 1,
            acquire_timeout: Duration::from_secs(5),
            idle_timeout: Duration::from_secs(300),
            max_lifetime: Duration::from_secs(1_800),
            statement_timeout_ms: 15_000,
            lock_timeout_ms: 5_000,
        }
    }
}

impl PostgresRuntimeConfig {
    pub fn from_environment() -> Result<Self, PostgresConfigurationError> {
        Self::from_values(std::env::vars())
    }

    fn from_values(
        values: impl IntoIterator<Item = (String, String)>,
    ) -> Result<Self, PostgresConfigurationError> {
        let values = values.into_iter().collect::<HashMap<_, _>>();
        let defaults = Self::default();
        let max_connections = parse_bounded(
            &values,
            "UNCIV_V3_DB_POOL_MAX",
            u64::from(defaults.max_connections),
            1,
            64,
        )? as u32;
        let min_connections = parse_bounded(
            &values,
            "UNCIV_V3_DB_POOL_MIN",
            u64::from(defaults.min_connections),
            0,
            64,
        )? as u32;
        if min_connections > max_connections {
            return Err(PostgresConfigurationError::InvalidPoolBounds);
        }
        Ok(Self {
            max_connections,
            min_connections,
            acquire_timeout: Duration::from_millis(parse_bounded(
                &values,
                "UNCIV_V3_DB_ACQUIRE_TIMEOUT_MS",
                defaults.acquire_timeout.as_millis() as u64,
                100,
                60_000,
            )?),
            idle_timeout: Duration::from_secs(parse_bounded(
                &values,
                "UNCIV_V3_DB_IDLE_TIMEOUT_SECONDS",
                defaults.idle_timeout.as_secs(),
                1,
                86_400,
            )?),
            max_lifetime: Duration::from_secs(parse_bounded(
                &values,
                "UNCIV_V3_DB_MAX_LIFETIME_SECONDS",
                defaults.max_lifetime.as_secs(),
                1,
                86_400,
            )?),
            statement_timeout_ms: parse_bounded(
                &values,
                "UNCIV_V3_DB_STATEMENT_TIMEOUT_MS",
                defaults.statement_timeout_ms,
                100,
                300_000,
            )?,
            lock_timeout_ms: parse_bounded(
                &values,
                "UNCIV_V3_DB_LOCK_TIMEOUT_MS",
                defaults.lock_timeout_ms,
                100,
                300_000,
            )?,
        })
    }
}

fn parse_bounded(
    values: &HashMap<String, String>,
    name: &'static str,
    default: u64,
    minimum: u64,
    maximum: u64,
) -> Result<u64, PostgresConfigurationError> {
    let Some(value) = values.get(name) else {
        return Ok(default);
    };
    let value = value
        .parse::<u64>()
        .map_err(|_| PostgresConfigurationError::InvalidInteger(name))?;
    if !(minimum..=maximum).contains(&value) {
        return Err(PostgresConfigurationError::OutOfRange(
            name, minimum, maximum,
        ));
    }
    Ok(value)
}

impl PostgresGameRepository {
    pub async fn connect(database_url: &str) -> Result<Self, sqlx::Error> {
        Self::connect_with_config(database_url, PostgresRuntimeConfig::default()).await
    }

    pub async fn connect_with_config(
        database_url: &str,
        config: PostgresRuntimeConfig,
    ) -> Result<Self, sqlx::Error> {
        let statement_timeout = format!("{}ms", config.statement_timeout_ms);
        let lock_timeout = format!("{}ms", config.lock_timeout_ms);
        let object_store = LockwellObjectStore::from_env()
            .map_err(|error| sqlx::Error::Configuration(Box::new(error)))?;
        let pool = PgPoolOptions::new()
            .max_connections(config.max_connections)
            .min_connections(config.min_connections)
            .acquire_timeout(config.acquire_timeout)
            .idle_timeout(Some(config.idle_timeout))
            .max_lifetime(Some(config.max_lifetime))
            .after_connect(move |connection, _metadata| {
                let statement_timeout = statement_timeout.clone();
                let lock_timeout = lock_timeout.clone();
                Box::pin(async move {
                    sqlx::query(
                        "SELECT set_config('statement_timeout', $1, false), set_config('lock_timeout', $2, false)",
                    )
                    .bind(statement_timeout)
                    .bind(lock_timeout)
                    .execute(connection)
                    .await?;
                    Ok(())
                })
            })
            .connect(database_url)
            .await?;
        Ok(Self { pool, object_store })
    }

    pub async fn migrate(&self) -> Result<(), sqlx::migrate::MigrateError> {
        MIGRATOR.run(&self.pool).await
    }

    pub async fn verify_schema_compatibility(&self) -> Result<(), SchemaCompatibilityError> {
        let rows =
            sqlx::query("SELECT version, success, checksum FROM _sqlx_migrations ORDER BY version")
                .fetch_all(&self.pool)
                .await
                .map_err(SchemaCompatibilityError::Database)?;
        if rows.len() != MIGRATOR.migrations.len() {
            return Err(SchemaCompatibilityError::VersionSet);
        }
        for (row, expected) in rows.iter().zip(MIGRATOR.migrations.iter()) {
            let version: i64 = row.get("version");
            if version != expected.version {
                return Err(SchemaCompatibilityError::VersionSet);
            }
            if !row.get::<bool, _>("success") {
                return Err(SchemaCompatibilityError::FailedMigration(version));
            }
            if row.get::<Vec<u8>, _>("checksum") != expected.checksum.as_ref() {
                return Err(SchemaCompatibilityError::ChecksumMismatch(version));
            }
        }
        Ok(())
    }

    pub async fn readiness_check(&self) -> Result<(), sqlx::Error> {
        sqlx::query_scalar::<_, i32>("SELECT 1")
            .fetch_one(&self.pool)
            .await
            .map(|_| ())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn production_pool_configuration_is_bounded() {
        let config = PostgresRuntimeConfig::from_values([
            ("UNCIV_V3_DB_POOL_MAX".to_owned(), "12".to_owned()),
            ("UNCIV_V3_DB_POOL_MIN".to_owned(), "2".to_owned()),
            (
                "UNCIV_V3_DB_STATEMENT_TIMEOUT_MS".to_owned(),
                "20000".to_owned(),
            ),
            ("UNCIV_V3_DB_LOCK_TIMEOUT_MS".to_owned(), "2500".to_owned()),
        ])
        .unwrap();
        assert_eq!(config.max_connections, 12);
        assert_eq!(config.min_connections, 2);
        assert_eq!(config.statement_timeout_ms, 20_000);
        assert_eq!(config.lock_timeout_ms, 2_500);

        assert!(matches!(
            PostgresRuntimeConfig::from_values([(
                "UNCIV_V3_DB_POOL_MAX".to_owned(),
                "65".to_owned()
            )]),
            Err(PostgresConfigurationError::OutOfRange(..))
        ));
        assert!(matches!(
            PostgresRuntimeConfig::from_values([
                ("UNCIV_V3_DB_POOL_MAX".to_owned(), "2".to_owned()),
                ("UNCIV_V3_DB_POOL_MIN".to_owned(), "3".to_owned())
            ]),
            Err(PostgresConfigurationError::InvalidPoolBounds)
        ));
    }

    #[tokio::test]
    #[ignore = "requires an explicit UNCIV_V3_DATABASE_URL"]
    async fn exact_schema_and_session_timeouts_are_enforced() {
        let database_url = std::env::var("UNCIV_V3_DATABASE_URL").unwrap();
        let config = PostgresRuntimeConfig {
            statement_timeout_ms: 12_345,
            lock_timeout_ms: 2_345,
            ..PostgresRuntimeConfig::default()
        };
        let repository = PostgresGameRepository::connect_with_config(&database_url, config)
            .await
            .unwrap();
        repository.migrate().await.unwrap();
        repository.verify_schema_compatibility().await.unwrap();
        repository.readiness_check().await.unwrap();
        let settings = sqlx::query(
            "SELECT current_setting('statement_timeout') AS statement_timeout, current_setting('lock_timeout') AS lock_timeout",
        )
        .fetch_one(&repository.pool)
        .await
        .unwrap();
        assert_eq!(settings.get::<String, _>("statement_timeout"), "12345ms");
        assert_eq!(settings.get::<String, _>("lock_timeout"), "2345ms");
    }
}
