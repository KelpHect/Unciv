use crate::postgres::{PostgresGameRepository, SnapshotMaintenanceConfig};

/// Starts the opt-in/Lockwell-backed snapshot maintenance loop. The loop has
/// no authority over gameplay: it only moves already-committed immutable bytes
/// after verification and leaves checkpoints required for bounded replay.
pub fn start(repository: PostgresGameRepository, config: SnapshotMaintenanceConfig) {
    tokio::spawn(async move {
        let mut interval = tokio::time::interval(config.interval);
        interval.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
        // Do not add archival latency to API startup or the first gameplay call.
        interval.tick().await;
        loop {
            interval.tick().await;
            let started = std::time::Instant::now();
            match repository
                .run_snapshot_maintenance_once(config.clone())
                .await
            {
                Ok(report) => {
                    tracing::info!(
                        games_scanned = report.games_scanned,
                        games_over_storage_budget = report.games_over_storage_budget,
                        archived_payloads = report.archived_payloads,
                        delta_payloads = report.delta_payloads,
                        bytes_archived = report.bytes_archived,
                        postgres_bytes = report.postgres_bytes,
                        archive_bytes = report.archive_bytes,
                        budget_exceeded = report.budget_exceeded,
                        archive_quota_exceeded = report.archive_quota_exceeded,
                        game_storage_budget_exceeded = report.game_storage_budget_exceeded,
                        elapsed_ms = started.elapsed().as_millis() as u64,
                        "snapshot maintenance pass completed"
                    );
                    if report.budget_exceeded {
                        tracing::warn!(
                            postgres_bytes = report.postgres_bytes,
                            "snapshot storage remains above budget because protected checkpoints were retained"
                        );
                    }
                    if report.archive_quota_exceeded {
                        tracing::warn!(
                            archive_bytes = report.archive_bytes,
                            "snapshot archive quota reached; cold payload archival is paused"
                        );
                    }
                    if report.game_storage_budget_exceeded {
                        tracing::warn!(
                            games_over_storage_budget = report.games_over_storage_budget,
                            "one or more matches exceed their per-match storage budget; archival paused for those games"
                        );
                    }
                }
                Err(error) => {
                    metrics::counter!("unciv_v3_snapshot_archive_failures_total").increment(1);
                    tracing::warn!(error = %error, "snapshot maintenance pass failed closed");
                }
            }
        }
    });
}
