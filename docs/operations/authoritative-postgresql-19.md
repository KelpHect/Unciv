# Authoritative PostgreSQL 19 operations

PostgreSQL 19 Beta 2 is the sole database target for authoritative multiplayer
API v3. The project does not maintain a second production or compatibility
version. This is an explicit prerelease-risk decision: PostgreSQL upstream does
not recommend beta releases for production, and beta upgrades may require
`pg_upgrade` or dump/restore. Operators accepting this profile must test every
subsequent beta, release candidate, and final release before replacing the
running image.

The development composition pins both the `19beta2-alpine` tag and its image
digest. It enables PostgreSQL 19 worker-based asynchronous I/O with bounded
workers and one parallel autovacuum worker. These conservative values fit the
initial 1 vCPU / 1 GB target; benchmarks, not the presence of a new setting,
must justify increasing them. The volume mounts `/var/lib/postgresql`, as
required by the PostgreSQL 18+ official image layout for major-version-aware
data directories and future `pg_upgrade` workflows.

## Start the database

From `authoritative-server`, copy `.env.example` to `.env`, replace the password
with a generated secret, and run:

```text
docker compose up -d postgres
docker compose exec postgres pg_isready -U unciv_authoritative -d unciv_authoritative
```

Run the API with a URL assembled outside source control:

```text
UNCIV_V3_DATABASE_URL=postgres://unciv_authoritative:<password>@127.0.0.1:5432/unciv_authoritative
```

Development can apply checked-in migrations with the dedicated binary:

```text
UNCIV_V3_MIGRATION_DATABASE_URL=postgres://unciv_authoritative:<password>@127.0.0.1:5432/unciv_authoritative
cargo run --manifest-path authoritative-server/Cargo.toml --bin unciv-v3-migrate
```

The Rust API never applies migrations. It validates the complete version and
checksum set before binding and then uses bounded pool, acquisition, idle,
lifetime, statement, and lock timeouts. Production must use a private database
network or loopback binding, encrypted transport, a distinct least-privilege
runtime role, and separately protected migration and backup roles. See
`authoritative-api-systemd.md`. The development composition remains a developer
database; the systemd and PostgreSQL files below are the production backup/PITR
boundary.

## Continuous WAL archive and physical backups

Install `authoritative-server/postgresql/authoritative-pitr.conf` as a
PostgreSQL `include` file. Its restart-required settings enable recovery-grade
WAL and continuous archiving. `archive-wal.sh` accepts only a PostgreSQL WAL
segment, base-backup history file, or timeline-history filename. It stages in
the archive filesystem, refuses symlinks and differing existing files, and
atomically renames the completed `0600` file. Never configure a command that
overwrites an existing archive object.

Create separate roots with no shared write access:

```text
install -d -m 0700 -o 70 -g 70 /var/backups/unciv-authoritative/wal
install -d -m 0700 -o unciv-backup -g unciv-backup /var/backups/unciv-authoritative/base
```

Create a login that can stream a physical backup but cannot read application
tables through ordinary SQL:

```sql
CREATE ROLE unciv_backup LOGIN REPLICATION;
ALTER ROLE unciv_backup SET statement_timeout = 0;
```

Set its generated password outside SQL history, add only the required
`hostssl replication unciv_backup ... scram-sha-256` HBA rule for the backup
host or loopback address, and reload PostgreSQL. Do not grant schema/table
privileges. Store the credential in an `0600` pgpass file readable only by
`unciv-backup`; `/etc/unciv-authoritative/backup/backup.env` contains:

```text
UNCIV_V3_BACKUP_DATABASE_URL=postgresql://unciv_backup@127.0.0.1:5432/postgres?sslmode=require
UNCIV_V3_BASE_BACKUP_ROOT=/var/backups/unciv-authoritative/base
PGPASSFILE=/etc/unciv-authoritative/backup/pgpass
```

Install and enable `unciv-authoritative-backup.service` and
`unciv-authoritative-backup.timer`. The daily timer uses a randomized delay and
is persistent across downtime. Each run streams required WAL, creates a
SHA-256 backup manifest, runs `pg_verifybackup --exit-on-error`, and publishes
the directory only after verification. A failed staging directory is removed;
an existing completed backup is never replaced.

Monitor `pg_stat_archiver`. Alert when `failed_count` increases, when
`last_archived_time` falls behind the configured recovery-point objective, when
free space crosses the operator threshold, or when the backup timer fails.
Copy verified base backups and the continuous WAL archive to independently
encrypted, access-controlled storage. Keep WAL from at least the start of the
oldest retained base backup; deleting an apparently old segment without
checking that boundary can make the backup unrecoverable.

## Isolated restore and promotion gate

Never restore over the production data directory. Under a separate
`unciv-restore` operating-system identity, copy one verified base backup into a
new empty PostgreSQL 19 Beta 2 data directory, rerun `pg_verifybackup` before
adding recovery files, mount the matching WAL archive read-only, and configure:

```text
restore_command = 'cp /read-only-wal-archive/%f %p'
recovery_target_time = '<reviewed UTC incident boundary>'
recovery_target_action = 'promote'
```

Use `recovery_target_name` instead when an operator created a unique named
restore point. Create `recovery.signal`, start only the isolated instance, and
wait until `pg_is_in_recovery()` becomes false. Before any traffic can reach
the instance:

1. Run the ignored
   `restored_backup_fixture_preserves_every_required_invariant` qualification
   or the equivalent release-specific restore assertions.
2. Run `unciv-v3-reconcile` and require exit code zero and
   `total_findings: 0`.
3. Verify canonical heads, snapshot payload/hash pairs, the revision/command
   journal, membership and ownership, sessions, security audit events, and
   transactional outbox rows.
4. Confirm the target boundary with incident-specific included/excluded
   records and preserve the backup ID, target, PostgreSQL image digest, reports,
   and operator approvals.

`authoritative-server/tests/run-postgres-pitr-smoke.ps1` automates that entire
destructive workflow in disposable Docker containers and volumes. It uses only
the pinned PostgreSQL 19 Beta 2 digest, a replication-only backup login,
streamed WAL, a SHA-256 manifest, `pg_verifybackup`, a named restore point, and
the Rust canonical reconciliation path. Its cleanup targets only
GUID-suffixed resources created by that invocation.

## Required upgrade gate

Before changing the pinned image:

1. Take a verified physical base backup and preserve its continuous WAL range.
2. Restore into a separate PostgreSQL 19 candidate instance through the
   automated PITR drill.
3. Run every ignored Rust PostgreSQL integration test serially.
4. Run the read-only reconciliation CLI and require a clean report.
5. Run command concurrency, idempotency, outbox, and representative load tests.
6. Promote only after rollback has been rehearsed; never point two major/beta
versions at the same data directory.

## Production service

Production uses
`authoritative-server/postgresql/compose.production.yaml`, owned by the
`unciv-authoritative-postgres.service` systemd unit. The compose file names only
the exact accepted PostgreSQL 19 Beta 2 image digest, uses Linux host networking
with PostgreSQL listening on loopback only, bounds CPU, memory, PIDs, shared
memory, and capabilities, and reads the bootstrap password from a root-owned
file. The systemd unit validates the compose model, waits for the TLS-enabled
healthcheck, and starts before migration and API units.

Install these root-owned files:

```text
install -d -o root -g root -m 0750 /etc/unciv-authoritative/postgres
install -d -o 70 -g 70 -m 0700 /etc/unciv-authoritative/postgres/tls
install -o root -g root -m 0644 authoritative-server/postgresql/compose.production.yaml /etc/unciv-authoritative/postgres/compose.yaml
install -o root -g root -m 0644 authoritative-server/postgresql/production-postgresql.conf /etc/unciv-authoritative/postgres/postgresql.conf
install -o root -g root -m 0644 authoritative-server/postgresql/production-pg_hba.conf /etc/unciv-authoritative/postgres/pg_hba.conf
install -o root -g root -m 0644 authoritative-server/postgresql/production-tls.conf /etc/unciv-authoritative/postgres/tls.conf
install -o root -g root -m 0644 authoritative-server/postgresql/authoritative-pitr.conf /etc/unciv-authoritative/postgres/pitr.conf
install -o root -g root -m 0644 authoritative-server/systemd/unciv-authoritative-postgres.service /etc/systemd/system/
```

Install a CA-issued certificate for the database endpoint as `server.crt`, its
private key as `server.key`, and the issuing chain as `ca.crt`. The key must be
owned by UID/GID 70 used by the exact pinned Alpine image and mode `0600`;
PostgreSQL refuses an overly permissive key. The production HBA rejects all
non-TLS TCP sessions
before considering SCRAM authentication. It admits runtime, migration, and
audit only to `unciv_authoritative`, admits backup only to the replication
pseudo-database, and has no production admission for the restore role.

Generate the one-time bootstrap password outside shell history, store it at
`/etc/unciv-authoritative/postgres/postgres-bootstrap-password` mode `0600`,
then run:

```text
docker compose --file /etc/unciv-authoritative/postgres/compose.yaml config --quiet
systemd-analyze verify /etc/systemd/system/unciv-authoritative-postgres.service
systemctl enable --now unciv-authoritative-postgres.service
```

After creating the five role passwords in protected temporary variables, run
`bootstrap-roles.sql` locally as the `postgres` OS identity with psql variables
`runtime_password`, `migration_password`, `backup_password`,
`restore_password`, and `audit_password`. It is idempotent: it closes role
attributes, revokes public database/schema/function access, makes migration the
database/schema owner, installs future default grants, and reapplies exact
grants to existing objects. Do not place those variable values in a checked-in
file or command transcript. Remove or lock the bootstrap superuser password
after validating all five roles.

The roles have deliberately different authority:

- `unciv_runtime`: TLS login, schema usage, table DML, and sequence use; no DDL.
- `unciv_migrate`: TLS login and ownership/DDL; no superuser or role creation.
- `unciv_backup`: TLS physical-replication login only; no table access.
- `unciv_restore`: no production database admission; use only after granting it
  access inside an isolated restored cluster.
- `unciv_audit`: TLS read-only login with table/sequence reads and bounded
  timeouts; no writes or DDL.

## Credential rotation

Rotate one consumer at a time. Generate a new password without logging it, run
`rotate-role-password.sql` locally as the PostgreSQL administrator with
`role_name` and `new_password` psql variables, update only that consumer's
protected credential or pgpass file, restart that consumer, and require its
readiness/operation check. Then prove a new session with the old credential is
denied and a new TLS session with the replacement succeeds. Terminate any old
long-lived sessions after the consumer is healthy and record the role, time,
operator, reason, and validation result without recording either credential.

The rotation SQL accepts exactly the five named service roles and refuses every
other target. Rotation does not grant privileges and does not weaken TLS/HBA.
For suspected disclosure, stop the affected consumer first, rotate immediately,
terminate its sessions, inspect audit and connection logs, and revoke `LOGIN`
until the incident is understood.

## Read-only canonical reconciliation

Run reconciliation with a database role that has `SELECT` access only. The CLI
does not apply migrations, quarantine games, or perform repairs:

```text
cargo run --manifest-path authoritative-server/Cargo.toml --bin unciv-v3-reconcile
```

See `authoritative-snapshot-retention.md` for the dry-run-first snapshot
payload compaction workflow. Run reconciliation after every applied batch.
See `authoritative-reconciliation-repair.md` for the reviewed, dry-run-first
response to every finding and the narrow deterministic outbox repair.
See `authoritative-outbox-operations.md` for poison-event dead-lettering,
backlog/lag alerts, audited requeue, and delivered-row compaction into immutable
receipts.

Set `UNCIV_V3_DATABASE_URL` outside source control before running it. Exit code
`0` means no findings, `2` means the JSON report contains findings, and `1`
means configuration, connection, or query failure. Treat a truncated report as
an incident requiring database-preserved investigation; the tool records at
most 1,000 detailed findings while still counting every finding it scans.

The report checks heads, revision parent chains, snapshot/revision links,
accepted-command links, revision-commit outbox correspondence, owner count,
duplicate civilization assignment, quarantine state, and every stored
snapshot's bounded codec, sizes, protocol, UTF-8, payload hash, and canonical
hash. It outputs only game/revision identifiers and fixed invariant messages;
canonical bytes, credentials, command payloads, and account data are excluded.

Never make an ad hoc repair directly from this report. Preserve a backup and
audit evidence, identify the failure source, use the reviewed bounded workflow,
rehearse canonical recovery or a bespoke migration on a restored copy, and run
reconciliation plus the complete PostgreSQL suite before promotion.

PostgreSQL 19 features to evaluate next with measured workloads include
autovacuum scoring, lock/autovacuum statistics, online checksum management,
concurrent `REPACK`, and sequence-aware logical replication. They are not
enabled merely for novelty: canonical correctness remains in transactions,
constraints, immutable revisions, and application validation.

## Validated baseline

On 2026-07-18 the pinned composition reported:

```text
19beta2|worker|1|2|16|1
```

Those fields are server version, I/O method, minimum/maximum I/O workers, I/O
concurrency, and parallel autovacuum workers. The complete ignored Rust database
suite passed 8/8 against this instance, covering migrations, revision CAS and
idempotency, account/session lifecycle, authorization, snapshot quarantine,
outbox leasing, discovery, and durable rate limiting.

On 2026-07-28 the isolated destructive PITR qualification passed against
`postgres:19beta2-alpine@sha256:bc62313e826eb44d5f608425b7665962b72820e686da017799e906604bfeb8a5`.
The backup used streamed WAL, a SHA-256 manifest, and `pg_verifybackup`; recovery
promoted at `unciv_v3_backup_qualification`. The transaction committed before
the target was present and the transaction committed after it was absent.
Post-restore assertions validated one canonical head at revision 1, both
snapshot/payload hashes, two revision and one command journal rows, membership,
session, audit, and outbox rows. Reconciliation scanned one game, two revisions,
and two snapshots with zero findings. All disposable containers, volumes, and
the network were removed.

Also on 2026-07-28,
`authoritative-server/tests/run-postgres-security-smoke.ps1` passed against the
same exact digest. PostgreSQL negotiated TLS 1.3; non-TLS and restore-role
production connections were denied. Runtime DML succeeded while runtime DDL
failed; migration DDL succeeded; audit reads and reconciliation succeeded while
audit writes failed; and the replication-only role completed and verified a
physical base backup. All roles were non-superuser, non-createdb,
non-createrole, and non-bypass-RLS; only backup had replication, and all five
passwords were SCRAM. Runtime password rotation denied the old credential and
accepted the replacement. The disposable container, volume, private CA,
certificates, and keys were removed.
