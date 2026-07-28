# Authoritative multiplayer observability

The API emits newline-delimited JSON logs to standard output and exposes
Prometheus metrics on a separate loopback-only listener. The default scrape
address is `127.0.0.1:9464`; `UNCIV_V3_METRICS_BIND` may select a different
loopback socket but deliberately rejects public addresses. The public API and
Caddy never route this listener.

Set `UNCIV_V3_LOG` to a `tracing-subscriber` filter when more detail is needed.
Do not enable dependency-wide trace logging in production without first
checking its fields. Application events intentionally use only request IDs and
closed classifications. They never record account IDs, game IDs, command IDs,
session tokens, client addresses, ruleset names, snapshots, projections, worker
frames, usernames, or error detail.

Load `authoritative-server/observability/prometheus-alerts.yml` into Prometheus
and `authoritative-server/observability/grafana-dashboard.json` into Grafana.
Prometheus must scrape over loopback or a separately authenticated local
collector. Alert receivers and dashboard access are part of the operator
authentication domain, not the player API.

## API unavailable

Keep public writes closed. Check the API unit, release-bundle verification,
database readiness, and worker handshake. Follow the worker or database
runbook before reopening traffic.

## Authentication abuse

Correlate the bounded failure rate with durable security-audit events. Do not
inspect or export submitted credentials. Apply the abuse runbook and preserve
the rate-limit and audit evidence.

## Stale conflicts

Confirm clients are fetching a fresh projection after conflicts and that
notification/outbox health is normal. A stale response is safe and must never
be bypassed by accepting the client state.

## Command failures

Separate stable 4xx rejections from 5xx failures. For 5xx failures, check worker
and PostgreSQL panels. Never replay a command with a different idempotency key
until its durable result is known.

## Command latency

Check worker latency, database lock conflicts, outbox health, and host resource
pressure. Preserve transaction and worker deadlines; do not raise them merely
to hide overload.

## Worker failures

Follow `authoritative-worker-failure.md`. The worker is the only rules engine;
do not substitute Rust or client-side execution. Keep writes closed whenever
worker identity, protocol, or deterministic execution is uncertain.

## Database locks

Inspect PostgreSQL lock waiters through the operator role and correlate with
command latency. Preserve per-game serialization and fencing. Do not terminate
transactions until the owning operation and recovery consequence are known.

## Revision growth

Commands arriving with no canonical commits can indicate worker, database, or
authorization failure. Check the stable error distribution and reconcile the
database. Never synthesize or skip a revision.

## Projection size

Identify the projection kind, reproduce it with approved operator tooling, and
audit disclosure as well as size. Do not raise the limit until hidden-state
tests and resource qualification cover the proposed new bound.

## Outbox lag

Follow `authoritative-outbox-backlog.md`. Notifications are hints only; clients
must reconcile over authenticated HTTP. Repair the durable outbox rather than
inventing or replaying canonical state from WebSocket data.

## WebSocket load

Inspect admission rejections, slow-reader eviction, and notification lag.
Preserve global/per-account limits and bounded queues. Clients may reconnect
with backoff and fetch HTTP projections; sockets never receive authority.
