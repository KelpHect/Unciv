# Authoritative V3 verification contract

The repository treats formatting, compilation, tests, security checks, and
runtime qualifications as one contract. A green compiler is necessary but not
sufficient: V3 authority, confidentiality, idempotency, revision lineage,
worker ownership, and compatibility are executable invariants.

## Local entry point

Run the safe default lanes from the repository root:

```bash
bash authoritative-server/tests/run-authoritative-verification.sh
```

On Windows with Git Bash installed, the equivalent native wrapper is:

```powershell
pwsh -File authoritative-server/tests/run-authoritative-verification.ps1 -Lane --all
```

The wrapper delegates to the same Bash contract; it does not duplicate or
weaken any lane. The default runs Rust and Kotlin/server checks. Select additional production
client packaging lanes explicitly:

```bash
bash authoritative-server/tests/run-authoritative-verification.sh --all
# or: --desktop / --android
```

The script resolves a Java 21 installation through `JAVA_HOME_21` or
`JAVA21_HOME`, and otherwise accepts an already-selected Java 21. It sets
`JAVA_HOME` only in child Gradle processes; it never changes the caller's Java
25 configuration or any machine-wide setting.

PostgreSQL integration is deliberately opt-in and requires a disposable,
digest-pinned PostgreSQL 19 Beta 2 database:

```bash
UNCIV_V3_DATABASE_URL='postgres://...' \
  bash authoritative-server/tests/run-authoritative-verification.sh --postgres
```

PITR, disk-full, TLS, object-store, and live packaged-stack workflows remain
separate qualification scripts because they require explicit services,
credentials, storage fixtures, or destructive disposable capacity. They are
never implied by `--all`.

## Required invariants for changes

Every material V3 change must select the smallest affected lane first and then
add broader lanes when its impact requires them:

| Boundary | Required evidence |
| --- | --- |
| Rust API/protocol/persistence | `cargo fmt`, library or focused tests, all-target check, strict Clippy |
| Kotlin rules/worker/client | Focused test, then `:tests:test` and/or `:server:test` with Java 21 |
| Desktop/Android production UI | Affected package gate; Android device/instrumentation when available |
| PostgreSQL schema/concurrency/recovery | Serialized tests against PostgreSQL 19 Beta 2; destructive workflows separately |
| Worker/release/mods | Packaged worker distribution, bundle verification, and representative mod parity |
| Online gameplay state | Stale revision, duplicate command ID, authorization, hidden-field, crash/retry, and terminal-state coverage |

The PostgreSQL carrier must always use `--test-threads=1`. Its fixtures
truncate canonical tables and parallel execution can create false failures or,
worse, misleading results. Exclusion lists are allowed only for tests whose
external fixture is run by a named qualification script; they must not be used
to hide a normal regression.

## Readability and review rules

Prefer explicit typed commands, closed projection DTOs, narrow modules, and
logic-free entry façades. Keep client presentation disposable and projection
only. Never add a client-side rules fallback, whole-save upload, optimistic
canonical mutation, or client-owned randomness to V3. New automatic behavior
belongs in the private Kotlin worker and must preserve the Rust control-plane
protocol and compatibility contract.

## Security and supply chain

The hosted lanes remain authoritative for dependency review, complete-history
secret scanning, RustSec, Gradle dependency submission, SPDX SBOM generation,
pinned actions, and content-addressed release bundles. Local verification does
not claim hosted attestation or deployment acceptance. A release is accepted
only when the exact revision's bundle, SBOM, provenance, migration, worker, and
runtime smoke evidence are bound together.

## Boundary/property coverage

Keep deterministic property tests at binary and protocol boundaries. Current
coverage includes worker frames, protocol envelopes, bounded snapshots, zstd
payloads, content-defined snapshot deltas, projection shape, malformed fields,
and command/revision behavior. Extend the existing Rust property suites when
changing replay cursors, refresh-token transitions, archive metadata, or retry
classification; do not introduce a second rules engine or an unrelated test
framework merely to follow a language-specific blog recommendation.

## Benchmark evidence

The Huge-map benchmark writes its ordinary timing CSV plus structured telemetry
next to it. Telemetry must distinguish transient HTTP 429/5xx retries, stale
409 conflicts, projection failures, worker failures, and recovered operations.
Do not report a benchmark as clean when it progressed only after recoverable
errors. Preserve the CSV, telemetry, stderr, game ID, commit, manifest hash,
configuration, and final projection/reconciliation result together.

The benchmark is a workload probe, not a deployment acceptance test. Use its
late-game measurements to choose a representative JVM/JFR, Rust, PostgreSQL,
and storage profile before optimizing. For a run with loopback Prometheus and
Docker PostgreSQL available, add `-MetricsPort <port> -DatabaseContainer
<container> -ResourceSampleEveryRounds 10`; the NDJSON then records only
bounded resource samples (metrics size, selected counters, database bytes, and
snapshot-blob bytes), never credentials or response bodies. The worker can be
profiled without changing the project Java configuration by launching it with
Java 21's `-XX:StartFlightRecording=filename=<temp-file>,settings=profile,dumponexit=true`.
The Rust API already exposes bounded request/worker histograms and PostgreSQL
query timing remains an operator-side `pg_stat_statements`/database lane rather
than a public label or client payload. Do not add Go, Go PGO, or `govulncheck`
without Go production code; the existing Rust/Kotlin toolchains are the
project's more coherent and safer contract.

## Snapshot maintenance and storage budgets

When Lockwell is configured completely, the API starts a bounded background
maintenance loop unless `UNCIV_V3_SNAPSHOT_ARCHIVE_ENABLED=false` is set. The
loop runs outside the command hot path and, per tick, examines a bounded number
of games and revisions. It verifies the object with size, hash, and byte-for-byte
GET evidence before deleting only the PostgreSQL payload blob. Long-term
checkpoints, genesis, recent revisions, accepted end-turn checkpoints, recovery
and rewind revisions remain in PostgreSQL; delta archives reference one of those
checkpoints and reconstruction is depth-bounded.

Recommended controls are:

```text
UNCIV_V3_SNAPSHOT_ARCHIVE_INTERVAL_SECONDS=60
UNCIV_V3_SNAPSHOT_ARCHIVE_RECENT_REVISIONS=64
UNCIV_V3_SNAPSHOT_ARCHIVE_LONG_TERM_INTERVAL=100
UNCIV_V3_SNAPSHOT_ARCHIVE_MAX_GAMES=4
UNCIV_V3_SNAPSHOT_ARCHIVE_MAX_REVISIONS=128
UNCIV_V3_SNAPSHOT_ARCHIVE_USE_DELTAS=true
UNCIV_V3_SNAPSHOT_ARCHIVE_BUDGET_BYTES=0
UNCIV_V3_SNAPSHOT_POSTGRES_BUDGET_BYTES=0
```

A nonzero aggregate Lockwell archive budget is a hard upper bound on new
verified object bytes. Once reached, cold archival pauses and reports
`archive_quota_exceeded`; it never deletes existing objects or protected
checkpoints automatically. A nonzero per-game PostgreSQL budget causes
maintenance to archive that game's cold payloads after the retained bytes cross
the limit. If protected checkpoints still keep the database above budget,
maintenance does not delete those checkpoints: it reports `budget_exceeded` and
exposes the bounded Prometheus gauges/counters for operator action. No Lockwell
configuration means no automatic destructive compaction. The explicit `unciv-v3-compact` CLI remains
an operator-only tool and is not a substitute for recoverable archival.
