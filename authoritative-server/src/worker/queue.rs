use std::{sync::Arc, time::Duration};

use tokio::{
    sync::{OwnedSemaphorePermit, Semaphore},
    time::timeout,
};

const MAX_QUEUE_CAPACITY: usize = 1_024;
const MAX_QUEUE_TIMEOUT_MILLIS: u64 = 10 * 60 * 1_000;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct WorkerQueueConfig {
    pub capacity: usize,
    pub wait_timeout: Duration,
}

#[derive(Debug, Eq, PartialEq)]
pub enum WorkerQueueConfigError {
    MissingValue,
    InvalidValue,
    OutOfRange,
}

impl WorkerQueueConfig {
    pub const DEFAULT_CAPACITY: usize = 64;
    pub const DEFAULT_WAIT_TIMEOUT_MILLIS: u64 = 30_000;

    pub fn new(capacity: usize, wait_timeout: Duration) -> Result<Self, WorkerQueueConfigError> {
        if !(1..=MAX_QUEUE_CAPACITY).contains(&capacity)
            || wait_timeout.is_zero()
            || wait_timeout > Duration::from_millis(MAX_QUEUE_TIMEOUT_MILLIS)
        {
            return Err(WorkerQueueConfigError::OutOfRange);
        }
        Ok(Self {
            capacity,
            wait_timeout,
        })
    }

    pub fn default_bounded() -> Self {
        Self::new(
            Self::DEFAULT_CAPACITY,
            Duration::from_millis(Self::DEFAULT_WAIT_TIMEOUT_MILLIS),
        )
        .expect("default worker queue configuration is valid")
    }

    pub fn from_environment() -> Result<Self, WorkerQueueConfigError> {
        Self::new(
            environment_usize("UNCIV_ENGINE_WORKER_QUEUE_CAPACITY", Self::DEFAULT_CAPACITY)?,
            Duration::from_millis(environment_u64(
                "UNCIV_ENGINE_WORKER_QUEUE_TIMEOUT_MS",
                Self::DEFAULT_WAIT_TIMEOUT_MILLIS,
            )?),
        )
    }
}

fn environment_usize(name: &str, default: usize) -> Result<usize, WorkerQueueConfigError> {
    environment_value(name)?
        .map(|value| {
            value
                .parse()
                .map_err(|_| WorkerQueueConfigError::InvalidValue)
        })
        .unwrap_or(Ok(default))
}

fn environment_u64(name: &str, default: u64) -> Result<u64, WorkerQueueConfigError> {
    environment_value(name)?
        .map(|value| {
            value
                .parse()
                .map_err(|_| WorkerQueueConfigError::InvalidValue)
        })
        .unwrap_or(Ok(default))
}

fn environment_value(name: &str) -> Result<Option<String>, WorkerQueueConfigError> {
    match std::env::var(name) {
        Ok(value) if value.is_empty() => Err(WorkerQueueConfigError::MissingValue),
        Ok(value) => Ok(Some(value)),
        Err(std::env::VarError::NotPresent) => Ok(None),
        Err(std::env::VarError::NotUnicode(_)) => Err(WorkerQueueConfigError::InvalidValue),
    }
}

pub(super) struct WorkerQueue {
    admission: Arc<Semaphore>,
    execution: Arc<Semaphore>,
    wait_timeout: Duration,
}

pub(super) struct WorkerQueuePermit {
    _admission: OwnedSemaphorePermit,
    _execution: OwnedSemaphorePermit,
}

impl WorkerQueue {
    pub(super) fn new(config: WorkerQueueConfig) -> Self {
        Self {
            admission: Arc::new(Semaphore::new(config.capacity)),
            execution: Arc::new(Semaphore::new(1)),
            wait_timeout: config.wait_timeout,
        }
    }

    pub(super) async fn acquire(&self) -> Result<WorkerQueuePermit, super::WorkerClientError> {
        let admission = self
            .admission
            .clone()
            .try_acquire_owned()
            .map_err(|_| super::WorkerClientError::QueueFull)?;
        let execution = timeout(self.wait_timeout, self.execution.clone().acquire_owned())
            .await
            .map_err(|_| super::WorkerClientError::QueueTimeout)?
            .map_err(|_| super::WorkerClientError::QueueFull)?;
        Ok(WorkerQueuePermit {
            _admission: admission,
            _execution: execution,
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::worker::WorkerClientError;

    #[test]
    fn configuration_rejects_disabled_or_unbounded_values() {
        assert_eq!(
            WorkerQueueConfig::new(0, Duration::from_secs(1)),
            Err(WorkerQueueConfigError::OutOfRange),
        );
        assert_eq!(
            WorkerQueueConfig::new(1_025, Duration::from_secs(1)),
            Err(WorkerQueueConfigError::OutOfRange),
        );
        assert_eq!(
            WorkerQueueConfig::new(1, Duration::ZERO),
            Err(WorkerQueueConfigError::OutOfRange),
        );
        assert_eq!(
            WorkerQueueConfig::new(1, Duration::from_secs(601)),
            Err(WorkerQueueConfigError::OutOfRange),
        );
    }

    #[tokio::test]
    async fn admission_is_bounded_and_execution_is_serialized() {
        let queue = Arc::new(WorkerQueue::new(
            WorkerQueueConfig::new(2, Duration::from_secs(1)).unwrap(),
        ));
        let first = queue.acquire().await.unwrap();
        let waiting_queue = queue.clone();
        let second = tokio::spawn(async move { waiting_queue.acquire().await });
        tokio::task::yield_now().await;

        assert!(matches!(
            queue.acquire().await,
            Err(WorkerClientError::QueueFull)
        ));
        drop(first);
        assert!(second.await.unwrap().is_ok());
    }

    #[tokio::test]
    async fn queued_work_has_an_independent_deadline() {
        let queue = WorkerQueue::new(WorkerQueueConfig::new(2, Duration::from_millis(10)).unwrap());
        let _first = queue.acquire().await.unwrap();

        assert!(matches!(
            queue.acquire().await,
            Err(WorkerClientError::QueueTimeout)
        ));
    }
}
