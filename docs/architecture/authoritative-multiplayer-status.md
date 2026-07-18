# Authoritative multiplayer v3 status

## Milestone 0 — baseline and design

Completed on 2026-07-18:

- Verified that the existing protocol is client-authored full-save storage.
- Recorded the current call/data flow in
  `docs/architecture/current-multiplayer-flow.md`.
- Selected the Rust control-plane/Kotlin headless-worker architecture in ADR
  0001; no alternate rules engine will be introduced.
- Added repository guardrails in `AGENTS.md`.

Verification attempted:

```text
.\\gradlew.bat :tests:test --no-daemon
```

The task did not reach compilation. Gradle exited with `25.0.3`, indicating the
currently selected Java runtime is unsupported by the project toolchain. This
is an environment blocker, not a passing baseline. Rerun the exact command
with the documented supported JDK before modifying engine code.

Next executable milestone: add the Kotlin headless engine boundary and a
deterministic fixture that loads a game, rebuilds transients, executes a
server-owned operation, and asserts a canonical state hash.
