use std::time::Duration;

const MAX_DEADLINE_MILLIS: u64 = 10 * 60 * 1_000;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct WorkerDeadlines {
    pub connect: Duration,
    pub write: Duration,
    pub read: Duration,
    pub total: Duration,
}

#[derive(Debug, Eq, PartialEq)]
pub enum WorkerDeadlineConfigError {
    MissingValue,
    InvalidValue,
    OutOfRange,
}

impl WorkerDeadlines {
    pub const DEFAULT_CONNECT_MILLIS: u64 = 2_000;
    pub const DEFAULT_WRITE_MILLIS: u64 = 5_000;
    pub const DEFAULT_READ_MILLIS: u64 = 30_000;
    pub const DEFAULT_TOTAL_MILLIS: u64 = 30_000;

    pub fn new(
        connect: Duration,
        write: Duration,
        read: Duration,
        total: Duration,
    ) -> Result<Self, WorkerDeadlineConfigError> {
        let deadlines = Self {
            connect,
            write,
            read,
            total,
        };
        for value in [connect, write, read, total] {
            if value.is_zero() || value > Duration::from_millis(MAX_DEADLINE_MILLIS) {
                return Err(WorkerDeadlineConfigError::OutOfRange);
            }
        }
        Ok(deadlines)
    }

    pub fn uniform(timeout: Duration) -> Self {
        Self::new(timeout, timeout, timeout, timeout)
            .expect("worker timeout must be nonzero and at most ten minutes")
    }

    pub fn from_environment() -> Result<Self, WorkerDeadlineConfigError> {
        Self::new(
            environment_duration(
                "UNCIV_ENGINE_WORKER_CONNECT_TIMEOUT_MS",
                Self::DEFAULT_CONNECT_MILLIS,
            )?,
            environment_duration(
                "UNCIV_ENGINE_WORKER_WRITE_TIMEOUT_MS",
                Self::DEFAULT_WRITE_MILLIS,
            )?,
            environment_duration(
                "UNCIV_ENGINE_WORKER_READ_TIMEOUT_MS",
                Self::DEFAULT_READ_MILLIS,
            )?,
            environment_duration(
                "UNCIV_ENGINE_WORKER_TOTAL_TIMEOUT_MS",
                Self::DEFAULT_TOTAL_MILLIS,
            )?,
        )
    }
}

fn environment_duration(
    name: &str,
    default_millis: u64,
) -> Result<Duration, WorkerDeadlineConfigError> {
    let value = match std::env::var(name) {
        Ok(value) => value,
        Err(std::env::VarError::NotPresent) => return Ok(Duration::from_millis(default_millis)),
        Err(std::env::VarError::NotUnicode(_)) => {
            return Err(WorkerDeadlineConfigError::InvalidValue);
        }
    };
    if value.is_empty() {
        return Err(WorkerDeadlineConfigError::MissingValue);
    }
    let millis = value
        .parse::<u64>()
        .map_err(|_| WorkerDeadlineConfigError::InvalidValue)?;
    Ok(Duration::from_millis(millis))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn deadlines_reject_zero_and_excessive_values() {
        assert_eq!(
            WorkerDeadlines::new(
                Duration::ZERO,
                Duration::from_secs(1),
                Duration::from_secs(1),
                Duration::from_secs(1),
            ),
            Err(WorkerDeadlineConfigError::OutOfRange),
        );
        assert_eq!(
            WorkerDeadlines::new(
                Duration::from_secs(1),
                Duration::from_secs(1),
                Duration::from_secs(601),
                Duration::from_secs(601),
            ),
            Err(WorkerDeadlineConfigError::OutOfRange),
        );
        assert!(
            WorkerDeadlines::new(
                Duration::from_secs(2),
                Duration::from_secs(2),
                Duration::from_secs(2),
                Duration::from_secs(1),
            )
            .is_ok()
        );
    }

    #[test]
    fn defaults_retain_the_bounded_thirty_second_total() {
        let deadlines = WorkerDeadlines::new(
            Duration::from_millis(WorkerDeadlines::DEFAULT_CONNECT_MILLIS),
            Duration::from_millis(WorkerDeadlines::DEFAULT_WRITE_MILLIS),
            Duration::from_millis(WorkerDeadlines::DEFAULT_READ_MILLIS),
            Duration::from_millis(WorkerDeadlines::DEFAULT_TOTAL_MILLIS),
        )
        .unwrap();
        assert_eq!(deadlines.connect, Duration::from_secs(2));
        assert_eq!(deadlines.write, Duration::from_secs(5));
        assert_eq!(deadlines.read, Duration::from_secs(30));
        assert_eq!(deadlines.total, Duration::from_secs(30));
    }
}
