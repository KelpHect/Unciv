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

The Rust service applies checked-in migrations on startup. Production must use
a private database network or loopback binding, TLS for any non-local database
connection, a distinct least-privilege runtime role, and a separately protected
migration role. The development composition is not the final production
systemd/TLS/backup deployment.

## Required upgrade gate

Before changing the pinned image:

1. Take and verify a logical backup plus the volume/storage snapshot.
2. Restore into a separate PostgreSQL 19 candidate instance.
3. Run every ignored Rust PostgreSQL integration test serially.
4. Validate every canonical snapshot hash and quarantined-game invariant.
5. Run command concurrency, idempotency, outbox, and representative load tests.
6. Promote only after rollback has been rehearsed; never point two major/beta
   versions at the same data directory.

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
