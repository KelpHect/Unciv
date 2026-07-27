# Authoritative WebSocket runtime policy

Revision WebSockets are non-authoritative hints. A client must fetch its
authenticated HTTP projection after any hint, reconnect, lag signal, or
uncertain delivery. Losing, duplicating, or reordering a frame cannot commit or
replace canonical state.

The Rust API applies these defaults:

| Environment variable | Default | Accepted range |
| --- | ---: | ---: |
| `UNCIV_V3_WS_MAX_CONNECTIONS` | 1024 | 1-100000 |
| `UNCIV_V3_WS_MAX_CONNECTIONS_PER_ACCOUNT` | 4 | 1-32 and no greater than the global limit |
| `UNCIV_V3_WS_HEARTBEAT_SECONDS` | 30 | 5-300 |
| `UNCIV_V3_WS_IDLE_SECONDS` | 90 | 15-900 and at least two heartbeat intervals |
| `UNCIV_V3_WS_WRITE_TIMEOUT_SECONDS` | 5 | 1-30 |

Invalid or non-Unicode values fail process startup. Admission is counted after
authentication and before the protocol upgrade. A rejected connection receives
the normal bounded `rate_limited` response and a one-second retry hint.
Connection permits are released on every normal, error, idle, and slow-reader
exit. Per-account channel state is removed when its final socket closes.

The server sends numbered WebSocket ping frames at the heartbeat interval.
Only peer ping/pong control traffic refreshes the idle deadline; arbitrary text
or binary input cannot keep a connection alive. A peer silent for the complete
idle interval is disconnected. Every notification, resynchronization hint, and
ping write has the configured hard deadline. The upgrade boundary also retains
the 4 KiB message/frame and 64 KiB maximum write-buffer limits.

An account-local broadcast queue holds at most 64 hints. Lag produces one
`resync_required` frame instead of replaying guessed intermediate revisions.
The supported clients already reconnect with capped exponential delay and
always reconcile through HTTP.

These limits are per Rust process. Multi-replica fanout and fleet-wide admission
still require the shared notification transport described in
`missing_multiplayer.md`.
