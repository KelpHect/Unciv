use std::{
    sync::Mutex,
    time::{Duration, Instant},
};

const MAX_FAILURE_THRESHOLD: u32 = 100;
const MAX_OPEN_MILLIS: u64 = 10 * 60 * 1_000;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct WorkerCircuitBreakerConfig {
    pub failure_threshold: u32,
    pub open_duration: Duration,
}

#[derive(Debug, Eq, PartialEq)]
pub enum WorkerCircuitBreakerConfigError {
    MissingValue,
    InvalidValue,
    OutOfRange,
}

impl WorkerCircuitBreakerConfig {
    pub const DEFAULT_FAILURE_THRESHOLD: u32 = 3;
    pub const DEFAULT_OPEN_MILLIS: u64 = 5_000;

    pub fn new(
        failure_threshold: u32,
        open_duration: Duration,
    ) -> Result<Self, WorkerCircuitBreakerConfigError> {
        if !(1..=MAX_FAILURE_THRESHOLD).contains(&failure_threshold)
            || open_duration.is_zero()
            || open_duration > Duration::from_millis(MAX_OPEN_MILLIS)
        {
            return Err(WorkerCircuitBreakerConfigError::OutOfRange);
        }
        Ok(Self {
            failure_threshold,
            open_duration,
        })
    }

    pub fn default_bounded() -> Self {
        Self::new(
            Self::DEFAULT_FAILURE_THRESHOLD,
            Duration::from_millis(Self::DEFAULT_OPEN_MILLIS),
        )
        .expect("default worker circuit breaker configuration is valid")
    }

    pub fn from_environment() -> Result<Self, WorkerCircuitBreakerConfigError> {
        Self::new(
            environment_u32(
                "UNCIV_ENGINE_WORKER_CIRCUIT_FAILURE_THRESHOLD",
                Self::DEFAULT_FAILURE_THRESHOLD,
            )?,
            Duration::from_millis(environment_u64(
                "UNCIV_ENGINE_WORKER_CIRCUIT_OPEN_MS",
                Self::DEFAULT_OPEN_MILLIS,
            )?),
        )
    }
}

fn environment_u32(name: &str, default: u32) -> Result<u32, WorkerCircuitBreakerConfigError> {
    let value = environment_value(name)?;
    match value {
        Some(value) => value
            .parse()
            .map_err(|_| WorkerCircuitBreakerConfigError::InvalidValue),
        None => Ok(default),
    }
}

fn environment_u64(name: &str, default: u64) -> Result<u64, WorkerCircuitBreakerConfigError> {
    let value = environment_value(name)?;
    match value {
        Some(value) => value
            .parse()
            .map_err(|_| WorkerCircuitBreakerConfigError::InvalidValue),
        None => Ok(default),
    }
}

fn environment_value(name: &str) -> Result<Option<String>, WorkerCircuitBreakerConfigError> {
    match std::env::var(name) {
        Ok(value) if value.is_empty() => Err(WorkerCircuitBreakerConfigError::MissingValue),
        Ok(value) => Ok(Some(value)),
        Err(std::env::VarError::NotPresent) => Ok(None),
        Err(std::env::VarError::NotUnicode(_)) => {
            Err(WorkerCircuitBreakerConfigError::InvalidValue)
        }
    }
}

pub(super) struct WorkerCircuitBreaker {
    config: WorkerCircuitBreakerConfig,
    state: Mutex<CircuitState>,
}

#[derive(Default)]
struct CircuitState {
    consecutive_failures: u32,
    open_until: Option<Instant>,
    probe_in_flight: bool,
}

pub(super) struct CircuitPermit {
    recovery_probe: bool,
}

impl WorkerCircuitBreaker {
    pub(super) fn new(config: WorkerCircuitBreakerConfig) -> Self {
        Self {
            config,
            state: Mutex::new(CircuitState::default()),
        }
    }

    pub(super) fn acquire(&self) -> Result<CircuitPermit, super::WorkerClientError> {
        let mut state = self
            .state
            .lock()
            .map_err(|_| super::WorkerClientError::CircuitOpen)?;
        let now = Instant::now();
        let recovery_probe = match state.open_until {
            Some(open_until) if now < open_until => {
                return Err(super::WorkerClientError::CircuitOpen);
            }
            Some(_) if state.probe_in_flight => {
                return Err(super::WorkerClientError::CircuitOpen);
            }
            Some(_) => {
                state.probe_in_flight = true;
                true
            }
            None => false,
        };
        Ok(CircuitPermit { recovery_probe })
    }

    pub(super) fn record_responsive(&self, _permit: CircuitPermit) {
        if let Ok(mut state) = self.state.lock() {
            state.consecutive_failures = 0;
            state.open_until = None;
            state.probe_in_flight = false;
        }
    }

    pub(super) fn record_failure(&self, permit: CircuitPermit) {
        if let Ok(mut state) = self.state.lock() {
            state.probe_in_flight = false;
            state.consecutive_failures = state.consecutive_failures.saturating_add(1);
            if permit.recovery_probe || state.consecutive_failures >= self.config.failure_threshold
            {
                state.open_until = Some(Instant::now() + self.config.open_duration);
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::worker::WorkerClientError;

    #[test]
    fn configuration_rejects_disabled_or_unbounded_values() {
        assert_eq!(
            WorkerCircuitBreakerConfig::new(0, Duration::from_secs(1)),
            Err(WorkerCircuitBreakerConfigError::OutOfRange),
        );
        assert_eq!(
            WorkerCircuitBreakerConfig::new(1, Duration::ZERO),
            Err(WorkerCircuitBreakerConfigError::OutOfRange),
        );
        assert_eq!(
            WorkerCircuitBreakerConfig::new(101, Duration::from_secs(1)),
            Err(WorkerCircuitBreakerConfigError::OutOfRange),
        );
        assert_eq!(
            WorkerCircuitBreakerConfig::new(1, Duration::from_secs(601)),
            Err(WorkerCircuitBreakerConfigError::OutOfRange),
        );
    }

    #[test]
    fn threshold_opens_and_only_one_recovery_probe_can_run() {
        let breaker = WorkerCircuitBreaker::new(
            WorkerCircuitBreakerConfig::new(2, Duration::from_millis(5)).unwrap(),
        );
        breaker.record_failure(breaker.acquire().unwrap());
        breaker.record_failure(breaker.acquire().unwrap());
        assert!(matches!(
            breaker.acquire(),
            Err(WorkerClientError::CircuitOpen)
        ));

        std::thread::sleep(Duration::from_millis(10));
        let probe = breaker.acquire().unwrap();
        assert!(probe.recovery_probe);
        assert!(matches!(
            breaker.acquire(),
            Err(WorkerClientError::CircuitOpen)
        ));
        breaker.record_responsive(probe);
        assert!(!breaker.acquire().unwrap().recovery_probe);
    }

    #[test]
    fn failed_recovery_probe_reopens_for_the_complete_cooldown() {
        let breaker = WorkerCircuitBreaker::new(
            WorkerCircuitBreakerConfig::new(1, Duration::from_millis(5)).unwrap(),
        );
        breaker.record_failure(breaker.acquire().unwrap());
        std::thread::sleep(Duration::from_millis(10));
        breaker.record_failure(breaker.acquire().unwrap());
        assert!(matches!(
            breaker.acquire(),
            Err(WorkerClientError::CircuitOpen)
        ));
    }
}
