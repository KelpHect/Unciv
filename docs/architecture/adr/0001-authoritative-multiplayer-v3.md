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
one JVM for the low-memory target, but they do not replace the still-required
Linux, large-save, AI/end-turn, and sustained-load qualification.

Licensing: no code from the separate AGPL `hopfenspace/runciv` reference may be
copied or vendored without an explicit compatibility and notice decision.
