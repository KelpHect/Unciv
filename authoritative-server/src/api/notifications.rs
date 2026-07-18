use super::*;

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
    let receiver = state.notifications.subscribe(actor.id).await;
    Ok(websocket.on_upgrade(move |socket| serve_websocket(socket, receiver)))
}

pub(super) async fn serve_websocket(
    socket: WebSocket,
    mut receiver: tokio::sync::broadcast::Receiver<
        unciv_authoritative_server::notifications::RevisionNotification,
    >,
) {
    let (mut sender, mut incoming) = socket.split();
    loop {
        tokio::select! {
            notification = receiver.recv() => match notification {
                Ok(notification) => {
                    let payload = serde_json::to_string(&notification)
                        .expect("revision notification is serializable");
                    if sender.send(Message::Text(payload.into())).await.is_err() {
                        break;
                    }
                }
                Err(tokio::sync::broadcast::error::RecvError::Lagged(_)) => {
                    // Exact missed revisions do not matter: this explicitly
                    // instructs the client to fetch its latest HTTP projection.
                    if sender.send(Message::Text(
                        r#"{"type":"resync_required","protocol_version":3}"#.into()
                    )).await.is_err() {
                        break;
                    }
                }
                Err(tokio::sync::broadcast::error::RecvError::Closed) => break,
            },
            message = incoming.next() => match message {
                Some(Ok(Message::Close(_))) | None | Some(Err(_)) => break,
                _ => {}
            }
        }
    }
}
