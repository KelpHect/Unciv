use super::*;

use std::time::Duration;

use tokio::time::{Instant, MissedTickBehavior};
use unciv_authoritative_server::notifications::{
    NotificationAdmissionError, NotificationSubscription,
};

const MAX_WEBSOCKET_MESSAGE_BYTES: usize = 4 * 1024;
const MAX_WEBSOCKET_WRITE_BUFFER_BYTES: usize = 64 * 1024;
const MIN_GLOBAL_CONNECTION_LIMIT: usize = 1;
const MAX_GLOBAL_CONNECTION_LIMIT: usize = 100_000;
const MIN_ACCOUNT_CONNECTION_LIMIT: usize = 1;
const MAX_ACCOUNT_CONNECTION_LIMIT: usize = 32;
const MIN_HEARTBEAT_SECONDS: u64 = 5;
const MAX_HEARTBEAT_SECONDS: u64 = 300;
const MIN_IDLE_SECONDS: u64 = 15;
const MAX_IDLE_SECONDS: u64 = 900;
const MIN_WRITE_TIMEOUT_SECONDS: u64 = 1;
const MAX_WRITE_TIMEOUT_SECONDS: u64 = 30;

#[derive(Clone, Copy, Debug)]
pub(super) struct WebSocketRuntimePolicy {
    pub(super) global_connection_limit: usize,
    pub(super) account_connection_limit: usize,
    heartbeat_interval: Duration,
    idle_timeout: Duration,
    write_timeout: Duration,
}

impl WebSocketRuntimePolicy {
    const DEFAULT_GLOBAL_CONNECTION_LIMIT: usize = 1_024;
    const DEFAULT_ACCOUNT_CONNECTION_LIMIT: usize = 4;
    const DEFAULT_HEARTBEAT_SECONDS: u64 = 30;
    const DEFAULT_IDLE_SECONDS: u64 = 90;
    const DEFAULT_WRITE_TIMEOUT_SECONDS: u64 = 5;

    pub(super) fn from_environment() -> Result<Self, &'static str> {
        Self::from_values(
            environment_number(
                "UNCIV_V3_WS_MAX_CONNECTIONS",
                Self::DEFAULT_GLOBAL_CONNECTION_LIMIT,
            )?,
            environment_number(
                "UNCIV_V3_WS_MAX_CONNECTIONS_PER_ACCOUNT",
                Self::DEFAULT_ACCOUNT_CONNECTION_LIMIT,
            )?,
            environment_number(
                "UNCIV_V3_WS_HEARTBEAT_SECONDS",
                Self::DEFAULT_HEARTBEAT_SECONDS,
            )?,
            environment_number("UNCIV_V3_WS_IDLE_SECONDS", Self::DEFAULT_IDLE_SECONDS)?,
            environment_number(
                "UNCIV_V3_WS_WRITE_TIMEOUT_SECONDS",
                Self::DEFAULT_WRITE_TIMEOUT_SECONDS,
            )?,
        )
    }

    fn from_values(
        global_connection_limit: usize,
        account_connection_limit: usize,
        heartbeat_seconds: u64,
        idle_seconds: u64,
        write_timeout_seconds: u64,
    ) -> Result<Self, &'static str> {
        if !(MIN_GLOBAL_CONNECTION_LIMIT..=MAX_GLOBAL_CONNECTION_LIMIT)
            .contains(&global_connection_limit)
        {
            return Err("WebSocket global connection limit is outside bounds");
        }
        if !(MIN_ACCOUNT_CONNECTION_LIMIT..=MAX_ACCOUNT_CONNECTION_LIMIT)
            .contains(&account_connection_limit)
            || account_connection_limit > global_connection_limit
        {
            return Err("WebSocket account connection limit is outside bounds");
        }
        if !(MIN_HEARTBEAT_SECONDS..=MAX_HEARTBEAT_SECONDS).contains(&heartbeat_seconds) {
            return Err("WebSocket heartbeat interval is outside bounds");
        }
        if !(MIN_IDLE_SECONDS..=MAX_IDLE_SECONDS).contains(&idle_seconds)
            || idle_seconds < heartbeat_seconds.saturating_mul(2)
        {
            return Err("WebSocket idle timeout is outside bounds");
        }
        if !(MIN_WRITE_TIMEOUT_SECONDS..=MAX_WRITE_TIMEOUT_SECONDS).contains(&write_timeout_seconds)
        {
            return Err("WebSocket write timeout is outside bounds");
        }
        Ok(Self {
            global_connection_limit,
            account_connection_limit,
            heartbeat_interval: Duration::from_secs(heartbeat_seconds),
            idle_timeout: Duration::from_secs(idle_seconds),
            write_timeout: Duration::from_secs(write_timeout_seconds),
        })
    }
}

#[utoipa::path(
    get,
    path = "/api/v3/notifications",
    security(("bearer_auth" = [])),
    responses(
        (status = 101, description = "WebSocket revision-hint stream", body = unciv_authoritative_server::notifications::RevisionNotification),
        (status = 401, body = ErrorResponse)
    )
)]
pub(super) async fn websocket_notifications(
    websocket: WebSocketUpgrade,
    State(state): State<AppState>,
    headers: HeaderMap,
) -> Result<Response, ApiError> {
    let actor = authenticated_account(&state, &headers).await?;
    let subscription =
        state
            .notifications
            .try_subscribe(actor.id)
            .map_err(|error| match error {
                NotificationAdmissionError::GlobalLimit
                | NotificationAdmissionError::AccountLimit => ApiError::rate_limited(1),
            })?;
    let policy = state.websocket_policy;
    Ok(websocket
        .read_buffer_size(MAX_WEBSOCKET_MESSAGE_BYTES)
        .write_buffer_size(1_024)
        .max_write_buffer_size(MAX_WEBSOCKET_WRITE_BUFFER_BYTES)
        .max_message_size(MAX_WEBSOCKET_MESSAGE_BYTES)
        .max_frame_size(MAX_WEBSOCKET_MESSAGE_BYTES)
        .on_upgrade(move |socket| serve_websocket(socket, subscription, policy)))
}

pub(super) async fn serve_websocket(
    socket: WebSocket,
    subscription: NotificationSubscription,
    policy: WebSocketRuntimePolicy,
) {
    let (sender, incoming) = socket.split();
    serve_websocket_parts(sender, incoming, subscription, policy).await;
}

async fn serve_websocket_parts<S, R, E>(
    mut sender: S,
    mut incoming: R,
    mut subscription: NotificationSubscription,
    policy: WebSocketRuntimePolicy,
) where
    S: futures_util::Sink<Message> + Unpin,
    R: futures_util::Stream<Item = Result<Message, E>> + Unpin,
{
    let mut heartbeat = tokio::time::interval(policy.heartbeat_interval);
    heartbeat.set_missed_tick_behavior(MissedTickBehavior::Delay);
    let mut last_peer_activity = Instant::now();
    let mut ping_sequence = 0_u64;
    loop {
        tokio::select! {
            notification = subscription.recv() => match notification {
                Ok(notification) => {
                    let payload = serde_json::to_string(&notification)
                        .expect("revision notification is serializable");
                    if send_with_deadline(
                        &mut sender,
                        Message::Text(payload.into()),
                        policy.write_timeout,
                    ).await.is_err() {
                        break;
                    }
                }
                Err(tokio::sync::broadcast::error::RecvError::Lagged(_)) => {
                    // Exact missed revisions do not matter: this explicitly
                    // instructs the client to fetch its latest HTTP projection.
                    if send_with_deadline(
                        &mut sender,
                        Message::Text(
                            r#"{"type":"resync_required","protocol_version":3}"#.into(),
                        ),
                        policy.write_timeout,
                    ).await.is_err() {
                        break;
                    }
                }
                Err(tokio::sync::broadcast::error::RecvError::Closed) => break,
            },
            message = incoming.next() => match message {
                Some(Ok(Message::Close(_))) | None | Some(Err(_)) => break,
                Some(Ok(Message::Pong(_))) | Some(Ok(Message::Ping(_))) => {
                    last_peer_activity = Instant::now();
                }
                Some(Ok(Message::Text(_))) | Some(Ok(Message::Binary(_))) => {}
            },
            _ = heartbeat.tick() => {
                if last_peer_activity.elapsed() >= policy.idle_timeout {
                    break;
                }
                ping_sequence = ping_sequence.wrapping_add(1);
                if send_with_deadline(
                    &mut sender,
                    Message::Ping(ping_sequence.to_be_bytes().to_vec().into()),
                    policy.write_timeout,
                ).await.is_err() {
                    break;
                }
            }
        }
    }
}

async fn send_with_deadline(
    sender: &mut (impl futures_util::Sink<Message> + Unpin),
    message: Message,
    deadline: Duration,
) -> Result<(), ()> {
    tokio::time::timeout(deadline, sender.send(message))
        .await
        .map_err(|_| ())?
        .map_err(|_| ())
}

fn environment_number<T>(name: &str, default: T) -> Result<T, &'static str>
where
    T: std::str::FromStr,
{
    match std::env::var(name) {
        Ok(value) => value
            .parse()
            .map_err(|_| "WebSocket runtime environment contains an invalid integer"),
        Err(std::env::VarError::NotPresent) => Ok(default),
        Err(std::env::VarError::NotUnicode(_)) => {
            Err("WebSocket runtime environment contains non-Unicode data")
        }
    }
}

#[cfg(test)]
mod tests {
    use std::convert::Infallible;
    use std::{
        pin::Pin,
        task::{Context, Poll},
    };

    use super::*;

    struct BlockedSink;

    impl futures_util::Sink<Message> for BlockedSink {
        type Error = ();

        fn poll_ready(
            self: Pin<&mut Self>,
            _context: &mut Context<'_>,
        ) -> Poll<Result<(), Self::Error>> {
            Poll::Pending
        }

        fn start_send(self: Pin<&mut Self>, _item: Message) -> Result<(), Self::Error> {
            Ok(())
        }

        fn poll_flush(
            self: Pin<&mut Self>,
            _context: &mut Context<'_>,
        ) -> Poll<Result<(), Self::Error>> {
            Poll::Pending
        }

        fn poll_close(
            self: Pin<&mut Self>,
            _context: &mut Context<'_>,
        ) -> Poll<Result<(), Self::Error>> {
            Poll::Ready(Ok(()))
        }
    }

    #[test]
    fn websocket_runtime_policy_defaults_are_bounded() {
        let policy = WebSocketRuntimePolicy::from_values(1_024, 4, 30, 90, 5).unwrap();
        assert_eq!(policy.global_connection_limit, 1_024);
        assert_eq!(policy.account_connection_limit, 4);
        assert_eq!(policy.heartbeat_interval, Duration::from_secs(30));
        assert_eq!(policy.idle_timeout, Duration::from_secs(90));
        assert_eq!(policy.write_timeout, Duration::from_secs(5));
    }

    #[test]
    fn websocket_runtime_policy_rejects_disabled_incoherent_and_unbounded_values() {
        for values in [
            (0, 1, 30, 90, 5),
            (100_001, 1, 30, 90, 5),
            (4, 0, 30, 90, 5),
            (4, 5, 30, 90, 5),
            (64, 33, 30, 90, 5),
            (64, 4, 4, 90, 5),
            (64, 4, 301, 700, 5),
            (64, 4, 30, 59, 5),
            (64, 4, 30, 901, 5),
            (64, 4, 30, 90, 0),
            (64, 4, 30, 90, 31),
        ] {
            assert!(
                WebSocketRuntimePolicy::from_values(
                    values.0, values.1, values.2, values.3, values.4,
                )
                .is_err(),
                "{values:?}",
            );
        }
    }

    #[tokio::test]
    async fn idle_connections_are_closed_without_notification_authority() {
        let hub = NotificationHub::with_connection_limits(1, 1);
        let subscription = hub.try_subscribe(uuid::Uuid::new_v4()).unwrap();
        let policy = WebSocketRuntimePolicy {
            global_connection_limit: 1,
            account_connection_limit: 1,
            heartbeat_interval: Duration::from_millis(5),
            idle_timeout: Duration::from_millis(15),
            write_timeout: Duration::from_millis(5),
        };
        tokio::time::timeout(
            Duration::from_millis(100),
            serve_websocket_parts(
                futures_util::sink::drain::<Message>(),
                futures_util::stream::pending::<Result<Message, Infallible>>(),
                subscription,
                policy,
            ),
        )
        .await
        .expect("idle connection did not close");
    }

    #[tokio::test]
    async fn blocked_writers_are_dropped_within_the_write_deadline() {
        let hub = NotificationHub::with_connection_limits(1, 1);
        let account_id = uuid::Uuid::new_v4();
        let subscription = hub.try_subscribe(account_id).unwrap();
        let policy = WebSocketRuntimePolicy {
            global_connection_limit: 1,
            account_connection_limit: 1,
            heartbeat_interval: Duration::from_secs(30),
            idle_timeout: Duration::from_secs(90),
            write_timeout: Duration::from_millis(5),
        };
        hub.publish(
            &[account_id],
            unciv_authoritative_server::notifications::RevisionNotification {
                event_type: "revision_committed",
                protocol_version: unciv_authoritative_server::PROTOCOL_VERSION,
                game_id: uuid::Uuid::new_v4(),
                committed_revision: 1,
                canonical_state_hash: "a".repeat(64),
            },
        )
        .await;

        tokio::time::timeout(
            Duration::from_millis(100),
            serve_websocket_parts(
                BlockedSink,
                futures_util::stream::pending::<Result<Message, Infallible>>(),
                subscription,
                policy,
            ),
        )
        .await
        .expect("blocked writer did not close");
    }
}
