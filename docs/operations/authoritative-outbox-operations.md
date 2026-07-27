# Authoritative outbox operations

The API-v3 outbox carries non-authoritative revision and resynchronization
hints. Canonical history is committed before an outbox row exists, and clients
always converge through authenticated HTTP projections. Outbox repair must
never create, replace, or infer canonical `GameInfo`.

Migration `0021_outbox_operations.sql` adds bounded poison handling, compact
delivery receipts, operator audit records, and indexes that exclude
dead-lettered rows from ordinary claims.

## Runtime policy and alerts

| Environment variable | Default | Accepted range |
| --- | ---: | ---: |
| `UNCIV_V3_OUTBOX_MAX_DELIVERY_ATTEMPTS` | 12 | 3-100 |
| `UNCIV_V3_OUTBOX_LAG_ALERT_SECONDS` | 60 | 10-86400 |

Invalid values fail API startup. Each claim increments the durable attempt
count. A failed delivery is delayed by a bounded backoff; after the configured
attempt it is atomically dead-lettered, removed from the claim index, and
retained for operator review. The stored reason is a fixed redacted category,
not a database URL, credential, payload, or internal exception.

Every API replica checks outbox health every 30 seconds. Any dead-letter row or
oldest pending event at or beyond the lag threshold emits a stable redacted
alert containing only counts, ages, and the maximum attempt count. Operators
can query the same state directly:

```text
unciv-v3-outbox status
```

The command emits JSON, returns zero when healthy, two when an alert is active,
and one for configuration/storage failure. Connect monitoring to the nonzero
status or the `authoritative outbox alert` process log.

## Dead-letter review and requeue

Inspect the outbox row, canonical revision, current membership, PostgreSQL
health, and shared notification listener before requeueing. Requeue is dry-run
by default:

```text
unciv-v3-outbox requeue <outbox-id>
unciv-v3-outbox requeue <outbox-id> --apply
```

Apply succeeds only while the exact row remains dead-lettered. It clears the
bounded delivery state, makes the row immediately available, and appends one
`requeue_dead_letter` operator-audit record in the same transaction. It does
not edit the topic, payload, game, revision, canonical history, or membership.
Repeated apply against a row that is no longer dead-lettered returns status
two instead of guessing.

## Delivered-event compaction

Full delivered outbox rows include transient payload, lease, and retry data.
Compact them after the operational investigation window:

```text
unciv-v3-outbox compact --older-than-days 30 --limit 1000
unciv-v3-outbox compact --older-than-days 30 --limit 1000 --apply
```

The allowed age is 1-3650 days and each transaction handles at most 10,000
rows. Dry-run reports the bounded eligible count without mutation. Apply locks
only its selected delivered rows with `SKIP LOCKED`, inserts minimal immutable
receipts, deletes only rows whose receipt was created, and appends one
`compact_delivered` audit record atomically.

Receipts retain outbox ID, game, revision, topic, creation/delivery times, and
attempt count. They exclude the JSON payload, claim token, and error text.
Reconciliation counts active rows plus receipts, so compaction preserves the
exact one-commit-hint-per-revision invariant. The repair tool also treats a
receipt as proof and will not recreate a compacted event.

Never compact undelivered or dead-lettered rows. Never delete receipts or
operator-audit rows manually. Back them up with canonical revision history and
include their counts in restore drills.
