# ADR 0001: Rust control plane with a private Kotlin authoritative engine worker

- Status: accepted for the first vertical slice
- Date: 2026-07-18

## Context

Legacy multiplayer accepts client-authored, whole `GameInfo` saves. The existing
Kotlin engine is the only mature implementation of creation, actions, AI, and
turn progression. A public service must make exactly one revisioned canonical
commit per accepted command without exposing the full state to clients.

## Decision

Build API v3 as a Rust public control plane and a bounded Kotlin/JVM headless
engine worker boundary. The initial 1-vCPU/1-GB deployment uses one persistent
sequential JVM with a bounded Rust admission queue and a fresh authenticated
connection per command. The services use a versioned, length-prefixed JSON
protocol over loopback; a future Unix-domain socket remains an isolation
hardening option rather than a correctness dependency. The
Rust service owns authentication, membership, rate limits, PostgreSQL
transactions, revision CAS, idempotency, snapshots, and outbox notifications.
The Kotlin worker owns loading pinned snapshots/rulesets and executing typed
commands by reusing the existing engine. It has no public listener.

The initial schema has a closed command union and always carries protocol
version, game ID, command ID, and expected revision. The Rust transaction is
the final authority even if an in-memory per-game queue is used.

## Options considered

| Option | Result |
| --- | --- |
| Rust control plane + Kotlin worker | Chosen: keeps public operations isolated while retaining one rules engine. |
| Kotlin-only service | Viable fallback, but combines public networking/persistence failure domain with a large UI-oriented runtime. |
| Complete Rust rules engine | Rejected for v3: duplicated rules would create parity/desync risk and is not a thin vertical slice. |
| PHP/shared hosting | Rejected: cannot provide the required authoritative engine execution model. |

## Consequences and validation plan

The cross-process boundary adds startup, protocol, and worker-crash complexity.
It must stay narrow: create game, load snapshot, apply one command, run end
turn, and return a state hash plus player-scoped projection. The worker is
version-pinned with every game/ruleset manifest.

The first reproducible process-model measurements are recorded in
`docs/benchmarks/authoritative-multiplayer.md`. On the Windows development host,
500 fresh authenticated handshakes measured 3.05-ms p50 and 6.39-ms p95; 50
real tiny-game creations measured 20.59-ms p50 and 109.69-ms p95. Ten cold
workers became connectable in 1.09 seconds on average and used about 108 MiB at
readiness. These measurements justify retaining the fresh-connection model and
one JVM for the low-memory target.

The final representative Windows run measured Large-game creation at 233.99-ms
p50, player projection at 105.32-ms p50, and the first human end turn plus seven
server AI civilizations at 319.13-ms p50. The immutable
`authoritative-v3-0.1.0-beta.2.8` Linux qualification then completed 60 Large
game/projection/AI scenarios with eight-way command contention in 152.586
seconds under hard limits of 1 CPU, 992 MiB, and no swap. End-turn-plus-AI p50
was 1,510.21 ms and p95 was 3,301.81 ms; exactly 60 commands committed, 420
lost the revision race as expected, and 120 WebSocket notifications arrived.
Summed per-container peak memory was 333.98 MiB, database growth was 6,021,120
bytes, and packaged-worker readiness recovered 1,122 ms after restart.

This qualifies a reproducible floor for the exact scenario rather than an
unlimited-user or SLA claim. Late-game saves, different mods, network
geography, retention, and target hardware remain deployment-specific capacity
inputs. Operational complexity is explicit: one public Rust service, one
private sequential Kotlin worker, one PostgreSQL service, digest-pinned runtime
images, bounded queues/timeouts, readiness that fails closed on worker loss,
and a signed self-verifying release bundle.

Licensing: no code from the separate AGPL `hopfenspace/runciv` reference may be
copied or vendored without an explicit compatibility and notice decision.
