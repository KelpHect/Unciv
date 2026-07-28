# Authoritative WebSocket runtime policy

Revision WebSockets are non-authoritative hints. A client must fetch its
authenticated HTTP projection after any hint, reconnect, lag signal, or
uncertain delivery. Losing, duplicating, or reordering a frame cannot commit or
replace canonical state.

The generated public lifecycle contract is available from
`GET /api/v3/asyncapi.json` and checked in at
`authoritative-server/openapi/notifications-v3.json`. It is an AsyncAPI 3.1
document covering the authenticated WSS upgrade, both exact JSON message
shapes, server-to-client receive operation, control-frame heartbeat, reconnect,
delivery uncertainty, and transport limits. Release bundles must contain the
same generated document at `contracts/notifications-v3.json`.

The Rust API applies these defaults:

| Environment variable | Default | Accepted range |
| --- | ---: | ---: |
| `UNCIV_V3_WS_MAX_CONNECTIONS` | 1024 | 1-100000 |
| `UNCIV_V3_WS_MAX_CONNECTIONS_PER_ACCOUNT` | 4 | 1-32 and no greater than the global limit |
| `UNCIV_V3_WS_HEARTBEAT_SECONDS` | 30 | 5-300 |
| `UNCIV_V3_WS_IDLE_SECONDS` | 90 | 15-900 and at least two heartbeat intervals |
| `UNCIV_V3_WS_WRITE_TIMEOUT_SECONDS` | 5 | 1-30 |
| `UNCIV_V3_WS_LEASE_TTL_SECONDS` | 90 | 30-300 |
| `UNCIV_V3_WS_LEASE_RENEW_SECONDS` | 30 | 5 to less than the lease TTL, and no more than half the TTL |

Invalid or non-Unicode values fail process startup. Admission is counted after
authentication and before the protocol upgrade. A rejected connection receives
the normal bounded `rate_limited` response and a one-second retry hint.
Connection permits are released on every normal, error, idle, and slow-reader
exit. Per-account channel state is removed when its final socket closes.

The connection caps apply to the complete Rust API fleet. Each authenticated
upgrade first takes the process-local permit and then atomically acquires a
short-lived row in `websocket_connection_leases` while holding a PostgreSQL
transaction advisory lock. The row is bound to a random process replica ID.
The socket renews it at the configured interval and disconnects fail-closed if
the lease expires, disappears, or cannot be renewed. A normal disconnect
deletes it immediately; a process crash leaves no permanent capacity leak
because the next admission removes expired rows. A forged or stale replica ID
cannot renew or release another replica's lease.

The server sends numbered WebSocket ping frames at the heartbeat interval.
Only peer ping/pong control traffic refreshes the idle deadline; arbitrary text
or binary input cannot keep a connection alive. A peer silent for the complete
idle interval is disconnected. Every notification, resynchronization hint, and
ping write has the configured hard deadline. The upgrade boundary also retains
the 4 KiB message/frame and 64 KiB maximum write-buffer limits.

An account-local broadcast queue holds at most 64 hints. Lag produces one
`resync_required` frame instead of replaying guessed intermediate revisions.
The supported API-v3 client reconnects with capped exponential equal jitter
(125 ms through 10 seconds) and always reconciles through HTTP. Jitter affects
transport timing only; it never enters canonical gameplay state or deterministic
rules execution.

## Cross-instance fan-out

Canonical commits and membership/lifecycle changes first create durable
`game_outbox` rows in their database transaction. One dispatcher claims each
row with the existing lease and publishes a versioned JSON hint on PostgreSQL
channel `unciv_v3_revision_hints`. The payload is limited to 1 KiB and contains
only its schema version, public event type, protocol version, game ID,
committed revision, and canonical hash. It never contains a snapshot,
projection, account ID, or private rules-engine data.

Every Rust replica establishes `LISTEN` before its local dispatcher starts.
Upon receipt it strictly validates the closed payload, queries current
recipients from authoritative membership, and fans the hint out only to local
subscribers for those accounts. The dispatcher acknowledges the durable outbox
row only after `pg_notify` succeeds. A crash between publish and acknowledge
can duplicate a hint; it cannot lose or duplicate a canonical commit.

PostgreSQL notifications are transient by design. SQLx reconnects the listener
and restores its channel subscription after a connection failure. When the
listener reports a gap, a shared payload is rejected, or membership cannot be
queried, the replica sends `resync_required` to every local socket. Clients
then fetch authenticated HTTP projections. A newly connected client likewise
reconciles through HTTP, so events published before that replica subscribed do
not need notification replay.

## Contract regeneration and validation

Regenerate both public API contracts with:

```text
cargo run --bin unciv-authoritative-server -- --write-openapi
```

Rust parity tests compare the generated AsyncAPI document and its two live
outbound DTOs with the checked-in artifact. The independent specification gate
is:

```text
npx --yes @asyncapi/cli@2.16.0 validate openapi/notifications-v3.json --diagnostics-format json --fail-severity error
```
