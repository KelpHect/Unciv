use std::{
    collections::HashMap,
    sync::{Arc, Mutex},
    time::Duration,
};

use serde::Serialize;
use tokio::sync::broadcast;
use uuid::Uuid;

use crate::postgres::PostgresGameRepository;

#[derive(Clone, Debug, Serialize, PartialEq, Eq, utoipa::ToSchema)]
pub struct RevisionNotification {
    #[serde(rename = "type")]
    pub event_type: &'static str,
    pub protocol_version: u16,
    pub game_id: Uuid,
    pub committed_revision: u64,
    pub canonical_state_hash: String,
}

const DEFAULT_GLOBAL_CONNECTION_LIMIT: usize = 1_024;
const DEFAULT_ACCOUNT_CONNECTION_LIMIT: usize = 4;

#[derive(Clone)]
pub struct NotificationHub {
    inner: Arc<Mutex<NotificationHubState>>,
    global_connection_limit: usize,
    account_connection_limit: usize,
}

struct NotificationHubState {
    accounts: HashMap<Uuid, AccountChannel>,
    active_connections: usize,
}

struct AccountChannel {
    sender: broadcast::Sender<RevisionNotification>,
    active_connections: usize,
}

pub struct NotificationSubscription {
    hub: NotificationHub,
    account_id: Uuid,
    receiver: broadcast::Receiver<RevisionNotification>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum NotificationAdmissionError {
    GlobalLimit,
    AccountLimit,
}

impl Default for NotificationHub {
    fn default() -> Self {
        Self::with_connection_limits(
            DEFAULT_GLOBAL_CONNECTION_LIMIT,
            DEFAULT_ACCOUNT_CONNECTION_LIMIT,
        )
    }
}

impl NotificationHub {
    pub fn with_connection_limits(
        global_connection_limit: usize,
        account_connection_limit: usize,
    ) -> Self {
        assert!(global_connection_limit > 0);
        assert!(account_connection_limit > 0);
        assert!(account_connection_limit <= global_connection_limit);
        Self {
            inner: Arc::new(Mutex::new(NotificationHubState {
                accounts: HashMap::new(),
                active_connections: 0,
            })),
            global_connection_limit,
            account_connection_limit,
        }
    }

    pub fn try_subscribe(
        &self,
        account_id: Uuid,
    ) -> Result<NotificationSubscription, NotificationAdmissionError> {
        let mut state = self.inner.lock().expect("notification hub lock poisoned");
        if state.active_connections >= self.global_connection_limit {
            return Err(NotificationAdmissionError::GlobalLimit);
        }
        let channel = state
            .accounts
            .entry(account_id)
            .or_insert_with(|| AccountChannel {
                sender: broadcast::channel(64).0,
                active_connections: 0,
            });
        if channel.active_connections >= self.account_connection_limit {
            return Err(NotificationAdmissionError::AccountLimit);
        }
        channel.active_connections += 1;
        let receiver = channel.sender.subscribe();
        state.active_connections += 1;
        Ok(NotificationSubscription {
            hub: self.clone(),
            account_id,
            receiver,
        })
    }

    pub async fn publish(&self, recipients: &[Uuid], notification: RevisionNotification) {
        let state = self.inner.lock().expect("notification hub lock poisoned");
        for account_id in recipients {
            if let Some(channel) = state.accounts.get(account_id) {
                // No receiver means the client is offline. The notification is
                // only a hint; authenticated HTTP reconciliation is authoritative.
                let _ = channel.sender.send(notification.clone());
            }
        }
    }
}

impl NotificationSubscription {
    pub async fn recv(&mut self) -> Result<RevisionNotification, broadcast::error::RecvError> {
        self.receiver.recv().await
    }
}

impl Drop for NotificationSubscription {
    fn drop(&mut self) {
        let mut state = self
            .hub
            .inner
            .lock()
            .expect("notification hub lock poisoned");
        let remove_account = if let Some(channel) = state.accounts.get_mut(&self.account_id) {
            channel.active_connections = channel
                .active_connections
                .checked_sub(1)
                .expect("notification account connection count underflow");
            channel.active_connections == 0
        } else {
            false
        };
        state.active_connections = state
            .active_connections
            .checked_sub(1)
            .expect("notification global connection count underflow");
        if remove_account {
            state.accounts.remove(&self.account_id);
        }
    }
}

pub async fn run_outbox_dispatcher(repository: PostgresGameRepository, hub: NotificationHub) {
    loop {
        let events = match repository.claim_outbox_batch(100).await {
            Ok(events) => events,
            Err(error) => {
                eprintln!("authoritative outbox claim failed: {error}");
                tokio::time::sleep(Duration::from_secs(1)).await;
                continue;
            }
        };
        if events.is_empty() {
            tokio::time::sleep(Duration::from_millis(250)).await;
            continue;
        }
        for event in events {
            let payload_state_hash = event
                .payload
                .get("state_hash")
                .and_then(serde_json::Value::as_str)
                .filter(|hash| hash.len() == 64)
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
                Ok(canonical_state_hash) => repository
                    .outbox_recipients(event.game_id)
                    .await
                    .map(|recipients| (recipients, canonical_state_hash)),
                Err(error) => Err(error),
            };
            match result {
                Ok((recipients, canonical_state_hash)) => {
                    hub.publish(
                        &recipients,
                        RevisionNotification {
                            event_type: notification_type(&event.topic),
                            protocol_version: crate::PROTOCOL_VERSION,
                            game_id: event.game_id,
                            committed_revision: event.revision,
                            canonical_state_hash,
                        },
                    )
                    .await;
                    if let Err(error) = repository
                        .acknowledge_outbox(event.id, event.claim_token)
                        .await
                    {
                        // A retry may duplicate a hint, which clients must tolerate.
                        eprintln!("authoritative outbox acknowledgement failed: {error}");
                    }
                }
                Err(error) => {
                    eprintln!("authoritative outbox delivery failed: {error}");
                    let _ = repository
                        .retry_outbox(event.id, event.claim_token, &error.to_string())
                        .await;
                }
            }
        }
    }
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

    #[tokio::test]
    async fn hub_delivers_only_to_subscribed_accounts_and_allows_duplicates() {
        let hub = NotificationHub::default();
        let member = Uuid::new_v4();
        let outsider = Uuid::new_v4();
        let mut member_rx = hub.try_subscribe(member).unwrap();
        let mut outsider_rx = hub.try_subscribe(outsider).unwrap();
        let notification = RevisionNotification {
            event_type: "revision_committed",
            protocol_version: crate::PROTOCOL_VERSION,
            game_id: Uuid::new_v4(),
            committed_revision: 7,
            canonical_state_hash: "a".repeat(64),
        };

        hub.publish(&[member], notification.clone()).await;
        hub.publish(&[member], notification.clone()).await;

        assert_eq!(member_rx.recv().await.unwrap(), notification);
        assert_eq!(member_rx.recv().await.unwrap(), notification);
        assert!(
            tokio::time::timeout(Duration::from_millis(10), outsider_rx.recv())
                .await
                .is_err()
        );
    }

    #[test]
    fn connection_admission_is_bounded_per_account_and_globally_and_releases_exactly() {
        let hub = NotificationHub::with_connection_limits(3, 2);
        let first_account = Uuid::new_v4();
        let second_account = Uuid::new_v4();
        let first = hub.try_subscribe(first_account).unwrap();
        let second = hub.try_subscribe(first_account).unwrap();
        assert!(matches!(
            hub.try_subscribe(first_account),
            Err(NotificationAdmissionError::AccountLimit),
        ));
        let third = hub.try_subscribe(second_account).unwrap();
        assert!(matches!(
            hub.try_subscribe(Uuid::new_v4()),
            Err(NotificationAdmissionError::GlobalLimit),
        ));

        drop(second);
        let replacement = hub.try_subscribe(first_account).unwrap();
        drop(first);
        drop(third);
        drop(replacement);

        let state = hub.inner.lock().unwrap();
        assert_eq!(state.active_connections, 0);
        assert!(state.accounts.is_empty());
    }

    #[test]
    fn control_plane_outbox_topics_force_http_resynchronization() {
        assert_eq!(
            notification_type("game.revision.committed"),
            "revision_committed"
        );
        assert_eq!(
            notification_type("game.membership.changed"),
            "resync_required"
        );
        assert_eq!(
            notification_type("game.lifecycle.changed"),
            "resync_required"
        );
        assert_eq!(
            notification_type("game.revision.recovered"),
            "resync_required"
        );
    }
}
