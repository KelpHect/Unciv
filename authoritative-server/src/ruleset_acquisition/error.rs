#[derive(Debug, thiserror::Error)]
pub enum AcquisitionError {
    #[error("invalid acquisition policy")]
    InvalidPolicy,
    #[error("ruleset download was rejected")]
    DownloadRejected,
    #[error("ruleset archive was rejected")]
    ArchiveRejected,
    #[error("ruleset staging failed")]
    Staging,
    #[error("packaged worker validation failed")]
    WorkerValidation,
    #[error("ruleset manifest registration failed")]
    Registration,
    #[error("atomic ruleset activation failed")]
    Activation,
}

impl AcquisitionError {
    pub(super) fn io(_: std::io::Error) -> Self {
        Self::Staging
    }
}
