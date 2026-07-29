use std::time::Duration;

use serde::{Deserialize, Serialize};
use sqlx::postgres::PgListener;

use super::{NotificationHub, RevisionNotification};
use crate::{
    PROTOCOL_VERSION,
    postgres::{
        ClaimedOutboxEvent, OutboxRetryDisposition, OutboxRuntimePolicy, PostgresGameRepository,
    },
};

const SHARED_NOTIFICATION_SCHEMA_VERSION: u16 = 1;
const MAX_SHARED_NOTIFICATION_BYTES: usize = 1_024;

#[derive(Debug, Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
struct SharedNotification {
    schema_version: u16,
    event_type: String,
    protocol_version: u16,
    game_id: uuid::Uuid,
    committed_revision: u64,
    canonical_state_hash: String,
}

pub async fn start_notification_runtime(
    repository: PostgresGameRepository,
    hub: NotificationHub,
    policy: OutboxRuntimePolicy,
) -> Result<(), sqlx::Error> {
    // LISTEN must be active before a dispatcher can claim and publish. This
    // makes startup ordering explicit for the first local replica.
    let listener = repository.shared_notification_listener().await?;
    tokio::spawn(run_shared_listener(repository.clone(), hub, listener));
    tokio::spawn(run_outbox_dispatcher(repository.clone(), policy));
    tokio::spawn(run_outbox_monitor(repository, policy));
    Ok(())
}

pub(crate) async fn run_shared_listener(
    repository: PostgresGameRepository,
    hub: NotificationHub,
    mut listener: PgListener,
) {
    loop {
        let received = match listener.recv().await {
            Ok(notification) => decode_shared_notification(notification.payload()),
            Err(_) => {
                // LISTEN/NOTIFY is deliberately transient. Existing sockets
                // must reconcile after any listener gap while SQLx reconnects.
                hub.require_resync_for_all();
                metrics::counter!(
                    "unciv_v3_notification_runtime_failures_total",
                    "component" => "listener"
                )
                .increment(1);
                tracing::error!("shared notification listener disconnected");
                tokio::time::sleep(Duration::from_secs(1)).await;
                continue;
            }
        };
        let notification = match received {
            Ok(notification) => notification,
            Err(()) => {
                hub.require_resync_for_all();
                metrics::counter!(
                    "unciv_v3_notification_runtime_failures_total",
                    "component" => "payload"
                )
                .increment(1);
                tracing::warn!("shared notification payload rejected");
                continue;
            }
        };
        match repository.outbox_recipients(notification.game_id).await {
            Ok(recipients) => hub.publish(&recipients, notification).await,
            Err(_) => {
                hub.require_resync_for_all();
                metrics::counter!(
                    "unciv_v3_notification_runtime_failures_total",
                    "component" => "membership"
                )
                .increment(1);
                tracing::error!("shared notification recipients unavailable");
            }
        }
    }
}

async fn run_outbox_dispatcher(repository: PostgresGameRepository, policy: OutboxRuntimePolicy) {
    loop {
        let events = match repository.claim_outbox_batch(100).await {
            Ok(events) => events,
            Err(_) => {
                metrics::counter!(
                    "unciv_v3_notification_runtime_failures_total",
                    "component" => "outbox_claim"
                )
                .increment(1);
                tracing::error!(error_class = "database", "outbox claim failed");
                tokio::time::sleep(Duration::from_secs(1)).await;
                continue;
            }
        };
        if events.is_empty() {
            tokio::time::sleep(Duration::from_millis(250)).await;
            continue;
        }
        for event in events {
            deliver_outbox_event(&repository, event, policy).await;
        }
    }
}

async fn deliver_outbox_event(
    repository: &PostgresGameRepository,
    event: ClaimedOutboxEvent,
    policy: OutboxRuntimePolicy,
) {
    let payload_state_hash = event
        .payload
        .get("state_hash")
        .and_then(serde_json::Value::as_str)
        .filter(|hash| valid_state_hash(hash))
        .map(str::to_owned);
    let state_hash = match payload_state_hash {
        Some(hash) => Ok(hash),
        None => {
            repository
                .outbox_state_hash(event.game_id, event.revision)
                .await
        }
    };
    let result = match state_hash {
        Ok(canonical_state_hash) => encode_shared_notification(RevisionNotification {
            event_type: notification_type(&event.topic),
            protocol_version: PROTOCOL_VERSION,
            game_id: event.game_id,
            committed_revision: event.revision,
            canonical_state_hash,
        })
        .and_then(|payload| {
            if payload.len() <= MAX_SHARED_NOTIFICATION_BYTES {
                Ok(payload)
            } else {
                Err(())
            }
        }),
        Err(_) => Err(()),
    };
    match result {
        Ok(payload) => {
            if repository
                .publish_shared_notification(&payload)
                .await
                .is_err()
            {
                retry_outbox(repository, &event, policy).await;
                return;
            }
            if repository
                .acknowledge_outbox(event.id, event.claim_token)
                .await
                .is_err()
            {
                // A retry may duplicate a hint, which clients must tolerate.
                metrics::counter!(
                    "unciv_v3_notification_runtime_failures_total",
                    "component" => "outbox_ack"
                )
                .increment(1);
                tracing::error!("outbox acknowledgement failed");
            }
        }
        Err(()) => retry_outbox(repository, &event, policy).await,
    }
}

async fn retry_outbox(
    repository: &PostgresGameRepository,
    event: &ClaimedOutboxEvent,
    policy: OutboxRuntimePolicy,
) {
    metrics::counter!(
        "unciv_v3_notification_runtime_failures_total",
        "component" => "delivery"
    )
    .increment(1);
    tracing::warn!("outbox delivery failed");
    if matches!(
        repository
            .retry_outbox(
                event.id,
                event.claim_token,
                "shared notification delivery failed",
                policy.max_delivery_attempts,
            )
            .await,
        Ok(OutboxRetryDisposition::DeadLettered)
    ) {
        metrics::counter!("unciv_v3_outbox_dead_letters_total").increment(1);
        tracing::error!("outbox event dead-lettered after bounded retries");
    }
}

async fn run_outbox_monitor(repository: PostgresGameRepository, policy: OutboxRuntimePolicy) {
    let mut interval = tokio::time::interval(Duration::from_secs(30));
    interval.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
    loop {
        interval.tick().await;
        match repository.outbox_health(policy.lag_alert_after).await {
            Ok(report) => {
                metrics::gauge!("unciv_v3_outbox_pending").set(report.pending_events as f64);
                metrics::gauge!("unciv_v3_outbox_dead_letters")
                    .set(report.dead_letter_events as f64);
                metrics::gauge!("unciv_v3_outbox_oldest_pending_age")
                    .set(report.oldest_pending_age_seconds as f64);
                if report.alert {
                    tracing::error!(
                        pending = report.pending_events,
                        dead_letters = report.dead_letter_events,
                        oldest_pending_seconds = report.oldest_pending_age_seconds,
                        oldest_dead_letter_seconds = report.oldest_dead_letter_age_seconds,
                        maximum_attempts = report.maximum_attempt_count,
                        "outbox health threshold exceeded"
                    );
                }
            }
            Err(_) => {
                metrics::counter!(
                    "unciv_v3_notification_runtime_failures_total",
                    "component" => "outbox_health"
                )
                .increment(1);
                tracing::error!("outbox health query failed");
            }
        }
    }
}

fn encode_shared_notification(notification: RevisionNotification) -> Result<String, ()> {
    serde_json::to_string(&SharedNotification {
        schema_version: SHARED_NOTIFICATION_SCHEMA_VERSION,
        event_type: notification.event_type.to_owned(),
        protocol_version: notification.protocol_version,
        game_id: notification.game_id,
        committed_revision: notification.committed_revision,
        canonical_state_hash: notification.canonical_state_hash,
    })
    .map_err(|_| ())
}

fn decode_shared_notification(payload: &str) -> Result<RevisionNotification, ()> {
    if payload.len() > MAX_SHARED_NOTIFICATION_BYTES {
        return Err(());
    }
    let shared: SharedNotification = serde_json::from_str(payload).map_err(|_| ())?;
    if shared.schema_version != SHARED_NOTIFICATION_SCHEMA_VERSION
        || shared.protocol_version != PROTOCOL_VERSION
        || !valid_state_hash(&shared.canonical_state_hash)
    {
        return Err(());
    }
    let event_type = match shared.event_type.as_str() {
        "revision_committed" => "revision_committed",
        "resync_required" => "resync_required",
        _ => return Err(()),
    };
    Ok(RevisionNotification {
        event_type,
        protocol_version: shared.protocol_version,
        game_id: shared.game_id,
        committed_revision: shared.committed_revision,
        canonical_state_hash: shared.canonical_state_hash,
    })
}

fn valid_state_hash(hash: &str) -> bool {
    hash.len() == 64
        && hash
            .bytes()
            .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
}

fn notification_type(topic: &str) -> &'static str {
    if topic == "game.revision.committed" {
        "revision_committed"
    } else {
        "resync_required"
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn notification() -> RevisionNotification {
        RevisionNotification {
            event_type: "revision_committed",
            protocol_version: PROTOCOL_VERSION,
            game_id: uuid::Uuid::new_v4(),
            committed_revision: 17,
            canonical_state_hash: "ab".repeat(32),
        }
    }

    #[test]
    fn shared_notification_round_trip_is_exact_and_bounded() {
        let expected = notification();
        let encoded = encode_shared_notification(expected.clone()).unwrap();
        assert!(encoded.len() <= MAX_SHARED_NOTIFICATION_BYTES);
        assert_eq!(decode_shared_notification(&encoded).unwrap(), expected);
    }

    #[test]
    fn shared_notification_rejects_unknown_fields_versions_types_hashes_and_size() {
        let valid = encode_shared_notification(notification()).unwrap();
        let mut value: serde_json::Value = serde_json::from_str(&valid).unwrap();
        value["extra"] = serde_json::json!(true);
        assert!(decode_shared_notification(&value.to_string()).is_err());

        for (field, invalid) in [
            ("schema_version", serde_json::json!(2)),
            ("protocol_version", serde_json::json!(2)),
            ("event_type", serde_json::json!("canonical_state")),
            ("canonical_state_hash", serde_json::json!("z".repeat(64))),
            ("canonical_state_hash", serde_json::json!("AB".repeat(32))),
        ] {
            let mut value: serde_json::Value = serde_json::from_str(&valid).unwrap();
            value[field] = invalid;
            assert!(decode_shared_notification(&value.to_string()).is_err());
        }
        assert!(
            decode_shared_notification(&"x".repeat(MAX_SHARED_NOTIFICATION_BYTES + 1)).is_err()
        );
    }

    #[test]
    fn control_plane_outbox_topics_force_http_resynchronization() {
        assert_eq!(
            notification_type("game.revision.committed"),
            "revision_committed"
        );
        for topic in [
            "game.membership.changed",
            "game.lifecycle.changed",
            "game.revision.recovered",
            "game.revision.rewound",
            "game.rewind.changed",
        ] {
            assert_eq!(notification_type(topic), "resync_required");
        }
    }
}
