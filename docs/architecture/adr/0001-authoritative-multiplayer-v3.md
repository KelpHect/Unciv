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
engine worker pool. The services use a versioned, length-prefixed JSON protocol
over loopback in development and a Unix-domain socket in Linux production. The
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

The required measurements—worker startup, idle/large-save memory, command and
end-turn latency—are not yet available. The baseline Gradle test was blocked
before compilation because the installed Java is `25.0.3`; this Gradle/Kotlin
toolchain must first be run with a supported JDK. Record measured values in the
status document rather than treating estimates as results.

Licensing: no code from the separate AGPL `hopfenspace/runciv` reference may be
copied or vendored without an explicit compatibility and notice decision.
