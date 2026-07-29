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

## Final-state verification record

Every material API v3 change must end with one verification record in the
handoff. Create the record only after the last material edit. Identify that
state with the `HEAD` commit, the output of `git status --short`, and, when
tracked changes remain, the output of
`git diff HEAD --binary | git hash-object --stdin`. List and SHA-256 hash any
material untracked files separately. If a material file changes after a check,
that check is stale and must be rerun.

Assess each lane below and run the smallest affected project-owned gate first.
Add broader gates only when the impact requires them:

- Kotlin rules, commands, projections, saves, AI, or turn progression: run the
  focused `:tests:test --tests ...` case or class covering the changed
  invariant, then `./gradlew :tests:test`.
- Rust API, protocol, persistence, recovery, or observability: run the focused
  `cargo test` target, then `cargo fmt --all -- --check` and
  `cargo clippy --all-targets --all-features -- -D warnings` from
  `authoritative-server`.
- Desktop production UI or routing: run its focused Kotlin tests when present
  and the affected `:desktop:dist` packaging gate.
- Android production UI, routing, credentials, or platform behavior: run the
  focused unit or instrumentation test and the affected
  `:android:assembleDebug`, `:android:bundleDebug`,
  `:android:assembleRelease`, or `:android:bundleRelease` gate. Do not claim an
  emulator or release gate that was not run.
- PostgreSQL schema, queries, concurrency, backup, recovery, or reconciliation:
  run the affected deterministic database test against the digest-pinned
  PostgreSQL 19 Beta 2 target and name the exact image digest.
- Kotlin worker or release packaging: run the affected
  `:server:authoritativeWorkerDist`, `:desktop:dist`, release-bundle, or
  packaged-handoff preflight.
- Mods or rulesets: run packaged-worker parity with a representative approved,
  content-addressed mod manifest.
- Legacy or shared gameplay behavior: run the smallest affected single-player,
  hotseat, save-compatibility, API-v2, or legacy-v3 isolation regression gate.

Use exactly one row per assessed lane:

```text
Final-state verification
Revision: <HEAD commit; worktree state; diff hash; material untracked hashes>
Last material edit: <path and change>
| Lane | Status | Command or gate | Covered invariant | Result | Blocker |
| Kotlin | passed | <exact command> | <behavior> | <exact result> | - |
| Rust | not affected | - | <reason it is outside the change> | - | - |
| Android | unavailable | <exact attempted or required gate> | <behavior> | - | <missing prerequisite and resume condition> |
```

Allowed statuses are `passed`, `failed`, `unavailable`, and `not affected`.
`passed` requires a successful result from the recorded final state. `failed`
retains the exact failure and must not be presented as completion.
`unavailable` names the missing tool, service, credential, platform, or
external boundary and the condition for rerunning it. `not affected` gives a
specific impact reason and never implies that the lane ran. Never use a broad
suite, an earlier revision, a checklist mark, or a handoff statement as a
substitute for the affected final-state gate.

## Failure-repair record

When an affected check fails, preserve one ordered failure-repair record in the
validation handoff. Do not replace the original failure with only the final
pass:

```text
Failure repair
1. Failed check: <exact command, cwd, fixture or target, revision/worktree identity, exit status, and bounded failure output>
2. Minimal reproduction: <smallest command and inputs that reproduce the same failure>
3. Causal diagnosis: <supported cause, evidence that localizes it, and rejected alternatives when material>
4. Bounded repair owner: <smallest file, module, configuration, service, or external owner changed; exact repair>
5. Final state: <HEAD, git status, tracked diff hash, and material untracked hashes after the last repair edit>
6. Final rerun: <same command and result after step 5, or justified equivalent and equivalence evidence>
```

Keep the steps in that order. The causal diagnosis must connect the reproduced
failure to the bounded repair; a speculative explanation or unrelated cleanup
is not a diagnosis. The repair owner must be the smallest owner that corrects
the supported cause and must preserve the V3 authority and compatibility
boundaries.

Rerun the same failed check after the repair and after the last material edit.
Use an equivalent check only when the record explains why it covers the same
behavior, inputs, risk, and scope as the original. If another material edit
occurs after the rerun, mark the result stale and rerun it again.

A retry with no causal diagnosis is a `retry-only pass`, not a confirmed
repair. Record it separately with the original failure still unresolved.
Repeated retries, daemon restarts, cache clearing, broader suites, or a
different passing test do not establish repair unless the record contains the
reproduction, supported cause, bounded owner, and final-state rerun above. If
the failure cannot be reproduced or diagnosed, report it as unresolved with
the exact blocker and resume condition.

## Delivery-closure record

When a task requests a commit, push, PR, CI result, merge, release, deployment,
APK, desktop artifact, or another delivered effect, append one delivery-closure
record to the final-state verification record. Bind it to the same target
revision and worktree identity.

Record the requested artifact or remote target exactly. Keep local validation
in a separate table from delivery acceptance:

```text
Delivery closure
Target revision: <same final-state revision and worktree identity>
Requested delivery: <artifact names and paths, or remote repository, branch, PR, release, or environment>

Local validation
| Check | Status | Result | Blocker |
| <exact command> | passed|failed|unavailable|skipped | <exact result> | <resume condition or reason> |

Delivery acceptance
| Boundary | Decision | Evidence | Recovery owner | Blocker or next action |
| Commit | accepted|rejected|pending|unavailable|skipped|not requested | <commit id or exact absence> | <owner> | <next action> |
| PR / CI / merge | ... | <URL, run, revision-bound decision, or exact absence> | <owner> | <next action> |
| Release / deployment | ... | <release or environment decision, or exact absence> | <owner> | <next action> |
| APK | ... | <artifact path and acceptance result, or exact absence> | <owner> | <next action> |
| Desktop artifact | ... | <artifact path and acceptance result, or exact absence> | <owner> | <next action> |
```

The actual acceptance decision is the decision at that boundary, not the
agent's completion statement. Local tests, builds, lint, hashes, and
`git diff --check` are validation evidence only. They never prove that a commit
was created, a push reached its remote, a PR or CI run passed, a merge landed,
a release or deployment succeeded, or an APK or desktop artifact was accepted.

Use `pending` when an authorized boundary has started but has no final decision.
Use `unavailable` when a required boundary cannot be reached; retain the
missing credential, service, platform, approval, callback, or other prerequisite
and the exact condition for resuming it. Use `skipped` only with the authority
and reason that allowed the gate to be skipped. Use `not requested` only when
the delivery contract did not include that boundary. Never convert `pending`,
`unavailable`, `skipped`, or `not requested` into acceptance.

Name a recovery owner for every requested persistent or external effect. The
owner must have a concrete retry, revert, restore, compensation, artifact
replacement, or escalation route and a post-recovery check. If no effect
occurred, state who owns safe retry or escalation. A handoff is closed only
when every requested boundary is `accepted`, or when each unresolved boundary
remains explicitly `pending`, `unavailable`, `skipped`, or `rejected` with its
owner and next action.

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
- If Ktor, Netty, Logback, the Android Gradle Plugin, or security-forced build
  transitives change, preserve whole-family version alignment, inspect both
  server runtime and Gradle build graphs, rerun V3 server and Android gates,
  and confirm hosted dependency submission does not reopen fixed advisories.
- If a change affects victory evaluation or game termination, preserve the
  worker-owned canonical result, project only the public winner/type/turn,
  disable every terminal client control, reject post-victory mutations, and
  rerun both the packaged handoff preflight and the two-person full-match gate.
- Run the smallest focused V3 tests first, then all affected Rust, server,
  desktop, Android, PostgreSQL 19 Beta 2, packaging, mod-parity, and legacy
  regression gates. Do not dismiss a discovered failure as unrelated.
- Update `missing_multiplayer.md`,
  `docs/architecture/authoritative-multiplayer-status.md`,
  `docs/security/authoritative-multiplayer-threat-model.md` when a boundary
  changes, and affected protocol/operations/benchmark docs with exact evidence.
  A checklist mark is not a substitute for a current test.
