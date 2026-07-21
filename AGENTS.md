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
