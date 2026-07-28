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
runtime role, and a separately protected migration role. See
`authoritative-api-systemd.md`. The development composition is not the final
PostgreSQL TLS/backup deployment.

## Required upgrade gate

Before changing the pinned image:

1. Take and verify a logical backup plus the volume/storage snapshot.
2. Restore into a separate PostgreSQL 19 candidate instance.
3. Run every ignored Rust PostgreSQL integration test serially.
4. Run the read-only reconciliation CLI and require a clean report.
5. Run command concurrency, idempotency, outbox, and representative load tests.
6. Promote only after rollback has been rehearsed; never point two major/beta
versions at the same data directory.

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
