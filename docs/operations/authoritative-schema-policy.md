# Authoritative schema and capacity policy

API-v3 schema changes use expand-and-contract migrations. Every checked-in
migration is immutable after publication and moves only forward:

1. Expand with nullable/defaulted columns, new tables/indexes, or compatible
   triggers while the old release can still operate.
2. Deploy code that can read old and new shapes but writes the new shape.
3. Backfill through a bounded, restartable, observable operation.
4. Prove every active release no longer depends on the old shape.
5. Contract only in a later migration after a verified backup and restore
   rehearsal.

There are no automated down migrations. The API refuses to bind when migration
versions are missing, extra, failed, or checksum-mismatched. Consequently an
older binary cannot silently run against a newer schema. Before a contract
migration, rollback means restoring the verified pre-migration physical backup
and matching WAL target into an isolated PostgreSQL 19 Beta 2 instance, then
reactivating the matching release bundle. After data has been intentionally
written in the new shape, prefer a forward repair migration; never hand-edit the
SQLx journal or drop canonical history to make an older binary start.

Migration review must state the compatible application range, lock behavior,
estimated rewrite/WAL/storage growth, backfill and cancellation behavior,
forward repair, restore boundary, and exact validation queries. Apply through
`unciv-v3-migrate` with the migration role only. The runtime role never owns or
creates schema objects.

## Capacity gate

`check-capacity.sh` runs every five minutes as the read-only `unciv-monitor`
service identity. Configure its protected environment with the audit role:

```text
UNCIV_V3_AUDIT_DATABASE_URL=postgresql://unciv_audit:...@127.0.0.1:5432/unciv_authoritative?sslmode=require
UNCIV_V3_POSTGRES_DATA_ROOT=/var/lib/unciv-authoritative/postgresql
UNCIV_V3_WAL_ARCHIVE_ROOT=/var/backups/unciv-authoritative/wal
UNCIV_V3_BASE_BACKUP_ROOT=/var/backups/unciv-authoritative/base
UNCIV_V3_CAPACITY_WARN_PERCENT=80
UNCIV_V3_CAPACITY_CRITICAL_PERCENT=90
UNCIV_V3_DATABASE_WARN_BYTES=10737418240
```

Install the monitor without write access to the database or storage roots:

```text
groupadd --system unciv-monitor
useradd --system --gid unciv-monitor --home /nonexistent --shell /usr/sbin/nologin unciv-monitor
install -d -o root -g unciv-monitor -m 0750 /etc/unciv-authoritative/monitor
install -o root -g unciv-monitor -m 0640 capacity.env /etc/unciv-authoritative/monitor/capacity.env
install -o root -g root -m 0644 authoritative-server/systemd/unciv-authoritative-capacity.service /etc/systemd/system/
install -o root -g root -m 0644 authoritative-server/systemd/unciv-authoritative-capacity.timer /etc/systemd/system/
systemd-analyze verify /etc/systemd/system/unciv-authoritative-capacity.service /etc/systemd/system/unciv-authoritative-capacity.timer
systemctl enable --now unciv-authoritative-capacity.timer
```

The check emits one bounded JSON object containing filesystem percentages,
database bytes, pending outbox rows, and snapshot count—never credentials,
account data, commands, or snapshots. Exit `0` is healthy, `1` is warning, and
`2` is critical. Alert on any nonzero unit result and on a missing timer run.
Page before critical, stop game creation and nonessential maintenance at
critical, preserve WAL/backup continuity, and add capacity or move data through
a rehearsed procedure. Never delete WAL, snapshots, commands, or outbox rows ad
hoc to clear an alert.

## Disk-full response

PostgreSQL storage failure is an availability event, not permission to accept a
client repair. Remove the API from readiness, stop writes, preserve logs and
the affected filesystem, add capacity, and run a checkpoint plus
`unciv-v3-reconcile`. A failed authoritative transaction must leave no command,
snapshot, outbox event, or head revision. Retry only with the same command ID
after storage and reconciliation are healthy.

The destructive
`authoritative-server/tests/run-postgres-disk-full-smoke.ps1` gate uses a
160 MiB tmpfs and fills it to 1 MiB free. A 12 MiB incompressible valid UTF-8
snapshot then fails with PostgreSQL `No space left on device`; the Rust
repository returns `Storage` while revision zero and zero commands remain.
After removing only the drill filler and checkpointing, the same command ID
commits revision one, a duplicate returns that result without a second commit,
canonical validation passes, and reconciliation reports zero findings.
