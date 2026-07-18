use std::{collections::HashMap, sync::Arc, time::Duration};

use serde::Serialize;
use tokio::sync::{RwLock, broadcast};
use uuid::Uuid;

use crate::postgres::PostgresGameRepository;

#[derive(Clone, Debug, Serialize, PartialEq, Eq)]
pub struct RevisionNotification {
    #[serde(rename = "type")]
    pub event_type: &'static str,
    pub protocol_version: u16,
    pub game_id: Uuid,
    pub committed_revision: u64,
    pub canonical_state_hash: String,
}

#[derive(Clone, Default)]
pub struct NotificationHub {
    accounts: Arc<RwLock<HashMap<Uuid, broadcast::Sender<RevisionNotification>>>>,
}

impl NotificationHub {
    pub async fn subscribe(&self, account_id: Uuid) -> broadcast::Receiver<RevisionNotification> {
        let mut accounts = self.accounts.write().await;
        accounts
            .entry(account_id)
            .or_insert_with(|| broadcast::channel(64).0)
            .subscribe()
    }

    pub async fn publish(&self, recipients: &[Uuid], notification: RevisionNotification) {
        let accounts = self.accounts.read().await;
        for account_id in recipients {
            if let Some(sender) = accounts.get(account_id) {
                // No receiver means the client is offline. The notification is
                // only a hint; authenticated HTTP reconciliation is authoritative.
                let _ = sender.send(notification.clone());
            }
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
            let state_hash = event
                .payload
                .get("state_hash")
                .and_then(serde_json::Value::as_str)
                .filter(|hash| hash.len() == 64)
                .map(str::to_owned);
            let result = match state_hash {
                Some(canonical_state_hash) => repository
                    .outbox_recipients(event.game_id)
                    .await
                    .map(|recipients| (recipients, canonical_state_hash)),
                None => Err(crate::CommitError::Storage),
            };
            match result {
                Ok((recipients, canonical_state_hash)) => {
                    hub.publish(
                        &recipients,
                        RevisionNotification {
                            event_type: "revision_committed",
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

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn hub_delivers_only_to_subscribed_accounts_and_allows_duplicates() {
        let hub = NotificationHub::default();
        let member = Uuid::new_v4();
        let outsider = Uuid::new_v4();
        let mut member_rx = hub.subscribe(member).await;
        let mut outsider_rx = hub.subscribe(outsider).await;
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
        assert!(outsider_rx.try_recv().is_err());
    }
}
