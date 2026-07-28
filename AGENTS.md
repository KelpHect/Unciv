# Authoritative multiplayer v3 work

This repository currently ships legacy client-authored multiplayer. Treat that
protocol as legacy behaviour: it must not be extended as an authoritative
service.

## Guardrails

- Preserve single-player, hotseat, saved-game compatibility, and unrelated
  multiplayer API-v2 behaviour while introducing API v3 behind an explicit
  feature boundary.
- Only the server-side engine may mutate canonical online `GameInfo` state.
  Clients submit typed commands with an idempotency key and expected revision;
  they never upload, patch, or replace a v3 game save.
- Reuse the Kotlin game engine for rules execution. Do not create a second Rust
  rules engine. The Rust service is the public control plane; the Kotlin worker
  is private and headless.
- Do not commit credentials, production data, generated databases, or mutable
  game saves. Preserve existing user changes.
- Make each milestone buildable and record the exact verification result in
  `docs/architecture/authoritative-multiplayer-status.md`.
- PostgreSQL 19 Beta 2 is the sole production and test database target. Pin its
  image digest; do not retain compatibility profiles for older PostgreSQL
  majors.

## Rust structure

- Keep `main.rs`, `lib.rs`, and module façades nearly logic-free: declarations,
  narrow re-exports, and bootstrap delegation only.
- Split implementation early by purpose into descriptive, shallow modules.
  Prefer roughly 300-800 substantive lines per file and never allow a 2,000-line
  god file. Keep items private by default and widen visibility only as needed.
- Reuse behavior through focused traits/types where it removes real duplication;
  prefer explicit readable code over speculative abstraction.
- Run `cargo fmt` and warnings-as-errors `cargo clippy` for every Rust milestone.

## Required checks

- Run the smallest relevant Gradle test task before broad checks.
- Run `./gradlew :tests:test` for core/game changes when a supported JDK is
  available. The project currently requires a JDK supported by its Kotlin and
  Gradle toolchain; record an unavailable-toolchain blocker instead of
  weakening the build configuration.
- For protocol or persistence code, add deterministic tests for stale
  revisions, duplicate command IDs, authorization, and crash/retry behaviour.

See `docs/architecture/current-multiplayer-flow.md` and
`docs/architecture/adr/0001-authoritative-multiplayer-v3.md` before changing
the legacy multiplayer path.

## V3 change-impact contract

Authoritative multiplayer is a continuing compatibility surface, not a
finished side project. Any future change to gameplay rules, turn progression,
AI, randomness, maps, mods, uniques, saves, projections, player-visible UI,
multiplayer networking, or mutable `GameInfo` data must explicitly assess API
v3 in the same change.

The maintained baseline is a projection-only desktop/Android client, a typed
Rust API/control plane, a private packaged Kotlin rules worker, and
digest-pinned PostgreSQL 19 Beta 2. The server owns setup, all gameplay
mutations, every AI player, turn advancement, randomness, immutable
base-plus-mod manifests, canonical revisions, recovery, and notifications.
The client owns input, presentation, disposable projection caches, and exact
idempotent retries only. The complete evidence and current external blockers
live in `docs/architecture/authoritative-multiplayer-status.md` and
`missing_multiplayer.md`; do not duplicate their evolving inventories here.

- If the change adds or alters a player decision, add or update the closed typed
  command, Rust validation/route, private Kotlin worker execution, projection
  fields, projection-only client control, and deterministic authorization,
  stale-revision, idempotency, crash/retry, and confidentiality tests together.
- If the change affects automatic rules or AI, prove it executes in the private
  Kotlin worker under the server-owned execution context. Never add a V3 client
  autoplay, local rules fallback, client RNG, optimistic canonical mutation, or
  whole-save synchronization path.
- If the change affects mods or rulesets, preserve exact content-addressed
  manifest resolution and run packaged-worker parity with a representative
  approved mod. Client-local mod content and names are never authority.
- If the change adds a public gameplay route or session command, keep the
  OpenAPI-to-client route inventory and session-to-production-UI inventory
  exact. Every production V3 interaction must remain reachable without
  importing `GameInfo`, `GameStarter`, or legacy upload/download behavior.
- If the engine model gains hidden or mutable state, update the explicit player
  and spectator projections, Rust fail-closed validation, sentinel leak tests,
  compatibility version, cache/reconnect behavior, and size limits.
- Run the smallest focused V3 tests first, then all affected Rust, server,
  desktop, Android, PostgreSQL 19 Beta 2, packaging, mod-parity, and legacy
  regression gates. Do not dismiss a discovered failure as unrelated.
- Update `missing_multiplayer.md`,
  `docs/architecture/authoritative-multiplayer-status.md`,
  `docs/security/authoritative-multiplayer-threat-model.md` when a boundary
  changes, and affected protocol/operations/benchmark docs with exact evidence.
  A checklist mark is not a substitute for a current test.
