use std::{
    collections::HashMap,
    sync::{Arc, Mutex},
};

use serde::Serialize;
use tokio::sync::broadcast;
use uuid::Uuid;

mod dispatcher;

#[cfg(test)]
pub(crate) use dispatcher::run_shared_listener;
pub use dispatcher::start_notification_runtime;

#[derive(Clone, Debug, Serialize, PartialEq, Eq, utoipa::ToSchema)]
pub struct RevisionNotification {
    #[serde(rename = "type")]
    pub event_type: &'static str,
    pub protocol_version: u16,
    pub game_id: Uuid,
    pub committed_revision: u64,
    pub canonical_state_hash: String,
}

#[derive(Clone, Copy, Debug, Serialize, PartialEq, Eq, utoipa::ToSchema)]
pub struct ResyncRequiredNotification {
    #[serde(rename = "type")]
    pub event_type: &'static str,
    pub protocol_version: u16,
}

impl Default for ResyncRequiredNotification {
    fn default() -> Self {
        Self {
            event_type: "resync_required",
            protocol_version: crate::PROTOCOL_VERSION,
        }
    }
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
    sender: broadcast::Sender<NotificationDelivery>,
    active_connections: usize,
}

pub struct NotificationSubscription {
    hub: NotificationHub,
    account_id: Uuid,
    receiver: broadcast::Receiver<NotificationDelivery>,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum NotificationDelivery {
    Revision(RevisionNotification),
    ResyncRequired,
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
            metrics::counter!(
                "unciv_v3_websocket_admission_rejections_total",
                "scope" => "global"
            )
            .increment(1);
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
            metrics::counter!(
                "unciv_v3_websocket_admission_rejections_total",
                "scope" => "account"
            )
            .increment(1);
            return Err(NotificationAdmissionError::AccountLimit);
        }
        channel.active_connections += 1;
        let receiver = channel.sender.subscribe();
        state.active_connections += 1;
        metrics::gauge!("unciv_v3_websocket_connections").set(state.active_connections as f64);
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
                let _ = channel
                    .sender
                    .send(NotificationDelivery::Revision(notification.clone()));
            }
        }
    }

    pub fn require_resync_for_all(&self) {
        let state = self.inner.lock().expect("notification hub lock poisoned");
        for channel in state.accounts.values() {
            let _ = channel.sender.send(NotificationDelivery::ResyncRequired);
        }
    }
}

impl NotificationSubscription {
    pub async fn recv(&mut self) -> Result<NotificationDelivery, broadcast::error::RecvError> {
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
        metrics::gauge!("unciv_v3_websocket_connections").set(state.active_connections as f64);
        if remove_account {
            state.accounts.remove(&self.account_id);
        }
    }
}

#[cfg(test)]
mod tests {
    use std::time::Duration;

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

        assert_eq!(
            member_rx.recv().await.unwrap(),
            NotificationDelivery::Revision(notification.clone())
        );
        assert_eq!(
            member_rx.recv().await.unwrap(),
            NotificationDelivery::Revision(notification)
        );
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
    fn transport_reset_reaches_every_live_account_and_no_released_account() {
        let hub = NotificationHub::with_connection_limits(3, 2);
        let mut first = hub.try_subscribe(Uuid::new_v4()).unwrap();
        let mut second = hub.try_subscribe(Uuid::new_v4()).unwrap();
        let released = hub.try_subscribe(Uuid::new_v4()).unwrap();
        drop(released);

        hub.require_resync_for_all();

        assert_eq!(
            first.receiver.try_recv().unwrap(),
            NotificationDelivery::ResyncRequired
        );
        assert_eq!(
            second.receiver.try_recv().unwrap(),
            NotificationDelivery::ResyncRequired
        );
    }
}
