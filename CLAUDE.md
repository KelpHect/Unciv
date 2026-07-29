# Unciv — agent entry point

This file is a router, not a contract. **[AGENTS.md](AGENTS.md) is the binding
contract** for authoritative multiplayer v3 work. Read it before changing
anything that touches gameplay rules, turn progression, AI, randomness, maps,
mods, uniques, saves, projections, player-visible UI, multiplayer networking, or
mutable `GameInfo` data.

Where this file and AGENTS.md disagree, AGENTS.md wins.

## What AGENTS.md owns

Do not look for these anywhere else, and do not restate them here:

- Guardrails and the v3 authority/compatibility boundaries
- The per-lane gate assessment, allowed statuses, and broader-gate rules
- The final-state verification, failure-repair, and delivery-closure record formats
- The V3 change-impact contract

## Smallest gate by change area

Start here, then follow AGENTS.md's *Final-state verification record* for the
full lane assessment and any broader gates it requires.

- **Kotlin rules / commands / projections / saves / AI / turn progression** —
  the focused `:tests:test --tests ...` case covering the changed invariant
- **Rust API / protocol / persistence / recovery / observability** —
  the focused `cargo test` target
- **Desktop production UI or routing** — its focused Kotlin tests when present
- **Android production UI / routing / credentials / platform** —
  the focused unit or instrumentation test
- **PostgreSQL schema / queries / concurrency / backup / recovery / reconciliation** —
  the affected deterministic database test against the digest-pinned target
- **Kotlin worker or release packaging** — the affected packaging gate
- **Mods or rulesets** — packaged-worker parity with an approved manifest
- **Legacy or shared gameplay** — the smallest affected single-player, hotseat,
  save-compatibility, API-v2, or legacy-v3 isolation regression gate

## Orientation

- `docs/architecture/authoritative-multiplayer-status.md` — current evidence
- `missing_multiplayer.md` — open gaps and external blockers
- `docs/architecture/current-multiplayer-flow.md` and
  `docs/architecture/adr/0001-authoritative-multiplayer-v3.md` — read before
  touching the legacy multiplayer path
