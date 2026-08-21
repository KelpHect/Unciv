# Authoritative multiplayer v3 status

## Rust module re-split and gameplay-coverage re-audit (2026-08-13)

The 800-line Rust guardrail drifted after the storage/archival work, so the five
largest modules were re-split into shallow, purpose-named submodules while
preserving every public route and handler name:

- `postgres.rs` (1103 → 335): snapshot validation moved to
  `postgres/snapshot_validation.rs` and commit/creation to `postgres/commit.rs`.
- `worker.rs` (865 → 670): construction commands moved to
  `worker/construction.rs`.
- `projection.rs` (828 → 516): the semantic round-trip tests moved to the
  top-level `projection_tests.rs` test module.
- `api/commands.rs` (807 → 334): construction handlers moved to
  `api/construction_commands.rs`.
- `postgres/commands.rs` (802 → 424): construction execution moved to
  `postgres/construction_commands.rs`.

No non-test Rust source file now exceeds 800 lines; the largest is
`api/contracts.rs` (795). `postgres/integration_tests.rs` remains the only file
over 800 lines and is the environment-gated integration fixture include, not
implementation.

A full gameplay-coverage re-audit was also performed: all 81 `GameCommand`
variants map one-to-one to worker operations (`JoinGame` → `AssignPlayer`,
`KickMember` → `KickPlayer`); the 89 `WorkerOperation` variants add eight
non-gameplay intents (handshake, create/reconfigure/normalize game, and the
four projection intents). Every `UnitActionType` in the Kotlin engine resolves
to a closed V3 command. Unciv ships no BNW trade-route or archaeology mechanic,
so those are not applicable rather than missing. Whole-turn/military/civilian
autoplay remains a deliberate exclusion, not a gap.

City-state quests and influence status are now projected to V3 clients:
`ProjectedCityStatePartner` gained `influence`, `influenceLevel`
(`unforgivable`/`enemy`/`neutral`/`friend`/`ally`), and a bounded `quests`
list (`ProjectedCityStateQuest`), closing the one previously recorded parity
gap. Projection version is 62 on both the Kotlin DTO and the Rust control
plane, and `release/compatibility.json` was updated in lockstep with the
regenerated OpenAPI contract.

Validation on the current source: `cargo fmt --all -- --check`, `cargo clippy
--all-targets --all-features -- -D warnings`, and `cargo test --all-targets
--all-features` (284 passed, 0 failed, 62 environment-gated ignored) all pass;
`git diff --check` is clean. The focused Kotlin
`ProjectionLeakSentinelTests` and `AuthoritativeDiplomacyControllerTests` pass,
and `:server:test` `ReleaseCompatibilityContractTests` (which also exercises
`authoritativeWorkerDist`) passes.

## Latest optimization pass: bounded pathfinding and automatic snapshot maintenance (2026-08-12)

This pass applies the Huge-map evidence without introducing Go or a second rules
engine:

- `AStar` now caps distinct search nodes at the initialized map tile count and
  ignores stale queue entries left behind by a cheaper route. `MapPathing`
  rejects an impossible destination before allocating a full search, uses a
  bounded admissible target heuristic for generic connections, and makes
  unreachable-target diagnostics opt-in through
  `-Dunciv.pathing.failureLogs=true`. No-path is a normal AI outcome and is no
  longer formatted tens of thousands of times in a benchmark worker log.
- Cold archival now streams one PostgreSQL payload at a time instead of loading
  every Huge-map blob into the API process. Full Lockwell objects do not gain an
  envelope byte penalty; bounded delta objects retain the archive envelope and
  checkpoint/base hash evidence. Archive selection scans newest cold revisions
  first so a bounded maintenance batch continues to make progress as new turns
  arrive.
- A Lockwell-backed background maintenance loop starts automatically when a
  complete object-store configuration is present, unless explicitly disabled.
  It is outside the gameplay command hot path, archives only after verified
  PUT/GET evidence, preserves genesis/recent/end-turn/recovery/rewind and
  long-term checkpoints, and never invokes the destructive PostgreSQL-only
  compactor. A per-game PostgreSQL byte budget reports and warns when protected
  checkpoints keep storage above the configured limit.
- New bounded maintenance metrics expose retained PostgreSQL bytes, budget
  state, pass/failure counts, and budget-exceeded passes without game/account
  labels. Configuration is documented in `.env.example` and the operations
  verification contract.

Current validation for this pass:

- `:tests:test --tests com.unciv.logic.map.PathfindingTests` passed, including
  the map-count bound and impossible-destination cases.
- `:tests:test :server:test` passed with command-local Java 21; server worker
  distribution rebuilt and the expected negative worker-fixture diagnostics
  remained fail-closed.
- `:desktop:dist`, Android debug/release APK and AAB gates passed with command-
  local Java 21. No Android emulator was connected for a new instrumentation
  run after this core change.
- Rust full all-target tests passed in an isolated target directory: 203
  library tests, 32 API tests, all active integration/policy targets, with 53
  environment-gated tests ignored. Strict Clippy, format, and check passed.
- The disposable PostgreSQL 19 Beta 2 retention/compaction integration test
  passed serially. The Lockwell archival integration test also passed against
  the local native Lockwell service using a fresh disposable bucket; it verified
  PUT/GET integrity, checkpoint-relative delta reconstruction, cold-blob
  deletion, canonical-head validation, and reconciliation. The bucket and its
  nine test objects were removed afterward.

## Deterministic AI-assisted verification contract and benchmark telemetry

Implemented and qualified on 2026-08-12:

- Added `authoritative-server/tests/run-authoritative-verification.sh` as the
  single safe local entry point. Its default is Rust plus Kotlin/server; `--all`
  adds desktop and Android packaging, and `--postgres` explicitly opts into a
  disposable serialized PostgreSQL carrier. It never starts services, changes
  global Java settings, or runs destructive storage qualifications implicitly.
- The entry point resolves Java 21 only for child Gradle processes through
  `JAVA_HOME_21` or `JAVA21_HOME`; the host Java 25 installation remains
  untouched. It runs Rust formatting, all-target tests, all-target check, and
  warnings-as-errors Clippy, plus the shared client/server and packaging lanes.
- The observability workflow now invokes the same Rust contract. Its policy test
  checks that the workflow delegates to the entry point and that the entry
  point still contains the required formatter, test-thread, and Clippy gates.
- The Huge Continents benchmark now writes a matching NDJSON telemetry stream
  beside its CSV. It classifies HTTP 429, stale 409, HTTP 5xx, projection, and
  advance failures; retries only idempotent requests with bounded backoff and
  preserves the same command ID; records recovered requests and per-round
  counters; and never stores bearer tokens or response bodies.
- Regenerated `openapi/api-v3.json` after the refresh-token route gained its
  request/response schema. The contract test now correctly treats `/auth/refresh`
  as public, while keeping all gameplay and account routes authenticated.
- The shared PostgreSQL fixture now truncates outbox receipts and operator-audit
  rows, making repeated serialized runs independent instead of accumulating
  audit records between runs.

Verification on the final worktree state:

- `bash authoritative-server/tests/run-authoritative-verification.sh --rust`:
  passed; 202 library tests, 32 API tests, all active integration/policy targets,
  format, all-target check, and strict Clippy passed. 53 environment-gated Rust
  tests remain intentionally ignored by that non-destructive lane.
- `JAVA_HOME_21=/c/Users/KellHect/AppData/Local/NeoMD-build-tools/jdk-21 bash
  authoritative-server/tests/run-authoritative-verification.sh --kotlin`:
  passed; `:tests:test` and `:server:test` completed successfully.
- The same entry point with `--desktop` passed `:desktop:dist`; `--android`
  passed `:android:assembleDebug`, `:android:bundleDebug`,
  `:android:assembleRelease`, and `:android:bundleRelease`.
- The PostgreSQL 19 Beta 2 carrier ran against a disposable database on the
  pinned local image and passed 49 serialized persistence/integration tests,
  the two HTTP response-loss/process-death tests, packaged account handoff,
  packaged worker-death recovery, and ruleset acquisition. The disposable
  database and role were removed afterward. The Lockwell archive test and
  destructive PITR/disk-full/promotion cases remain separate explicit lanes.

Failure repair

1. Failed check: `bash authoritative-server/tests/run-authoritative-verification.sh --rust`
   first failed with exit 101 because the checked-in OpenAPI document did not
   contain the current refresh-token request/response shape, and the route
   policy still required authentication on `/api/v3/auth/refresh`.
2. Minimal reproduction: `cargo test --manifest-path authoritative-server/Cargo.toml
   --bin unciv-authoritative-server api::tests -- --test-threads=1`.
3. Causal diagnosis: the refresh implementation had advanced without regenerating
   the checked-in contract; the public refresh endpoint was also missing from
   the policy test's public set. The generated document and policy test were the
   bounded owners; no runtime authority code was involved.
4. Bounded repair owner: `authoritative-server/openapi/api-v3.json` was regenerated
   from the current API, and `authoritative-server/src/api/tests.rs` was updated
   to classify refresh as public.
5. Final state: the final verification identity below includes those contract
   changes and the workflow delegation.
6. Final rerun: the final Rust contract passed with 202 library tests, 32 API
   tests, all active targets, formatting, check, and strict Clippy.

7. Failed check: the first PostgreSQL carrier attempt on the disposable target
   exited 101 with 49 authentication failures (`28P01`) because the initial
   bootstrap command did not actually create its intended disposable role and
   database.
8. Minimal reproduction: the same `--postgres` carrier against the absent
   disposable role/URL.
9. Causal diagnosis: the database bootstrap shell used a `\gexec` form whose
   variable expansion did not execute the generated statements; the Rust tests
   all failed before migrations or application logic ran.
10. Bounded repair owner: the test environment was recreated with explicit
    `CREATE ROLE`/`CREATE DATABASE` statements and an ephemeral password; no
    production role, database, or source runtime was changed.
11. Final state: the disposable target was migrated, exercised, and removed.
12. Final rerun: the same carrier passed all 49 serialized tests, plus its active
    packaged integration targets.

13. Failed check: the response-loss targets then exited 101 because their fake
    worker advertised protocol version 2 while the current Rust worker contract
    is version 8.
14. Minimal reproduction: `cargo test --manifest-path authoritative-server/Cargo.toml
    --test http_response_loss -- --ignored --test-threads=1` against the
    disposable PostgreSQL target.
15. Causal diagnosis: the fixture's hard-coded worker protocol was stale; the
    API correctly rejected the incompatible worker during startup.
16. Bounded repair owner: `authoritative-server/tests/http_response_loss.rs`
    now uses the checked-in worker protocol version 8. The test's normal stderr
    isolation was restored after diagnosis.
17. Final state: the final disposable target retained the corrected fixture.
18. Final rerun: both response-loss/process-death tests passed, including exact
    once-only worker execution and retry recovery.

19. Failed check: the repeated PostgreSQL carrier exposed an outbox assertion
    mismatch (`(active, receipts, requeues, compactions) = (0,1,1,2)` instead
    of `(0,1,1,1)`).
20. Minimal reproduction: rerun the outbox poison/requeue test twice against the
    same disposable database.
21. Causal diagnosis: `seed_repository` cleared canonical rows but retained
    derived receipt/operator-audit rows, so the second run counted prior audit
    evidence. This was an order-dependent test fixture, not a canonical-state
    defect.
22. Bounded repair owner: `authoritative-server/src/postgres/integration_tests.rs`
    now truncates `game_outbox_receipts` and `outbox_operator_audit` with the
    other disposable fixture tables.
23. Final state: the fixture repair is included in the final Rust diff.
24. Final rerun: the full PostgreSQL carrier passed 49 tests and the complete
    active packaged integration targets.

Final-state verification
Revision: <recorded after this final documentation edit>; worktree has tracked changes and material untracked files. The exact identity is recorded below.
Last material edit: this document; it records the bounded pathfinding/archive implementation and its final gates.
| Lane | Status | Command or gate | Covered invariant | Result | Blocker |
| --- | --- | --- | --- | --- | --- |
| Rust | passed | `cargo fmt --manifest-path authoritative-server/Cargo.toml --all -- --check; cargo check --manifest-path authoritative-server/Cargo.toml --all-targets --all-features; cargo clippy --manifest-path authoritative-server/Cargo.toml --all-targets --all-features -- -D warnings; CARGO_TARGET_DIR=target/authoritative-verification-final cargo test --manifest-path authoritative-server/Cargo.toml --all-targets --all-features` | bounded pathfinding-adjacent shared code does not weaken typed authority, snapshot delta safety, observability, or strict warnings | 203 library + 32 API tests passed; all active targets, format, check, and warnings-as-errors Clippy passed | - |
| Kotlin | passed | `JAVA_HOME=/c/Users/KellHect/AppData/Local/NeoMD-build-tools/jdk-21 ./gradlew --no-daemon --no-parallel --console=plain :tests:test :server:test` | shared Kotlin rules/worker pathfinding behavior and packaged worker parity | full tests and worker distribution passed; focused `PathfindingTests` also passed; Java 25/global configuration untouched | - |
| Desktop | passed | `JAVA_HOME=/c/Users/KellHect/AppData/Local/NeoMD-build-tools/jdk-21 ./gradlew --no-daemon --no-parallel --console=plain :desktop:dist` | desktop packaging remains compatible with shared core changes | distribution gate passed | - |
| Android | passed | `JAVA_HOME=/c/Users/KellHect/AppData/Local/NeoMD-build-tools/jdk-21 ./gradlew --no-daemon --no-parallel --console=plain :android:assembleDebug :android:bundleDebug :android:assembleRelease :android:bundleRelease` | Android APK/AAB packaging remains compatible with shared core changes | all four packaging gates passed | no emulator was connected for a new device-level run after this core edit |
| PostgreSQL | passed | disposable pinned PostgreSQL 19 Beta 2 `retention::compaction_preserves_milestones_head_history_and_idempotency` with `--ignored --test-threads=1` | checkpoint retention, immutable history, compaction, replay fail-closed behavior | focused disposable integration passed; live database remains healthy; the separate archival lane also passed | - |
| Object storage | passed | `cargo test --manifest-path authoritative-server/Cargo.toml --lib postgres::integration_tests::archive::lockwell_archival_verifies_objects_and_removes_only_cold_blobs -- --ignored --test-threads=1 --nocapture` with a disposable PostgreSQL 19 Beta 2 database and local Lockwell bucket | verified PUT/GET, delta archive reconstruction, cold-blob deletion, canonical-head validation, reconciliation, and cleanup | integration passed in 1.62s; 9 archive objects were created and removed | - |
| Huge benchmark | unavailable | full `run-10-random-huge-continents-benchmark.ps1` rerun on the rebuilt API/worker | end-to-end late-game latency and MapPathing warning reduction under the new binaries | prior terminal artifact is Rome Domination turn 969, revision 15,508; it predates this optimization pass | a fresh multi-hour benchmark has not been started after the code edit |
| Toolchain contract | passed | `bash -n authoritative-server/tests/run-authoritative-verification.sh; PowerShell parser checks; git diff --check` | deterministic entry points, Java 21 child-process isolation, Windows parity | syntax/help/diff checks passed | - |
| Security/supply chain | not affected | - | no dependency, credential, release, or hosted workflow boundary was changed by this optimization pass | - | hosted attestation/deployment acceptance remains external |

Benchmark cleanup: the six old tracked CSV/JSON result artifacts were removed
from the worktree, and the stopped dedicated `unciv-v3-huge` PostgreSQL container
and volume were removed. The retained latest benchmark CSV and NDJSON remain
untracked runtime evidence. Its terminal telemetry recorded Rome Domination on
turn 969, final revision 15,508, one expected post-victory HTTP 422, and no
429/5xx/retry events. The current local qualification database still contains
that finished match and is approximately 2.15 GiB, of which approximately 2.12
GiB is `game_snapshot_blobs`; the new automatic Lockwell maintenance is not
running in the existing process because it was built before this pass and has no
Lockwell credentials.

Failure repair
1. Failed check: `cargo test --manifest-path authoritative-server/Cargo.toml --all-targets --all-features`; current worktree before the final isolated rerun; exit 101; Windows denied removal of the running `authoritative-server/target/debug/unciv-authoritative-server.exe` (`Acesso negado`, os error 5).
2. Minimal reproduction: rerun the same command while the local API process held the debug executable open.
3. Causal diagnosis: a live local API process owned the default Cargo target binary; this was a workspace-process lock, not a Rust compile or test failure. Stopping unrelated live services was avoided.
4. Bounded repair owner: invocation configuration only; rerun with `CARGO_TARGET_DIR=target/authoritative-verification-final`, leaving the source and global toolchains unchanged.
5. Final state: the isolated target was removed after verification; the exact worktree identity is recorded below.
6. Final rerun: `CARGO_TARGET_DIR=target/authoritative-verification-final cargo test --manifest-path authoritative-server/Cargo.toml --all-targets --all-features` passed with 203 library tests, 32 API tests, all active targets, and 53 ignored environment-gated tests.

7. Failed check: the first PowerShell syntax gate invocation exited 1 because the ad-hoc parser command passed undefined `[ref]` variables (`InvalidOperation: [ref] cannot be applied to a variable that does not exist`).
8. Minimal reproduction: the same parser command with `[ref]$null` in place of initialized token/error variables.
9. Causal diagnosis: the validation command was malformed; both project scripts were parsed by PowerShell only after the command initialized `$tokens` and `$errors`. No project parser or script failure was observed.
10. Bounded repair owner: the validation invocation, not repository source; it was rerun with initialized parser reference variables.
11. Final state: the scripts remained unchanged by this repair and the final worktree identity is recorded below.
12. Final rerun: PowerShell parsing of `run-authoritative-verification.ps1` and `run-10-random-huge-continents-benchmark.ps1` passed, alongside `bash -n` and `git diff --check`.

## Full AI match benchmark with per-turn timing and history

Verified on 2026-08-06:

- Two complete authoritative V3 matches were played from lobby creation
  through Domination victory, with every turn benchmarked and the full
  revision history verified for playback.
- **2-player match** (Rome human + Egypt AI, tiny Pangaea, Quick): 263 turns
  to Egypt Domination victory. Per-turn EndTurn+1AI: p50 75 ms, p95 106 ms,
  mean 76.2 ms. 264 immutable revisions (0–263), 263 commands, 20 rewind
  checkpoints. Total history: 2.4 MB compressed across 264 zstd snapshots.
  Match wall-clock: 40.5 s. Post-victory projection correctly reports
  winner/type/turn; post-victory commands rejected with 422.
- **4-player match** (Rome human + 3 AI, small Pangaea, Quick): 30 turns
  benchmarked. Per-turn EndTurn+3AI: p50 55 ms, p95 93 ms, mean 59.4 ms.
  Three AI civilizations add negligible per-turn overhead beyond the 2-player
  baseline.
- Every `EndTurn` produces exactly one revision, one snapshot, one command
  journal entry, and one outbox event. The full revision chain is preserved
  as immutable content-addressed snapshots, enabling complete game playback
  from any point and consensual whole-game rewind to any retained checkpoint.
- The `run-full-ai-match.ps1` script in `authoritative-server/tests/` automates
  the full match benchmark with per-turn timing, victory detection, and
  history verification.

## Destructive PostgreSQL qualifications re-run

Verified on 2026-08-06:

- The disk-full and backup/restore qualification tests were re-run manually
  against a disposable
  `postgres:19beta2-alpine@sha256:bc62313e826eb44d5f608425b7665962b72820e686da017799e906604bfeb8a5`
  container with production roles bootstrapped. The disk-full seed test passed,
  the disk was filled to 0 KiB free, and
  `disk_full_commit_leaves_no_phantom_revision` passed (the 12 MiB canonical
  commit was rejected with no phantom command/head). The backup/restore seed
  test also passed. The underlying tests and migration set (1-31) are correct.
- The smoke scripts (`run-postgres-disk-full-smoke.ps1`,
  `run-postgres-pitr-smoke.ps1`) now create the four production ACL target roles
  before migrations in their disposable fixtures. Both scripts run end-to-end
  unattended against the pinned PostgreSQL 19 Beta 2 image; the repaired
  procedure is fixture-local and does not change production schema or runtime
  code.

## Lobby terrain projection benchmark

Measured on 2026-08-06:

- The `unciv-v3-worker-benchmark` binary now includes a
  `lobby_terrain_preview` measurement (schema version 3). Each iteration
  creates a fresh tiny Pangaea game, then projects the lobby terrain from the
  resulting snapshot through the read-only `project_lobby_terrain` worker
  intent. No generation happens for a preview.
- On the Windows development host with worker build 4.21.4, the tiny-map
  lobby terrain projection measured p50 8.28 ms, p95 24.28 ms, mean 10.73 ms
  across 10 samples. This is well under the 1.5-second reconciliation poll
  interval and confirms the preview never blocks lobby reconciliation.
- `docs/benchmarks/authoritative-multiplayer.md` now has a measured row for
  snapshot-load-plus-terrain-projection. Formatting, warnings-as-errors
  Clippy, and library tests pass.

## AI traits surfaced in browser and match summary

Implemented on 2026-08-06:

- The lobby browser card now shows the AI count alongside the host, ruleset,
  and map info. The "Your matches" card shows the AI count alongside the
  lifecycle status and revision. The staging room's match summary panel lists
  each AI seat with its pinned civilization, difficulty, and personality when
  the host authored a roster.
- `GameSummary` carries a new `ai_count` field derived from the lobby setup's
  `major_civilizations` minus `human_slots` in the `list_games` SQL query. The
  `ApiV3Lobby` already exposed the full `aiCivilizations` roster; the
  `ApiV3GameSummary` Kotlin DTO now carries `ai_count`. The generated OpenAPI
  contract includes the new field.
- Focused Rust library tests (196 passed), formatting, warnings-as-errors
  Clippy, and the authoritative Kotlin client suite pass. A live disposable
  PostgreSQL 19 Beta 2 + packaged worker + Rust API stack confirmed the field
  is returned correctly for a four-major, two-AI lobby.

## Civilization-style multiplayer front end and committed-map preview

Implemented on 2026-07-30:

- The production multiplayer front end is rebuilt as three Civilization-shaped
  surfaces sharing one visual language (`AuthoritativeLobbyChrome.kt`):
  `MultiplayerScreen` is a game browser (server/account header, live
  name/host/ruleset filter, occupancy and access badges, own matches),
  `AuthoritativeCreateLobbyScreen` is a short host step with a leader portrait,
  and `AuthoritativeLobbyScreen` is a staging room with nation-portrait player
  rows, inline own-faction claiming, readiness badges, inline room chat, a match
  summary, and the committed-map preview. Desktop lays out three columns;
  `isNarrowerThan4to3()` collapses to stacked `ExpanderTab` sections for touch
  and portrait.
- Owner settings are live. Every editable control in
  `AuthoritativeGameSetupEditor` and `AuthoritativeLobbyConfigurationEditor`
  raises one edit hook; the staging room debounces a 0.9 s window into a single
  revisioned `lobby_reconfiguration`, commits nothing when the edit resolves to
  the already-committed setup, and coalesces rather than racing an in-flight
  revision. The explicit "Apply lobby settings" button is gone. The room is
  assembled once and refreshed panel by panel, so an owner's open control keeps
  focus and value while players, readiness, chat, and the map update live; only
  an actor role change rebuilds the workspace.
- New pregame map disclosure: `GET /api/v3/lobbies/{game_id}/map-preview`
  returns `LobbyMapPreview` (`game_id`, `preview_version`, `lobby_revision`,
  `canonical_state_hash`, `terrain`). `LobbyTerrainProjection` is a closed DTO of
  `worldWrap`, a bounding box, a sorted terrain-name palette, row-major palette
  indices, and flat unlabeled start coordinates. Nothing else: no units, cities,
  resources, improvements, natural wonders, ownership, civilization identities,
  or turn state, and no association between a start and a civilization.
- No generation happens to serve a preview. Every committed lobby revision
  already stores its own fully generated snapshot, so the new read-only
  `project_lobby_terrain` worker intent only loads and projects one. The route
  requires a seated `owner`/`player` membership and `started_at IS NULL`; the
  `lobby_started` gate on the gameplay-projection path in `postgres/games.rs` is
  unchanged, so a running match is still unreadable through it. Both the Kotlin
  worker and the Rust control plane assert `is_consistent()` fail-closed and
  treat a malformed projection as a protocol fault.
- The client renders the projection by materializing the projected coordinates
  verbatim into a bare `TileMap` and reusing the existing WorldScreen-free
  minimap renderer, so the drawn grid cannot disagree with the server and the
  client never imports `GameInfo`, `GameStarter`, or `MapGenerator`. Tile size is
  measured from the real hex layout, so every admitted shape and custom radius or
  rectangle fits. A terrain name this client's ruleset cannot resolve greys out
  instead of throwing. The preview is fetched once per committed
  `lobby_revision`, not per 1.5 s reconciliation poll.
- Compatibility moved together: `worker_protocol_version` 5 to 6 (Kotlin
  `EngineWorkerProtocol.VERSION` and Rust `WORKER_PROTOCOL_VERSION`), a new
  `lobby_terrain_projection_version` 1 in `release/compatibility.json` and
  `CompatibilityContract`, the regenerated OpenAPI contract, and the explicit
  route inventory in `api/tests.rs`.
- This changes the pregame confidentiality boundary: every lobby member now sees
  the terrain before turn 1, which removes pre-turn-1 map secrecy as a gameplay
  dimension. It is an accepted product decision, granted equally to all members
  and bounded by the closed DTO above; it is recorded in AGENTS.md's V3 lobby
  invariant and in
  `docs/security/authoritative-multiplayer-threat-model.md`.
- Repairs found during this work: the previously committed lobby editors used
  the deprecated libGDX `SelectBox.hideList`, which failed the warnings-as-errors
  Kotlin build at `f5327ceb0`; both call sites now use `hideScrollPane` and a
  routing test forbids the old overload returning. The staging-room chat draft
  initially used `onActivation`, whose `Tap` equivalence would have sent the
  message when a player merely tapped the field to type; it is now registered as
  `ActivationTypes.Keystroke` with `noEquivalence = true`. Live panels initially
  lost their card rules on refresh because they re-added only the caption; a
  shared `LobbyChrome.resetCard` now rebuilds caption and rule together.
- Source-text routing invariants were updated rather than weakened. Every
  authority assertion is retained (no `GameInfo`/`GameStarter`/`playerId`/
  `NewGameScreen`, typed transport only, `observeLobby` plus the poll fallback,
  responsive layout, victory filtering) and new ones were added for debounced
  auto-commit, no-op edit suppression, preview read-only-ness and per-revision
  fetching, and reachable room chat. The brittle header substring assertion was
  replaced by a skin-safety sweep over all six multiplayer surfaces that forbids
  unskinned `Table`, raw `Table.add(String)` cells, and reading `stage` from
  inside a `Table.apply` block.

## Warning-free shared engine and client builds

Implemented on 2026-07-30:

- Kotlin warnings are now build failures in the root multiplatform project,
  build tooling, core, tests, private server worker, desktop, and Android.
  Incremental compilation can no longer conceal a clean-build warning backlog.
- The clean compiler initially reported 389 shared-engine and UI warnings:
  377 deprecated tile/unique traversal calls plus 12 unnecessary null,
  conversion, safe-call, and constant-condition constructs. Call sites now use
  explicit lazy sequence APIs whose names preserve allocation and
  short-circuiting intent; callback forms remain available for hot paths.
- Compatibility-only references remain narrowly suppressed at their exact
  owners: the old specialist-food unique for legacy rulesets, the serialized
  victory-stat field for old saves, and the Coast name for legacy rulesets and
  test fixtures. No project-wide warning or deprecation suppression was added.
- The first full gameplay run found a recursion between the new interface alias
  and `BaseUnit`'s UnitType-aware override. The minimal repair makes the
  interface alias read its own unique map and gives `BaseUnit` an explicit
  UnitType-aware lazy override. A dedicated regression test proves UnitType
  uniques remain visible through that API.
- Focused tile/command-bus tests, the complete 1,140-test gameplay suite, the
  private packaged-worker server suite with the representative Authoritative
  V3 Parity mod, desktop distribution, and Android debug/release APK and AAB
  builds pass with warnings denied. The accepted self-contained desktop
  package continues to use `jpackage`; Packr's upstream reflection diagnostic
  is not part of the release path.
- This maintenance changes shared iteration entrypoints but not canonical
  authority: V3 clients remain projection-only, while all gameplay and AI
  execution remains inside the packaged Kotlin worker controlled by the Rust
  API. Rust protocol, OpenAPI, PostgreSQL schema, and production routing are
  unchanged.
- Runtime commit `db743ddf4e053703c663a7cafe6fbce60f7084f9` is deployed at
  `https://unciv.rusticstack.com` in verified ARM64 bundle
  `ab0fe00bc469d2b023f62fe8ffa6bf5dd4de3f8bf971f80242c21349f7319704`
  with 46 artifacts and a Syft 1.49.0 SPDX SBOM. Migration and ruleset one-shot
  services exited 0, the worker is healthy, and public `/healthz` and `/readyz`
  report protocol 4 with PostgreSQL and the engine worker ready. The API mount
  is the new immutable bundle and its startup log reports engine build 4.21.4
  with two installed rulesets.
- Fresh pilot artifacts are in
  `deploy/Unciv-V3-warning-clean-final-20260730`: direct-install APK
  `E4FA7B9AB13D8437572B16EC7A7B2A331AEC48C2BC4D7D555FFE1CF6CD2C3781`,
  portable Windows ZIP
  `E7A71CCE04AE9C13745F0227759D83506D9F25EE66A2F617B9A937668AB33B00`,
  AAB
  `2F796093ACC51521C55D891D2A07877F6C5918C51CF124C83EEB8F3C6DA5FF4`,
  and desktop JAR
  `E6F6FEFFD5F61DEA1EB3389D130435C0C679914B21053F4C06C5740D2163C31A`.
  The APK verifies with v1/v2/v3 signatures, installs, and resumes
  `AndroidLauncher`; the portable `Unciv.exe` passed a 12-second smoke launch.
- Deployment repair evidence: the first remote Cargo attempt used the
  unprivileged account against the existing root-owned release target and was
  denied at `.cargo-build-lock`; a direct root invocation then lacked that
  account's Rustup selection. The final bounded command kept the existing
  root-owned target and explicitly reused `/home/ubuntu/.rustup` and
  `/home/ubuntu/.cargo`, after which the five release binaries completed. A
  later combined Compose command returned nonzero only because `compose wait`
  queried one-shot containers after completion; direct inspection confirmed
  both exited 0 and the worker/API were running. No source, database, or
  credential repair was required.

## Revisioned live lobby configuration

Implemented on 2026-07-29:

- The API-v3 staging room is now mutable without becoming client-authoritative.
  Owners can edit match name, human capacity, password policy, map generation,
  game rules, victory conditions, seed, and advanced options; every joined
  account can change only its own faction to an unclaimed worker-approved
  civilization. All changes reset readiness and publish compact resync hints.
- PostgreSQL migration `0031_revisioned_lobby_reconfiguration.sql` adds a
  meaning-bound operation journal, password identity needed for safe retries,
  and the append-only `lobby_reconfiguration` revision kind. The transaction
  locks the lobby/head/members, invokes the private worker with exact human
  assignments, stores a new immutable snapshot, advances canonical and lobby
  revisions, updates membership factions, resets readiness, and inserts the
  outbox event together.
- Worker protocol v4 rebuilds pregame state from the pinned manifest, typed
  setup, server seed, and exact server-owned participants. It rejects forged
  owners, duplicate accounts, duplicate factions, spectators, unavailable
  civilizations, and over-capacity assignments. No snapshot crosses the public
  route or enters the operation request.
- Server-generated map seeds are now written back into the canonical lobby
  setup at creation. Reopening or changing a faction therefore preserves the
  same generated map unless the owner intentionally changes its visible seed;
  private runtime randomness remains server-owned.
- The shared desktop/Android UI reuses the mature `NewGameScreen` controls as
  an editor and returns the revisioned server response to the live lobby. A
  dedicated labeled popup owns access/capacity/password policy, and faction
  selection is actor-scoped. The editor retains one operation ID through
  resize and ambiguous retry.
- Focused evidence passed on
  `postgres:19beta2-alpine@sha256:bc62313e826eb44d5f608425b7665962b72820e686da017799e906604bfeb8a5`:
  three creation/retry tests, two transactional reconfiguration tests, two
  existing lobby tests, and the reconciliation, repair, and retention tests all
  passed serially. Packaged-worker lifecycle/parity/protocol tests, Kotlin
  setup/session/production-routing tests, the complete `:tests:test` and
  `:server:test` suites, the complete Rust all-target/all-feature suite, strict
  Rust Clippy, and the packaged Android-to-desktop handoff also passed. The
  handoff now performs both public reconfiguration routes before
  readiness/start, resumes the owner on desktop, and advances server AI. The
  real two-person Domination terminal run remains the separate unchecked P0
  release-acceptance item in `missing_multiplayer.md`.

## V3 lobby client candidate

Rebuilt from the final lobby implementation and qualified on 2026-07-29:

- `:server:authoritativeWorkerDist`, `:android:assembleRelease`,
  `:android:bundleRelease`, and `:desktop:dist` passed together.
  The final self-contained portable archive was produced with the installed
  Temurin 21 `jpackage --type app-image`, as recommended by Packr's current
  documentation. This removes Packr 4.0.0's unsupported-reflection diagnostic
  from the accepted artifact path while preserving the same qualified desktop
  JAR and a private Java 21 runtime.
- The non-debuggable release APK was aligned and signed with the repository's
  testing certificate. Signature schemes v1, v2, and v3 verify. It installed on
  the connected emulator, and the manifest-resolved
  `com.unciv.app/.AndroidLauncher` became the resumed activity with a live app
  process. This testing certificate remains unsuitable for Google Play.
- The portable archive contains `Unciv.exe` and a private runtime. It was
  extracted into a clean directory and remained alive through a 12-second
  smoke launch.
- Candidate artifacts are in
  `deploy/Unciv-V3-live-lobby-final-20260729`.
  SHA-256 values:
  `713B9723898D0BED3EE1E33754C25A376E6DDE9982957B3B246C484F4DF092F7`
  (direct-install APK),
  `5520D8DC401AE042A8E6B998C39F89689F895CDDF1DA91161EC8AFE1B9F31B18`
  (portable Windows ZIP),
  `33A1B6BED31F123275D7CBA9155D6ED3CFBB3AC16EC7402D36064AE65D921149`
  (desktop JAR), and
  `A13528F7AB7AA271D011671A791F8B91DD26E3B49E2E061F04C5C39F874D31C0`
  (Android App Bundle).
- The target Linux build exposed one pre-existing deprecated
  `getTilesInDistance` call in `WorldScreen`. Its bounded owner now uses
  `forEachTileInDistance`; `:core:compileKotlin`, the complete game/core tests,
  complete server tests, and both client builds passed without that warning.
  The first remote Rust build also found that root's secure-path environment
  omits Cargo; deployment uses the root-owned Cargo binary by absolute path
  rather than weakening sudo policy.
- ARM64 bundle
  `cba3979ce7fa0bf28ca8c1681858f4a4a9dd09633a9cc62e99b3b68a3fe3212d`
  contains 46 verified artifacts built from runtime revision `7ccacf397`.
  The Oracle pilot applied migration 31, reacquired the active vanilla ruleset,
  restarted the authenticated worker and API, and remained ready through Caddy
  at `https://unciv.rusticstack.com`. The live OpenAPI advertises both lobby
  mutation routes. A disposable HTTPS account discovered manifest
  `ffb1743100cd04c92fbb0360cd2abd18c203918ac364b6f1ca349cf4d06fd575`,
  created a lobby at revision 0, changed owner configuration at revision 1,
  selected a different worker-approved faction at revision 2, then closed the
  smoke game and deleted the account.

## Direct-install Android and portable Windows match clients

Qualified on 2026-07-29:

- The Android build no longer enables the unused deprecated RenderScript
  pipeline. The intentional API 21-22 `KeyPairGeneratorSpec` compatibility
  path has a documented file-scoped suppression; API 23+ continues to use
  `KeyGenParameterSpec`. The already-unstrippable libGDX native library is
  declared explicitly instead of producing a misleading strip warning.
- The JVM test runtime now includes the repository's aligned Logback provider
  and deliberately disables class-data sharing when the required Mockito agent
  is attached. This removes the missing-SLF4J-provider and bootstrap-classpath
  warnings without hiding application diagnostics.
- `:tests:test`, `:android:assembleDebug`, `:android:assembleRelease`, and
  `:desktop:dist` passed together after the changes. The project-owned
  `:desktop:packrWindows64` task produced a portable archive containing
  `Unciv.exe`, `Unciv.jar`, its configuration, icon, native libraries, and a
  private Java 21 runtime.
- The non-debuggable release APK was signed with the fork's existing testing
  certificate for direct pilot sideloading. Android signature schemes v1, v2,
  and v3 verify, the APK installed successfully, and `AndroidLauncher` became
  the resumed emulator activity. This testing certificate is not a production
  or Google Play signing identity.
- The portable ZIP was expanded into a clean directory and its packaged
  `Unciv.exe` remained alive through a 12-second smoke launch. Neither client
  requires a separately installed Java runtime or game-data download.

## Whole-game start-of-turn consensual rewind

Implemented on 2026-07-29:

- Active owners and players can list retained server-projected checkpoints,
  propose one exact target against the current head, and approve or reject it
  from the desktop/Android shared production UI. Checkpoints are genesis or
  accepted `EndTurn` results only. Because `EndTurn` includes server-owned AI
  execution and human rotation, each target is the complete start of the next
  human turn rather than the end of the previous player's action sequence.
- The proposal freezes every then-active human account. The proposer
  auto-approves; all frozen accounts must approve; a refusal closes the
  request; changed votes conflict; and head or electorate drift expires it.
  Pending changes publish compact outbox resynchronization hints.
- Unanimous finalization integrity-checks the retained blob and asks the
  private Kotlin worker to load and project it with the pinned manifest. One
  transaction copies the exact whole-game bytes into a new immutable snapshot,
  appends a `rewind` revision, advances the CAS head, marks the request
  applied, and emits `game.revision.rewound`. Later history remains intact.
- Migrations `0028` and `0029` add database-enforced parent, command-journal,
  and outbox canonical-lineage foreign keys plus frozen request/electorate/vote
  records. PostgreSQL 19 Beta 2 focused tests reject all three orphan shapes
  and prove rejection, changed retry, worker-failure retry, exact snapshot
  copying, immutable lineage, and one committed rewind outbox event.
- A digest-pinned single-VPS Compose topology mounts the attested release
  bundle read-only, isolates PostgreSQL, and shares only loopback between the
  Rust API and Kotlin worker. Clients accept a literal plaintext IP for an
  explicitly insecure pilot; production hostnames remain HTTPS-only.
- Final implementation qualification used
  `postgres:19beta2-alpine@sha256:bc62313e826eb44d5f608425b7665962b72820e686da017799e906604bfeb8a5`.
  Both deterministic rewind database cases passed serially, all three
  canonical-lineage orphan inserts were rejected by PostgreSQL, the complete
  Rust all-target/all-feature suite passed (190 library tests plus API and
  integration targets), and warnings-as-errors Clippy and formatting passed.
  The focused production-routing/session/release-contract Kotlin tests passed,
  followed by `:tests:test`, `:server:authoritativeWorkerDist`,
  `:desktop:dist`, `:android:assembleDebug`, and
  `:android:assembleRelease` in one successful 103-task Gradle invocation.
  `docker compose ... config --quiet` also accepted the VPS topology with
  operator-placeholder secrets and an absolute release-bundle path.

## Human-match client candidate

Built and qualified from clean commit `a42aa6263` on 2026-07-29:

- `:server:authoritativeWorkerDist`, `:desktop:dist`,
  `:android:assembleDebug`, and `:android:assembleRelease` passed together.
  Release lint-vital passed. The installable testing APK is the repository
  debug-signed build; no production Android signing credential is present.
- The packaged `account_handoff` PostgreSQL 19 Beta 2 preflight passed again
  with an Android-labelled owner session, a fresh desktop session for that
  account, a separate friend account, three major civilizations, and the
  intervening faction controlled by the packaged server worker.
- The complete focused `com.unciv.logic.multiplayer.authoritative.*` client
  suite passed, including exact production route coverage, projection-only
  controls, account/session lifecycle, invitation/join flows, reconciliation,
  turn notifications, and terminal-state behavior.
- The signed APK installed and launched on a connected Android emulator; its
  `AndroidLauncher` remained the resumed activity with a live
  `com.unciv.app` process. The self-contained Windows desktop image and the
  standalone desktop JAR each remained alive through a 12-second smoke launch.
- Candidate SHA-256 values are
  `4719041DBF2E1619061E8562350CA6CBD8511BD8ACF1D6D0ECCC221C133AA310`
  for the APK,
  `B20F0A92E15A1C1F82B28E08337E49E79902FF382FC9B39662A86E453104F906`
  for the portable Windows ZIP, and
  `A9F29A9658830B415D580FC5D08F5EF50B1D882D74C36AFFC9972CBD8E86BBCA`
  for the desktop JAR.
- These results prove the candidate clients and automated human-plus-AI
  protocol preflight, not the still-required real two-person Domination match.
  Preserve that match's evidence and mark the P0 checklist only after both
  people complete the qualification procedure.

## Ktor and transitive dependency security refresh

Implemented and qualified on 2026-07-29:

- The public Kotlin server is aligned on Ktor 3.5.0 and Logback 1.5.34.
  An enforced Netty BOM keeps its complete runtime family on 4.2.16.Final
  instead of accepting Ktor's older transitive selection.
- Android Gradle build tooling is forced above the repository's GitHub
  vulnerability findings: Bouncy Castle 1.84, Commons Compress 1.26.0,
  Commons Lang 3.18.0, JDOM 2.0.6.1, jose4j 0.9.6, Netty 4.1.136.Final,
  and Protobuf Java/Kotlin 3.25.5.
  These are tooling security floors and can be removed once the Android Gradle
  Plugin's own graph reaches or exceeds them.
- GitHub's submitted dependency artifact exposed additional AGP-created
  detached test-tool configurations that bypassed a classpath-only force and
  still contained Netty 4.1.93/4.1.110 and Protobuf 3.24.4. Every project
  configuration now upgrades only affected Netty 4.1 and Protobuf modules
  below the reviewed floors; Netty 4.2 and future versions are left untouched.
  Hosted dependency submission is the closure gate for those detached graphs.
- Gradle dependency evidence resolves server Netty to 4.2.16.Final and the
  build-tool Netty family to 4.1.136.Final. Android debug Kotlin compilation
  and all 60 server tests pass on the final graph. The complete core test suite
  is recorded with the verification health below.
- This refresh changes no V3 command, projection, persistence, or authority
  boundary. It does harden the public Ktor transport and the tooling that
  produces Android artifacts, so future Ktor/Netty/Android Gradle Plugin
  changes must preserve whole-family alignment and rerun V3 server, Android,
  and dependency-submission gates.

## Upstream synchronization and V3 turn notification closure

Implemented and qualified on 2026-07-28:

- `upstream/master` through `94a80907e` was merged into fork `master` as
  `ae326a22d` without dropping V3 work. All hosted workflows for the merge are
  green. Conflict resolution retained deterministic map placement and adopted
  upstream city views, city-state start biases, AI/combat fixes, and canonical
  per-city construction automation state.
- The authoritative worker now uses upstream's encapsulated city tile mutation
  APIs. Per-city and all-city construction disable/enable choices extend the
  existing closed `manage_construction_queues` command across projection,
  client, Rust, worker wire, and Kotlin execution; clients cannot submit target
  city sets or outcomes.
- Android now runs a unique background V3 poller backed by the protected token
  store. It lists account memberships, reads player projections only, derives
  `isCurrentTurn`, and deduplicates by committed revision. API 23 connected
  instrumentation ran two tests successfully, including protected credential
  round-trip and duplicate-scheduler collapse.
- Foreground desktop V3 reconciliation now requests OS attention exactly once
  on a false-to-true turn transition using the existing GLFW/Windows fallback.
  This is the supported in-app-only policy for Windows, macOS, and Linux; Unciv
  does not install a background tray daemon when the desktop process is closed.
- Focused Kotlin worker, command-bus, session, polling, production-routing, and
  upstream city-state tests pass. The complete core suite (`:tests:test`) and
  all 60 server tests pass serially, with only their documented intentional
  skips. Android debug Kotlin compilation passes. Rust's 190 active library
  tests, 29 API binary tests, and all ordinary non-environment-gated
  all-target tests pass; `cargo fmt`, generated OpenAPI/AsyncAPI parity, and
  warnings-as-errors Clippy are clean.
- A new ignored packaged-stack `account_handoff` preflight passed against the
  exact PostgreSQL 19 Beta 2 digest. Separate Android-labelled and freshly
  restored desktop sessions for one account reopened the same revision and
  projection hash; a second account joined; and a desktop resignation advanced
  through the remaining packaged-worker AI to that second human.
- The preflight exposed a previously missing terminal contract. Projection v60
  and spectator projection v2 now publish only the canonical winning
  civilization, victory type, and victory turn. Terminal projections advertise
  no turn actions, the projection-only UI renders the result, the worker
  rejects every later mutation, and `GameInfo.nextTurn()` stops AI processing
  once canonical victory data exists.
- Fresh-process parity after the upstream merge exposed two additional
  nondeterministic inputs: `GameContext` delegated RNG seeding to JVM object
  identity, and upstream region-start selection used identity-hashed
  `Tile` sets without tie-breaks. RNG context now hashes only serialized
  identities/coordinates; new-game setup uses the canonical game UUID; region
  starts and lake traversal have coordinate tie-breaks. The complete packaged
  server suite, including generated-map, espionage, city-context, and
  diplomatic-marriage fresh-worker parity, passes.
- The actual two-person Android/desktop Domination release run remains
  deliberately unchecked. Letting both clients resign and attempting to finish
  the match as one synchronous all-AI worker request exceeded the bounded
  execution model and was not accepted as equivalent evidence. The exact human
  run is specified in
  `docs/operations/authoritative-full-match-qualification.md`.

Audited on 2026-07-28 after fast-forwarding V3 to `master`:

- Server authority, typed gameplay reachability, packaged-worker AI/mod parity,
  foreground WebSocket reconciliation, account game discovery, and
  platform-protected session storage remain verified.
- Android background V3 turn polling and foreground desktop attention are now
  implemented as described above. Legacy polling remains isolated for API v1/v2.
- The server permits multiple active sessions per account, both Android and
  desktop have protected token stores, and authenticated game discovery opens
  server-owned projections. The packaged-stack preflight proves an
  Android-labelled session handing the same account to a fresh desktop session
  at an identical revision and projection hash. The remaining manual evidence
  is the production Android/desktop UI handoff inside a two-human match played
  through Domination.
- The complete command inventory, representative AI turns, mod parity, and
  constrained-load scenarios do not constitute a two-human match played from
  creation through an actual Domination terminal state. That qualification is
  now explicitly P0 in `missing_multiplayer.md`; the earlier “final
  end-to-end playability” heading below describes the authority and route audit,
  not proof of a complete human match.

## Attested constrained Linux capacity qualification

Qualified on 2026-07-28:

- Immutable tag `authoritative-v3-0.1.0-beta.2.8` fixes commit
  `1fb6d174c3316f8755570f583e25b94a799a2643`; hosted supply-chain run
  `30393852174` passed source SBOM, complete-history secret scan, Node 24
  RustSec audit, production bundle construction/verification, exact
  PostgreSQL/Kotlin/Rust smoke, worker kill/restart, constrained load, both
  attestations, and retained-evidence upload.
- The exact production stack completed 60 Large-game creation, projection, and
  server-AI scenarios in 152.586 seconds under hard limits of 1 CPU, 992 MiB,
  and disabled swap. Eight commands contended at the same revision for every
  turn: exactly 60 committed, all 420 losing requests returned the expected
  stale conflict, and 120 WebSocket notifications arrived.
- Large creation measured 395.87-ms p50/894.98-ms p95; projection measured
  86.52-ms p50/185.52-ms p95; contended end turn plus server AI measured
  1,510.21-ms p50/3,301.81-ms p95. The whole scenario measured 2,029.31-ms
  p50/4,589.46-ms p95.
- Peak CPU/memory was 5.04%/22.39 MiB for Rust, 6.76%/82.39 MiB for
  PostgreSQL, and 66.65%/229.20 MiB for Kotlin. PostgreSQL grew 6,021,120
  bytes. The adjacent production smoke observed fail-closed readiness after
  worker death and restored readiness 1,122 ms after packaged-worker restart.
- The retained archive independently matches SHA-256
  `37a0fcaf4767f577c7daacfa212e368080bf955794ccd0fde54b1644e93a1ef5`.
  Separate `gh attestation verify` calls accepted its SLSA provenance and SPDX
  document predicate for `KelpHect/Unciv`.
- This establishes a defensible floor for the exact workload, not an
  unlimited-user statement or SLA. Late-game saves, enabled mods, geography,
  retention, and production hardware still require deployment-specific
  measurement. The sole unchecked checklist item is the externally blocked
  rehearsal against a PostgreSQL 19 prerelease newer than Beta 2.

## Final end-to-end playability, mod, and authority audit

Qualified on 2026-07-28:

- The production projection client exposes all 80 typed gameplay/session
  command methods. A source inventory fails if any gameplay `*IfOpen` method is
  not reachable from production V3 UI, and a separate exact-set comparison
  fails if the public OpenAPI gameplay routes and Kotlin client transport ever
  differ.
- The projection-only world has no `GameInfo`, `GameStarter`, local
  `nextTurn`, legacy upload/download, or client autoplay path. Every accepted
  action is reconstructed from the returned projection; stale, offline, or
  ambiguous state disables new input until HTTP reconciliation or an exact
  idempotent retry. Whole-save multiplayer elsewhere must explicitly opt into
  the isolated `.legacy` façade.
- Production new-game routing sends the selected base-ruleset name and exact
  mod-name set to authenticated server manifest resolution, then opens only the
  returned player projection. It cannot construct or upload a local online
  game.
- Two fresh packaged Kotlin worker processes loaded the `Authoritative V3
  Parity` fixture and independently executed a stable scenario containing
  mod-defined map generation, construction, purchase, and transformation
  behavior. The worker validates the server-installed content-addressed
  base-plus-mod manifest; client-local content is never accepted.
- End turn loads the canonical snapshot in that same private worker and runs
  all AI civilizations through the shared Kotlin engine before Rust may commit
  the result. The public schema deliberately has no autoplay or AI-outcome
  command, and the client never advances an AI player.
- The final threat-model review covers the client, public API, worker, mods,
  PostgreSQL, projections, legacy boundary, recovery, operations, and release
  supply chain. No untracked V3 gameplay mutation family and no public/client
  canonical-state replacement path remain.

Verification on 2026-07-28:

- `AuthoritativeProductionRoutingTests` executes and passes after adding exact
  base/mod routing guards. It also enforces all 80 production command
  references, exact OpenAPI/client route parity, projection-only UI isolation,
  server-only AI, explicit legacy access, and typed account/administration
  routing.
- `PackagedWorkerParityModTests` executes and passes with both fresh worker
  processes loading the test mod and producing the same stable state.
- The complete `:android:assembleDebug :android:lint :tests:test :server:test
  :desktop:dist --no-parallel` regression passes in 3m34s. This includes the
  supported Android/desktop clients, single-player/hotseat/save and legacy
  regressions, authoritative client suite, server worker suite, every
  generated-game setup parity scenario, mod parity, and server-AI turn cases.
- Rust formatting and warnings-as-errors Clippy pass. All 189 active library
  tests, 29 API/OpenAPI/AsyncAPI tests, both load/benchmark unit pairs, and all
  active packaging, security, observability, and operator policy/runtime tests
  pass. Environment-gated destructive cases remain covered by their dedicated
  qualification lanes.
- On a fresh disposable exact-digest PostgreSQL 19 Beta 2 instance, migrations
  1-27, the schema/timeout test, and all 39 ordinary serialized persistence,
  authorization, concurrency, recovery, reconciliation, notification, and
  malicious-client tests pass. The instance was removed afterward. The final
  rerun used a clean database; earlier fixture attempts that mixed production
  roles or reused test state were rejected as evidence.

## Attested Linux production-stack smoke

Qualified on 2026-07-28:

- Immutable tag `authoritative-v3-0.1.0-beta.2.4` fixes commit
  `79808f736` and hosted run `30388093014`. Its verified release bundle includes
  the Rust API and tools, private Kotlin worker, ruleset manager, desktop engine
  input, exact vanilla manifest, migrations 1-27, operational units/config, MPL
  license, and SPDX 2.3 SBOM.
- The tagged Linux smoke boots the exact PostgreSQL 19 Beta 2 image digest,
  waits for the image's final post-bootstrap server, creates the five production
  roles, migrates as `unciv_migrate`, runs the API as `unciv_runtime`, and starts
  only bundled Rust/JVM artifacts without the unpackaged-development override.
  Health, three-way readiness, and account registration pass. Killing the
  worker makes readiness fail closed with HTTP 503 while PostgreSQL remains
  ready; restarting the packaged worker restores readiness.
- Three immutable predecessor tags remain useful negative evidence and were
  never moved: `.2.1` caught missing production-role bootstrap, `.2.2` caught
  the official image's temporary-bootstrap readiness race, and `.2.3` caught
  the JVM-listener startup race. Each failed before attestation. The final gate
  requires both the official PostgreSQL final-start marker and a live worker
  listener.
- The retained `.2.4` archive independently matches SHA-256
  `c16f6d40bffe0c3424fa2f8b15b64287bb19aeef4413196ac472bedd89c38962`.
  Separate `gh attestation verify` calls accepted its SLSA provenance and
  `https://spdx.dev/Document/v2.3` predicate for `KelpHect/Unciv`.
- Linux production smoke and supported desktop/Android reconnect/build evidence
  now close that P2 item. PostgreSQL's later-prerelease upgrade rehearsal remains
  honestly blocked because Beta 2 is still the newest PostgreSQL 19 image.

## First hosted production-bundle attestations

Qualified on 2026-07-28:

- Annotated tag `authoritative-v3-0.1.0-beta.2` fixes commit
  `f584c22d0aa9d1d60e9156f866390cce9e497335`. Hosted GitHub Actions run
  `30383681835` passed source-SBOM generation, complete-history secret scanning,
  RustSec audit, the full Java 21/Rust 1.97.0 Linux build, ruleset-manifest
  validation, closed-bundle creation/self-verification, both OIDC attestation
  steps, and retained-evidence upload.
- The retained archive independently matched SHA-256
  `7a97b727b14c7f6dde3d24b577756b76c594e941f5040c018265c622923cb97f`.
  `gh attestation verify` accepted its SLSA provenance and, in a separate
  invocation, the `https://spdx.dev/Document/v2.3` predicate against
  `KelpHect/Unciv`. Both bind the exact archive digest and immutable tag; the
  provenance additionally binds the complete commit.
- This closes the supply-chain P1 evidence gap. It does not substitute for the
  still-required full-stack Linux service smoke or a PostgreSQL 19 upgrade to a
  later prerelease that does not yet exist.
- Product review also resolved the conditional player-visible administration
  history item as unnecessary: current lifecycle, ownership, membership, and
  mutation controls are complete, while immutable audit/outbox history remains
  intentionally operator-only.

## Enforced MPL/AGPL provenance boundary

Implemented and qualified on 2026-07-28:

- The repository remains MPL-2.0. A focused source, history, Cargo, and Gradle
  audit found no copied, adapted, vendored, generated, or dependency material
  from the separately AGPL-3.0 `hopfenspace/runciv` project. Its only
  production-source mention is a pre-existing 2023 API-v2 documentation link.
- `docs/legal/authoritative-multiplayer-licensing.md` records the provenance
  result and makes future copying, translation, adaptation, or vendoring fail
  the contribution gate unless a reviewed compatible basis and preserved
  notices exist.
- Every production release bundle now includes the repository license at
  `legal/LICENSE`. It is part of the closed content-addressed manifest, so
  removal or alteration fails verification; the mandatory SPDX 2.3 SBOM
  continues to carry dependency-license evidence.
- The release runbook's previously stale migration range and compatibility
  head were corrected from 24 to the current checked-in head 27.

Verification on 2026-07-28:

- `cargo test --test licensing_boundary -- --nocapture` passes both provenance
  and dependency-boundary tests.
- `cargo test --lib release_bundle::packaging::tests -- --nocapture` passes the
  content-addressed bundle test, including explicit rejection of a missing
  `legal/LICENSE`.
- `cargo fmt --all -- --check`, warnings-as-errors
  `cargo clippy --all-targets --all-features -- -D warnings`, and
  `git diff --check` pass.

## Disposable client cache and explicit retry UX

Implemented and qualified on 2026-07-28:

- The shared desktop/Android API-v3 command bus now has an explicit
  `OfflineCached` state. A failed HTTP reconciliation retains only the last
  player projection for presentation; it cannot satisfy the synchronized-state
  precondition for a command. A successful reconnect replaces it from
  authenticated HTTP without merging or advancing local state.
- The projection world labels offline content as read-only and disables every
  projected input. Its reconnect control replaces the cache. An ambiguous
  command instead hides generic refresh and exposes a dedicated retry routed to
  `retryPendingIfOpen`, which resubmits the exact retained command identity and
  meaning. This prevents cache refresh from discarding a possibly committed
  idempotency key.
- Projection compatibility remains negotiated before game opening. A client
  never attempts a trusted local schema migration: incompatible projection
  versions fail closed and require a compatible client build; a compatible
  client reconstructs from the server. Multiplayer selection continues to
  label `API v3 server game` separately from `legacy saved game`.

Verification on 2026-07-28:

- `./gradlew.bat :tests:test --tests
  'com.unciv.logic.multiplayer.authoritative.*' --no-daemon` passes all 272
  authoritative client tests. New cases prove failed refresh retains a
  read-only projection, backward server responses cannot replace it or enable
  commands, reconnect replaces it, and uncertain-command retry preserves the
  exact session command identity.
- `./gradlew.bat :tests:test :android:assembleDebug --no-daemon --no-parallel`
  passes in 2m19s, covering the complete JVM suite and supported Android client
  compilation/packaging. Production-routing assertions keep the retry,
  read-only reconnect, and explicit legacy/v3 labels wired into the real UI.
- The first broad run exposed 18 controller tests whose programmatic exact-action
  retry path was over-restricted. The UI lock was kept, while domain controllers
  may still route the same projected action to the session, where exact pending
  payload comparison and command-ID reuse fail closed on changed meaning. The
  authoritative package and broad suite then passed; no discovered test or
  compile error remains deferred.

## Fleet-wide WebSocket admission and reconnect hardening

Implemented and qualified on 2026-07-28:

- PostgreSQL migration 25 adds indexed `websocket_connection_leases` rows with
  account, replica, acquisition, renewal, and expiry identity. Admission uses
  one transaction-scoped advisory lock, removes expired rows, checks both
  fleet and account counts, and inserts exactly one short-lived lease.
- Every API process owns one random replica UUID. An authenticated upgrade must
  obtain both its existing process-local permit and the fleet lease. A live
  socket renews at a startup-validated interval; a missing, expired, or
  database-unrenewable lease closes the socket fail-closed. Normal exits delete
  immediately, while process-death leftovers expire and are reclaimed by the
  next admission. Replica identity binds renewal and release.
- `UNCIV_V3_WS_LEASE_TTL_SECONDS` defaults to 90 seconds and is bounded to
  30-300. `UNCIV_V3_WS_LEASE_RENEW_SECONDS` defaults to 30, is bounded from 5
  to less than the TTL, and cannot exceed half the TTL. Fleet account/global
  rejections and lease-loss disconnect causes use bounded metric dimensions.
- The Kotlin API-v3 client now uses capped exponential equal jitter between
  125 ms and 10 seconds. The random delay changes transport timing only; it is
  never part of canonical state, worker execution, server RNG, or gameplay.
- A sustained 10,000-hint duplicate/reordered burst test proves the account
  channel stays at 64 entries, reports the exact lag condition, and retains a
  `resync_required` marker. Existing session tests prove duplicate, stale,
  reordered, and explicit resync hints converge by authenticated HTTP.
- `docs/operations/authoritative-websocket-runtime.md` now documents the
  fleet algorithm, environment bounds, crash behavior, and HTTP-source-of-truth
  invariant. Release compatibility is advanced to migration 25.

Verification on 2026-07-28:

- Against only
  `postgres:19beta2-alpine@sha256:bc62313e826eb44d5f608425b7665962b72820e686da017799e906604bfeb8a5`,
  both WebSocket lease tests execute and pass. Two independent pools race
  account and fleet limits and produce exactly one winner; forged replica
  renewal/release fails, an expired crash lease cannot renew, and its capacity
  is reclaimed. All disposable PostgreSQL containers were stopped and removed.
- `cargo test --all-targets --all-features` passes 189 active library tests,
  29 API tests, and every active packaging, observability, policy, and operator
  test. Forty-two library tests remain intentionally environment-gated.
  Warnings-as-errors `cargo clippy --all-targets --all-features -- -D warnings`,
  `cargo fmt --all -- --check`, and `git diff --check` pass.
- `./gradlew.bat :tests:test --no-daemon` passes the complete JVM test suite in
  1m54s. Focused backoff and notification reconciliation tests also pass.
  The hosted server gate then found its separate release-contract assertion
  still expected migration 24; it was advanced to 25 and the server suite was
  rerun locally before the follow-up push.
- The first focused Rust compile found missing `Uuid` imports in split API
  modules; those were fixed. Rust 1.97 then hit an incremental recovery ICE, so
  the clean verification reran with `CARGO_INCREMENTAL=0`. The first database
  filter used `--exact` with a short name and executed zero tests; it was
  rejected as evidence and rerun with filters that executed both cases. The
  migration-version change initially exposed stale Rust and Kotlin release
  compatibility constants; both were updated before their complete green
  reruns. No compile, test, migration, formatting, Clippy, or cleanup error
  remains deferred.
- `main.rs` remains a 6-line bootstrap facade and `lib.rs` a 65-line module
  facade. WebSocket API logic is 496 lines and the new persistence module is
  128 lines; concerns remain in shallow descriptive modules.

## Redacted authoritative observability

Implemented and qualified on 2026-07-28:

- `telemetry.rs` initializes newline-delimited JSON tracing and a Prometheus
  exporter on `127.0.0.1:9464` by default. A configured metrics address must be
  loopback, and the exporter independently allowlists IPv4 and IPv6 loopback.
  Metrics never share the public Axum/Caddy listener.
- Every public request receives a generated correlation ID and a structured
  span with only closed route, method, status-class, and elapsed-time fields.
  Dynamic paths collapse into bounded route classes and attacker-controlled
  methods collapse to `OTHER`; account, game, command, session, network,
  ruleset, snapshot, projection, payload, and credential values are prohibited
  as trace fields or metric labels.
- Low-cardinality counters, gauges, and histograms cover authentication abuse,
  stable API failures, stale conflicts, command commit/latency/failure,
  worker latency/timeouts/capacity/identity/protocol/transport failures,
  PostgreSQL serialization/deadlock/lock/statement timeout conflicts,
  canonical revision growth, player/spectator projection size, security-audit
  write failure, notification runtime/outbox lag/dead letters, WebSocket
  admission/load, and bounded disconnect causes.
- Private worker transport failure logging is centralized. The per-command
  repository adapters no longer duplicate unstructured diagnostics. Worker
  rejection detail, SQL detail, identifiers, canonical state, projections, and
  credentials remain outside normal logs and metrics.
- `observability/grafana-dashboard.json` provides eight operational panels.
  `observability/prometheus-alerts.yml` provides 13 alerts covering every
  required failure class and links each alert to the redacted operator runbook.
  Both artifacts are mandatory, hash-covered release-bundle inputs.
- `.github/workflows/authoritativeV3Observability.yml` is least privilege,
  path-scoped, uses the pinned Rust 1.97.0 toolchain and immutable checkout
  action, runs formatting, warnings-as-errors Clippy, all Rust tests, and the
  official Prometheus parser from an exact image digest.

Verification on 2026-07-28:

- The real loopback scrape integration launched the exporter in an isolated
  process, received HTTP 200, observed the expected bounded command dimensions,
  and found no private identifiers in the exposition.
- Four observability policy tests validate YAML/JSON parsing, all required
  alert classes and runbook links, loopback/systemd/release packaging, exact
  toolchain/image/action pins, and source-wide private-dimension exclusions.
- Prometheus `v3.13.1-distroless` at
  `sha256:214f8427c8fba80c327bb94a75feb802ae12f2d6ca30812aa6e7d22f09bbea80`
  reports `SUCCESS: 13 rules found`. Actionlint `v1.7.12` accepts the hosted
  workflow.
- GitHub Actions run `30373117779` passed the complete new observability job in
  3m56s: pinned Rust installation, formatting, warnings-as-errors Clippy, every
  Rust test, and the digest-pinned Prometheus parser. The same pushed commit
  passed the main build/test run `30373117469`, supply-chain run `30373117412`,
  and conflict-marking run `30373117349`.
- `cargo test --all-targets --all-features` passes 228 library tests, 29 API
  tests, and every active integration/packaging/policy test; only explicitly
  environment-gated destructive/packaged-worker tests remain ignored.
  `cargo fmt --all -- --check`, warnings-as-errors
  `cargo clippy --all-targets --all-features -- -D warnings`, and
  `git diff --check` pass.
- The ordinary serial PostgreSQL lane passes all 34 tests against the sole
  digest-pinned PostgreSQL 19 Beta 2 image. The first qualification attempt used
  the runtime URL where the migration-only URL was required and correctly
  failed before schema creation; the rerun bootstrapped the required
  least-privilege roles, passed without test failures, and removed its
  disposable container, network, and volume.

## OS-protected client session credentials

Implemented and runtime-qualified on 2026-07-28:

- Desktop API-v3 sessions now select one server-scoped OS credential store:
  current-user DPAPI on Windows, Security.framework generic-password items in
  the current user's macOS Keychain, or the freedesktop Secret Service through
  `secret-tool` on Linux. macOS token bytes cross only the native API boundary;
  Linux supplies them only over the child process's stdin. Neither implementation
  puts credentials in arguments, environment variables, preferences, or
  plaintext files.
- Android keeps ciphertext and IV data in app-private preferences. API 23+
  encrypts with a non-exportable Android Keystore AES-GCM key. API 21-22 uses a
  Keystore RSA pair to encrypt a random 256-bit AES key, then uses that key for
  AES-GCM. Token, decrypted key, ciphertext, and IV byte buffers are bounded
  and cleared after use; corrupt credentials fail closed and are removed.
- A dedicated least-privilege workflow exercises the real platform stores on
  macOS 15, Ubuntu 24.04, and Android API 21 and 23. Every action is pinned to
  a reviewed full commit. Emulator installation, AVD creation, device boot,
  and failure diagnostics are bounded so a missing device cannot hang CI.

Verification on 2026-07-28:

- GitHub Actions run `30369675831` passed all four jobs: macOS Keychain,
  Ubuntu Secret Service under a private D-Bus/keyring session, Android API 21,
  and Android API 23. Each test saved an opaque token, loaded it from a new
  store instance, and cleared it. Android additionally inspected app-private
  preferences and found no plaintext token; macOS and Linux expose no
  application-owned credential file or preference layer.
- Local Windows runtime tests passed current-user DPAPI round-trip,
  no-plaintext-at-rest, corrupt-ciphertext removal, and clear behavior.
  Local Android emulators independently passed API 21 (`Android 5.0.2`) and
  API 23 (`Android 6.0`) through
  `./gradlew.bat :android:connectedDebugAndroidTest`.
- The first API 21 runtime test exposed two real compatibility defects that
  compile/lint could not detect: its RSA provider did not support
  `Cipher.unwrap`, and its GCM provider generated a 16-byte IV rather than the
  assumed 12 bytes. RSA encryption/decryption replaced provider-specific
  wrapping, and authenticated IVs are now bounded to 12-32 bytes. Both API
  branches passed after the corrections.
- `./gradlew.bat :android:lintDebug`, focused desktop credential tests,
  production-routing tests, Android instrumentation compilation, and the full
  `./gradlew.bat :tests:test` lane pass. The full lane reports 1,099 tests,
  zero failures, zero errors, and 15 intentional skips.
  Additional gates include
  `cargo fmt --all -- --check`, warnings-as-errors
  `cargo clippy --all-targets --all-features -- -D warnings`,
  `cargo test --test credential_store_workflow_policy`, `actionlint v1.7.12`,
  and `git diff --check` pass.

## Attested complete Linux release bundle

Implemented and locally qualified on 2026-07-28:

- Exact `authoritative-v3-*` tags now build the Rust API, migrator, audit
  exporter, and bundle verifier with locked dependencies under Rust 1.97.0;
  build the worker and desktop JARs under Java 21; derive the vanilla manifest
  from the worker's exact engine/ruleset catalog; and revalidate that manifest
  in the packaged worker.
- The tag lane downloads Syft 1.49.0 only after checking the exact Linux
  archive digest, inventories the reviewed production inputs, validates the
  SPDX, creates and re-verifies the content-addressed release bundle, and
  produces a normalized Linux x86-64 archive plus external SHA-256.
- Two GitHub OIDC-backed `actions/attest` calls sign build provenance and bind
  the embedded SPDX predicate to the complete archive. The workflow retains
  the archive, external digest, bundle manifest, and exact SBOM. Pull requests
  still receive no OIDC, attestation, repository write, secret, or release
  authority.
- The bundle now requires and hashes its own `bin/unciv-v3-bundle` verifier.
  Unix creation gives all four Rust executables exact mode `0555`, and
  verification rejects mode drift as well as content drift. An extracted
  release can therefore self-verify without trusting a separately downloaded
  executable.
- The operator runbook requires external digest verification, GitHub
  provenance verification, SPDX-predicate verification, extracted bundle
  verification, and byte comparison of the separately retained manifest/SBOM.
  Source-only evidence or a locally assembled archive is explicitly
  insufficient for deployment.

Verification on 2026-07-28:

- `actionlint v1.7.12` accepts the updated workflow and all 16 action
  invocations remain pinned to reviewed 40-character commits.
- A disposable Linux build used
  `rust@sha256:8fa55b2f3ddf97471ab6a767bfa3f37e6bad0986ba823e75fea57e2a2a5c3073`
  and Rust 1.97.0. The worker reported engine `4.21.1 (Build 1236)` and vanilla
  ruleset SHA-256
  `36e87ccb29d7c30e01ae4a76087ac83753a2832afb68671a8168851c12579280`;
  packaged-worker validation accepted the derived manifest.
- Pinned Syft generated a 79,897-byte SPDX. Bundle creation and both pre- and
  post-extraction self-verification agreed on 35 artifacts and bundle ID
  `32b94ccca3740f5a90b8041b0374c17a0317978ef5ba56f165117eb0eb569c3c`.
  Every Rust binary was mode `0555`.
- The normalized archive was 102,751,682 bytes. Two independent archive
  creations were byte-identical at SHA-256
  `abb69cbb9263e664d580e9e5a106868b686aa927568f982401681ead763c2eed`.
  The extracted manifest and SBOM were byte-identical to the staged evidence.
  All disposable qualification output was removed.

The OIDC attestation step itself can execute only on GitHub. This implementation
is not represented as hosted release evidence until an exact release tag runs
successfully and the retained archive passes the documented `gh attestation
verify` gates. Full service-stack Linux qualification and PostgreSQL 19
post-Beta-2 upgrade rehearsal remain separate open requirements.

## Production-bundle SPDX evidence boundary

Implemented and locally qualified on 2026-07-28:

- Every release bundle now requires `evidence/sbom.spdx.json`. The SPDX 2.3
  document is copied from a separately reviewed input, bounded to 32 MiB,
  source-named `unciv-authoritative-v3-release-bundle`, required to inventory
  at least one package, and checked for bounded unique package IDs and
  non-dangling optional `documentDescribes` references.
- The SBOM is an ordinary required release artifact: its size and SHA-256 are
  included in the closed bundle manifest and therefore in the content-derived
  bundle ID. Missing, changed, linked, oversized, malformed, unrelated, or
  extra evidence fails the existing verification and runtime-startup boundary.
- `unciv-v3-bundle verify-sbom` provides a preflight before immutable bundle
  creation. The build runbook pins Syft 1.49.0, generates evidence from the
  exact reviewed artifact input tree, and verifies it before packaging.
- The validator lives in the focused `release_bundle/sbom.rs` module; the CLI
  facade and `main.rs` remain bootstrap-only.

Verification on 2026-07-28:

- Pinned Syft 1.49.0, checksum-verified as
  `6edff6c6e06ddd43ae3b779099653f499a856009786b5375a7cf23aed6b67b1a`,
  generated an 80,331-byte SPDX 2.3 document inventorying 29 packages from the
  current Rust, worker, desktop, and ruleset inputs.
- The complete create/verify smoke produced and reverified a 34-artifact bundle
  with ID
  `2bcf1bf6721eed8c3d6605ef4e5ab0805f324a6c747cfc03f1e6ec676be5f98d`;
  its manifest required the exact 80,331-byte SBOM. Temporary tooling and
  artifacts were removed afterward.
- The smoke first rejected a stale desktop artifact at migration contract 20;
  `./gradlew.bat :desktop:dist` rebuilt it at contract 24 and passed. This
  demonstrates that packaging fails closed rather than mixing incompatible
  binaries.
- Focused bundle policy tests cover valid Syft output, malformed/dangling SPDX,
  required-evidence deletion, ordinary tampering, and extra files. Rust
  formatting and warnings-as-errors Clippy pass.

This closes the binary-SBOM inclusion boundary. GitHub OIDC provenance for the
complete production bundle, production signing where practical, full Linux
stack qualification, and the future PostgreSQL 19 prerelease upgrade rehearsal
remain open and are not represented as completed here.

## Supply-chain scanning, SBOM, and source attestations

Implemented and locally qualified on 2026-07-28:

- A dedicated API-v3 workflow runs dependency review, complete-history secret
  scanning, RustSec audit, SPDX source SBOM generation, and daily advisory
  refresh. Default permissions are read-only. The only repository-write job is
  Gradle dependency submission, restricted to the default branch or schedule.
- All 16 action invocations are pinned to reviewed 40-character commits.
  Gitleaks and Syft tool versions are also fixed. Pull requests cannot use
  release credentials, repository writes, OIDC, or attestation authority.
- The initial exact-tag lane proved GitHub OIDC source-archive attestations.
  It is now superseded by the complete production-bundle build and attestation
  above; the independent source SBOM still runs on every workflow invocation.
- Five historical scan exceptions are exact commit/path/rule/line
  fingerprints. Broad regex/path/rule suppressions are forbidden. One removed
  historical PAT disclosure is treated as permanently compromised and
  prohibited; its leaked value is never tested or reused, and only its original
  owner can confirm revocation.

Verification on 2026-07-28:

- Pinned `actionlint v1.7.12` accepts the workflow.
- `cargo audit --file authoritative-server/Cargo.lock` loads 1,170 current
  RustSec advisories and reports no vulnerability in 287 locked crates.
- Pinned Gitleaks 8.30.1 scans all 12,807 commits and approximately 129 MB with
  no unreviewed leak. The first run exposed the five historical candidates;
  each was inspected before its exact fingerprint was registered.
- Pinned Syft 1.49.0 emits a valid SPDX 2.3 document containing 646 packages and
  2,099 relationships. The 1,381,602-byte qualification artifact was generated
  outside the repository and removed after validation.
- Five Rust supply-chain policy tests pass, enforcing immutable action pins,
  read-only PR authority, closed scan exceptions, coverage, and the production
  release boundary.
- Warnings-as-errors Clippy and the complete Rust all-target gate pass: 182
  library tests, 29 API tests, and every active policy/packaging test execute
  without failure. Rust formatting and repository diff hygiene pass.

## Operator isolation and immutable security-audit export

Implemented and live-qualified on 2026-07-28:

- API-v3 has no operator route or standing application superuser. Repair,
  recovery, reconciliation, outbox, migration, and audit export remain local
  executables with separate database identities; player-facing owner
  administration is membership-authorized gameplay, not operator authority.
- Migration `0024_append_only_security_audit.sql` removes runtime
  update/delete/truncate access from redacted security events while retaining
  insert/select. Schema-aware role bootstrap preserves that restriction both
  before and after migrations. The separately authenticated `unciv_audit` role
  remains loopback/TLS and transaction-read-only.
- `unciv-v3-export-security-audit` fixes an ID high-water mark, reads ascending
  pages of at most 1,000 rows, and refuses to replace an existing destination.
  It emits deterministic hash-chained NDJSON plus a final count/range/hash
  manifest, flushes and synchronizes the artifact, and fails closed with a
  quarantinable partial file.
- The export is bundled with the matching release and migration head 24. The
  custody runbook requires at least 400 days in separate write-once/object-lock
  storage, least-privilege quarterly access review, daily and incident exports,
  missing/gap/chain alerts, and named service-owner, incident-commander, and
  independent-review responsibilities.

Verification on 2026-07-28:

- The exact PostgreSQL security smoke passes against
  `postgres:19beta2-alpine@sha256:bc62313e826eb44d5f608425b7665962b72820e686da017799e906604bfeb8a5`.
  It proves runtime insertion succeeds while update/delete fail, audit SELECT
  succeeds while writes fail, the CLI exports exactly one redacted event and
  one matching chain manifest, TLS 1.3 is mandatory, role rotation works, and
  reconciliation remains clean.
- Seven operator-boundary/runbook tests pass, including a static assertion that
  no operator capability appears in the public router. The deterministic chain
  unit test and release compatibility/bundle tests pass.
- `cargo clippy --manifest-path authoritative-server/Cargo.toml --all-targets
  -- -D warnings` and `cargo test --manifest-path
  authoritative-server/Cargo.toml --all-targets --no-fail-fast` pass. The latter
  executes 182 library tests, 29 HTTP/API tests, and every active
  integration/packaging test; database/process scenarios without explicit
  fixtures remain intentionally ignored by that generic command and the
  relevant live database scenario passed separately above.
- `.\gradlew.bat :server:test --tests
  com.unciv.app.server.authoritative.ReleaseCompatibilityContractTests
  --no-daemon --console=plain` passes against migration head 24. Rust formatting,
  diff hygiene, and the 800-line source ceiling pass.

## Legacy write telemetry and staged retirement

Implemented and live-qualified on 2026-07-28:

- The legacy file service now has a dedicated, content-free write-control
  component outside its runner. Process-local telemetry counts completed and
  rejected whole-save/authentication writes without storing identity,
  filenames, addresses, credentials, save data, or any v3 state.
- `GET /legacy-status` exposes the bounded aggregate snapshot for external
  scraping. Operators can enable `-no-legacy-writes` or
  `UncivServerLegacyWrites=false`; both legacy PUT routes then return
  `410 Gone`, while existing legacy reads, health, and the independently
  deployed API-v3 service remain available.
- The retirement runbook defines client labeling, a complete observation
  cycle, owner notification, dry-run/import conflict handling, pilot and
  fleet-wide cutoff, a read-only recovery window, rollback, and audited final
  listener-removal gates. Low traffic alone is explicitly insufficient
  evidence for deletion.

Verification on 2026-07-28:

- `.\gradlew.bat :server:test --tests
  com.unciv.app.server.LegacyWriteControlTests --no-daemon` passes both focused
  counter/state tests.
- `.\authoritative-server\tests\run-legacy-v3-isolation.ps1` packages the real
  legacy jar and passes two live process tests against the sole pinned
  PostgreSQL 19 Beta 2 digest. The retirement case reads a pre-existing save,
  rejects its replacement and a password write with `410 Gone`, preserves the
  file byte-for-byte, and reports exactly one rejected request for each class.
  The same-UUID v3 isolation attack continues to pass. Both cases complete in
  2.21 seconds; the disposable container and file roots are removed.
- The first focused compile exposed JUnit 5 imports in the JUnit 4 server test
  module, and the first Rust compile exposed reqwest's intentionally disabled
  JSON convenience feature. The test was aligned with JUnit 4 and uses the
  existing `serde_json` dependency directly; both gates then passed with no
  deferred error.
- The broad server gate also exposed a stale Kotlin migration assertion and an
  intermittent fresh-worker AI divergence. The release assertion now matches
  migration 23. Root-cause diagnostics identified unordered first-contact
  discovery, unordered seeded AI policy candidates, and unordered
  `LastSeenImprovement` serialization. Civilization IDs and encounter
  coordinates are now sorted, policy candidates have a stable name order, and
  improvement keys serialize by coordinate. The 24-round late-era campaign
  passes twice across fresh worker processes with identical snapshot bytes and
  canonical hashes.
- `.\gradlew.bat :tests:cleanTest :server:cleanTest :tests:test :server:test
  :android:assembleDebug --no-parallel --no-build-cache --no-daemon
  --console=plain` passes 1,157 JVM/server tests (1,143 executed, 14 intentional
  skips) and builds the Android debug APK. The existing Android SDK XML version
  warning is non-fatal and skips no task.

## Legacy and v3 runtime isolation

Implemented and live-qualified on 2026-07-28:

- The deployment contract keeps legacy and v3 on separate origins/listeners,
  operating-system identities, credentials, storage, and session namespaces.
  The v3 systemd/Caddy surface does not route legacy `/files/`, `/auth`, or
  `/chat`; legacy receives no PostgreSQL role, worker secret, worker address, or
  v3 release storage.
- The one-way, operator-only legacy importer remains the sole bridge. Matching
  usernames or game UUIDs never link legacy files and v3 canonical state.
- A static packaging test rejects v3 services that expose legacy endpoints,
  legacy source that gains a v3 runtime/storage path, a live-test child process
  that inherits v3 database/worker variables, or a runner that changes from the
  sole pinned PostgreSQL 19 Beta 2 digest.
- The live test packaged the real legacy server, created a real v3
  PostgreSQL-backed game, and used the same UUID to upload and read an
  attacker-controlled legacy save. Legacy returned only its own payload while
  the v3 head revision, canonical snapshot hash, game/revision counts, command
  journal, and repository reconciliation remained unchanged.

Verification on 2026-07-28:

- `.\authoritative-server\tests\run-legacy-v3-isolation.ps1` passes the live
  same-UUID isolation scenario against
  `postgres:19beta2-alpine@sha256:bc62313e826eb44d5f608425b7665962b72820e686da017799e906604bfeb8a5`.
  The packaged legacy server completed the attack scenario in 1.32 seconds and
  the disposable PostgreSQL container and file root were removed.
- `.\gradlew.bat :server:dist --no-daemon` succeeds and produces the legacy jar
  exercised by the test.
- `cargo test --manifest-path authoritative-server/Cargo.toml --all-targets
  --no-fail-fast` passes 181 library tests, 29 API tests, and all 19 active
  integration/packaging tests. Warnings-as-errors Clippy, formatting, diff
  hygiene, the 800-line Rust source limit, and disposable-container cleanup
  pass.

## Production account management and recovery UI

Implemented on 2026-07-28:

- The Kotlin API-v3 contract and production transport now expose account
  recovery, one-time recovery-code replacement, and all-session logout. Recovery
  stores the returned replacement session only in the platform token store;
  account secrets never enter a game command, worker frame, or projection.
- The authenticated all-session endpoint derives the account from the bearer
  session, applies the existing durable account-security throttle, revokes all
  of that account's sessions, and records a redacted audit. A PostgreSQL test
  proves another account's live session is untouched.
- The multiplayer screen now presents focused login/recovery and authenticated
  account-management popups. Players can register, log in, recover, rotate a
  password, replace recovery codes, log out one or all devices, disable, or
  delete the account. Password and recovery fields are cleared after every
  attempt. Recovery-code lifetime and complete-batch invalidation are stated
  before use, and codes are displayed once for external secure storage.
- Stable API failures map to specific, redacted player messages for invalid
  credentials/password policy, duplicate usernames, throttling, invalid server
  responses, and other closed server errors. No raw error body or account
  secret is rendered.
- Lifecycle state moves between login-required and authenticated after recovery,
  logout-all, disable, and delete. Production routing tests require each account
  action to use the explicit API-v3 facade and forbid `GameInfo`, `GameStarter`,
  worker, or whole-save upload access from the account UI.

Verification on 2026-07-28:

- The focused Gradle command covering
  `AuthoritativeSessionLifecycleTests`,
  `AuthoritativeMultiplayerSessionTests`,
  `AuthoritativeAccountMessagesTests`, and
  `AuthoritativeProductionRoutingTests` passes all 76 tests.
- `.\gradlew.bat :tests:test --no-daemon` passes the complete JVM regression
  suite on Temurin JDK 21.0.11.
- `.\gradlew.bat :android:assembleDebug --no-daemon` succeeds with 45 tasks,
  including Android Kotlin compilation, dexing, and APK packaging. The existing
  Android SDK XML version warning remains non-fatal and does not skip the build.
- `cargo test --manifest-path authoritative-server/Cargo.toml --all-targets
  --no-fail-fast` passes 181 active library tests, all 29 API/OpenAPI tests, and
  all active integration/packaging tests. Warnings-as-errors Clippy passes.
- Three focused account-security transactions and the complete 34-test ordinary
  PostgreSQL lane pass against the exact pinned PostgreSQL 19 Beta 2 digest.
  Disposable containers were removed.
- The first Kotlin compile rejected an inferred `Nothing` return for the default
  logout-all transport and a GL-thread extension called outside its receiver;
  both were corrected. The first production-routing run then rejected the new
  typed facade members until its explicit non-legacy allowlist was updated. All
  focused and broad gates were rerun; no failure was deferred.

## Bounded sessions and player-owned account recovery

Implemented on 2026-07-28:

- Login issuance now locks the account row and atomically retains only the most
  recently used active sessions. The deployment-wide limit defaults to eight,
  is bounded to 1-32 by `UNCIV_V3_MAX_ACTIVE_SESSIONS`, and is enforced by
  PostgreSQL across Rust replicas. Evicted sessions retain the durable
  `session_limit` reason; logout, rotation, password change, recovery, disable,
  and delete also record distinct revocation reasons.
- Authenticated players can replace their recovery-code batch after password
  verification. Eight independent 256-bit codes are returned once, expire
  after 90 days, and are stored only as SHA-256 digests. Generating a new batch
  invalidates all older batches.
- One valid recovery code atomically invalidates its complete batch, replaces
  the Argon2id password hash, revokes every old session, and creates one new
  session. Unknown, disabled, expired, replayed, and invalid-code cases return
  the same redacted authentication response and change nothing. There is no
  email, operator, security-question, or client-save override.
- Durable source and source-plus-identity recovery throttles and redacted
  security audits extend the existing registration/login credential-stuffing
  controls. A disclosure regression rejects password, recovery-code, or
  session-token fields in worker and projection schemas and rejects recovery
  code interpolation in logs.
- Migration `0023_session_limits_and_recovery.sql`, the generated OpenAPI
  contract, release compatibility head, environment templates, and the
  account-security operations guide carry the same policy.

Verification on 2026-07-28:

- `cargo test --manifest-path authoritative-server/Cargo.toml --all-targets
  --no-fail-fast` passes 181 active library tests, all 29 API tests, and every
  active integration and packaging test.
- The focused account-security lane passes both transactional tests against
  `postgres:19beta2-alpine@sha256:bc62313e826eb44d5f608425b7665962b72820e686da017799e906604bfeb8a5`.
  It proves cross-replica LRU session eviction, the active bound, digest-only
  recovery storage, wrong-code rollback, one-time batch consumption, password
  replacement, and complete old-session revocation.
- The complete ordinary serialized PostgreSQL lane passes all 33 tests against
  that exact digest. Destructive disk-full and backup/PITR qualifications
  remain separately orchestrated and retain their prior recorded evidence.
- The first live session-limit run exposed an inverted SQL ordering that
  evicted the newest session. The query was corrected to retain descending
  activity order, then both the focused and complete fresh-database lanes
  passed. No failing result was deferred.
- `postgres/integration_tests.rs` is exactly 800 lines after the focused account
  security module split; all other Rust sources remain below the enforced
  limit.

## Malicious-client request identity and cross-scope isolation

Implemented on 2026-07-28:

- Durable idempotency now binds a command ID to its complete authoritative
  request identity: protocol, game, command ID, expected revision, and closed
  typed command. The client-observed hash remains diagnostic and may change on
  an otherwise exact retry. Both the pre-worker fast path and the locked commit
  transaction deserialize and compare the original journal envelope.
- Reusing a committed ID with changed meaning now fails before worker execution
  with stable `409 idempotency_conflict`; it can no longer masquerade as a
  successful exact retry. A different authenticated account still receives the
  non-enumerating authorization failure first.
- The in-memory reference repository implements the same rule, and its
  deterministic property model now distinguishes exact retries from
  changed-identity reuse over generated revision sequences.
- A dedicated PostgreSQL 19 Beta 2 malicious-client test commits one command,
  then attacks it with changed command meaning, another game's owner, another
  game ID, and a reordered stale command. The accepted game remains at revision
  one with one journal command and one outbox row; the unrelated game remains
  at revision zero.
- Explicit protocol coverage rejects client-supplied
  `actor_civilization_id`. Existing bounded JSON/body, authenticated worker
  frame, WebSocket admission/idle/slow-writer, worker queue/deadline, and
  ruleset acquisition/archive tests cover malformed, oversized, exhaustion,
  and expensive-input boundaries without trusting client state.

Verification on 2026-07-28:

- `cargo test --manifest-path authoritative-server/Cargo.toml --lib --quiet`
  passes 179 active tests with 37 environment-dependent tests intentionally
  ignored.
- `cargo test --manifest-path authoritative-server/Cargo.toml --all-targets
  --quiet` passes those 179 active library tests, all 29 API/OpenAPI tests, and
  every active integration and packaging test.
- `cargo clippy --manifest-path authoritative-server/Cargo.toml --all-targets
  --all-features -- -D warnings`, `cargo fmt --check`, `git diff --check`,
  source-size enforcement, and disposable-container cleanup all pass.
- The exact PostgreSQL 19 Beta 2 live test
  `cross_scope_stale_and_changed_id_attacks_preserve_both_canonical_heads`
  passes against the pinned digest and its disposable container is removed.
- The existing live
  `postgres_commit_is_atomic_idempotent_and_stale_safe` test also passes with
  changed-identity rejection added.
- The complete ordinary serialized PostgreSQL lane passes all 31 account,
  command, administration, migration, projection, recovery, replica-race,
  outbox, retention, notification, and worker-fault tests against the exact
  Beta 2 digest. The separately orchestrated destructive backup/PITR and
  disk-full cases were excluded from this ordinary lane and retain their
  previously recorded live qualifications.
- The first library run exposed the old property model's assumption that every
  reused UUID was an exact retry. The model was corrected to retain original
  request identity and the complete deterministic suite passed. The first live
  filter used Cargo `--exact` without the module-qualified test name and ran
  zero tests; the corrected filter ran and passed the intended live case.
- The final size gate found the newly added tests had pushed `lib_tests.rs` and
  `postgres/integration_tests.rs` past 800 lines. Malicious-client tests were
  split into focused in-memory and PostgreSQL modules and duplicate coverage
  removed. The first split compile correctly rejected sibling access to private
  test helpers; the focused module now owns its helpers. The rerun passes and
  every Rust source is at or below 800 lines. No product or verification error
  remains deferred.

## Operator incident response and break-glass boundary

Implemented on 2026-07-28:

- Added one production incident-response entry point covering private-worker
  failure/timeout, corrupt-game quarantine and bounded recovery, fenced
  PostgreSQL promotion, outbox backlog/dead-letter handling, database credential
  compromise, authentication/denial-of-service abuse, and break-glass access.
- Every workflow starts by preserving redacted evidence and stopping public
  writes when integrity is uncertain. Mutating repair, recovery, and outbox
  operations remain dry-run-first, require an isolated PostgreSQL 19 Beta 2
  rehearsal and reviewer approval, and end with reconciliation and readiness
  gates. Lost-response retries explicitly retain the original command ID.
- Break-glass is a local-console, named, time-bounded, two-person operation.
  There is no public operator endpoint or standing application superuser.
  `unciv_restore` remains isolated from production, `unciv_migrate` owns schema
  changes, and `unciv_audit` owns read-only investigation. Client saves and
  hand-edited canonical rows are prohibited as recovery inputs.
- Added a focused Rust regression suite that makes all seven incident classes,
  authority-preserving commands, detailed-runbook links, break-glass controls,
  and evidence-redaction rules part of the build.

Verification on 2026-07-28:

- `cargo test --manifest-path authoritative-server/Cargo.toml --test
  operator_runbooks` passes all five runbook policy tests.
- `cargo test --manifest-path authoritative-server/Cargo.toml --all-targets
  --quiet` passes 176 active library tests, 28 PostgreSQL tests in the
  non-live lane, the five new operator tests, and every other active
  integration/packaging test; 42 environment-dependent fault/worker/database
  cases remain intentionally ignored in this lane.
- `cargo clippy --manifest-path authoritative-server/Cargo.toml --all-targets
  --all-features -- -D warnings` passes.
- `cargo fmt --manifest-path authoritative-server/Cargo.toml -- --check`
  and `git diff --check` pass, and every linked detailed runbook exists.
- The first test run exposed rustfmt layout drift and two assertions that
  assumed Markdown phrases were not line-wrapped. The test strings were made
  layout-independent, formatting was applied, and the complete focused suite
  passed. No product error remains deferred.
- PostgreSQL 19 Beta 2 remains the newest published PostgreSQL 19 prerelease on
  this date. The separate later-beta/RC/final upgrade gate remains open because
  no later image exists to rehearse honestly.

## Production TLS, HSTS, and trusted client identity

Implemented on 2026-07-28:

- Added a production Caddy 2.11.4 automatic-HTTPS boundary and hardened systemd
  unit. Only Caddy owns public ports; Rust stays on loopback. The proxy redirects
  HTTP, emits one-year HSTS plus response hardening, removes its server header,
  actively gates on `/readyz`, forces HTTPS forwarding context, and removes
  competing `Forwarded`/`X-Real-IP` input.
- Authentication and account-security rate limits no longer collapse every
  proxied player into one loopback identity. `UNCIV_V3_TRUSTED_PROXY` is a
  closed `disabled|loopback` policy. Loopback mode refuses a public Rust bind
  and accepts exactly one syntactically valid, non-unspecified,
  non-multicast `X-Forwarded-For` address from the loopback peer. Missing,
  repeated, comma-chained, malformed, or competing values fail before
  registration/login rate-limit logic or any account mutation. Every
  forwarding claim from a non-loopback peer is ignored.
- Added separate protected proxy configuration, ACME state, service identity,
  resource/capability ceilings, deployment/rotation/failure guidance, a pinned
  Caddy qualification image, a reusable live TLS smoke, and static policy
  regression tests. No CDN/private-range trust shortcut is enabled.

Verification on 2026-07-28:

- `run-tls-proxy-smoke.ps1` passes against
  `caddy:2.11.4-alpine@sha256:5f5c8640aae01df9654968d946d8f1a56c497f1dd5c5cda4cf95ab7c14d58648`.
  A real TLS connection receives `Strict-Transport-Security:
  max-age=31536000` with no `Server` header; HTTP returns 308 to HTTPS; spoofed
  `X-Forwarded-For`, `Forwarded`, and `X-Real-IP` input is replaced/removed and
  the upstream receives one direct-peer IP plus HTTPS context.
- The real Rust API, packaged Kotlin worker, and exact digest-pinned PostgreSQL
  19 Beta 2 ran together in loopback-proxy mode. Authentication returned 400
  for missing and ambiguous proxy identity, accepted the single Caddy-shaped
  address with 201, and `/readyz` changed to 503 after the worker was killed.
  A separate startup rehearsal proved loopback trust with a wildcard bind is
  refused. The disposable database/container and all child processes were
  removed.
- Caddy configuration validation is clean. `systemd-analyze verify` passes for
  the proxy unit in the pinned Ubuntu 24.04 qualification image. The focused
  Kotlin release-compatibility test passes.
- `cargo test --all-features` passes 176 active library tests, 28 HTTP/runtime
  tests, two benchmark tests, eight systemd/package policy tests, and all other
  active contract/binary tests; 37 prerequisite-gated cases remain ignored in
  that lane. Warnings-as-errors
  `cargo clippy --all-targets --all-features -- -D warnings`,
  `cargo fmt --all -- --check`, OpenAPI parity, and source-size limits pass.
- Iterative harness/configuration issues were fixed rather than deferred: the
  first digest-addressed Caddy validation omitted the executable; explicit
  `X-Forwarded-For` configuration produced an unnecessary-policy warning and
  was removed in favor of Caddy's protected default; PowerShell initially
  treated the expected 308 as an exception; and the first API smoke launched a
  stale standalone binary even though the test harness had recompiled. The
  corrected validator, no-redirect client, rebuilt binary, fresh database, and
  complete smokes all pass. No source, test, TLS, proxy, permission, process,
  container, or documentation error remains deferred.

## Split database migration authority and active API readiness

Implemented on 2026-07-28:

- Removed schema mutation from the Rust API bootstrap. The separately packaged
  `unciv-v3-migrate` executable requires its own migration database URL, applies
  the embedded ordered migration set, and verifies the resulting versions and
  checksums. API startup is read-only with respect to schema and fails closed
  on missing, extra, failed, or changed migrations.
- Added bounded PostgreSQL runtime configuration for maximum/minimum pool size,
  acquisition timeout, idle retirement, maximum connection lifetime, and
  session-level statement/lock timeouts. Invalid or incoherent settings stop
  startup rather than silently disabling a limit.
- Preserved `/healthz` as process liveness and added `/readyz` as active
  dependency admission. It concurrently probes PostgreSQL and performs the
  authenticated private-worker handshake, returns 503 when either dependency
  is unavailable, and exposes only fixed component status.
- Added separate hardened systemd units and service identities for the Rust API
  and one-shot migrator. The release bundle now requires and hashes the
  migration executable. The deployment guide keeps runtime and migration
  credentials in different protected environment files and requires loopback
  database/worker connectivity.

Verification on 2026-07-28:

- `unciv-v3-migrate` applied the complete embedded migration set to a fresh
  exact digest-pinned PostgreSQL 19 Beta 2 container. The new integration case
  proved exact schema compatibility plus `12345ms` statement and `2345ms` lock
  session settings. All 31 serialized PostgreSQL repository/fault scenarios
  then passed in 9.52 seconds.
- The real packaged Kotlin worker, non-migrating Rust API, and database ran
  together through `tests/run-api-readiness-smoke.ps1`. `/healthz` returned
  protocol 3, `/readyz` reported both dependencies ready, and killing the worker
  changed readiness to HTTP 503 with PostgreSQL still ready. The same smoke
  passed under a temporary DML-only runtime role, and PostgreSQL independently
  denied that role schema `CREATE`.
- Separate startup fault rehearsals proved the API refuses both a database with
  no migration history and a database whose migration-22 checksum was changed.
  Both disposable databases and the temporary runtime role were removed.
- `systemd-analyze verify` passed for the API and migration units inside the
  pinned Ubuntu 24.04 qualification image. Static packaging tests also prove
  their separate identities, credential files, executables, resource caps, and
  loopback-only policy.
- `cargo test --all-features` passes 176 active library tests, 25 HTTP/runtime
  tests, two benchmark tests, six systemd packaging tests, and all other active
  binary/contract tests; 37 prerequisite-gated cases remain ignored in that
  lane and the 31 database cases were run separately. Warnings-as-errors
  `cargo clippy --all-targets --all-features -- -D warnings`,
  `cargo fmt --all -- --check`, generated OpenAPI parity, and the focused Kotlin
  `ReleaseCompatibilityContractTests` all pass.
- The first generated-contract test correctly failed until OpenAPI was
  regenerated. An inline PowerShell smoke was rejected by execution policy
  before launching anything; it became the reusable bounded smoke script above.
  A permission review caught and removed a shared-group credential exposure
  before live testing. The source-size audit found `worker/protocol.rs` at 804
  physical lines; response/result types moved into the descriptive
  `worker/response.rs` module, leaving the protocol operation union at 744
  lines. The first final formatting command was run from the repository root,
  where no Cargo manifest exists; the corrected command used the explicit
  authoritative-server manifest and passed. No discovered source, test,
  formatting, packaging, database, permission, or cleanup error remains
  deferred.

## Live Linux systemd worker qualification

Implemented on 2026-07-28:

- Added a repeatable qualification environment built from digest-pinned Ubuntu
  24.04. It boots systemd as PID 1 under Docker Desktop's Linux engine, stages
  the real packaged worker and immutable game assets, and drives the private
  protocol with an authenticated v2 probe.
- The rehearsal verifies `systemd-analyze`, authenticated startup, SIGKILL
  restart, five-second scheduled recycling, watchdog exit status 124 during a
  real huge-map creation, deterministic JVM OOM during constrained ruleset
  loading, and authenticated recovery after every failure.
- Live cgroup-v2 values prove the 80% CPU, 448/512 MiB memory, zero-swap, and
  64-task ceilings. The process limit proves 1,024 descriptors. Identity checks
  prove the group-readable mode-0640 secret is unavailable to `nobody` and the
  worker cannot modify the root-owned active ruleset.
- Live qualification exposed a production startup failure when LibGDX tried to
  map its extracted native library from a `noexec` private `/tmp`. The unit now
  directs `java.io.tmpdir` to a systemd-owned mode-0700
  `/run/unciv-worker`; the rehearsal deliberately retains `noexec` `/tmp`.
- Corrected the release-bundle runbook's stale migration-head references from
  20 to the verified current head 22.

Verification on 2026-07-28:

- `authoritative-server/systemd/qualification/run-linux-worker-qualification.ps1`
  passes against Ubuntu image digest
  `sha256:4fbb8e6a8395de5a7550b33509421a2bafbc0aab6c06ba2cef9ebffbc7092d90`.
  Its JSON report marks systemd verification, authenticated handshake,
  SIGKILL/recycle/watchdog/OOM recovery, cgroup CPU-memory-swap-task controls,
  descriptor limit, immutable assets, and secret permissions as passed.
- `cargo test --all-features` passes 175 active Rust library tests, 25
  HTTP/runtime tests, two benchmark tests, and four systemd packaging/
  qualification contract tests; 36 database/process cases remain explicitly
  prerequisite-gated in that lane. `cargo fmt --all -- --check` and
  warnings-as-errors `cargo clippy --all-targets --all-features -- -D warnings`
  pass.
- A clean `./gradlew :server:test --no-parallel --rerun-tasks --console=plain`
  passes in 3 minutes 14 seconds with all 12 actionable tasks executed.
- Iterative rehearsal errors were fixed rather than deferred: the minimal image
  lacked `/sbin/init`; LibGDX could not execute from `noexec` `/tmp`;
  `systemctl set-property` rejected the runtime-limit property; intentional
  restarts tripped the production start limiter; an `Environment=` drop-in did
  not supersede the protected environment file; and the first OOM scenario did
  not apply pressure. The final complete run passes and cleans up its disposable
  container and temporary context.

## Complete setup and long-horizon AI recovery qualification

Implemented on 2026-07-28:

- Added packaged-worker parity coverage for every supported generated-game setup
  dimension and named value: ruleset difficulties, speeds, starting eras,
  visible victory types, map shapes and sizes, resource densities, barbarian
  modes, both states of every boolean option, and timer extrema. Each case runs
  through two independent packaged JVMs and compares the complete response
  bytes before decoding and validating the resulting canonical setup.
- Added a 24-round late-era campaign through three server-controlled AI
  civilizations, two city states, and raging barbarians. The fixture resolves
  server-reported mandatory policy blockers with typed commands, then proves
  every human-to-AI-to-human turn response is byte-identical across independent
  packaged JVMs and that every AI civilization remains active with a city.
- The setup fixture is exhaustive by supported dimension/value rather than by
  the Cartesian product of semantically independent values. Together with the
  existing all-13-map-type, randomized combat/event, all-84-operation, ordinary
  eight-round AI, process-fault, bounded replay, and PostgreSQL recovery tests,
  this closes deterministic fresh-process parity and recovery qualification.

Verification on 2026-07-28:

- `./gradlew :server:test --tests
  com.unciv.app.server.authoritative.PackagedWorkerRandomParityTests.everyGeneratedSetupDimensionHasFreshWorkerParity
  --no-parallel --console=plain` passes.
- `./gradlew :server:test --tests
  com.unciv.app.server.authoritative.PackagedWorkerAiCampaignParityTests.twentyFourLateEraAiRoundsAreByteStableAcrossFreshWorkers
  --no-parallel --console=plain` passes.
- A clean `./gradlew :tests:test :server:test --no-parallel --rerun-tasks
  --console=plain` passes in 3 minutes 2 seconds with all 17 actionable tasks
  executed.
- The first campaign draft selected the terminal future era, which the worker
  correctly rejected. The second reached a mandatory policy blocker, and the
  fixture now follows the projected blocker with typed `AdoptPolicy` commands.
  The final assertion originally expected the command-free human to own a city;
  it now checks the three server-run AI civilizations instead. Every corrected
  case was rerun successfully; no discovered source or test error is deferred.

## Dry-run-first one-way legacy migration

Implemented on 2026-07-28:

- `unciv-v3-import-legacy` is a focused operator-only binary and defaults to a
  read-only dry run. It accepts only bounded candidate paths, a caller-stable
  operation UUID, an explicit selected candidate index, the approved manifest
  hash, and an exhaustive legacy-player-to-v3-account mapping. It never exposes
  an HTTP whole-save endpoint and never edits or removes a source file.
- Every candidate is read through the 16 MiB cap and normalized independently
  by the private Kotlin engine under the installed content-addressed manifest.
  The report records source/path hashes and independently identifies turn,
  current-player, and canonical-state-hash divergence. The tool never chooses
  between divergent candidates implicitly.
- Before apply, the CLI generates each initial player or spectator projection,
  validates the closed Rust projection contract, and scans its serialized bytes
  for every legacy player identity. A leak, malformed projection, unavailable
  account, unavailable manifest bundle, worker error, or changed retry fails
  closed.
- Migration `0022_legacy_game_imports.sql` and the focused PostgreSQL repository
  transaction atomically create one deterministic API-v3 game at revision zero,
  all worker-derived memberships, immutable genesis state, and append-only
  provenance/conflict/projection evidence. Exact operation retries return the
  original result; changed-meaning reuse and a second operation for the same
  `(legacy origin, legacy game ID)` are rejected.
- The complete operator procedure and retry contract are documented in
  `docs/operations/legacy-game-import.md`.

Verification on 2026-07-28:

- `cargo test --lib legacy_import` passes seven focused unit/contract tests;
  the two database cases are intentionally gated in that lane.
- `cargo test --lib` passes 175 active Rust tests with 30 explicitly gated
  PostgreSQL cases. `cargo fmt --all -- --check`, warnings-as-errors
  `cargo clippy --all-targets --all-features -- -D warnings`, and
  `git diff --check` pass.
- Both focused PostgreSQL integration tests pass against the sole pinned image
  `postgres:19beta2-alpine@sha256:bc62313e826eb44d5f608425b7665962b72820e686da017799e906604bfeb8a5`;
  the running server reports `PostgreSQL 19beta2`. They prove atomic genesis,
  exact idempotent retry, source uniqueness, owner/player/spectator membership,
  changed-meaning rejection, projection-evidence enforcement, and rollback with
  zero game/provenance rows on invalid input. The disposable container was
  removed.
- The focused Kotlin legacy-normalization test passes through
  `:server:test`, proving canonical rekeying, exhaustive human mapping, and
  fail-closed legacy identity validation.
- The first Rust compile found a duplicate façade re-export already present
  from the persistence milestone; it was removed and the focused suite reran
  cleanly. The first integration-test compile found a local fixture binding
  shadowing its builder; it was renamed and both the compile-only and live
  PostgreSQL lanes passed. The broad Rust bundle test then exposed that
  migration 22 had not advanced the release compatibility contract; both Rust
  and Kotlin compatibility assertions now require 22 and their reruns pass.
  Two exploratory PowerShell commands had quoting errors and were replaced
  with direct checks; neither changed repository or database state. No
  discovered source, test, database, or cleanup error is deferred.

## Outbox acknowledgement process-death recovery

Implemented on 2026-07-26:

- Added a controlled process fixture around the real API outbox dispatcher and
  the packaged authenticated Kotlin worker used during API startup.
- A temporary test-only PostgreSQL trigger pauses the production
  `acknowledge_outbox` update after the dispatcher has durably claimed a valid
  revision event. The harness proves the claim is visible, forcibly terminates
  the API process and its exact sleeping database backend, then verifies the
  acknowledgement transaction rolled back.
- The interrupted event remains undelivered with attempt count one and its
  durable claim token. The genesis revision and immutable snapshot remain
  unchanged and no command appears; notifications therefore remain
  non-authoritative hints.
- After removing the test trigger and aging the claim beyond the production
  30-second lease, a restarted API reclaims the event as attempt two,
  acknowledges it, clears both claim fields, and records delivery. Final
  reconciliation reports zero findings.
- The trigger and function exist only inside the disposable test database and
  are removed before recovery. No production failpoint, endpoint, or alternate
  dispatcher was added.

Verification on 2026-07-26:

- The focused
  `outbox_acknowledgement_process_death_recovers_the_persisted_claim` process
  test passes in 1.51 seconds against only
  `postgres:19beta2-alpine@sha256:bc62313e826eb44d5f608425b7665962b72820e686da017799e906604bfeb8a5`.
- All 24 in-crate PostgreSQL integration/fault tests pass in 8.01 seconds, both
  Rust API process tests pass in 4.39 seconds, and the two packaged-worker/API
  process tests pass in 3.82 seconds in the same serialized run. The disposable
  PostgreSQL container was removed and cleanup was verified.
- `cargo test --all-features` passes 143 active Rust library tests and all 16
  HTTP/OpenAPI tests; 24 database tests and four process tests are intentionally
  ignored without their explicit prerequisites. `cargo fmt --all -- --check`
  and warnings-as-errors `cargo clippy --all-targets --all-features -- -D
  warnings` pass.
- `./gradlew :android:lintDebug :android:assembleDebug :tests:test :server:test
  :desktop:compileKotlin --no-parallel --console=plain` passes with 63
  actionable tasks: three executed and 60 up-to-date.

## Forced packaged Kotlin worker termination

Implemented on 2026-07-26:

- Added a process-level fixture for the actual 37 MB
  `UncivAuthoritativeWorker.jar`, launched headlessly from the production game
  assets with protocol-v2 mutual service identity.
- A private test proxy relays the API startup handshake, then forwards one
  complete authenticated `create_game` frame to the packaged JVM. The JVM is
  forcibly terminated and the proxy drops the private connection before any
  response can reach the real Rust API process.
- The failed request returns the stable worker failure response and PostgreSQL
  contains no game, creation operation, genesis revision, snapshot, or member.
  Thus a worker that dies after receiving canonical setup intent cannot create a
  phantom game or poison the operation's idempotency key.
- A fresh packaged JVM and Rust API accept the exact same authenticated account,
  operation ID, manifest, and setup. The worker runs real `GameStarter`, creates
  revision zero once, and a further identical creation request returns the same
  game without creating another artifact.
- The harness discovers the engine build and exact vanilla ruleset content hash
  from the packaged worker handshake. It does not substitute a fake manifest,
  rules engine, save, or production fault hook.

Verification on 2026-07-26:

- `./gradlew :server:authoritativeWorkerDist --console=plain` passes and provides
  the packaged worker used by the fault test.
- The focused
  `packaged_worker_death_during_creation_leaves_no_game_and_retry_succeeds`
  process test passes in 2.59 seconds against only
  `postgres:19beta2-alpine@sha256:bc62313e826eb44d5f608425b7665962b72820e686da017799e906604bfeb8a5`.
- All 24 in-crate PostgreSQL integration/fault tests pass in 8.03 seconds, both
  Rust API process tests pass in 4.38 seconds, and the packaged-JVM death test
  passes in 2.46 seconds in the same serialized run. The disposable PostgreSQL
  container was removed and cleanup was verified.
- `cargo test --all-features` passes 143 active Rust library tests and all 16
  HTTP/OpenAPI tests; 24 database tests and three process tests are
  intentionally ignored without their explicit database/worker prerequisites.
  `cargo fmt --all -- --check` and warnings-as-errors `cargo clippy
  --all-targets --all-features -- -D warnings` pass.
- `./gradlew :android:lintDebug :android:assembleDebug :tests:test :server:test
  :desktop:compileKotlin --no-parallel --console=plain` passes with 63
  actionable tasks: three executed and 60 up-to-date.

## Forced Rust process death at the commit boundary

Implemented on 2026-07-26:

- Extended the process-level fault harness to hold the canonical PostgreSQL game
  row lock, submit `EndTurn` through the actual Rust API binary, and observe one
  completed authenticated worker execution.
- The API's transaction is proven blocked on its production `SELECT ... FOR
  UPDATE` head lock before the process is forcibly terminated. Releasing the
  external lock then proves PostgreSQL rolled the interrupted transaction back:
  no revision, snapshot, command journal entry, or outbox event exists.
- A newly launched API and worker accept the exact same game, command ID,
  expected revision, account session, and snapshot. Rules execute again because
  the first result was never committed, but only the retry becomes canonical;
  it creates exactly one complete revision and reconciliation remains clean.
- The test drives only the shipped binary, raw HTTP, authenticated worker TCP,
  and PostgreSQL. It introduces no production failpoint or privileged endpoint.

Verification on 2026-07-26:

- The focused
  `rust_process_death_after_worker_execution_leaves_no_phantom_commit` test
  passes in 2.94 seconds against only
  `postgres:19beta2-alpine@sha256:bc62313e826eb44d5f608425b7665962b72820e686da017799e906604bfeb8a5`.
- All 24 in-crate PostgreSQL integration/fault tests pass in 8.11 seconds; both
  process tests then pass in 4.43 seconds in the same serialized run. The
  disposable PostgreSQL container was removed and cleanup was verified.
- `cargo test --all-features` passes 143 active Rust library tests and all 16
  HTTP/OpenAPI tests; the 24 database tests and two process tests are
  intentionally ignored without an explicit database. `cargo fmt --all
  -- --check` and warnings-as-errors `cargo clippy --all-targets --all-features
  -- -D warnings` pass.
- `./gradlew :android:lintDebug :android:assembleDebug :tests:test :server:test
  :desktop:compileKotlin --no-parallel --console=plain` passes with 63
  actionable tasks: three executed and 60 up-to-date.

## Lost HTTP response recovery

Implemented on 2026-07-26:

- Added a process-level Cargo integration fixture that launches the actual
  `unciv-authoritative-server` binary with a real PostgreSQL repository and an
  authenticated private worker peer.
- The first raw HTTP client submits `EndTurn`, waits until the committed command
  is independently visible in PostgreSQL, and then discards its TCP connection
  without reading any response bytes. A fresh connection retries the same
  command ID and expected revision.
- The retry returns the original accepted revision and hash while the worker
  peer proves exactly one gameplay execution. PostgreSQL contains exactly one
  revision, immutable snapshot, command journal entry, and outbox event, and
  post-fault reconciliation reports zero findings.
- The harness uses only public API/process behavior and production framing; it
  adds no production fault hooks, debug endpoints, or router seams.

Verification on 2026-07-26:

- The focused
  `lost_http_response_retries_without_reexecuting_the_worker` process test
  passes in 1.70 seconds against only
  `postgres:19beta2-alpine@sha256:bc62313e826eb44d5f608425b7665962b72820e686da017799e906604bfeb8a5`.
- All 24 in-crate PostgreSQL integration/fault tests pass in 8.10 seconds and
  the separate lost-response process test passes in 1.58 seconds in the same
  serialized run. The disposable PostgreSQL container was removed and cleanup
  was verified.
- `cargo test --all-features` passes 143 active Rust library tests and all 16
  HTTP/OpenAPI tests; the 24 database tests and one process test are
  intentionally ignored without an explicit database. `cargo fmt --all
  -- --check` and warnings-as-errors `cargo clippy --all-targets --all-features
  -- -D warnings` pass.
- `./gradlew :android:lintDebug :android:assembleDebug :tests:test :server:test
  :desktop:compileKotlin --no-parallel --console=plain` passes with 63
  actionable tasks: three executed and 60 up-to-date.

## Worker-boundary crash consistency

Implemented on 2026-07-26:

- Added a PostgreSQL integration fault fixture around the real authenticated
  Rust worker client. The worker peer reads and verifies a complete `EndTurn`
  frame, then disappears without returning any proposal.
- The failed execution returns a worker-boundary error and leaves all canonical
  commit artifacts absent: no revision, immutable snapshot, command journal
  entry, or outbox event is created.
- Retrying the identical command through a healthy authenticated worker commits
  exactly one complete revision. Retrying again with the worker address
  unreachable returns the original durable acceptance before transport, proving
  response-uncertain idempotency does not re-execute gameplay.
- Post-fault reconciliation reports zero findings. Actual forced termination of
  the packaged JVM and Rust API processes, raw HTTP response loss, and
  outbox-dispatch process boundaries remain explicit open qualification work.

Verification on 2026-07-26:

- The focused
  `worker_connection_death_cannot_create_a_phantom_revision_and_retry_is_safe`
  PostgreSQL test passes against only
  `postgres:19beta2-alpine@sha256:bc62313e826eb44d5f608425b7665962b72820e686da017799e906604bfeb8a5`.
- All 24 serialized PostgreSQL integration and controlled fault tests pass in
  8.10 seconds against that pinned PostgreSQL 19 Beta 2 image. Both disposable
  containers were removed and cleanup was verified.
- `cargo test --all-features` passes 143 active Rust library tests and all 16
  HTTP/OpenAPI tests; 24 database-only tests are intentionally ignored in that
  command. `cargo fmt --all -- --check` and warnings-as-errors
  `cargo clippy --all-targets --all-features -- -D warnings` pass.
- `./gradlew :android:lintDebug :android:assembleDebug :tests:test :server:test
  :desktop:compileKotlin --no-parallel --console=plain` passes with 63
  actionable tasks: three executed and 60 up-to-date.

## Mutually authenticated private worker protocol

Implemented on 2026-07-26:

- Worker protocol version 2 adds mutual HMAC-SHA256 service identity to the
  private Rust/Kotlin boundary. Every request uses a fresh 128-bit OS-random
  nonce. Direction-separated tags bind the nonce, big-endian frame length, and
  exact payload; the worker echoes the nonce in its authenticated response.
- Kotlin verifies request identity before bounded JSON decoding or engine
  execution. Rust verifies the echoed nonce and response identity before JSON
  parsing, accepting a proposal, or reaching PostgreSQL.
- Both production entrypoints require the same independently generated 256-bit
  `UNCIV_ENGINE_WORKER_SECRET` and fail closed on absent, malformed, or
  incorrectly sized values. The secret is never carried in JSON, canonical
  history, logs, or command-line arguments.
- Authentication lives in focused Rust and Kotlin modules; `main.rs`, `lib.rs`,
  bootstrap façades, and the worker engine remain free of cryptographic logic.
- Broad validation exposed identity/hash-order traversal in shared
  `TileMap.placeUnitNearTile()`: equivalent city-state settler destinations
  could differ between fresh JVMs. Candidate tiles are now ordered
  canonically by coordinates at every search depth, preserving the shared
  engine while making this server game-creation path byte-stable.
- Added `docs/operations/authoritative-worker-identity.md` with generation,
  distribution, framing, rotation, and compromise procedures.

Verification on 2026-07-26:

- Rust unit/property tests prove exact key parsing, payload/nonce/direction/tag
  binding, forged-response rejection, authenticated handshake and proposal
  exchange, and deadline cancellation with the new frame.
- The packaged fresh-process Kotlin worker suite proves cross-language
  request/response authentication while retaining deterministic creation and
  replay fixtures. Kotlin tests reject changed payloads, tags, directions, and
  malformed keys.
- A shared fixed protocol-v2 vector proves Rust and Kotlin produce the exact
  same request and response tags. The fresh-process city-state creation fixture
  passed 12 consecutive complete class runs after canonical tile ordering, then
  passed the full regression gate.
- `cargo test --all-features` passes 143 active Rust library tests and all 16
  HTTP/OpenAPI tests; 23 database-only tests are intentionally ignored in that
  command. `cargo fmt --all -- --check` and warnings-as-errors
  `cargo clippy --all-targets --all-features -- -D warnings` pass.
- All 23 serialized PostgreSQL integration and replica-fault tests pass in
  7.82 seconds against only the pinned PostgreSQL 19 Beta 2 image digest. This
  includes authenticated worker game-creation and recovery mocks. The
  disposable database was removed.
- `./gradlew :android:lintDebug :android:assembleDebug :tests:test :server:test
  :desktop:compileKotlin --no-parallel --console=plain` passes. The retained
  reports contain 1101 JVM/server cases: 1088 executed, 13 intentional skips,
  zero failures, and zero errors.
- Every substantive Rust source remains below the 800-line guardrail. The
  largest is 796 lines; `worker.rs` is 640 lines, authentication is isolated in
  a 132-line module, and client transport tests are isolated from the façade.

## Reviewed reconciliation repair workflows

Implemented on 2026-07-26:

- Migration `0018_reconciliation_repairs.sql` enforces one commit/recovery
  outbox event per revision and adds append-only, idempotent repair audit
  events.
- Added game-lock-serialized `unciv-v3-repair`. It is dry-run by default and
  fails closed on a truncated audit. The only automatic reconstruction is a
  missing derived commit-outbox hint, rebuilt from the immutable revision's
  exact topic, revision, and canonical hash.
- Every canonical-history, snapshot, replay-evidence, membership, ownership,
  and orphan finding instead marks the game unavailable with
  `reconciliation_required`. The tool never deletes evidence, rewrites
  snapshots or immutable history, changes membership, advances a head, or
  clears quarantine.
- Added `docs/operations/authoritative-reconciliation-repair.md`, mapping every
  closed finding to its reviewed response and defining backup, rehearsal,
  recovery, verification, and promotion gates.

Verification on 2026-07-26:

- The PostgreSQL repair fixture proves that preview changes no rows, apply
  reconstructs and audits exactly one missing outbox event, repeat apply is
  idempotent, missing canonical snapshot bytes trigger quarantine, and later
  commands fail with `GameUnavailable`.
- All 23 serialized PostgreSQL integration and replica-fault tests pass against
  only the pinned PostgreSQL 19 Beta 2 image digest. The disposable database
  was removed.
- `cargo test --all-features` passes 140 active Rust library tests and all 16
  HTTP/OpenAPI tests; 23 database-only tests are intentionally ignored in that
  command. `cargo fmt --all -- --check` and warnings-as-errors
  `cargo clippy --all-targets --all-features -- -D warnings` pass.
- `./gradlew :android:lintDebug :android:assembleDebug :tests:test :server:test
  :desktop:compileKotlin --no-parallel --console=plain` passes. The retained
  reports contain 1099 JVM/server cases: 1086 executed, 13 intentional skips,
  zero failures, and zero errors.
- Every substantive Rust source remains below the 800-line guardrail. The
  largest remains 796 lines; repair logic is isolated in a 133-line focused
  module, and the binary delegates to a 47-line CLI module.

## Immutable-history snapshot payload retention

Implemented on 2026-07-26:

- Migration `0017_snapshot_payload_retention.sql` separates immutable snapshot
  identity, codec, size, canonical hash, and payload-hash metadata from retained
  compressed payload blobs. Composite PostgreSQL constraints bind every blob
  to its exact metadata and enforce its declared compressed size.
- New genesis, command, and recovery snapshots insert metadata and bytes in the
  same transaction. Historical revisions, command journals, recovery events,
  outbox/audit evidence, and both canonical and payload hashes are never
  deleted or rewritten by compaction.
- Added a game-lock-serialized retention policy. It always protects genesis,
  the current head, every recovery revision, every completed turn, at least two
  recent revisions, and configurable long-term checkpoints. Defaults retain 64
  recent revisions and every 100th revision.
- Added dry-run-first `unciv-v3-compact`. It reports retained/eligible payload
  counts and reclaimable bytes; `--apply` atomically marks selected metadata
  and removes only corresponding blob rows. Reconciliation accepts intentional
  compaction but reports a retained metadata row whose blob is missing.
- Added `docs/operations/authoritative-snapshot-retention.md` with exact preview,
  apply, reconciliation, backup, and restore guidance.

Verification on 2026-07-26:

- The 12-revision PostgreSQL fixture protects genesis, turn snapshots,
  long-term checkpoints, recent revisions, and head while compacting seven
  ordinary payloads. It proves dry-run makes no change, the canonical head
  remains readable, every revision and command remains, reconciliation stays
  clean, and a duplicate command whose original snapshot payload was compacted
  still returns its original accepted revision.
- `cargo test --all-features` passes 140 active Rust library tests and all 16
  HTTP/OpenAPI tests; 22 database-only tests are intentionally ignored in that
  command. `cargo fmt --all -- --check` and warnings-as-errors
  `cargo clippy --all-targets --all-features -- -D warnings` pass.
- All 22 serialized PostgreSQL integration and replica-fault tests pass in
  7.33 seconds against only the pinned PostgreSQL 19 Beta 2 digest. The
  disposable database was removed.
- `./gradlew :android:lintDebug :android:assembleDebug :tests:test :server:test
  :desktop:compileKotlin --no-parallel --console=plain` passes. The retained
  reports contain 1099 JVM/server cases: 1086 executed, 13 intentional skips,
  zero failures, and zero errors.
- Every substantive Rust source remains below the 800-line guardrail; the
  largest is 796 lines and the retention implementation is isolated in a
  focused module.

## Bounded public and worker protocol execution

Implemented on 2026-07-26:

- Added one fail-closed public boundary policy before all API-v3 handlers.
  Requests are limited to 8 KiB bodies, 2 KiB URIs, 64 headers, 8 KiB per
  header value, JSON depth 16, 1 KiB strings and keys, 128 entries per
  collection, and 512 total JSON nodes. Malformed or structurally excessive
  JSON is rejected before typed extraction or command execution.
- Added a 40-second deadline around every public handler and a 16 MiB maximum
  serialized response body. Timeouts return the stable `request_timeout`
  response and drop the handler future; idempotency and revision CAS continue
  to make any client retry safe.
- WebSocket upgrade configuration caps inbound messages and frames at 4 KiB,
  the read buffer at 4 KiB, and the write buffer at 64 KiB. Revision frames
  remain compact hints; HTTP remains the recovery and source-of-truth path.
- Retained the stricter 30-second combined worker connect/write/read deadline
  and proved that expiry drops the private socket. Rust now validates every
  outbound request and inbound response frame for the existing 16 MiB byte
  limit plus depth 64, 65,536 entries per collection, 262,144 nodes, and
  bounded keys before typed deserialization.
- Added matching byte, depth, collection, and node validation to the private
  Kotlin worker before request deserialization. Worker responses pass the same
  validation and 16 MiB frame limit before the length prefix is written.
- Kept the implementation isolated in descriptive
  `api/boundary_limits.rs` and `worker/json_limits.rs` modules; API and worker
  façades contain declarations and middleware wiring only.

Verification on 2026-07-26:

- Boundary tests cover oversized bodies, URIs, headers, JSON strings,
  collections, nesting, responses, public handler cancellation, worker socket
  cancellation, and Kotlin inbound/outbound structural validation.
- `cargo test --all-features` passes 139 active Rust library tests and all 16
  HTTP/OpenAPI tests; 21 database-only tests are intentionally ignored in that
  command. `cargo fmt --all -- --check` and warnings-as-errors
  `cargo clippy --all-targets --all-features -- -D warnings` pass.
- All 21 serialized PostgreSQL integration and replica-fault tests pass in
  7.12 seconds against only the pinned PostgreSQL 19 Beta 2 digest. The
  disposable database was removed.
- `./gradlew :android:lintDebug :android:assembleDebug :tests:test :server:test
  :desktop:compileKotlin --no-parallel --console=plain` passes. The retained
  reports contain 1099 JVM/server cases: 1086 executed, 13 intentional skips,
  zero failures, and zero errors.
- Every substantive Rust source remains below the 800-line guardrail; the
  largest is 796 lines and `worker.rs` is 795.

## Deterministic protocol and persistence property testing

Implemented on 2026-07-26:

- Added fixed-seed Proptest coverage for closed command-envelope parsing,
  unknown command variants and fields, player-projection serialization,
  revision transitions, stale commands, and idempotency-key reuse with changed
  command content. The saved regression case remains checked in.
- Closed a parser weakness discovered by the generated cases: internally
  tagged unit command variants could accept unexpected payload fields.
  Payload-free commands now use closed empty-object variants without changing
  their JSON wire representation.
- Added arbitrary snapshot round trips and adversarial declared-size/frame
  combinations around zstd decompression. Successful decodes must remain
  within the configured size limit and match the exact canonical hash.
- Added arbitrary worker frame and length-prefix parsing, closed response
  parsing, and generated ruleset-manifest validation. Engine/ruleset names,
  lowercase SHA-256 hashes, count limits, and ruleset-name uniqueness are
  enforced before execution and replay.
- Added seeded Kotlin malformed-frame, snapshot, request, and ruleset-manifest
  tests through the same bounded closed decoder used by the worker server.
- Kept the Rust façades thin and moved manifest DTOs and validation into the
  focused `worker/manifest.rs` module.

Verification on 2026-07-26:

- `cargo test --all-features` passes 137 active Rust library tests and all 12
  HTTP/OpenAPI tests; 21 database-only tests are intentionally ignored in that
  command. `cargo fmt --all -- --check` and warnings-as-errors
  `cargo clippy --all-targets --all-features -- -D warnings` pass.
- All 21 serialized PostgreSQL integration and replica-fault tests pass in
  7.48 seconds against only the pinned PostgreSQL 19 Beta 2 digest. The
  disposable database was removed.
- `./gradlew :android:lintDebug :android:assembleDebug :tests:test :server:test
  :desktop:compileKotlin --no-parallel --console=plain` passes. The retained
  reports contain 1098 JVM/server cases: 1085 executed, 13 intentional skips,
  zero failures, and zero errors.
- Every substantive Rust source remains below the 800-line guardrail; the
  largest is 796 lines, `worker.rs` is 794, and `worker/protocol.rs` is 785.

## Fail-closed public and observability disclosure boundaries

Implemented on 2026-07-26:

- Closed an actual normal-log disclosure path. Private Kotlin worker rejection
  reasons may contain canonical-state or rule diagnostics, so neither
  `WorkerClientError` nor `CommitError` now includes that reason in `Display` or
  `Debug`. Public HTTP continues to return only the stable
  `invalid_command` code, and the service no longer logs the private reason.
- Added a public-OpenAPI disclosure gate that recursively inventories every
  property and rejects canonical snapshots, `GameInfo`, replay operations,
  worker request/response fields, server execution time, and RNG/seed state.
  This complements the real-canonical-game player and spectator secret
  sentinel matrix for response values.
- Added an exact WebSocket serialization test. Revision hints contain only
  event type, protocol version, game ID, committed revision, and canonical
  state hash; lag recovery remains the fixed `resync_required` frame and forces
  authenticated HTTP recovery.
- Added a source/dependency observability gate. Runtime log calls cannot
  interpolate snapshot, payload, projection, reason, request/response,
  credential, token, password, or identity values or debug objects. The
  service currently has no tracing, metrics, Prometheus, or OpenTelemetry
  dependency; introducing one fails until its disclosure contract is reviewed.
- Replaced arbitrary security-audit event/outcome strings with closed Rust
  enums. Migration `0016_close_security_audit_payload.sql` enforces the same
  event and outcome sets in PostgreSQL and permanently constrains the JSON
  `details` payload to `{}`. Source identities remain SHA-256 hashes and
  network addresses remain bounded prefixes.

Verification on 2026-07-26:

- Disclosure-focused tests pass private worker sentinels through `Display`,
  `Debug`, HTTP error conversion, WebSocket serialization, the OpenAPI property
  inventory, and the runtime observability source/dependency scan without
  disclosure.
- Rust passes 127 active library tests and all 12 HTTP/OpenAPI tests.
  `cargo fmt --all -- --check` and warnings-as-errors
  `cargo clippy --all-targets --all-features -- -D warnings` pass.
- All 21 serialized PostgreSQL integration and replica-fault tests pass in
  7.64 seconds against only the pinned PostgreSQL 19 Beta 2 digest. The audit
  test proves exact closed labels, an empty details object, hashed identity,
  bounded network prefix, and database rejection of a canonical-state details
  payload. The disposable database was removed.
- `./gradlew :android:lintDebug :android:assembleDebug :tests:test :server:test
  :desktop:compileKotlin --no-parallel --console=plain` passes. The retained
  reports contain 1097 JVM/server cases: 1084 executed, 13 intentional skips,
  zero failures, and zero errors.
- Rust entry façades remain declaration/re-export/bootstrap-only, and every
  substantive Rust source remains below the 800-line guardrail.

## Compact authenticated projection deltas

Implemented on 2026-07-26:

- Added authenticated `GET /api/v3/games/{game_id}/projection/delta` and
  advertised it through capabilities and the generated OpenAPI contract. The
  request identifies the client's exact base revision, canonical-state hash,
  and player-projection hash; it contains no canonical state or client patch.
- PostgreSQL resolves an immutable snapshot for the authenticated player's
  exact base revision and independently reprojects it through the private
  Kotlin worker. The service rejects wrong hashes, missing snapshots, backward
  revisions, spans above 64 revisions, more than 4096 operations, paths above
  1024 bytes, and deltas that are not smaller than the full target projection.
- Deltas are deterministic JSON-pointer replacement operations over the closed
  player projection. Same-shape objects and arrays are traversed; collection
  shape changes replace only that collection. The response binds base and
  target revision, canonical-state hash, projection version, and projection
  hash.
- The Kotlin client accepts only sorted, unique, non-overlapping existing
  paths, canonical pointer escaping, bounded array indices, the current closed
  projection schema, and an exact SHA-256 result. A revision WebSocket hint
  attempts the delta path, but any unavailable or invalid delta falls back to
  the authenticated full projection. `resync_required` always performs a full
  projection fetch.

Verification on 2026-07-26:

- Focused delta/applier and command-bus tests cover deterministic
  reconstruction, collection replacement, escaped paths, stale base
  identities, unknown paths, duplicate operations, tampered hashes, successful
  delta reconciliation, malformed-delta fallback, duplicate/old hints, and
  forced full resynchronization.
- `./gradlew :android:lintDebug :android:assembleDebug :tests:test :server:test
  :desktop:compileKotlin --no-parallel` passes 1097 JVM/server cases: 1084
  executed, 13 intentional skips, zero failures, and zero errors. The existing
  Android SDK was connected through ignored `local.properties`; lint and the
  debug APK both passed.
- Rust passes 124 active library tests, all 10 HTTP/OpenAPI tests, checked-in
  OpenAPI parity, formatting, and warnings-as-errors Clippy. All 21 serialized
  PostgreSQL integration and replica-fault tests pass in 7.67 seconds against
  only the pinned PostgreSQL 19 Beta 2 digest. The disposable database was
  removed.
- `main.rs` remains 6 lines and `lib.rs` remains a declaration/re-export
  facade. The delta implementation is isolated in `projection_delta.rs`, and
  the PostgreSQL projection module remains 277 lines.

## Canonical secret-sentinel projection matrix

Implemented on 2026-07-26:

- Added deterministic projection leak tests built from real canonical
  `GameInfo` state rather than hand-authored projection DTOs. The fixture
  plants unique secrets in known and unknown major civilizations, a city-state,
  barbarians, foreign cities and units, player notifications, diplomatic
  modifiers, foreign religion state, and foreign espionage state.
- Runs the player projection against every closed `DiplomaticStatus` value and
  proves only the actor's own city sentinel is disclosed. All foreign,
  unknown, minor, barbarian, notification, diplomacy, religion, and espionage
  sentinels remain absent.
- Covers all fog states independently: never-explored tiles are absent;
  explored-but-not-visible tiles retain terrain while hiding stale
  improvements and unrevealed resources; currently visible improvements are
  positively disclosed.
- Proves visible foreign units cannot expose their exact movement, destination,
  escort, automation, or instance-name state.
- Builds the spectator projection from the same secret-bearing canonical game
  and proves its public summary contains no city, unit, notification,
  diplomacy, religion, or espionage payload.

Verification on 2026-07-26:

- Focused `ProjectionLeakSentinelTests` pass all four matrix cases.
- `./gradlew :android:lintDebug :android:assembleDebug :tests:test :server:test
  :desktop:compileKotlin --no-parallel` passes 1090 JVM/server cases: 1077
  executed, 13 intentional skips, zero failures, and zero errors.
- `git diff --check` passes. This milestone changes tests and status documents
  only; projection v59 and its production implementation remain unchanged.

## Fail-closed projection disclosure policy

Implemented on 2026-07-26:

- Added a machine-readable disclosure decision for every serialized leaf in
  the player and spectator projections. Descriptor traversal requires exact
  policy equality, so added, removed, renamed, or newly nested fields fail
  tests until their audience, classification, and rationale are reviewed.
- Documented public, player-private, legally-known, action-allowlist,
  presentation, and structurally-redacted classifications plus the required
  schema review procedure.
- The audit found and fixed a real shared-DTO leak: visible foreign units
  exposed exact remaining movement. Projection v59 makes the field nullable,
  includes it for owned units, serializes it as `null` for foreign units, and
  rejects missing owner values or disclosed foreign values in Rust semantic
  validation.
- Preserved projection v58 as an immutable historical fixture, added the v59
  Kotlin/Rust fixture, and regenerated the closed OpenAPI contract.

Verification on 2026-07-26:

- Focused Kotlin disclosure-policy, cross-language contract, and live
  projection-builder confidentiality tests pass.
- Focused Rust projection tests pass: 24 executed, one database-dependent test
  intentionally skipped, zero failures.
- `./gradlew :android:lintDebug :android:assembleDebug :tests:test :server:test
  :desktop:compileKotlin --no-parallel` passes 1086 JVM/server cases: 1073
  executed, 13 intentional skips, zero failures, and zero errors.
- Full Rust tests pass: 130 executed, 21 database-dependent tests intentionally
  skipped, zero failures. `cargo fmt --check`, warnings-as-errors Clippy,
  OpenAPI parity, and `git diff --check` pass.

## Closed persistent-order inventory and no client-side autoplay

Implemented on 2026-07-26:

- Closed the remaining persistent-unit-order inventory. Exact exploration,
  automation, posture/setup, improvement/repair, road, movement/escort,
  cancellation, promotion, upgrade, swap, disband, pillage, founding,
  paradrop, and rename inputs are projection-bound typed commands whose effects
  run in the private Kotlin worker.
- API v3 deliberately does not retain the legacy whole-turn/military/civilian/
  economy autoplay feature. That code invokes AI from `WorldScreen` using
  client settings and UI globals, which is incompatible with a disposable
  presentation client.
- Actual AI civilizations, turn rotation, and pending automated unit orders
  remain worker-owned. Local, hotseat, saved, and explicit legacy games retain
  their existing client autoplay convenience without crossing into v3.
- Regression coverage forbids `AutoPlay`, `TurnManager`,
  `NextTurnAutomation`, local autoplay settings, or `automateTurn()` in the
  projection-only world; it also forbids an autoplay operation in the public
  command schema and Rust command union.

Verification on 2026-07-26:

- `./gradlew :android:lintDebug :android:assembleDebug :tests:test :server:test
  :desktop:compileKotlin --no-parallel` passes 1083 JVM/server cases: 1070
  executed, 13 intentional skips, zero failures, and zero errors.
- Focused execution-context coverage proves the private worker runs the
  automated-order phase before authoritative end turn. `git diff --check`
  passes; no production source or protocol was changed because the forbidden
  v3 autoplay surface was already absent.

## Projection-only owner and invited-player lobby transitions

Implemented on 2026-07-26:

- Server-created games now validate the returned owner role, worker-assigned
  civilization, lifecycle, revision-zero state hash, and synchronized
  projection before entering `AuthoritativeWorldScreen`. The new-game UI no
  longer stops at a success popup or requires directory rediscovery.
- Invitation acceptance now continues through account membership rediscovery,
  validates the committed join revision/hash and non-empty server-assigned
  civilization, opens the exact player projection, and transitions directly
  into the projection-only world.
- Both paths share a focused `OpenedAuthoritativePlayerGame` boundary. Neither
  accepts a client civilization identity, constructs `GameInfo`, loads a local
  multiplayer save, calls `GameStarter`, or uploads canonical state.
- Deterministic tests reject missing assignments, older membership metadata,
  same-revision hash disagreement, projection/metadata civilization mismatch,
  and creation revision mismatch. Production routing tests ensure both UI paths
  terminate in the projection-only screen.

Verification on 2026-07-26:

- `./gradlew :android:lintDebug :android:assembleDebug :tests:test :server:test
  :desktop:compileKotlin --no-parallel` passes 1082 JVM/server cases: 1069
  executed, 13 intentional skips, zero failures, and zero errors.
- `git diff --check` passes. The new lifecycle modules are 55 and 59 lines;
  no façade, Rust, worker, persistence, schema, or legacy path changed.

## API v3 is the sole new-online-game creation path

Implemented on 2026-07-26:

- Explicit API-v1/v2 server detection still preserves discovery and opening of
  existing legacy games, but it now maps new online-game setup to a closed
  `LegacyCreationDisabled` route.
- The production new-game screen reports that a v3 login is required and
  returns before `GameStarter` for both disabled legacy creation and unavailable
  v3 sessions. Only local single-player/hotseat setup may reach local game
  construction; authenticated API v3 creation returns through the server-owned
  setup/projection path.
- Removed legacy player-ID/friend-assignment widgets, Dropbox/file-server
  probing, and the legacy creation warning from the new-game UI. This does not
  remove or reinterpret existing legacy saves or their explicit multiplayer
  screen service.
- Routing tests prove all non-local online branches return before local
  construction and statically reject restoration of the removed legacy
  creation controls.

Verification on 2026-07-26:

- `./gradlew :android:lintDebug :android:assembleDebug :tests:test :server:test
  :desktop:compileKotlin --no-parallel` passes 1073 JVM/server cases: 1060
  executed, 13 intentional skips, zero failures, and zero errors.
- `git diff --check` passes. This milestone changes no server, worker, Rust,
  persistence, protocol, or legacy discovery/open implementation.

## Production session restoration and OS-protected credentials

Implemented on 2026-07-26:

- Production startup now detects the configured multiplayer server before
  choosing a protocol. An explicit API-v1/v2 result enables the legacy service;
  unknown, unreachable, incompatible, or incompletely initialized servers fail
  closed and cannot fall through to local whole-save game creation.
- Added a focused `AuthoritativeSessionLifecycle` with explicit detection,
  secure-store, login-required, authenticated, legacy, and failure states. It
  restores the server-scoped token automatically, negotiates capabilities,
  starts authenticated notifications, and owns login, registration-plus-login,
  logout, and session replacement.
- API-v3 origins now require HTTPS except for exact loopback development hosts.
  URLs containing credentials, queries, or fragments are rejected; normalized
  origins receive distinct SHA-256 credential scopes so a token for one server
  is never offered to another.
- Windows desktop persists only current-user-bound DPAPI ciphertext using an
  atomic replacement file. Oversized or corrupt ciphertext is deleted rather
  than loaded. A live Windows test proves save/restart/load, absence of the
  plaintext token on disk, corrupt-file cleanup, and logout deletion.
- Android now has an app-private, server-scoped Keystore implementation:
  API 23+ uses a non-exportable AES-GCM key; supported API 21-22 devices wrap a
  random AES key with a non-exportable Keystore RSA key pair. Ciphertext, IV,
  wrapped-key, decoded-size, and token-size bounds fail closed.
- The production multiplayer screen exposes login, account creation, and
  logout without retaining passwords. New-game validation and submission block
  while server detection or secure storage is unavailable instead of silently
  executing `GameStarter` and uploading a save.
- `Multiplayer.kt` remains a 43-line façade; detection/restoration, credential
  implementations, account UI, and URL identity are separate focused modules.

Verification on 2026-07-26:

- Focused lifecycle, creation-route, production-routing, URL/origin, token
  validation, and live Windows DPAPI tests pass with desktop compilation.
- Installed the official Android command-line tools and Android 36 SDK locally.
  `:android:compileDebugKotlin`, `:android:lintDebug`, and
  `:android:assembleDebug` pass. Lint's API-level findings were fixed with an
  explicit API-23 contract on the modern Keystore path rather than suppressed.
- `./gradlew :tests:test :server:test :desktop:compileKotlin --no-parallel`
  passes 1072 JVM/server cases: 1059 executed, 13 intentional skips, zero
  failures, and zero errors.
- `git diff --check` passes. No Rust, OpenAPI, persistence, or database source
  changed, and no known error from this milestone is deferred.
- Android runtime Keystore verification on API 21-22 and API 23+ devices or
  emulators remains open. Secure macOS and Linux desktop stores,
  password/account management beyond login/register/logout, and revision-zero
  transition into the projection lobby/world also remain open.

## Explicit legacy whole-save boundary and completed UI authority audit

Implemented on 2026-07-26:

- Split the former mixed `Multiplayer` service into a 40-line lifecycle façade
  and a focused `LegacyMultiplayer` implementation. The façade owns only the
  authenticated API-v3 session lifecycle and an explicitly named `legacy`
  boundary; it cannot accept `GameInfo`, upload/download saves, or advance a
  turn.
- Updated every API-v1/v2 whole-save, preview, file, chat, authentication,
  status, deep-link, creation, resignation, skip-turn, and world-screen call
  site to opt into `onlineMultiplayer.legacy`. Existing legacy games retain
  their behavior while accidental use is visible in source and review.
- API-v3 creation returns before local `GameStarter` execution, authoritative
  directory entries open only the projection world, and that world cannot
  reference `GameInfo`, `WorldScreen`, local saves, upload/download, or local
  turn advancement.
- Completed the source-level UI mutation/fallback audit across creation,
  multiplayer directory/administration, world, city, unit, combat, movement,
  research, policy, religion, diplomacy, trade, espionage, alert, picker,
  save, and status call sites. Production API v3 now has no route to the
  explicitly legacy whole-save service.
- Added a repository-wide routing regression that fails if any Kotlin caller
  reaches a whole-save member through the thin façade rather than `.legacy`.
  Existing projection-world and legacy-screen assertions continue to prevent
  canonical-state or API-v3 interceptor regressions.

Verification on 2026-07-26:

- Focused production-routing tests and desktop compilation pass.
- `./gradlew :tests:test :server:test :desktop:compileKotlin --no-parallel`
  passes 1063 JVM/server cases: 1050 executed, 13 intentional skips, zero
  failures, and zero errors.
- `git diff --check` passes. No Rust, OpenAPI, persistence, or database source
  changed, and no known error from this milestone is deferred.

## Legacy unit, combat, movement, and alert authority-boundary removal

Implemented on 2026-07-26:

- Removed the complete historical API-v3 overlay from the canonical
  `WorldScreen` unit, combat, movement, action, turn-status, and alert paths.
  These legacy surfaces now contain only local, saved, hotseat, and explicit
  API-v2 behavior.
- Deleted the legacy-only `AuthoritativeCombatUi` and
  `AuthoritativeMovementUi` adapters and their obsolete three-test
  `UnitActionsTable` API-v3 routing suite.
- Production API v3 continues to expose projected combat, direct unit actions,
  and persistent unit orders only through `AuthoritativeCombatPanel`,
  `AuthoritativeUnitActionPanel`, `AuthoritativeUnitOrderPanel`, and their
  focused controllers.
- Retained `associatedUnique` on the shared instant-improvement action. The
  first full gate caught that this metadata is also consumed by the private
  headless worker; restoring it preserved server-side rule derivation without
  restoring any legacy client command route.
- Source-level routing coverage rejects authoritative session, command,
  outcome, movement, and combat adapters throughout the affected legacy files,
  proves the deleted adapters remain absent, and proves representative
  projection-only production controls remain wired.

Verification on 2026-07-26:

- Focused routing/controller tests, the exact instant-improvement worker
  regression test, and desktop compilation pass.
- `./gradlew :tests:test :server:test :desktop:compileKotlin --no-parallel`
  passes 1062 JVM/server cases: 1049 executed, 13 intentional skips, zero
  failures, and zero errors.
- A source scan finds no API-v3 session, command-outcome, submission, movement
  adapter, or combat-adapter reference in the affected legacy world, picker,
  and popup surfaces.
- No Rust or OpenAPI source changed. No known compile, test, routing, or
  formatting error from this milestone is deferred.
- The broader repository-wide mutation and fallback audit remains unchecked
  until every remaining multiplayer call site is classified.

## Legacy city-screen authority-boundary removal

Implemented on 2026-07-26:

- Removed the complete historical API-v3 overlay from the seven canonical
  city-screen files and their construction context-menu helper. The restored
  city UI now contains only local, saved, hotseat, and legacy/API-v2 behavior.
- Production API v3 retains construction selection and tile targeting, queue
  actions/reordering/removal, purchases, tile and batch buying, building sales,
  governance/disposition, tile assignments, specialist counts/mode, citizen
  reset/growth/focus, and unit-promotion preferences exclusively in focused
  projection-world city panels and controllers.
- Modernized two restored local code paths to current allocation-free unique
  and tile traversal APIs, eliminating the deprecation warnings found by the
  first compile.
- Source-level routing coverage now rejects API-v3 session/types/outcomes in
  every legacy city file and the context-menu helper while proving
  representative projection-only city controls remain wired.

Verification on 2026-07-26:

- Focused production-routing/city-controller tests and desktop compilation
  pass without compile warnings.
- `./gradlew :tests:test :server:test :desktop:compileKotlin --no-parallel`
  passes 1065 JVM/server cases: 1052 executed, 13 intentional skips, zero
  failures, and zero errors.
- No Rust or OpenAPI source changed. No known compile, warning, test, routing,
  or formatting error from this milestone is deferred.
- Historical API-v3 interceptors remain in legacy-shaped unit and alert
  surfaces; the broad mutation audit remains correctly unchecked until those
  are removed or explicitly classified.

## Legacy diplomacy and trade authority-boundary removal

Implemented on 2026-07-26:

- Removed historical API-v3 branches from `DiplomacyScreen`,
  `MajorCivDiplomacyTable`, `CityStateDiplomacyTable`, `TradeTable`, and
  `TradePopup`. These canonical-`GameInfo` surfaces now retain only local,
  saved, hotseat, and legacy/API-v2 behavior.
- Production API v3 continues to expose war, denouncement, friendship,
  demands, city-state gold/protection/tribute/improvement/peace/marriage, and
  the complete offer/retract/accept/decline/counter trade lifecycle only
  through focused projection-world panels and controllers.
- Source-level routing coverage rejects session lookup, typed API-v3
  submission, and authoritative outcome handling in all five legacy surfaces,
  while proving representative diplomacy and trade controller routes remain.

Verification on 2026-07-26:

- Focused production-routing/diplomacy/trade controller tests and desktop
  compilation pass.
- `./gradlew :tests:test :server:test :desktop:compileKotlin --no-parallel`
  passes 1065 JVM/server cases: 1052 executed, 13 intentional skips, zero
  failures, and zero errors.
- No Rust or OpenAPI source changed. No known compile, test, routing, or
  formatting error from this milestone is deferred.
- Historical API-v3 interceptors remain in legacy-shaped city, unit, and alert
  surfaces; the broad mutation audit remains correctly unchecked until those
  are removed or explicitly classified.

## Legacy espionage authority-boundary removal

Implemented on 2026-07-26:

- Removed historical API-v3 movement and coup submission branches from
  `EspionageOverviewScreen`. This canonical-`GameInfo` overview is now
  explicitly limited to local, saved, hotseat, and legacy/API-v2 behavior.
- Production API v3 continues to expose exact projected hideout/city movement,
  coup staging, and coup cancellation only through `AuthoritativeSpyPanel` and
  its projection-validating controller.
- Source-level routing coverage now rejects API-v3 session lookup, typed
  submission, and authoritative outcomes in the legacy espionage overview
  while proving the projection-only spy controls remain wired.

Verification on 2026-07-26:

- Focused production-routing/spy-controller tests and desktop compilation pass.
- `./gradlew :tests:test :server:test :desktop:compileKotlin --no-parallel`
  passes 1065 JVM/server cases: 1052 executed, 13 intentional skips, zero
  failures, and zero errors.
- No Rust or OpenAPI source changed. No known compile, test, routing, or
  formatting error from this milestone is deferred.
- Historical API-v3 interceptors remain in legacy-shaped city, unit,
  diplomacy, and alert surfaces; the broad mutation audit remains correctly
  unchecked until those are removed or explicitly classified.

## Legacy great-person and religion picker authority-boundary removal

Implemented on 2026-07-26:

- Removed historical API-v3 projection filtering and command submission from
  `GreatPersonPickerScreen` and removed API-v3 religion submission from
  `ReligionPickerScreenCommon`.
- Simplified the pantheon and religious-belief picker APIs so they no longer
  accept a `WorldScreen` or construct an authoritative payload. These
  canonical-`GameInfo` pickers are now explicitly local/legacy surfaces.
- Production API v3 continues to render only server-projected great-person,
  pantheon, founding, enhancement, and reformation choices through the focused
  projection-world prompt and religion panels.
- Extended source-level routing coverage to forbid API-v3 session lookup,
  typed submission, and authoritative outcome handling in the two shared
  legacy picker implementations while proving the projection-world routes
  remain present.

Verification on 2026-07-26:

- Focused production-routing/world-controller tests and desktop compilation
  pass.
- `./gradlew :tests:test :server:test :desktop:compileKotlin --no-parallel`
  passes 1065 JVM/server cases: 1052 executed, 13 intentional skips, zero
  failures, and zero errors.
- No Rust or OpenAPI source changed. No known compile, test, routing, or
  formatting error from this milestone is deferred.
- Historical API-v3 interceptors remain in legacy-shaped city, unit,
  diplomacy, espionage, and alert surfaces; the broad mutation audit remains
  correctly unchecked until those are removed or explicitly classified.

## Legacy decision-picker authority-boundary removal

Implemented on 2026-07-26:

- Removed the historical API-v3 session, projection, command, and outcome
  branches from `TechPickerScreen`, `PolicyPickerScreen`, and
  `DiplomaticVotePickerScreen`. These canonical-`GameInfo` pickers are now
  explicitly limited to offline, saved, hotseat, and legacy/API-v2 behavior.
- API-v3 research selection, queue management, free-technology selection,
  policy adoption, and diplomatic-victory voting remain exclusively in the
  projection-only world and its focused controller/prompt surfaces.
- Preserved each legacy picker's direct local behavior without adding a second
  rules path or allowing an opened command bus to change screen semantics.
- Source-level routing coverage now rejects API-v3 session lookup, typed
  command submission, and authoritative outcome handling in all three legacy
  pickers while proving the corresponding projection-world controller routes
  remain present.

Verification on 2026-07-26:

- Focused production-routing/world-controller tests and desktop compilation
  pass.
- `./gradlew :tests:test :server:test :desktop:compileKotlin --no-parallel`
  passes 1065 JVM/server cases: 1052 executed, 13 intentional skips, zero
  failures, and zero errors.
- `git diff --check` passes.
- No Rust or OpenAPI source changed, and no known compile, test, routing, or
  formatting error from this milestone is deferred.
- Historical API-v3 interceptors remain in legacy-shaped city, unit,
  diplomacy, religion, espionage, and alert surfaces; the broad mutation audit
  remains correctly unchecked until those are removed or explicitly
  classified.

## Legacy WorldScreen authority-boundary removal

Implemented on 2026-07-26:

- Removed the historical API-v3 prompt and end-turn interceptors from
  `WorldScreen`. That canonical-`GameInfo` screen is now explicitly responsible
  only for offline, saved, hotseat, and legacy/API-v2 behavior; it can no
  longer switch between server `EndTurn` and local clone/advance/upload based
  on whether a command bus happens to be open.
- API-v3 end turn remains exclusively in `AuthoritativeWorldScreen`, where the
  immutable player projection supplies mandatory blockers and the focused
  controller refuses submission until the server advertises readiness.
- The existing local/legacy path is behaviorally preserved: it still clones
  `GameInfo`, runs local `nextTurn`, and uploads only for explicit legacy online
  games. Server-authoritative games never construct or enter that screen.
- Source-level routing tests now require the legacy world to contain no
  authoritative session, end-turn, or pending-action reference and require the
  projection world to retain both typed session submission and controller
  gating.

Verification on 2026-07-26:

- Focused production-routing/world-controller tests and desktop compilation
  pass.
- `./gradlew :tests:test :server:test :desktop:compileKotlin --no-parallel`
  passes 1064 JVM/server cases: 1051 executed, 13 intentional skips, zero
  failures, and zero errors.
- No Rust or OpenAPI source changed, and no known compile, test, routing, or
  formatting error from this milestone is deferred.
- Historical API-v3 interceptors remain in legacy-shaped city, unit,
  diplomacy, religion, espionage, and alert surfaces; the broad mutation audit
  remains correctly unchecked until those are removed or explicitly
  classified.

## Production authority routing and lifecycle separation

Implemented on 2026-07-26:

- The production route audit now proves that installed-session online creation
  returns through server `CreateGame` before any `GameStarter` call; account
  directory selection branches to `AuthoritativeWorldScreen` before legacy
  preview loading; and that projection screen has no canonical game, whole-save
  upload/download, local load, or local turn-advance dependency.
- Removed the legacy preview resignation function's hybrid behavior that used
  API v3 only when a command bus happened to be open and otherwise fell through
  to whole-save download/mutation/upload. Explicit legacy selection now remains
  legacy, while authoritative selection has a distinct server-only resignation
  control.
- `AuthoritativeResignationCoordinator` opens an absent command bus once and
  delegates to the session's retry-stable typed resignation. Uncertain retries
  reuse the pending command identity; accepted resignation removes the local
  membership session and refreshes account-scoped discovery.
- Production owner administration now exposes active-only kick,
  server-derived force-resignation, ownership transfer, and close actions, plus
  closed-only archive. This fixes the prior unreachable Archive control, whose
  popup could only be opened for active games even though the server correctly
  requires a closed lifecycle.
- Source-level regression tests lock the authoritative creation/selection/world
  routing order, forbid canonical/legacy state operations from the projection
  world, forbid conditional API-v3 fallback inside legacy resignation, and
  enforce active-versus-closed administration actions.

Verification on 2026-07-26:

- The first focused routing run exposed an overbroad assertion that matched
  boundary documentation containing the words `GameInfo` and `WorldScreen`.
  It was corrected to check forbidden imports and executable operation calls.
- Focused resignation, administration, session, production-routing, and
  desktop compilation gates pass.
- `./gradlew :tests:test :server:test :desktop:compileKotlin --no-parallel`
  passes 1063 JVM/server cases: 1050 executed, 13 intentional skips, zero
  failures, and zero errors.
- `MultiplayerScreen.kt` remains below the project file guardrail at 695 lines;
  the new resignation coordinator is 27 lines and the administration
  coordinator/popup remain focused at 115 and 187 lines.
- No Rust or OpenAPI source changed. No known compile, test, routing,
  lifecycle-state, or formatting error from this milestone is deferred.
- The broader source-level mutation audit remains open because historical
  legacy-shaped screens still contain API-v3 interceptors keyed to an open
  command bus. Current production v3 entry paths do not reach those screens,
  but they must be removed or explicitly reclassified before the broader
  checklist item can be checked.

## Projection-only status and history completion

Implemented on 2026-07-26:

- A field-by-field audit of `PlayerProjection` and the production
  `AuthoritativeWorldScreen` confirmed that every command-bearing top-level
  family already routes through a focused fail-closed panel. The remaining
  unconsumed fields were read-only player/current-turn identity, treasury,
  known civilizations, adopted policies, researched technologies, and public
  wonder history.
- Focused `AuthoritativePlayerStatusPanel` and `AuthoritativeHistoryPanel`
  modules now render those exact server fields. Knowledge-gated builder and
  location details remain absent when the projection omits them; the client
  never attempts to reconstruct hidden wonder context or gameplay history.
- Deterministic presentation tests use the shared v58 fixture to prove both
  fully disclosed and deliberately hidden wonder rows, player status, known
  civilizations, treasury, and researched history. The projection-only
  source-boundary test includes both new panels and continues to reject
  `GameInfo`, `WorldScreen`, `GameStarter`, and legacy save dependencies.
- This closes the production projection-only rendering checklist item for the
  current player projection contract. Richer future notification/history
  feeds remain a separately listed product/schema expansion rather than an
  unrendered existing contract.

Verification on 2026-07-26:

- The first focused compile showed that the separate Gradle `tests` module
  cannot call an `internal` core formatter. Only the two stateless
  presentation formatters were widened for deterministic cross-module tests;
  both panels and all mutation controllers remain internal or narrowly scoped.
- The focused world-controller tests and desktop compilation pass.
- `./gradlew :tests:test :server:test :desktop:compileKotlin --no-parallel`
  passes 1054 JVM/server cases: 1041 executed, 13 intentional skips, zero
  failures, and zero errors.
- No Rust or OpenAPI source changed: existing projection v58 semantic tests
  already validate researched history and bounded, sorted, visibility-coherent
  wonder events.
- No known compile, test, source-boundary, or formatting error from this
  milestone is deferred.

## Projection-only route and worker orders

Implemented on 2026-07-26:

- Projection v58 adds sorted, bounded, exact per-unit multi-turn movement
  destinations, tile improvement/repair/cancellation choices, optional
  remove-then-build follow-ups, and reachable road destinations. These private
  choices are empty for foreign and out-of-turn units.
- Focused Kotlin `MovementTargetProjection` and `UnitOrderProjection` modules
  derive choices from the shared game engine. The private worker requires the
  submitted choice to remain canonical and then independently revalidates and
  executes movement, improvement, and road rules; Rust remains a control plane
  and contains no second rules engine.
- The production projection-only unit panel can enter an exact map-target mode
  for long routes and roads, render exact improvement choices, and cancel
  active road orders. Invalid map clicks cannot fall through into a different
  command while target mode is active, and every controller/command-bus layer
  rejects invented or stale choices before transport.
- Rust validates projection v58 bounds, coordinate ordering/uniqueness,
  exploration, improvement-name shape and ordering, cancellation coherence,
  and complete absence of these controls from foreign or out-of-turn units.
  The shared fixture and generated OpenAPI contract are updated to v58.

Verification on 2026-07-26:

- The first focused Kotlin run found five older tests whose synthetic
  projections omitted the newly required exact choices. Their fixtures were
  updated without weakening authorization; the focused command-bus, session,
  controller, projection-contract, and desktop compile gate then passed all
  134 tests.
- `./gradlew :tests:test :server:test :desktop:compileKotlin --no-parallel`
  passes 1052 JVM/server cases: 1039 executed, 13 intentional skips, zero
  failures, and zero errors.
- `cargo test --all-targets --all-features` passes 120 active library tests and
  10 HTTP/OpenAPI tests; 21 PostgreSQL tests are intentionally ignored without
  `UNCIV_V3_DATABASE_URL`. Warnings-as-errors Clippy, `cargo fmt --check`, and
  generated OpenAPI parity pass.
- `main.rs` remains 6 lines and `lib.rs` a 53-line facade. The largest touched
  Rust implementation file is `projection.rs` at 788 lines; the new focused
  Kotlin order and movement modules are 112 and 59 lines.
- No known compile, test, format, projection-contract, or OpenAPI error from
  this milestone is deferred.

## Projection-only direct unit controls

Implemented on 2026-07-26:

- Projection v57 adds exact worker-derived posture choices, direct
  disband/pillage/found capability, sorted visible paradrop destinations,
  affordable upgrade targets with canonical gold cost, and turn-scoped rename
  capability to each owned unit. Foreign and out-of-turn units receive empty
  or false controls rather than client-hidden rule state.
- `UnitControlProjection` is the focused Kotlin legality source. The headless
  worker now consumes its posture allowlist before applying the shared posture
  mutation, so projection and execution cannot drift into separate rule
  implementations. Every command is still independently revalidated against
  canonical state inside the private worker.
- `AuthoritativeUnitOrderController` and the production projection-only panel
  now route posture, disband, pillage, found city, paradrop, single-unit
  upgrade, and bounded rename intents through the authenticated retry-stable
  session. Invented capabilities, coordinates, targets, names, stale
  projections, and out-of-turn actions fail before transport.
- Rust accepts projection v57, validates bounded/sorted/unique direct controls,
  requires paradrop coordinates to be currently visible, rejects malformed
  costs and all foreign/out-of-turn leaks, and publishes the regenerated
  OpenAPI contract. It still contains no gameplay rule implementation.
- `ProjectedUnit.kt` now owns unit/combat DTOs instead of allowing
  `PlayerProjection.kt` to cross the file-size guardrail. The Rust spectator
  contract test was likewise split from `projection.rs` before that
  implementation file crossed 800 lines.

Verification on 2026-07-26:

- The first focused Kotlin run exposed seven pre-v57 tests that constructed
  permissive units without the new exact allowlists. Those fixtures were
  corrected; the focused command-bus, session, controller, contract, and
  desktop compilation gate then passed.
- The initial OpenAPI command omitted Cargo's binary selector, and the first
  Rust compile exposed missing ordering derives used by semantic validation.
  Both invocation and derives were corrected. The explicit server binary now
  regenerates `api-v3.json`, and generated-contract parity passes.
- `./gradlew :tests:test :server:test :desktop:compileKotlin --no-parallel`
  passes 1051 JVM/server cases: 1038 executed, 13 intentional skips, zero
  failures, and zero errors.
- `cargo test --all-targets --all-features` passes 120 active library tests and
  10 HTTP/OpenAPI tests; 21 PostgreSQL tests are intentionally ignored without
  `UNCIV_V3_DATABASE_URL`. Warnings-as-errors Clippy, `cargo fmt --check`, and
  `git diff --check` pass.
- `main.rs` remains 6 lines and `lib.rs` a 53-line facade. The largest Rust
  source remains `worker/protocol.rs` at 796 lines; `projection.rs`,
  `PlayerProjection.kt`, `ProjectedUnit.kt`, the focused Kotlin projection,
  controller, and panel are 778, 672, 153, 59, 150, and 94 lines.
- No compile, test, formatting, OpenAPI, contract, or cleanup error remains
  deferred.

The next production projection-only unit gap is exact multi-turn route and
improvement/road choice input. Whole-turn autoplay remains intentionally
fail-closed unless it is implemented as explicit server-owned AI.

## Projection-only bilateral trade

Implemented on 2026-07-26:

- The production projection-only world now renders bilateral partners,
  pending-outgoing status, exact available offers from both sides, bounded
  integer quantity fields, incoming request terms, and offer/retract/accept/
  decline/counter controls.
- `AuthoritativeTradeController` requires the actor's current turn, exact
  projected partner/request identity, pending-state compatibility, a nonempty
  composition, and every selected amount within the projected maximum before
  typed submission. `AuthoritativeTradePanel` only holds ephemeral form state;
  it cannot mutate gold, resources, diplomacy, requests, or canonical trades.
- `TradeProjectionValidation` now protects both direct offers and
  counteroffers in the client command bus. Every offer must match one projected
  `(name,type,duration)` identity, quantities must be positive and bounded,
  sides are capped, and duplicate identities are rejected.
- The canonical worker previously distinguished duplicates by
  `(name,type,amount)`, allowing one available identity to be split into
  different amounts, and ignored client duration. It now permits each
  `(name,type)` identity once and requires the exact canonical duration before
  copying the canonical offer and resolving the trade.
- `TradeProjection` keeps counterpart identities visible but clears available
  offers, outgoing-action state, and incoming actionable requests outside the
  actor's turn. Existing canonical tests were updated to obtain exact duration
  from the projection and to read incoming requests only on the recipient's
  turn, eliminating their prior client-authored assumptions.
- Deterministic tests cover offer, retraction, acceptance, decline,
  counteroffer, empty/excessive/duplicate/forged-duration inputs, out-of-turn
  rejection, retry without local revision mutation, and canonical duplicate
  and duration rejection.

Verification on 2026-07-26:

- The first two focused runs exposed stale test assumptions: a hardcoded
  duration and an incoming request read before the recipient's turn. Both tests
  were corrected to consume the authoritative projection, then the full
  focused trade/canonical gate reran cleanly.
- `./gradlew :tests:test :server:test :desktop:compileKotlin --no-parallel`
  passes 1051 JVM/server cases: 1038 executed, 13 intentional skips, zero
  failures, and zero errors.
- `git diff --check` passes. `PlayerProjection.kt` remains below threshold at
  795 lines; the projection helper, shared validation, controller, panel,
  session adapter, and world screen are 25, 38, 82, 116, 243, and 285 lines.
  This milestone changes no Rust, wire schema, OpenAPI, persistence, or
  PostgreSQL behavior.
- No compile, test, formatting, contract, database, or cleanup error remains
  deferred.

The remaining projection-only world work is concentrated in under-specified
unit controls and richer history/event presentation rather than social actions.

## Projection-only major and city-state diplomacy

Implemented on 2026-07-26:

- The production projection-only world now renders every worker-advertised
  major-civilization action: declaration of war, denunciation, friendship
  offer, and each exact diplomatic demand.
- City-state controls now render exact projected war, gold gifts, protection
  pledge/revocation, gold/worker tribute, resource-improvement gifts, peace,
  and diplomatic-marriage choices. Displayed amounts, costs, coordinates, and
  improvement names come only from the projection.
- `AuthoritativeDiplomacyController` requires the actor's current turn and
  rechecks every counterpart and capability before typed submission.
  `AuthoritativeDiplomacyPanel` contains presentation/routing only; relationship
  rules, influence, costs, yields, ownership changes, and consequences remain
  canonical worker behavior.
- Mutable diplomacy capabilities were previously projected even outside the
  actor's turn and relied on worker rejection. `DiplomacyProjection` now keeps
  visible counterparts and adopted public policy branches while clearing every
  action flag/list/cost and all actionable prompts outside the turn. This makes
  direct command-bus callers fail closed from the same server-authored view.
- The turn-scoping concern was extracted instead of growing
  `PlayerProjection.kt` past the agreed file threshold. The builder is 794
  lines and the focused diplomacy projection module is 44 lines.
- Deterministic controller tests cover all ten major/city-state operation
  routes, invented counterpart, unadvertised amount/state, out-of-turn
  rejection, and response-uncertain retry without local projection mutation.
  Structural tests cover the new controller/panel.

Verification on 2026-07-26:

- The first focused run exposed one test-only exception-class mismatch for an
  invented war target. The assertion was corrected, and the complete focused
  gate reran cleanly.
- `./gradlew :tests:test :server:test :desktop:compileKotlin --no-parallel`
  passes 1048 JVM/server cases: 1035 executed, 13 intentional skips, zero
  failures, and zero errors.
- `git diff --check` passes. The projection helper, controller, panel, session
  adapter, and production world screen are 44, 144, 99, 221, and 284 lines.
  This milestone changes no Rust, wire schema, OpenAPI, persistence, or
  PostgreSQL behavior.
- No compile, test, formatting, contract, database, or cleanup error remains
  deferred.

The remaining projection-only social interaction gap is trade composition and
decisions; broader unit and history/event presentation also remains tracked.

## Projection-only religion choices and complete end-turn inputs

Implemented on 2026-07-26:

- The production projection-only world now renders every pending religious
  belief slot from `requiredBeliefTypes`, only worker-advertised compatible
  beliefs, and—only while founding—one projected unused religion icon plus a
  bounded display-name field. Selection state is presentation-only and has no
  gameplay effect until the exact typed command is accepted.
- `ReligionChoiceValidation` centralizes the public projection contract used by
  both the focused controller and command bus: exact slot count, distinct
  belief names, projected membership, required type multiset including wildcard
  slots, icon membership, identity presence/absence, and a 1-128 printable
  character name.
- The command bus previously accepted any distinct projected belief names even
  when their types did not fill the projected slots. It now rejects that
  mismatch before transport. The private worker independently re-derives every
  slot and belief from canonical rules and now also rejects control characters
  in religion display names before checking reserved or duplicate identities.
- `AuthoritativeReligionController` and `AuthoritativeReligionPanel` remain
  projection-only. The client does not infer belief availability, religion
  state, free-belief counts, uniqueness, holy city, costs, yields, or effects.
- With this surface, all ten `PendingEndTurnAction` enum families now have
  production projection-only input paths: construction, technology, policy,
  spies, pantheon/founding/enhancement/reformation, diplomatic vote, and
  great-person selection. End turn remains disabled until a refreshed
  projection reports the pending list empty.
- Deterministic tests cover exact religion submission, slot-type mismatch,
  invented belief/icon, invalid identity, missing choice, and
  response-uncertain retry without local revision mutation. A direct
  command-bus test proves a projected belief with the wrong type never reaches
  transport.

Verification on 2026-07-26:

- Focused religion/controller, canonical readiness, command-bus, session,
  world-boundary, and desktop compilation gates pass.
- `./gradlew :tests:test :server:test :desktop:compileKotlin --no-parallel`
  passes 1045 JVM/server cases: 1032 executed, 13 intentional skips, zero
  failures, and zero errors.
- `git diff --check` passes. The shared validation, controller, panel, and
  production world screen are 64, 30, 93, and 283 lines. This milestone changes
  no Rust, wire schema, OpenAPI, persistence, or PostgreSQL behavior.
- No compile, test, formatting, contract, database, or cleanup error remains
  deferred.

The end-turn input-coverage gate is complete. The broader projection-only world
still needs non-blocking unit, diplomacy, trade, and history/event surfaces.

## Projection-only spy controls

Implemented on 2026-07-26:

- The production projection-only world now renders every owned spy's projected
  rank, action, remaining turns, exact available city IDs, hideout
  availability, and coup stage/cancel capability. A visible `move_spies`
  blocker directs the player to these controls.
- `AuthoritativeSpyController` requires the actor's current turn, an exact
  projected spy name, and either a projected destination/hideout capability or
  the matching coup capability before typed submission. It never derives city
  visibility, spy occupancy, coup odds, progress, or outcomes.
- The client command bus previously relied on the private worker to reject
  out-of-turn spy movement and coup changes. Both operations now fail before
  transport when the latest projection is not the actor's current turn; the
  worker independently retains the same canonical check.
- `AuthoritativeSpyPanel` and the focused session adapter route exact move and
  coup operations without `GameInfo`, legacy espionage screens, or local turn
  state. All progress and deterministic/random spy outcomes remain private
  worker-owned state.
- Deterministic tests cover hideout and city movement, coup stage/cancel,
  invented destination, unavailable action, missing spy, out-of-turn
  rejection, and response-uncertain retry without projection mutation. A
  direct command-bus test proves out-of-turn movement never reaches transport.

Verification on 2026-07-26:

- Focused spy/controller, command-bus, session, world-boundary, and desktop
  compilation gates pass.
- `./gradlew :tests:test :server:test :desktop:compileKotlin --no-parallel`
  passes 1041 JVM/server cases: 1028 executed, 13 intentional skips, zero
  failures, and zero errors.
- `git diff --check` passes. The controller and panel are 46 and 61 lines; the
  focused session adapter and production world screen remain 182 and 278 lines.
  This milestone changes no Rust, wire schema, OpenAPI, persistence, or
  PostgreSQL behavior.
- No compile, test, formatting, contract, database, or cleanup error remains
  deferred.

Every pending-turn action family except religion identity/belief selection now
has a projection-only production input path.

## Projection-only votes, selections, events, and prompts

Implemented on 2026-07-26:

- The projection-only production world now renders the exact blocking
  diplomatic-victory vote and great-person selection choices. Voting includes
  an explicit worker-supported abstention path plus only projected candidate
  civilizations; great-person buttons use only projected unit names.
- The same focused surface renders mod-defined event text and opaque choices,
  friendship/demand accept/decline prompts, and protected-city-state response
  enums. It does not reconstruct event conditions or diplomacy consequences.
- `AuthoritativePromptController` requires the matching pending-turn action
  where applicable and rechecks every candidate, unit name, prompt ID, choice
  ID, prompt kind, and city-state response against the latest projection before
  typed submission.
- The command bus previously allowed any projected diplomacy prompt to use the
  generic boolean accept/decline operation. It now restricts that route to
  friendship and demand prompts; protected-minor prompts must use one of their
  projected `CityStateProtectionResponse` values.
- `AuthoritativePromptPanel` and the focused session adapter connect the exact
  operations without canonical state or legacy popup dependencies. Religion
  identity/belief selection remains absent because it needs a deliberate
  bounded name/icon and belief-type combination UI; it is not auto-selected.
- Deterministic tests cover vote/abstain, great-person selection, event choice,
  ordinary diplomacy, protected-city-state response, invented and wrong-kind
  rejection before transport, and response-uncertain retry. A command-bus test
  directly proves wrong-kind boolean diplomacy rejection.

Verification on 2026-07-26:

- Focused prompt/controller, command-bus, session, world-boundary, and desktop
  compilation gates pass.
- `./gradlew :tests:test :server:test :desktop:compileKotlin --no-parallel`
  passes 1037 JVM/server cases: 1024 executed, 13 intentional skips, zero
  failures, and zero errors.
- `git diff --check` passes. The controller and panel are 91 and 78 lines; the
  focused session adapter and production world screen remain 169 and 277 lines.
  This milestone changes no Rust, wire schema, OpenAPI, persistence, or
  PostgreSQL behavior.
- No compile, test, formatting, contract, database, or cleanup error remains
  deferred.

The voting and great-person pending-turn blockers now have exact production UI.
Religion choice and spy movement are the known remaining projected end-turn
blockers, alongside the broader diplomacy/trade/history surfaces.

## Projection-only exact unit order state

Implemented on 2026-07-26:

- The production projection-only world now exposes the unit-order controls
  whose exact state or immediate choice is present in the latest player
  projection: cancellation of a projected movement destination,
  exploration/automation state flips, one immediate available promotion, and
  exact friendly swap destinations.
- A focused `AuthoritativeUnitOrderController` requires the actor's current
  turn, an exact owned unit, a real projected movement order, a non-no-op state
  transition, an exact worker-advertised promotion, or a projected swap
  coordinate before typed submission. It never infers order or promotion
  legality from unit type, ruleset, XP, movement, or tile state.
- A focused `AuthoritativeUnitOrderPanel` renders these controls only for the
  selected current-turn unit. The session adapter submits the existing typed
  operations, and response-uncertain retries continue to reuse the session's
  pending command identity and meaning.
- Posture, long-route, improvement/road, upgrade, rename,
  disband/found/pillage/paradrop, and optional autoplay UI remain intentionally
  absent until each has an equally precise projected choice contract. Their
  existing worker commands are not treated as proof of safe production UI.
- Deterministic controller tests cover every exposed order, no-op and invented
  rejection, missing-unit and out-of-turn rejection, and retry without local
  revision mutation. The canonical/legacy dependency boundary test covers both
  new source files.

Verification on 2026-07-26:

- Focused controller, command-bus, session, world-boundary, and desktop
  compilation gates pass.
- `./gradlew :tests:test :server:test :desktop:compileKotlin --no-parallel`
  passes 1033 JVM/server cases: 1020 executed, 13 intentional skips, zero
  failures, and zero errors.
- `git diff --check` passes. The new controller and panel are 81 and 55 lines;
  the focused session adapter and production world screen remain 147 and 276
  lines. This milestone changes no Rust, wire schema, OpenAPI, persistence, or
  PostgreSQL behavior.
- No compile, test, formatting, contract, database, or cleanup error remains
  deferred.

The remaining unit UI gap is now explicitly limited to controls that need new
per-unit projected choice contracts rather than client-side rules inference.

## Projection-only direct unit special actions

Implemented on 2026-07-26:

- The production projection-only world now exposes the closed direct unit
  action families already derived by the private worker: founding/enhancing/
  spreading religion and removing heresy; direct great-person uses; gifting;
  capital-project consumption; instant improvements; transformations; and
  opaque triggered uniques.
- A focused `AuthoritativeUnitActionController` rechecks the selected owned
  unit and exact enum or opaque action ID against the latest projection before
  typed submission. The client does not reconstruct availability from unit
  names, uniques, rulesets, tile state, costs, or effects.
- A focused `AuthoritativeUnitActionPanel` renders only those advertised
  actions. Display labels and transformation/project names are projected
  presentation data; canonical legality, costs, yields, diplomacy effects,
  transformation results, and unit consumption remain worker-owned.
- The authenticated session already preserves one pending command identity and
  meaning for every action family. The production adapter now connects those
  exact operations without local mutation or a legacy `WorldScreen` fallback.
- Deterministic controller tests cover exact routing across all seven action
  groups, invented/unadvertised identity rejection before transport, and
  response-uncertain retry without revision mutation. The structural boundary
  test includes both new source files.

Verification on 2026-07-26:

- The first focused run exposed one test-only exception-class mismatch:
  unadvertised identities use Kotlin `require` and therefore throw
  `IllegalArgumentException`. The assertion was corrected and the complete
  focused gate reran cleanly; production behavior did not change.
- `./gradlew :tests:test :server:test :desktop:compileKotlin --no-parallel`
  passes 1030 JVM/server cases: 1017 executed, 13 intentional skips, zero
  failures, and zero errors.
- `git diff --check` passes. The controller, panel, session adapter, and world
  screen are 96, 65, 125, and 275 lines. This milestone changes no Rust,
  protocol schema, OpenAPI, persistence, or PostgreSQL behavior.
- No compile, test, formatting, contract, database, or cleanup error remains
  deferred.

The projection-only world now exposes combat and direct special actions from
server-authored allowlists. Unit orders/promotions and the remaining diplomacy,
trade, choice, religion-selection, and history surfaces stay tracked.

## Projection-only authoritative combat controls

Implemented on 2026-07-26:

- The production projection-only world now exposes all combat families whose
  legal targets are already explicitly advertised by the private worker:
  ordinary/ranged unit attacks, nuclear strikes, air sweeps, and city
  bombardment. No combat action consults a client `GameInfo`, ruleset, target
  search, or legacy world-screen state.
- A focused `AuthoritativeCombatController` accepts only exact unit/city and
  coordinate pairs present in the latest player projection before routing to
  typed authenticated session commands. Canonical legality, deterministic
  combat randomness, damage, capture, nuclear effects, and interception remain
  entirely worker-owned.
- A focused `AuthoritativeCombatPanel` renders the canonical strength and
  health preview already supplied for ordinary attacks and bombardment. Nuclear
  radius and air-sweep attacker information are shown without revealing the
  intentionally hidden nuclear-effect or interceptor result before commit.
- Combat session wiring lives with the existing focused world-session action
  adapters. `AuthoritativeWorldScreen` remains a 274-line bootstrap and
  presentation surface; the new controller and panel are 61 and 64 lines.
- Deterministic controller tests prove exact routing for every exposed combat
  family, rejection of invented unit, city, and coordinate identities before
  transport, and response-uncertain retry without local revision mutation.
  The projection-world dependency test now covers both new source files and
  continues to reject canonical `GameInfo`, legacy `WorldScreen`,
  `multiplayerFiles`, and `GameStarter` dependencies.

Verification on 2026-07-26:

- The focused combat/controller/command-bus tests and desktop compilation pass.
- `./gradlew :tests:test :server:test :desktop:compileKotlin --no-parallel`
  passes 1027 JVM/server cases: 1014 executed, 13 intentional skips, zero
  failures, and zero errors.
- `git diff --check` passes. This milestone changes no Rust, wire schema,
  OpenAPI, persistence, or PostgreSQL behavior, so the previously verified
  Rust and exact PostgreSQL 19 Beta 2 gates remain unchanged.
- No compile, test, formatting, contract, database, or cleanup error remains
  deferred.

Projection-only combat rendering is now complete for the four target families
already covered by closed server-authored projection allowlists. Non-combat
unit actions, diplomacy/trade/religion, choices, history/events, and remaining
end-turn blockers stay explicitly tracked.

## Generated OpenAPI and typed projection contract

Implemented on 2026-07-18:

- The Rust service now derives an OpenAPI 3.1 contract from its actual request,
  response, error, command, notification, game-list, and projection types using
  pinned `utoipa` 5.5.0. All 15 public paths are registered, including auth,
  discovery, projections, typed commands, WebSocket upgrade, health, and the
  OpenAPI document itself.
- `GET /api/v3/openapi.json` serves the generated contract. The deterministic
  `--write-openapi` mode writes
  `authoritative-server/openapi/api-v3.json`; a test compares the generated
  pretty JSON byte-for-byte with that checked-in artifact, so route or schema
  changes require intentional regeneration.
- Bearer-auth requirements, stable response classes, path/query parameters,
  closed request objects, and the absence of `GameInfo`/snapshot fields are
  regression-tested. Public unauthenticated operations are explicitly limited
  to health, capabilities, registration, login, and the OpenAPI document.
- Rust no longer accepts the worker's player projection as an untyped JSON
  value. `PlayerProjection` and its nested DTOs are closed Rust types used by
  worker decoding, response serialization, and OpenAPI. Rust and Kotlin both
  round-trip the versioned player-projection fixture and reject an injected
  canonical-game field.

Generation and verification:

```text
cargo run --manifest-path authoritative-server/Cargo.toml --bin unciv-authoritative-server -- --write-openapi
cargo fmt --manifest-path authoritative-server/Cargo.toml -- --check
cargo clippy --manifest-path authoritative-server/Cargo.toml --all-targets -- -D warnings
cargo test --manifest-path authoritative-server/Cargo.toml
UNCIV_V3_DATABASE_URL=postgres://postgres:postgres@localhost:55452/unciv_test \
  cargo test --manifest-path authoritative-server/Cargo.toml --lib postgres::integration_tests:: -- --ignored --test-threads=1
JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot \
  .\gradlew.bat :server:test :tests:test --no-daemon --no-build-cache
git diff --check
```

Result: Rust reported 13 passing default library tests, seven PostgreSQL tests
ignored unless configured, and seven passing HTTP/OpenAPI tests. All seven
PostgreSQL integration tests passed on an owned disposable PostgreSQL 16
instance. Kotlin reported four passing worker tests and 754 shared tests with
zero failures/errors and 13 intentionally skipped.

`utoipa` and `utoipa-gen` are pinned at 5.5.0 and declare `MIT OR Apache-2.0`,
which is compatible with this MPL-2.0 project. OpenAPI documents the WebSocket
upgrade and revision-notification frame but is not an AsyncAPI lifecycle model.
The account UI, secure platform token stores, projection-only game screen, and
production command routing remain pending.

## Authenticated game discovery

Implemented on 2026-07-18:

- `GET /api/v3/games` now enumerates only games linked to the authenticated
  account by server-side membership records. It returns game ID, head revision
  and hash, role, assigned civilization, and availability; it never returns a
  canonical snapshot or player projection.
- Discovery is bounded to 1-100 entries per request, defaults to 50, uses stable
  UUID ordering and an opaque-compatible `next_cursor`, and rejects invalid
  limits with `400 invalid_page_limit`. Malformed cursors receive the stable
  `400 invalid_page_cursor` response. Quarantined memberships remain visible as
  unavailable so clients can show maintenance state without exposing an
  internal quarantine reason.
- The shared Kotlin transport and `AuthoritativeMultiplayerSession` carry the
  same paginated contract. A fresh authenticated client can now enumerate its
  server-owned games and open each available game through the existing HTTP
  player-projection bootstrap without consulting local multiplayer saves.

Verification:

```text
cargo fmt --manifest-path authoritative-server/Cargo.toml -- --check
cargo clippy --manifest-path authoritative-server/Cargo.toml --all-targets -- -D warnings
cargo test --manifest-path authoritative-server/Cargo.toml
UNCIV_V3_DATABASE_URL=postgres://postgres:postgres@localhost:55451/unciv_test \
  cargo test --manifest-path authoritative-server/Cargo.toml --lib postgres::integration_tests:: -- --ignored --test-threads=1
JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot \
  .\gradlew.bat :tests:test --no-daemon --no-build-cache
git diff --check
```

Result: Rust reported 12 passing library tests with seven PostgreSQL tests
ignored by default and five passing HTTP/error-contract tests. All seven
PostgreSQL integration tests passed serially against an owned disposable
PostgreSQL 16 instance, including outsider exclusion, pagination, and
quarantine availability. The shared Kotlin module reported 752 tests, zero
failures/errors, and 13 intentionally skipped.

This closes server-owned membership discovery, not the client UI: account/game
list screens, secure platform token stores, and projection-only rendering remain
pending.

## Production-owned authoritative client lifecycle

Implemented on 2026-07-18:

- `AuthoritativeMultiplayerSession` now owns capability negotiation,
  registration/login/session restore and refresh, per-game command buses,
  authenticated HTTP projection bootstrap, WebSocket hint reconciliation,
  logout, and shutdown in the shared client module.
- Negotiation fails closed on another protocol/projection version or any API
  claiming that authoritative v3 permits whole-state uploads. Opening a game
  requires authentication and always starts with an HTTP projection.
- Notifications remain advisory: revision hints, forced resync, duplicates,
  and reordering feed the existing command bus, which accepts state only from
  authenticated HTTP reconciliation. A failed refresh does not permanently
  stop subsequent notification handling.
- The production `Multiplayer` owner installs exactly one such lifecycle from
  the selected server URL and a platform-supplied secure token store. Replacing
  or clearing it closes its worker and transport; it does not share credentials
  or state with legacy save-file multiplayer.

Verification:

```text
JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot \
  .\gradlew.bat :tests:test \
  --tests com.unciv.logic.multiplayer.authoritative.AuthoritativeMultiplayerSessionTests \
  --tests com.unciv.logic.multiplayer.authoritative.AuthoritativeGameCommandBusTests \
  --no-daemon --no-build-cache
JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot \
  .\gradlew.bat :tests:test --no-daemon --no-build-cache
git diff --check
```

Result: the focused shared-client suites passed, and the complete shared test
module reported 750 tests, zero failures/errors, and 13 intentionally skipped.
Coverage includes incompatible and whole-upload capability rejection,
unauthenticated game access, session restore, HTTP bootstrap, duplicate/older
notification suppression, forced resync, and logout cleanup. The active Java
25 host runtime is incompatible with this Gradle version, so verification uses
the installed JDK 21 toolchain.

This is the lifecycle prerequisite, not UI migration: account screens, secure
platform token-store implementations, projection-only game rendering, and the
first production world-screen command route remain pending.

## Canonical snapshot integrity and quarantine

Implemented on 2026-07-18:

- Migration `0006_snapshot_integrity_and_quarantine.sql` records the snapshot
  protocol version, validation status, exact compressed and uncompressed byte
  counts, and a durable game-unavailable marker. Database constraints and the
  Rust repository enforce a 16 MiB maximum; the currently supported `identity`
  codec must have identical declared and stored sizes.
- Every worker-facing canonical snapshot read now validates the codec, protocol,
  status, non-empty and bounded payload, declared sizes, payload hash, snapshot
  state hash, revision state hash, and UTF-8 encoding before invoking Kotlin.
- A corrupt canonical head is transactionally marked `corrupt`, and its game is
  quarantined with reason `corrupt_canonical_snapshot`. Metadata reads, commands,
  and attempted client-side repair commits then fail with the stable
  `503 game_unavailable` response while the canonical revision remains unchanged.
- `validate_canonical_head` exposes the same validation path for administrative
  integrity checks and restore drills instead of maintaining a weaker parallel
  decoder.

Verification:

```text
cargo fmt --manifest-path authoritative-server/Cargo.toml -- --check
cargo clippy --manifest-path authoritative-server/Cargo.toml --all-targets -- -D warnings
cargo test --manifest-path authoritative-server/Cargo.toml
UNCIV_V3_DATABASE_URL=postgres://postgres:postgres@localhost:55450/unciv_test \
  cargo test --manifest-path authoritative-server/Cargo.toml --lib postgres::integration_tests:: -- --ignored --test-threads=1
git diff --check
```

Result: 12 default library tests and four HTTP tests passed. All six PostgreSQL
integration tests passed serially against an owned disposable PostgreSQL 16
database, including deliberate canonical-payload corruption, durable quarantine,
stable unavailability, rejected repair, and unchanged-head assertions.

This milestone deliberately marks an invalid canonical head unavailable instead
of serving it. Bounded journal replay/fallback recovery, actual snapshot
compression, and retention/compaction policies remain to be implemented.

## First city command: QueueConstruction

Implemented on 2026-07-18:

- `QueueConstruction(city_id, construction_name)` is now a closed API-v3
  command across the JSON Schema, Rust request/router/repository, private worker
  protocol, Kotlin shared engine, shared client transport, and revision-aware
  command bus. Unknown fields such as a client-computed cost are rejected.
- Rust derives the account and civilization from the bearer session and game
  membership. The Kotlin engine verifies current turn, canonical city
  ownership, bounded construction identity, queue capacity, and shared Unciv
  `isBuildable` rules before `CityConstructions.addToQueue` executes.
- The allowlisted player projection exposes each owned city's queue and current
  constructable building/unit names. It still exposes no foreign city queue or
  canonical `GameInfo`, and the command bus refuses identifiers absent from its
  current projection before sending.
- This additive DTO change advances the advertised and per-response projection
  version to `2`; the shared command bus rejects any incompatible version.
- Accepted commands use the existing PostgreSQL revision lock/CAS, immutable
  snapshot, command journal, idempotency, and outbox transaction, followed by
  HTTP projection reconciliation.

Verification:

```text
cargo test --manifest-path authoritative-server/Cargo.toml
.\gradlew.bat :tests:test --tests com.unciv.logic.AuthoritativeGameExecutionContextTests --tests com.unciv.logic.multiplayer.authoritative.AuthoritativeGameCommandBusTests :server:test --no-daemon --no-build-cache
```

Result: Rust reported 14 passing default tests and five PostgreSQL tests ignored
unless explicitly configured. The focused Kotlin engine/client suites and all
server worker tests passed. Coverage includes canonical buildability and city
ownership, cross-civilization rejection, closed wire shape, projected queue
reconciliation, and content-addressed worker validation.

This row remains partial: remove/reorder/perpetual/tile-specific construction,
purchases, production progress/cost projections, and production UI migration
are not implemented. Legacy online city screens still mutate local state and
upload a whole save.

## Command-coverage inventory

Added on 2026-07-18:

- `docs/multiplayer-command-coverage.md` now inventories the online mutation
  surface across unit combat/actions/orders, cities, research, policies,
  religion, diplomacy/trades, city states, espionage, votes, automation,
  captured-city choices, resignation, spectators, and administration.
- The matrix distinguishes an implemented server handler from completed client
  migration. `MoveUnit`, `EndTurn`, creation, and join remain partial because
  production online UI paths still mutate a local `GameInfo` and use the
  legacy whole-save upload flow.
- Each row records the required closed command shape, current domain/UI call
  sites, authorization/validation rules, projection needs, and honest status.
  Projection gaps and the final direct-mutation search gate are explicit.

Next executable command milestone: route one production world-screen action
through the authoritative bus and projection reconciliation, while preserving
the legacy path only for explicitly negotiated non-v3 games.

## Worker handshake and enforced engine/ruleset pins

Implemented on 2026-07-18:

- The private protocol now has a versioned `handshake` operation that requires
  neither actor identity nor game state. It reports the worker's exact Unciv
  engine build and installed content-addressed rulesets.
- The Rust service completes that handshake before binding its public listener
  and fails startup when the worker is absent, malformed, or speaks another
  protocol version.
- Every state creation, projection, join, move, and end-turn request is rejected
  inside the Kotlin worker unless the pinned engine build equals the running
  build and every named ruleset is installed with the exact expected SHA-256.
  A display-name match can no longer silently substitute different bytes.
- Ruleset identity hashes all gameplay JSON files recursively in sorted
  relative-path order. Each path and payload is length-framed before hashing,
  making the digest independent of filesystem enumeration order and
  unambiguous across file boundaries. Media is outside gameplay identity.
- Server game creation additionally requires its base ruleset and complete mod
  set to equal the pinned manifest before `GameStarter` runs.

Verification:

```text
.\gradlew.bat :server:test --no-daemon --no-build-cache
cargo clippy --manifest-path authoritative-server/Cargo.toml --all-targets -- -D warnings
cargo test --manifest-path authoritative-server/Cargo.toml
UNCIV_V3_DATABASE_URL=postgres://unciv:unciv-test-only@127.0.0.1:55446/unciv_v3_test \
  cargo test --manifest-path authoritative-server/Cargo.toml -- --ignored --test-threads=1
git diff --check
```

Result: `BUILD SUCCESSFUL`; four Kotlin worker protocol/catalog tests passed,
including real bundled-ruleset enumeration, order-independent hashing, engine
version rejection, and content-hash rejection before malformed snapshot
parsing. Rust reported 13 passing default tests, including an actorless
length-prefixed handshake contract test. All five ignored-by-default
PostgreSQL integration tests passed against an owned disposable PostgreSQL 16
container. A separate live background-process probe was blocked
by the local command policy before launch and is not counted as verification.

Remaining versioning work includes durable bundle installation/registration,
modded fixtures, old-worker routing or a compatibility-tested snapshot
upgrader, and deployment packaging of pinned worker builds.

## Milestone 0 — baseline and design

Completed on 2026-07-18:

- Verified that the existing protocol is client-authored full-save storage.
- Recorded the current call/data flow in
  `docs/architecture/current-multiplayer-flow.md`.
- Selected the Rust control-plane/Kotlin headless-worker architecture in ADR
  0001; no alternate rules engine will be introduced.
- Added repository guardrails in `AGENTS.md`.
- Added the versioned API-v3 command envelope and closed initial `JoinGame` /
  `EndTurn` / `MoveUnit` command union. Later milestones below now consume the
  first two variants through closed HTTP endpoints.

Verification attempted:

```text
.\\gradlew.bat :tests:test --no-daemon
```

The initial invocation under Java `25.0.3` did not reach compilation because
that runtime is unsupported by the project toolchain. Temurin `21.0.11` was
then installed and used with non-incremental in-process Kotlin compilation:

```text
GRADLE_OPTS='-Dkotlin.incremental=false -Dkotlin.compiler.execution.strategy=in-process'
.\\gradlew.bat :tests:test --no-daemon --no-build-cache
```

Result: `BUILD SUCCESSFUL` (29s).

Next executable milestone: add the Kotlin headless engine boundary and a
deterministic fixture that loads a game, rebuilds transients, executes a
server-owned operation, and asserts a canonical state hash.

## Milestone 1 — headless engine boundary

In progress:

- `GameExecutionContext` now makes actor identity, server clock, ruleset
  manifest identity, feature flags, local-settings persistence, and UI effects
  explicit execution dependencies.
- `GameStarter` can run without modifying local settings.
- `GameInfo.nextTurn()` can consume server time and suppress UI-only music
  effects. The supplied context is not serialized into a save.
- `HeadlessGameEngine` is the Kotlin worker-side boundary for server-created
  games, shared `nextTurn()` processing, snapshot transient rebuild, and
  canonical SHA-256 state hashing. It has no network listener or persistence
  authority.
- Added headless tests for server creation and server-controlled turn time.

Verification:

```text
.\\gradlew.bat :tests:test --tests com.unciv.logic.AuthoritativeGameExecutionContextTests --no-daemon --no-build-cache
```

Result: `BUILD SUCCESSFUL` (9s) on Temurin `21.0.11`. The full `:tests:test`
suite also passed under the same runtime (29s).

## Milestone 2 - Rust control plane and persistence foundation

In progress:

- Added the independent `authoritative-server` Rust crate. Its control-plane
  contract serializes each game commit, rejects stale heads, validates worker
  snapshot hashes, and returns the original accepted result for duplicate
  command IDs.
- Added one shared API-v3 JSON Schema at
  `protocol/command-envelope-v3.schema.json`; it forbids whole-save uploads and
  generic patches by admitting only the closed command union.
- Added PostgreSQL migration `0001_authoritative_multiplayer.sql` for accounts,
  ruleset manifests, games/members, immutable snapshots, command journal,
  revisions, and transactional-outbox rows.
- The Rust binary now provides a loopback-only-by-default `/healthz` endpoint;
  API-v3 game routes stay absent until authentication and PostgreSQL-backed
  authorization can enforce their invariants.
- `PostgresGameRepository` now locks the canonical game row and atomically
  writes the accepted command, immutable snapshot, revision, head update, and
  outbox notification. It derives the actor from the API caller and accepts
  only owner/player/admin memberships at this layer.

Verification:

```text
cargo test --manifest-path authoritative-server/Cargo.toml
```

Result: `3 passed; 0 failed`. The in-memory repository remains a fast contract
test double; `PostgresGameRepository` is now the durable implementation. The
private Kotlin worker protocol is still not connected.

PostgreSQL integration verification used an owned disposable container, not an
unrelated local service:

```text
docker run --detach --name unciv-v3-postgres-test --publish 127.0.0.1:55432:5432 \
  --env POSTGRES_USER=unciv --env POSTGRES_PASSWORD=unciv-test-only \
  --env POSTGRES_DB=unciv_v3_test postgres:18-alpine
UNCIV_V3_DATABASE_URL=postgres://unciv:unciv-test-only@127.0.0.1:55432/unciv_v3_test \
  cargo test --manifest-path authoritative-server/Cargo.toml -- --ignored
```

Result: `1 passed; 0 failed`; the test container was removed afterward.

## Private worker transport

In progress:

- The `server` Gradle module now depends on `core` and LibGDX headless.
- `EngineWorkerMain` initializes a headless Unciv runtime and rulesets, then
  starts a dedicated loopback-only worker. It is separate from `UncivServer`.
- `LoopbackEngineWorkerServer` accepts one bounded length-prefixed JSON frame,
  decodes a versioned request, and routes `EndTurn` through
  `HeadlessGameEngine`; no network request can provide a `GameInfo` patch.

Verification:

```text
.\\gradlew.bat :server:compileKotlin --no-daemon --no-build-cache
```

Result: `BUILD SUCCESSFUL` (17.5s) on Temurin `21.0.11`. Rust has not yet
connected this private worker to PostgreSQL commits, so this is not an end-to-
end authoritative game flow yet.

The Rust control plane now has a matching bounded, timed worker client. It sends
only an actor ID, pinned ruleset manifest, and `EndTurn` intent, then translates
the worker response into a `CommitProposal`. PostgreSQL remains responsible for
hash validation and the only canonical commit. `cargo test --manifest-path
authoritative-server/Cargo.toml` remains successful (3 passed; 1 PostgreSQL test
ignored unless explicitly configured).

`PostgresGameRepository.execute_end_turn()` now loads the canonical head
snapshot and stored worker manifest, delegates only `EndTurn` to the private
Kotlin worker, then submits its result through the revision-CAS commit method.
It never accepts a client replacement snapshot; a concurrent change becomes a
stale conflict.

Authentication foundation: migration `0002_revocable_sessions.sql` adds hashed,
rotating, revocable server-side session records. Registration/login handlers and
Argon2id verification are still pending.

`PasswordService` now produces and verifies Argon2id PHC password hashes and
rejects passwords shorter than 12 characters. Password storage remains limited
to the PHC value; registration/login endpoints, rate limits, and session-token
issuance still need to be wired to the PostgreSQL account/session tables.

Session issuance now has a 256-bit opaque bearer credential primitive. It
returns the raw token only to the caller and the SHA-256 digest intended for
`sessions.token_digest`; the persistence, rotation, expiry, and revocation API
flows remain pending.

The initial account namespace is now explicit and collation-independent:
trimmed ASCII usernames normalize to lowercase and allow only letters, digits,
`_`, and `-` (3-32 characters).

## Authentication lifecycle

Implemented:

- `PostgresGameRepository` now transactionally persists normalized accounts
  with Argon2id PHC hashes and maps the unique username constraint to a stable
  conflict.
- Login verifies stored hashes without revealing whether a username or password
  was wrong. Sessions contain only a SHA-256 token digest, expire after 30
  days, track use server-side, can be revoked through logout, and rotate
  atomically through `POST /api/v3/auth/refresh` with an auditable parent link.
- The loopback-default Rust API now exposes bounded JSON endpoints for
  `POST /api/v3/auth/register`, `login`, and `logout`. Login/logout failures
  use the generic `invalid_credentials` response; no endpoint accepts a
  caller-supplied account ID.

Verification:

```text
cargo test --manifest-path authoritative-server/Cargo.toml
UNCIV_V3_DATABASE_URL=postgres://unciv:unciv-test-only@127.0.0.1:55433/unciv_v3_test \
  cargo test --manifest-path authoritative-server/Cargo.toml -- --ignored
```

Result: seven unit tests passed and two PostgreSQL integration tests passed
against an owned disposable PostgreSQL 18 container. A separate HTTP smoke run
registered, logged in, logged out, and confirmed the revoked token received
HTTP 401. Rate limits/backoff, password changes, TLS proxy deployment, and
client secure storage remain pending.

## Server-created game vertical slice

Implemented:

- Worker protocol v1 now has a `create_game` operation. Rust sends only a
  pinned manifest and an empty/default setup intent; Kotlin deserializes that
  setup and calls the existing `GameStarter` to produce canonical revision
  zero. No create route accepts a `GameInfo` or save payload.
- `POST /api/v3/games` authenticates the owner from its bearer session and
  asks the private loopback Kotlin worker to create state. Rust validates the
  returned hash, writes immutable revision zero, and adds the owner membership.
- `GET /api/v3/games/{game_id}` returns a metadata-only member projection
  (revision, canonical hash, role). It deliberately does not return the
  canonical snapshot; a complete player projection remains future work.
- The worker listener now discards malformed/abandoned loopback connections
  instead of exiting on `EOFException`; a TCP readiness probe can no longer
  kill the worker.

Verification:

```text
.\gradlew.bat :server:compileKotlin --no-daemon --no-build-cache
cargo test --manifest-path authoritative-server/Cargo.toml
```

Both compiled/passed. An owned PostgreSQL 18 plus Kotlin worker/Rust API smoke
run registered an owner, inserted a test pinned manifest, created a game over
HTTP, persisted revision `0`, fetched owner metadata, and verified a second
authenticated non-member received HTTP `403`. The test container and worker
processes were removed afterward. Joining/assignment, player projections, and
typed non-turn gameplay commands are still pending.

## Authenticated EndTurn command

Implemented:

- `POST /api/v3/games/{game_id}/commands/end-turn` accepts only a typed
  idempotency key, expected revision, and diagnostic observed hash. It derives
  the actor from the bearer session and delegates turn processing to Kotlin;
  it cannot accept a save, object patch, or caller-selected actor.
- The control plane now checks a durable command result before invoking the
  worker, so a lost-response retry returns the original accepted revision
  rather than attempting turn processing again after the head advanced.
- Worker responses now SHA-256 hash the exact serialized snapshot bytes sent
  over the private protocol. This fixed a serialization-twice mismatch that
  correctly caused Rust to reject a candidate commit.
- A regression test verifies that a canonical serialize/reload cycle can still
  run the server turn engine.

Verification:

```text
.\gradlew.bat :tests:test --tests com.unciv.logic.AuthoritativeGameExecutionContextTests --no-daemon --no-build-cache
cargo test --manifest-path authoritative-server/Cargo.toml
```

Both passed. The owned PostgreSQL/Kotlin/Rust HTTP smoke run proved create,
EndTurn commit to revision `1`, duplicate retry returning the identical result,
and a new command with expected revision `0` receiving HTTP `409`. Civilization
turn ownership is now enforced as described below. Player projections and
typed non-turn gameplay commands remain pending.

## Canonical owner assignment and turn authorization

Implemented:

- `GameStarter` creation assigns the authenticated owner ID inside canonical
  `GameInfo`; the worker returns the resulting civilization ID and PostgreSQL
  persists it on the owner membership in revision-zero creation.
- Migration `0003_unique_civilization_assignments.sql` prevents two accounts
  from controlling the same civilization in one game.
- `EndTurn` derives the civilization from authenticated membership. The shared
  `HeadlessGameEngine` verifies that canonical `playerId` matches the account
  and that the civilization is the canonical current player before it calls
  `nextTurn()`.
- Game metadata exposes the caller's assigned civilization but never the
  canonical snapshot. Unknown command fields are rejected, so a modified
  client cannot smuggle an actor or civilization override into `EndTurn`.

Verification:

```text
.\gradlew.bat :tests:test --tests com.unciv.logic.AuthoritativeGameExecutionContextTests --no-daemon --no-build-cache
.\gradlew.bat :server:compileKotlin --no-daemon --no-build-cache
cargo test --manifest-path authoritative-server/Cargo.toml
UNCIV_V3_DATABASE_URL=postgres://unciv:unciv-test-only@127.0.0.1:55441/unciv_v3_test \
  cargo test --manifest-path authoritative-server/Cargo.toml -- --ignored --test-threads=1
```

All passed, including all three PostgreSQL integration tests. A fresh
PostgreSQL/Kotlin/Rust HTTP run created an owner-assigned
game (`The Netherlands` in that seeded run), committed its valid EndTurn,
rejected a non-member with HTTP `403`, and kept revision `1`. A malicious body
containing caller-selected `actor_id` and `civilization_id` was rejected with
HTTP `422`; the canonical revision remained `1`. Joining a second player still
required the worker-backed assignment command implemented in the next section.

Duplicate replay is also account-bound: the durable result is returned only to
the account that originally committed the command. The PostgreSQL test submits
the same `(game_id, command_id)` as another account and verifies
`Unauthorized`, without worker execution or state change.

## Authoritative player join and assignment

Implemented:

- `POST /api/v3/games/{game_id}/join` accepts only a command ID, expected
  revision, and diagnostic observed hash. The bearer session supplies the
  account; no request field can select an account or civilization.
- `JoinGame` delegates canonical assignment to the Kotlin worker. The shared
  engine deterministically selects the first unclaimed major AI civilization,
  changes it to a human player, and records the authenticated actor ID in the
  canonical snapshot.
- Joining is restricted to revision zero. PostgreSQL locks the game head and
  commits the worker snapshot, revision, command journal, outbox record, and
  new player membership/civilization in one transaction. Competing joins from
  the same head cannot both commit.
- Duplicate command replay remains bound to the original account. Unknown join
  fields are rejected by the closed request payload.

Verification:

```text
.\gradlew.bat :tests:test --tests com.unciv.logic.AuthoritativeGameExecutionContextTests --no-daemon --no-build-cache
.\gradlew.bat :server:compileKotlin --no-daemon --no-build-cache
cargo test --manifest-path authoritative-server/Cargo.toml
```

All passed: the Kotlin suite includes assignment and repeat-assignment
regressions, and Rust reported seven passing unit tests with three explicitly
configured PostgreSQL tests ignored. A fresh PostgreSQL 16/Kotlin worker/Rust
API smoke run created an owner assigned to Mongolia, assigned the joining
account to Sweden, atomically committed revision `1`, returned revision `1`
again for an idempotent retry, and rejected a third account's revision-zero
join with HTTP `409`. All disposable processes and the database were removed.

## First non-turn gameplay command: MoveUnit

Implemented:

- The placeholder `MoveUnit` shape is now a closed typed command using Unciv's
  durable integer unit ID and explicit canonical hex coordinates. The public
  request rejects unknown fields and cannot carry an actor or civilization.
- `POST /api/v3/games/{game_id}/commands/move-unit` derives the account and
  civilization from the bearer session and membership, loads only the current
  canonical snapshot, and delegates execution to the private Kotlin worker.
- `HeadlessGameEngine.moveUnit` verifies canonical actor assignment, current
  turn, unit ownership, map bounds, current-turn reachability, and destination
  enterability before calling the shared `UnitMovement.moveToTile` domain
  logic. Illegal gameplay requests return stable HTTP `422 invalid_command`;
  worker transport/protocol failures remain distinct gateway failures.
- The resulting snapshot uses the same PostgreSQL lock/CAS, immutable revision,
  idempotency, journal, and outbox transaction as `EndTurn`.

Verification:

```text
.\gradlew.bat :tests:test --tests com.unciv.logic.AuthoritativeGameExecutionContextTests --no-daemon --no-build-cache
.\gradlew.bat :server:compileKotlin --no-daemon --no-build-cache
cargo test --manifest-path authoritative-server/Cargo.toml
```

The Kotlin suite passed with movement legality, cross-civilization denial, and
fresh-load deterministic-hash coverage. A PostgreSQL 16/Kotlin worker/Rust API
smoke moved canonical unit `2` from `(11,1)` to `(10,1)`, committed revision
`1`, replayed the same command ID as revision `1`, rejected an authenticated
non-member with HTTP `403`, and retained head revision `1`. The disposable
services and database were removed afterward.

## Initial player-scoped projection and HTTP reconciliation

Implemented:

- `GET /api/v3/games/{game_id}/projection` authenticates the account, resolves
  its server membership and civilization, reads one consistent canonical head,
  and asks the private worker to build an allow-listed player DTO.
- The response carries committed revision, canonical state hash, a hash of the
  stable JSON value emitted by the Rust public boundary, and the projection. It never returns or
  redacts `GameInfo`; canonical `tileMap`, civilization objects, player IDs,
  rulesets, private queues, diplomacy state, notifications, and RNG state are
  structurally absent.
- The initial DTO includes own gold/cities/units, explored tile coordinates and
  current visibility, known civilization IDs, and foreign units only when the
  canonical visibility/invisibility rules allow them. Stable own unit IDs and
  coordinates are sufficient to construct `MoveUnit` commands after a fresh
  fetch.
- A serialized sentinel test confirms another civilization's account ID,
  private state key, and hidden unit instance name do not occur in the player's
  projection.

Verification:

```text
.\gradlew.bat :tests:test --tests com.unciv.logic.AuthoritativeGameExecutionContextTests --no-daemon --no-build-cache
.\gradlew.bat :server:compileKotlin --no-daemon --no-build-cache
cargo test --manifest-path authoritative-server/Cargo.toml
```

All passed. A fresh PostgreSQL 16/Kotlin worker/Rust API reconciliation smoke
fetched projection revision `0`, selected permitted unit `1` at `(-13,-6)`,
submitted a legal move using only projection data, and fetched revision `1`
with the unit at `(-12,-6)` and a changed projection hash. Exact canonical
field names were absent and a non-member received HTTP `403`. Disposable
services and the database were removed.

This is a partial projection schema, not a claim that the v3 client can render
the whole game or that anti-cheat work is complete. Terrain presentation,
legally known resources/improvements, diplomacy, notifications/events,
spectator policy, compact deltas, and broader sentinel fixtures remain pending.

## Shared API-v3 client and revision-aware command bus

Implemented:

- The Rust service exposes `GET /api/v3/capabilities`, advertising protocol and
  projection versions, its closed command set, the absence of whole-state
  upload, and current WebSocket support. Stale conflicts now include the
  canonical `current_revision` alongside stable `stale_revision` semantics.
- The shared Kotlin core has typed v3 contracts and a Ktor transport for
  capability negotiation, registration, login, session rotation/logout,
  server game creation, joining, projection fetches, `MoveUnit`, and `EndTurn`.
  Bearer tokens are abstracted behind `ApiV3SessionTokenStore`; the core never
  persists a password. The included in-memory store is for tests/development,
  not a substitute for Android/Desktop OS credential-store implementations.
- `ApiVersion.detect` prefers authoritative v3 capabilities when a migration
  server also exposes legacy endpoints, and rejects a purported v3 capability
  document that permits whole-state upload.
- `AuthoritativeGameCommandBus` serializes local command submission, tracks
  revision/hash from the latest permitted projection, refreshes without merge
  on a stale conflict, retains the exact idempotency key across an ambiguous
  lost response, and reconciles the accepted revision/hash through HTTP. It
  does not optimistically mutate its cached server projection, so rejection
  rollback is lossless.

Verification:

```text
cargo test --manifest-path authoritative-server/Cargo.toml
.\gradlew.bat :tests:test --tests com.unciv.logic.multiplayer.authoritative.AuthoritativeGameCommandBusTests --no-daemon --no-build-cache
```

The Rust suite covers capability safety and stale metadata. Kotlin tests cover
fresh-device reconstruction from a projection, stale refresh without replay,
lost-response retry with the identical command ID, rejected-command cache
preservation, and the closed snake-case movement request shape.

This is a shared client foundation, not UI migration completion. Platform
secure token stores, login/account screens, game-list discovery, world-screen
command routing, lifecycle reconnect wiring, WebSocket notifications, full
projection rendering, and removal of v3 access to legacy upload code remain
pending. Single-player and hotseat paths have not been changed.

## Durable revision notifications and HTTP convergence

Implemented:

- Migration `0004_outbox_delivery_leases.sql` adds bounded dispatch leases,
  claim tokens, attempt counts, retry availability, and truncated last-error
  diagnostics. PostgreSQL claims ordered batches with `FOR UPDATE SKIP LOCKED`;
  an expired lease can be reclaimed, and only the current claim token can
  acknowledge delivery.
- The Rust process runs a bounded outbox dispatcher and an account-scoped
  notification hub. It resolves recipients from current server memberships,
  sends only game ID, committed revision, and canonical state hash, then
  acknowledges the row. A crash after socket send but before acknowledgement
  may duplicate a hint; a crash before acknowledgement cannot permanently
  strand the row.
- `GET /api/v3/notifications` upgrades only an authenticated bearer session to
  WebSocket. It has no client-controlled game subscription or actor identity;
  the server sends events only for games where that account is a member. A
  lagged in-process receiver gets `resync_required` rather than fabricated
  revision history.
- The shared Kotlin client exposes a reconnecting notification flow. The
  command bus ignores duplicate and older hints, refreshes future or
  hash-mismatched revisions over authenticated HTTP, and never treats the
  WebSocket as canonical state.

Verification:

```text
cargo test --manifest-path authoritative-server/Cargo.toml
UNCIV_V3_DATABASE_URL=postgres://postgres:uncivtest@127.0.0.1:55450/unciv \
  cargo test --manifest-path authoritative-server/Cargo.toml -- --ignored --test-threads=1
.\gradlew.bat :tests:test --tests com.unciv.logic.multiplayer.authoritative.AuthoritativeGameCommandBusTests --no-daemon --no-build-cache
```

Rust unit tests passed, and all four PostgreSQL integration tests passed against
an owned disposable PostgreSQL 16 container. The lease test proved exclusive
claiming, timeout reclaim with a new token, rejection of the obsolete token,
and final acknowledgement. Kotlin tests prove duplicate/lost/reordered hints
converge without rolling a newer projection backward.

A fresh PostgreSQL/Kotlin worker/Rust API smoke authenticated a WebSocket,
committed a legal `MoveUnit`, received `revision_committed` revision `1`, then
fetched HTTP projection revision `1` with the same canonical hash and verified
the outbox row was acknowledged. All disposable services and data were removed.

The current hub is appropriate for the documented single-Rust-process VPS
target. A multi-instance deployment will require a shared fan-out transport or
connection affinity; database CAS and HTTP reconciliation remain correct even
without it. Metrics, connection limits, heartbeat policy, and load testing are
still pending.

## Durable authentication throttling and security audit

Implemented:

- Migration `0005_auth_rate_limits_and_audit.sql` adds PostgreSQL-backed,
  atomic fixed-window request buckets and append-only security audit events.
  Buckets store only SHA-256 composite keys, never raw usernames, passwords,
  bearer tokens, or request bodies.
- Registration is limited to five attempts per source `/24` or `/64` per hour.
  Login has a source limit of 30 attempts per minute and a source-plus-identity
  limit of five attempts per 15 minutes. Exceeded limits return stable HTTP
  `429 {"code":"rate_limited"}` with `Retry-After`; successful authentication
  clears the identity backoff while leaving the broader source-abuse bucket.
- The HTTP boundary derives the source from the socket rather than a caller
  header. IPv4 audit addresses are reduced to `/24` and IPv6 to `/64`.
  Registration/login success, rejection, and rate limiting are audited with a
  one-way identity hash and optional account ID. Audit write failures never log
  credentials or tokens.
- Wrong username, wrong password, and disabled-account login continue to expose
  the same generic public `invalid_credentials` response.

Verification:

```text
cargo test --manifest-path authoritative-server/Cargo.toml
UNCIV_V3_DATABASE_URL=postgres://postgres:uncivtest@127.0.0.1:55452/unciv \
  cargo test --manifest-path authoritative-server/Cargo.toml -- --ignored --test-threads=1
```

All five PostgreSQL integration tests passed. The rate-limit test proved atomic
exhaustion, durable rejection, explicit reset, 64-character bucket/identity
hashes, and stored `192.0.2.0/24` rather than a host address.

A live Rust/PostgreSQL HTTP run returned five generic `401` login failures and
then `429 rate_limited` with `Retry-After: 900`; a successful login on a second
identity reset its identity bucket, so the next failure was again generic
`401`. The sixth registration from the same source returned `429`. Eighteen
audit rows had valid identity-hash lengths and only `127.0.0.0/24` as their
source. A separate minimal run confirmed the exact `rate_limited` JSON body.
Disposable databases and processes were removed.

Production reverse-proxy deployment must preserve the real peer address only
through an explicitly trusted proxy configuration; the service intentionally
does not trust arbitrary forwarded-address headers. Distributed rate limiting
is correct because PostgreSQL is the source of truth, though retention cleanup
and operator-configurable thresholds remain pending.

## Account lifecycle and PostgreSQL forward compatibility

Implemented:

- Migration `0007_account_lifecycle.sql` records password rotation time and
  complete disable/delete state while preserving stable account UUIDs and
  historical game memberships. Account deletion pseudonymizes the unique
  username and invalidates the password rather than breaking revision history.
- `POST /api/v3/account/password` verifies the current password, rejects reuse,
  revokes every existing session, and atomically returns one replacement
  session. `POST /api/v3/account/disable` and `DELETE /api/v3/account` verify the
  password and revoke every session in the same transaction.
- All three bearer-authenticated endpoints have durable per-account/source rate
  limits and append success/rejection audit events without recording passwords
  or tokens. The generated OpenAPI contract contains closed request schemas and
  stable error responses.
- The shared Kotlin transport and session expose all three operations. Password
  rotation replaces the locally held token. Disable/delete clear local
  authentication and authoritative game state even when the response is lost,
  because retaining a possibly revoked credential would be unsafe.

Verification on 2026-07-18:

```text
cargo fmt --manifest-path authoritative-server/Cargo.toml --check
cargo clippy --manifest-path authoritative-server/Cargo.toml --all-targets -- -D warnings
cargo test --manifest-path authoritative-server/Cargo.toml

UNCIV_V3_DATABASE_URL=postgres://postgres:unciv_test@127.0.0.1:55454/unciv \
  cargo test --manifest-path authoritative-server/Cargo.toml -- --ignored --test-threads=1
UNCIV_V3_DATABASE_URL=postgres://postgres:unciv_test@127.0.0.1:55455/unciv \
  cargo test --manifest-path authoritative-server/Cargo.toml -- --ignored --test-threads=1

.\gradlew.bat :server:test :tests:test --no-daemon
```

Rust formatting and warnings-as-errors linting passed. Default Rust tests passed
with 13 library tests and seven HTTP/OpenAPI tests; eight database tests were
explicitly ignored there. The complete eight-test database suite then passed
independently against both `postgres:18.4-alpine` and
`postgres:19beta2-alpine`. It covers account lifecycle, password reuse and
wrong-password rejection, session revocation/replacement, preserved membership
references, revision CAS/idempotency, authorization, snapshot quarantine,
outbox leases, discovery, and durable rate limiting. Gradle passed four server
tests and 755 shared tests with zero failures or errors (13 intentional skips).

PostgreSQL 19 Beta 2 is now the sole production and test baseline by explicit
project decision; no PostgreSQL 18 compatibility lane will be maintained. The
accepted prerelease risk and mandatory upgrade/restore gate are documented in
`docs/operations/authoritative-postgresql-19.md`. The older PostgreSQL 16/18
entries above remain historical evidence, not current deployment targets.

Platform credential-store implementations and account-management UI remain
pending. Account deletion is deliberately soft/pseudonymous; retention-policy
and operator erasure procedures still need to be defined before production.

## First production world-screen command route

Implemented:

- `WorldScreen.nextTurn()` checks whether its exact game was explicitly opened
  through the installed API-v3 session. If so, it submits the typed `EndTurn`
  command before the legacy clone, local `GameInfo.nextTurn()`, or whole-save
  upload path can execute.
- Merely installing an authenticated session does not capture other online
  games. Single-player, hotseat, and explicitly legacy online games retain
  their existing paths. This avoids silently changing old game semantics.
- Accepted commands leave the disposable local `GameInfo` stale and keep player
  input disabled; canonical revision/hash and turn ownership come from the
  refreshed server projection. Stale/rejected responses are visible, and an
  ambiguous retry reuses the original command ID through the command bus.
- Session-level tests prove an unopened game does not route, an opened game
  does route, and a retry after a lost response sends the identical idempotency
  key rather than creating a second command.

Verification:

```text
.\gradlew.bat :tests:test \
  --tests com.unciv.logic.multiplayer.authoritative.AuthoritativeMultiplayerSessionTests \
  --tests com.unciv.logic.multiplayer.authoritative.AuthoritativeGameCommandBusTests \
  --no-daemon
.\gradlew.bat :server:test :tests:test --no-daemon
git diff --check
```

The focused authoritative lifecycle/command-bus run passed 16 tests with no
failures or skips. The complete JDK 21 Gradle run passed both server and shared
test modules: four server tests and 757 shared tests, with zero failures or
errors and 13 intentional skips.

This is the first real production mutation boundary, not completion of the v3
game UI. A projection-only world renderer and v3 game-list/open flow are still
required before ordinary users can enter this path without integration wiring.
Most mandatory pre-end-turn choices are not commands yet, so API v3 must remain
opt-in and must not be advertised as generally playable.

## Server-owned end-turn readiness

Implemented:

- `HeadlessGameEngine.endTurn` derives unresolved actions from the canonical
  actor civilization before calling shared `GameInfo.nextTurn`. It rejects
  empty non-puppet production, required technology/policy/spy decisions,
  pantheon/religion belief phases, and an outstanding diplomatic-victory vote.
- Idle-unit and automation reminders remain client conveniences and do not
  block a legal server turn. The guard reuses the same civilization/domain
  predicates as the UI without importing `WorldScreen` or another UI type into
  the headless engine.
- A regression fixture creates a canonical city with no production, verifies
  ordered `pick_construction` and `pick_technology` projection blockers, proves
  `EndTurn` is rejected, and compares the
  complete canonical hash and current actor before/after to prove no mutation.

Verification:

```text
.\gradlew.bat :tests:test \
  --tests com.unciv.logic.AuthoritativeGameExecutionContextTests \
  :server:test --no-daemon --no-build-cache
```

The focused headless engine and private worker suites passed. The choice list
is not yet part of the player projection, and most corresponding resolution
commands remain missing; the server therefore fails closed rather than letting
a modified client skip those decisions.

The complete regression run then passed four server tests and 758 shared tests,
with zero failures/errors and 13 intentional skips. The command wrapper timed
out while the detached Gradle worker was still running; the worker completed
normally and wrote the full current XML result set above.

## Projection v3 pending-turn contract

Implemented:

- The player projection now includes ordered `pendingTurnActions` generated by
  the same `AuthoritativeTurnReadiness` helper used immediately before server
  `EndTurn`. There is no second client or Rust implementation that can drift
  from the Kotlin engine decision.
- Pending actions are a closed nine-value enum in Kotlin, Rust, and generated
  OpenAPI. Both decoders reject an invented action such as
  `replace_canonical_state`; clients cannot smuggle an extensible action name
  through this contract.
- Projection version advanced from 2 to 3 across capabilities, HTTP responses,
  the Kotlin client, the closed Rust DTO, OpenAPI, and the shared fixture. Old
  clients fail negotiation instead of silently ignoring a new readiness model.
- The v3 fixture proves semantic Rust/Kotlin round-trip and rejects injected
  canonical fields. The engine fixture proves multiple blockers are ordered as
  `pick_construction`, then `pick_technology`, while rejected turn processing
  leaves the canonical hash and current actor unchanged.

Verification:

```text
cargo run --manifest-path authoritative-server/Cargo.toml -- --write-openapi
cargo fmt --manifest-path authoritative-server/Cargo.toml --check
cargo clippy --manifest-path authoritative-server/Cargo.toml --all-targets -- -D warnings
cargo test --manifest-path authoritative-server/Cargo.toml
.\gradlew.bat :server:test :tests:test --no-daemon
git diff --check
```

Rust passed 13 default library tests and seven HTTP/OpenAPI tests; eight
database tests remained explicitly ignored in the non-database run. Gradle
passed four server tests and 759 shared tests with zero failures/errors and 13
intentional skips. A first focused run exposed that the fixture correctly had
two blockers rather than the one initially asserted; the corrected regression
now requires both and the complete suite is green.

The projection currently gives blocker identities, not the private legal
options or server-issued tokens needed to resolve them. Technology, policy,
religion, espionage, and vote commands remain missing, so v3 still fails closed
and is not generally playable.

## Projection v4 and server-derived research paths

Implemented:

- The player projection now carries current research, the ordered research
  queue, server-derived legal destination technologies, and the separately
  identified legal free-technology choices. Projection compatibility advanced
  from 3 to 4 across Kotlin, Rust, capabilities, OpenAPI, and the shared closed
  fixture.
- Public API v3 accepts only a bounded destination technology in the closed
  `set_research_path` command. The authenticated account and assigned
  civilization come from server membership, and the Kotlin worker reuses
  `TechManager.getRequiredTechsToDestination` to derive and order every
  prerequisite from canonical state.
- A normal research command cannot consume a free-technology grant; that case
  fails closed until a dedicated typed command is implemented. Unknown,
  already-completed, unreachable, off-turn, or actor-mismatched selections do
  not produce a canonical result.
- The Rust transaction path preserves the existing per-game lock, expected
  revision, idempotency, immutable snapshot, canonical hash, command journal,
  and outbox commit boundary. The client command bus permits only targets from
  its current projection and reconciles through HTTP after the response.

Verification:

```text
cargo fmt --all
cargo clippy --all-targets -- -D warnings
cargo test --all-targets
cargo run -- --write-openapi
.\gradlew.bat :tests:test --tests <focused authoritative research and contract tests> :server:test --no-daemon --no-build-cache --rerun-tasks
git diff --check
```

Rust passed 14 library tests and seven HTTP/OpenAPI tests; eight PostgreSQL
integration tests remain separately gated by `UNCIV_V3_DATABASE_URL`. The
forced JDK 21 focused run rebuilt all affected Kotlin modules and passed the
headless-engine, projection-contract, command-bus, session, and server tests.

This is a typed end-to-end research slice, not complete research migration.
Free-technology selection, queue removal and reordering, research progress/cost
projection, and public research events remain. API v3 therefore remains opt-in
and not generally playable.

## First production research-picker route

Implemented:

- `TechPickerScreen` recognizes only games explicitly opened by the API-v3
  session. A normal research choice for such a game submits
  `set_research_path` asynchronously and never assigns the local
  `TechManager.techsToResearch` list.
- Accepted results mark the disposable local game stale and return to the world
  screen. Conflicts, rule rejection, lost responses, and transport failures are
  distinct UI outcomes; an ambiguous retry retains the original command ID.
- Right-click queue append is deliberately changed to destination selection for
  an opened v3 game because queue append does not yet have a typed command.
  Single-player, hotseat, and legacy online games keep the original local queue
  and free-technology behavior.
- Session lifecycle tracks explicitly opened game IDs independently of merely
  having an authenticated session and clears that routing state on close,
  logout, failed restore, disable, or delete.

Verification:

```text
.\gradlew.bat :core:compileKotlin :tests:test \
  --tests com.unciv.logic.multiplayer.authoritative.AuthoritativeMultiplayerSessionTests \
  --no-daemon --no-build-cache
git diff --check
```

The focused JDK 21 build compiled the production picker and passed all session
lifecycle tests, including proof that an unopened game stays on its legacy
path and the same game routes research only after explicit v3 opening.

## Projection v5 and authoritative policy adoption

Implemented:

- Projection compatibility advanced from 4 to 5 and now exposes only the
  authenticated civilization's stored culture, next-policy cost, free-policy
  balance, adopted policy names, and canonically selectable policy names.
- The closed `adopt_policy` command accepts only a bounded policy name. Rust
  derives the account and assigned civilization from membership, and the Kotlin
  worker validates the current turn, pinned ruleset entry, culture/free-policy
  balance, era, prerequisites, and availability through `PolicyManager` before
  invoking its existing `adopt` method and all shared side effects.
- PostgreSQL applies the command through the same expected-revision,
  idempotency, immutable-snapshot, canonical-hash, journal, and transactional
  outbox boundary as the other gameplay commands. No client-authored culture,
  free-policy flag, prerequisite result, or resulting policy set is accepted.
- `PolicyPickerScreen` routes both ordinary policy buttons and policy-branch
  confirmation through the v3 session only for explicitly opened games.
  Accepted commands mark the local game stale and return to the world screen;
  conflicts, rejection, lost responses, and transport failures remain distinct.
  Single-player, hotseat, and legacy multiplayer keep their local behavior.

Verification:

```text
cargo run -- --write-openapi
cargo fmt --all -- --check
cargo clippy --all-targets -- -D warnings
cargo test --all-targets
.\gradlew.bat :tests:test --tests <focused policy, projection, bus, and session tests> \
  :server:test --no-daemon --no-build-cache --rerun-tasks
git diff --check
```

Rust passed 15 active library tests and seven HTTP/OpenAPI tests. All eight
database integration tests also passed serially against the composition's
pinned PostgreSQL 19 Beta 2 image on port 55432, including atomic
CAS/idempotency, persisted sessions, membership uniqueness, outbox leases,
rate limits/audit, corrupt-snapshot quarantine, discovery, and account
lifecycle. The forced JDK 21 focused run rebuilt every affected module and
passed the canonical policy handler, closed projection fixture, command bus,
explicit-session routing, and worker/server suites. The complete regression
then passed four server tests and 765 shared tests with zero failures/errors
and 13 intentional skips.

This resolves the ordinary `pick_policy` end-turn blocker when one adoption is
required. Ideology selection, ideology tenets with specialized UX, public
policy/ideology events, and projection-only policy rendering remain incomplete.

## Authoritative free-technology grants

Implemented:

- The closed `choose_free_technology` command accepts only a bounded technology
  name already present in projection v5's `freeTechnologyChoices`; no grant
  count, research result, cost, prerequisite assertion, or civilization ID is
  accepted from the client.
- The Kotlin authoritative engine derives the actor from the authenticated
  membership assignment, requires the current turn, a positive canonical
  `freeTechs` balance, a technology from the pinned ruleset, and canonical
  `canBeResearched` legality before invoking the existing
  `TechManager.getFreeTechnology` domain path and its research side effects.
- Rust, the private worker protocol, PostgreSQL transaction path, generated
  OpenAPI, shared client transport, command bus, and authenticated session all
  carry the same typed intent. The normal revision-CAS, idempotency, immutable
  snapshot, journal, canonical hash, and outbox boundary remains unchanged.
- `TechPickerScreen` now routes both normal research and free-technology
  selection through API v3 only for explicitly opened games. Accepted choices
  mark the local game stale instead of decrementing its local grant. Local,
  hotseat, and legacy multiplayer retain the existing domain behavior.

Verification:

```text
cargo run -- --write-openapi
cargo fmt --all -- --check
cargo clippy --all-targets -- -D warnings
cargo test --all-targets
.\gradlew.bat :tests:test --tests <focused free-tech engine, bus, and session tests> \
  :server:test --no-daemon --no-build-cache --rerun-tasks
git diff --check
```

Rust passed 16 active library tests and seven HTTP/OpenAPI tests. The forced
JDK 21 focused run rebuilt every affected module and passed canonical grant
consumption, projected-choice enforcement, explicit-session routing, and the
worker/server suite. The engine regression proves one grant is consumed and a
second attempt is rejected without changing the canonical state hash.

All eight database integration tests then passed serially against the pinned
PostgreSQL 19 Beta 2 composition, and the temporary test container, network,
volume, and generated data were removed. The complete JVM regression passed
four server tests and 768 shared tests with zero failures/errors and 13
intentional skips.

Research queue append/removal/reordering, research progress/cost/history, and
public research events remain incomplete. The ordinary and free-technology
picker paths no longer directly mutate authoritative v3 games.

## First production city-construction route

Implemented:

- `CityConstructionsTable` recognizes only games explicitly opened through the
  authenticated API-v3 session. Selecting an ordinary projected construction
  submits the existing `queue_construction` command asynchronously and never
  calls `CityConstructions.addToQueue` on the disposable local game.
- The session routes only an explicitly opened game, validates city and
  construction identifiers against its current player projection through the
  command bus, preserves the same command ID for an ambiguous retry, and
  reconciles the accepted revision through HTTP.
- Accepted commands mark the local game stale and return to the world screen.
  Stale conflicts, rule rejection, lost responses, and transport failures are
  distinct UI outcomes, with duplicate clicks bounded while a request is in
  flight.
- Queue reorder/removal buttons and construction context menus are suppressed
  for opened v3 games because their typed commands do not exist yet. Purchase
  controls and tile-specific construction also fail closed instead of mutating
  the local queue. Single-player, hotseat, and legacy multiplayer preserve all
  existing queue, context-menu, purchase, and tile-selection behavior.

Verification:

```text
.\gradlew.bat :core:compileKotlin :tests:test \
  --tests com.unciv.logic.multiplayer.authoritative.AuthoritativeMultiplayerSessionTests.constructionRoutesOnlyForAnExplicitlyOpenedAuthoritativeGame \
  --no-daemon --no-build-cache --rerun-tasks
git diff --check
```

The forced JDK 21 run rebuilt production city UI and shared client code, then
proved an unopened game performs no API-v3 request while the same explicitly
opened game commits the projected city/construction pair and reconciles to the
next revision.

The complete regression passed four server tests and 769 shared tests with
zero failures/errors and 13 intentional skips. A post-test mutation search
confirmed the remaining UI `addToQueue` calls are either behind the normal
v3 routing branch, behind the tile-specific fail-closed guard, or reachable
only from context menus suppressed for opened v3 games.
An additional lost-response regression submits construction twice and proves
both attempts use the identical command ID before the second response is
accepted and reconciled.

This resolves only ordinary append. Queue removal/reordering/top insertion,
perpetual construction policy, multi-city batches, purchases, and buildings
requiring a target tile remain separate typed-command and projection work.

## Typed production removal and reordering

City queue removal and one-position reordering now cross the complete API-v3
authority boundary. The Kotlin client sends `remove_construction` or
`move_construction`; the Rust service derives the actor civilization from
PostgreSQL membership; the private Kotlin worker validates ownership, current
turn, queue bounds, and the projected entry name before calling the shared
city-construction model. The existing transactional commit path supplies CAS,
idempotency, immutable revision snapshots, and outbox recording.

Both requests bind the integer position to `expected_construction_name`. A
stale or malicious position/name pair is rejected rather than removing or
moving whichever entry happens to occupy that position in canonical state.
The city screen restores only removal and adjacent priority controls for an
explicitly opened v3 game. It does not optimistically mutate the disposable
`GameInfo`; accepted or stale outcomes close the city screen and force normal
projection reconciliation. Purchase and unsupported batch/context-menu paths
remain fail closed.

Verification:

```text
cargo run --manifest-path authoritative-server/Cargo.toml -- --write-openapi
cargo test --manifest-path authoritative-server/Cargo.toml
UNCIV_V3_DATABASE_URL=postgres://postgres:unciv_test@127.0.0.1:55456/unciv \
  cargo test --manifest-path authoritative-server/Cargo.toml \
  postgres::integration_tests -- --ignored --test-threads=1
.\gradlew.bat :tests:test \
  --tests com.unciv.logic.AuthoritativeGameExecutionContextTests.authoritativeQueueRemovalAndMovementRequireTheProjectedEntry \
  --tests com.unciv.logic.multiplayer.authoritative.AuthoritativeGameCommandBusTests.queueRemovalAndMovementBindToTheProjectedEntry \
  --tests com.unciv.logic.multiplayer.authoritative.AuthoritativeGameCommandBusTests.queueMutationRejectsAnEntryThatDoesNotMatchTheProjection \
  --tests com.unciv.logic.multiplayer.authoritative.AuthoritativeMultiplayerSessionTests.authoritativeConstructionRemovalAndMovementRouteFromAnOpenedGame \
  :server:test --no-daemon --no-build-cache
```

The Rust run passed 24 executable unit/API tests; eight database tests remained
explicitly gated in that command. All eight separately passed against the
pinned `postgres:19beta2-alpine` image, including atomic CAS/idempotency,
corrupt-snapshot quarantine, durable rate limits, account lifecycle, and
exclusive outbox claims. The focused Kotlin rule/client tests and all four
worker protocol tests passed. The isolated PostgreSQL container was removed
afterward.

The complete JDK 21 regression then passed 774 shared tests and four server
tests with zero failures/errors and 13 intentional skips:

```text
.\gradlew.bat :server:test :tests:test --no-daemon --no-build-cache
```

## Authoritative unit postures and projection v11

Sleep, Sleep Until Healed, Fortify, Fortify Until Healed, and Guard now cross
the complete API-v3 authority boundary through the closed
`SetUnitPosture(unitId, posture)` command. Neither the public request nor the
private worker accepts a raw action string. Rust derives the actor civilization
from authenticated PostgreSQL membership; the Kotlin worker validates the
canonical turn, ownership, movement, healing, fortification, improvement-work,
and Guard-unique rules before using the existing shared `MapUnit` behavior.

Projection v11 adds the closed nullable `posture` field for owned units. Visible
foreign units always receive null, so private persistent orders do not cross the
anti-cheat boundary. The shared Kotlin/Rust fixture is now
`player-projection-v11.fixture.json` and remains closed to unknown fields.

For explicitly opened v3 games, all existing Sleep, Sleep Until Healed,
Fortify, Fortify Until Healed, and Guard controls submit through the
authoritative session without mutating local canonical state. Single-player,
hotseat, saved-game, legacy multiplayer, and unrelated API-v2 behavior keep
their existing local paths.

The Rust additions stay in focused `api/unit_orders.rs`,
`postgres/unit_orders.rs`, and `worker/unit_orders.rs` modules. `main.rs`
remains six lines, `lib.rs` remains a thin 27-line façade, and every Rust source
file remains below 800 lines (largest: `lib_tests.rs`, 731 lines). `AGENTS.md`
now makes these module boundaries and PostgreSQL 19 Beta 2-only target explicit
goal constraints.

Verification on 2026-07-21 passed:

```text
cargo test --manifest-path authoritative-server/Cargo.toml --all-targets
# 33 active library and 7 HTTP/OpenAPI tests passed; 8 DB tests gated without a URL
cargo clippy --manifest-path authoritative-server/Cargo.toml --all-targets --all-features -- -D warnings
# passed
cargo run --manifest-path authoritative-server/Cargo.toml -- --write-openapi
# regenerated authoritative-server/openapi/api-v3.json; parity test passed
UNCIV_V3_DATABASE_URL=postgres://unciv:unciv@127.0.0.1:55481/unciv \
  cargo test --manifest-path authoritative-server/Cargo.toml --all-targets -- --ignored --test-threads=1
# all 8 PostgreSQL integration tests passed
.\gradlew.bat :server:test :tests:test --no-daemon --no-build-cache
# 827 shared and 4 server tests passed; zero failures/errors; 13 intentional shared skips
```

The database lane used only
`postgres:19beta2-alpine@sha256:bc62313e826eb44d5f608425b7665962b72820e686da017799e906604bfeb8a5`.
The disposable PostgreSQL 19 Beta 2 container was verified by exact name, then
stopped and removed. Remaining unit gaps include combat, promotions/upgrades,
worker improvement/repair/pillage orders, and other special actions; this
milestone does not claim complete command coverage.

## Authoritative unit exploration

Unit exploration now crosses the complete API-v3 authority boundary through
the closed `SetUnitExploration(unitId, enabled)` command. The client supplies
only a stable unit ID and desired boolean state. Rust derives the actor and
civilization from authenticated PostgreSQL membership, and the private Kotlin
worker validates the canonical current turn, ownership, and non-air-unit
constraint. Starting exploration sets the canonical Explore action and runs
the immediate move with shared `UnitAutomation.automatedExplore` logic;
stopping requires an existing exploration order and clears it on the server.

For explicitly opened v3 games, Explore and Stop Exploration never mutate the
client `MapUnit`. Single-player, hotseat, saved games, legacy multiplayer, and
unrelated API-v2 behavior retain the existing local path. Generic unit
automation remains a separate command-coverage gap rather than being
conflated with exploration.

The implementation stays in the focused Rust unit-movement modules. `main.rs`
remains six lines; the largest Rust source file remains
`postgres/commands.rs` at 729 lines.

Verification on 2026-07-21 passed:

```text
cargo clippy --manifest-path authoritative-server/Cargo.toml --all-targets --all-features -- -D warnings
# passed
cargo test --manifest-path authoritative-server/Cargo.toml --all-targets
# 31 active library tests and 7 HTTP/OpenAPI tests passed; 8 DB tests gated without a URL
UNCIV_V3_DATABASE_URL=postgres://unciv_test:unciv_test_password@127.0.0.1:55479/unciv_v3_test \
  cargo test --manifest-path authoritative-server/Cargo.toml --lib -- --ignored --test-threads=1
# all 8 PostgreSQL integration tests passed
.\gradlew.bat :tests:test :server:test --no-daemon --no-build-cache
# 819 shared and 4 server tests passed; zero failures/errors; 13 intentional shared skips
```

The database tests used only
`postgres:19beta2-alpine@sha256:bc62313e826eb44d5f608425b7665962b72820e686da017799e906604bfeb8a5`.
The disposable container was stopped and automatically removed after the run.

## Authoritative generic unit automation and projection v10

Generic Automate and Stop Automation now use the closed
`SetUnitAutomation(unitId, enabled)` API-v3 command. Rust derives the actor and
civilization from authenticated membership. The private worker validates the
canonical current turn, ownership, current automation state, and movement
availability before enabling automation, then invokes the shared
`UnitAutomation.automateUnitMoves` implementation. Later automated actions run
inside the existing server-owned turn engine through `MapUnit.doAction()`.
Disabling automation clears its canonical action and flag on the server.

Projection v10 adds explicit `automated` and `exploring` booleans for owned
units. Both fields are forced false for visible foreign units, and the foreign
secret test now seeds a real automated foreign unit to prove the order does not
cross the projection boundary. The shared Rust/Kotlin fixture moved to
`player-projection-v10.fixture.json` and remains closed to unknown canonical
fields.

For explicitly opened v3 games, Automate, Stop Automation, Explore, and Stop
Exploration submit through the authoritative session and perform no local
canonical mutation. Other game modes preserve their existing paths. Rust HTTP,
PostgreSQL, and worker plumbing for these commands now lives in focused
`unit_orders.rs` modules; movement files no longer own automation concerns.

Verification on 2026-07-21 passed:

```text
cargo clippy --manifest-path authoritative-server/Cargo.toml --all-targets --all-features -- -D warnings
# passed
cargo test --manifest-path authoritative-server/Cargo.toml --all-targets
# 32 active library and 7 HTTP/OpenAPI tests passed; 8 DB tests gated without a URL
UNCIV_V3_DATABASE_URL=postgres://unciv_test:unciv_test_password@127.0.0.1:55480/unciv_v3_test \
  cargo test --manifest-path authoritative-server/Cargo.toml --lib -- --ignored --test-threads=1
# all 8 PostgreSQL integration tests passed
.\gradlew.bat :tests:test :server:test --no-daemon --no-build-cache
# 823 shared and 4 server tests passed; zero failures/errors; 13 intentional shared skips
```

The PostgreSQL run used only
`postgres:19beta2-alpine@sha256:bc62313e826eb44d5f608425b7665962b72820e686da017799e906604bfeb8a5`.
The disposable container was stopped and automatically removed. `main.rs`
remains six lines, and the largest Rust source file remains 729 lines.

## Authoritative specialist allocation vertical slice

Projection v7 adds an owned-city-only specialist allowlist containing each
canonical specialist name, assigned count, and slot capacity, plus the city's
manual-specialist state. The Rust projection DTO denies unknown fields and the
shared Kotlin/Rust v7 fixture round-trips semantically, so an older or broader
projection shape fails closed during capability negotiation.

API v3 now accepts the absolute `SetSpecialistCount(cityId, specialistName,
count)` command. Absolute counts make command intent deterministic across
retries. The public request contains no actor, population, capacity, yield, or
legality claim. Rust authenticates and revision-commits only the Kotlin worker
result; it implements no specialist rules.

`HeadlessGameEngine.setSpecialistCount` validates the authenticated current-turn
city, puppet/resistance state, specialist existence, built-building slot
capacity, and canonical free population. It then sets manual-specialist mode,
updates the allocation, and recomputes city statistics. The shared client bus
also refuses names or counts outside the projection-v7 allowlist.

For explicitly opened v3 games, specialist `+` and `-` controls submit this
command without mutating the local city. The auto/manual toggle is deliberately
suppressed until a distinct automation command is implemented. Local, hotseat,
saved-game, legacy multiplayer, and unrelated API-v2 behavior are unchanged.

The existing focused Rust city-population modules own the new handler; no
generic population endpoint or god module was introduced. Every Rust source
file remains below 800 lines (largest: 779); `main.rs` remains six lines.

Focused verification:

```text
cargo test --manifest-path authoritative-server/Cargo.toml
# 24 library tests and 7 HTTP/OpenAPI tests passed; 8 DB tests ignored without a URL
cargo clippy --manifest-path authoritative-server/Cargo.toml --all-targets -- -D warnings
# passed
cargo run --manifest-path authoritative-server/Cargo.toml -- --write-openapi
# generated contract parity passed
.\gradlew.bat :tests:test --tests 'com.unciv.logic.AuthoritativeGameExecutionContextTests.specialistAssignmentUsesCanonicalCapacityAndPopulation' --tests 'com.unciv.logic.multiplayer.authoritative.AuthoritativeGameCommandBusTests.specialistCountIsBoundToProjectedNameAndCapacity' --tests 'com.unciv.logic.multiplayer.authoritative.AuthoritativeGameCommandBusTests.specialistRequestContainsNoCapacityPopulationOrActorClaims' --tests 'com.unciv.logic.multiplayer.authoritative.PlayerProjectionContractTests' --no-daemon
# passed
```

All eight database integration tests passed serially against
`postgres:19beta2-alpine@sha256:bc62313e826eb44d5f608425b7665962b72820e686da017799e906604bfeb8a5`.
The disposable container was stopped and automatically removed. The complete
JDK 21 regression passed 794 shared tests and four server tests with zero
failures/errors and 13 intentional shared skips.

## Authoritative manual-specialist mode

API v3 now exposes the closed boolean `SetManualSpecialists(cityId, enabled)`
command. Enabling the mode freezes specialist assignment for explicit player
control. Disabling it invokes the shared Kotlin `City.reassignPopulation()`
path inside the authoritative worker, so specialist clearing, tile selection,
and city-stat recomputation use canonical state and the existing Unciv rules.

The public request contains no actor, population allocation, focus, or derived
yield fields. Rust authenticates, forwards the typed intent, and commits only
the worker result under the existing revision/idempotency transaction. The
client bus requires an owned projected city with specialist slots. For opened
v3 games, the specialist auto/manual toggle submits this command and performs
no local mutation; local, hotseat, legacy multiplayer, and API-v2 behavior are
unchanged.

The module-size review triggered an early split at 764 lines: city-population
worker methods now live in `worker/city_population.rs`. `worker.rs` returns to
a focused transport façade, all Rust files remain below 800 lines, and
`main.rs` remains six lines.

Focused verification:

```text
cargo test --manifest-path authoritative-server/Cargo.toml
# 25 library tests and 7 HTTP/OpenAPI tests passed; 8 DB tests ignored without a URL
cargo clippy --manifest-path authoritative-server/Cargo.toml --all-targets -- -D warnings
# passed
.\gradlew.bat :tests:test --tests 'com.unciv.logic.AuthoritativeGameExecutionContextTests.disablingManualSpecialistsReassignsPopulationCanonically' --tests 'com.unciv.logic.multiplayer.authoritative.AuthoritativeGameCommandBusTests.manualSpecialistModeRequiresProjectedSlots' --tests 'com.unciv.logic.multiplayer.authoritative.AuthoritativeGameCommandBusTests.manualSpecialistModeRequestIsClosedAndActorless' --no-daemon
# passed
```

All eight database integration tests passed serially against the sole pinned
PostgreSQL 19 Beta 2 digest. The disposable container was stopped and removed.
The complete JDK 21 regression passed 797 shared tests and four server tests
with zero failures/errors and 13 intentional shared skips.

## Authoritative movement-order cancellation

`CancelUnitMovementOrder(unitId)` now owns the Stop Movement action for an
explicitly opened v3 game. Its closed request contains only the stable unit ID
plus revision/idempotency metadata. The command bus requires the current own-
unit projection to contain a canonical movement destination, and the worker
independently revalidates membership-derived actor, current turn, ownership,
and an existing moving state before clearing only that unit's canonical order.

`UnitActions` routes Stop Movement through `WorldMapHolder` and the
authoritative session without assigning `unit.action = null` locally. Local,
hotseat, saved-game, legacy multiplayer, and API-v2 paths retain the existing
direct mutation. Accepted/stale outcomes mark the disposable cache out of date,
and an ambiguous response retains the original command ID.

All Rust additions remain in the focused movement modules. Verification passed
canonical set/cancel projection behavior, closed Rust/Kotlin request shapes,
projected-order command-bus gating, explicit-open session routing, 30 active
Rust library tests, seven HTTP/OpenAPI tests, generated OpenAPI parity,
formatting, strict all-target/all-feature Clippy with warnings denied, and
`git diff --check`. All eight database integration tests passed serially
against the pinned PostgreSQL 19 Beta 2 digest on port 55478; the disposable
container was then stopped and removed. The complete JDK 21 regression passed
816 shared tests and four server tests with zero failures/errors and 13
intentional skips. Every Rust source file remains below 800 lines; the largest
is `postgres/commands.rs` at 729.

```text
cargo test --manifest-path authoritative-server/Cargo.toml
cargo clippy --manifest-path authoritative-server/Cargo.toml \
  --all-targets --all-features -- -D warnings
UNCIV_V3_DATABASE_URL=postgres://unciv_test:unciv_test_password@127.0.0.1:55478/unciv_v3_test \
  cargo test --manifest-path authoritative-server/Cargo.toml --lib -- --ignored --test-threads=1
.\gradlew.bat :tests:test :server:test --no-daemon
```

## Authoritative multi-turn movement orders and projection v9

`MoveUnitToward(unitId, finalDestination)` now preserves the legacy long-distance
movement behavior without trusting a client path or serialized action string.
The Kotlin worker derives the actor, validates the canonical turn, ownership,
known map coordinate, bounds and reachability, advances through shared
`headTowards`, and stores the remaining `moveTo` order only in canonical server
state. Later server-side turn processing executes that order through the shared
`MapUnit.doAction()` path. Exact `MoveUnit` commands clear an earlier automation
or movement order before moving, matching manual-move semantics.

Projection v9 adds nullable movement-destination coordinates to own units so
the client can render canonical order arrows after refresh. The builder never
copies this field for foreign units, and both the real projection leak test and
the shared Rust/Kotlin v9 fixture assert that foreign movement plans remain
absent. Opened-v3 world-map movement chooses `MoveUnitToward` only when the
requested target lies beyond the current-turn endpoint; one-turn moves retain
the stricter exact-destination command.

All new Rust code remains in the focused movement modules. Verification passed
the canonical multi-turn/order projection test, foreign-plan leak test,
projection-v9 Rust/Kotlin round trip, closed request tests, projection-bound
command-bus routing, explicit-open session routing, 29 active Rust library
tests, seven HTTP/OpenAPI tests, generated OpenAPI parity, formatting, strict
all-target/all-feature Clippy with warnings denied, and `git diff --check`. All
eight database integration tests passed serially against the pinned PostgreSQL
19 Beta 2 digest on port 55477; the disposable container was then stopped and
removed. The complete JDK 21 regression passed 813 shared tests and four server
tests with zero failures/errors and 13 intentional skips. Every Rust source
file remains below 800 lines; the largest is `postgres/commands.rs` at 729.

```text
cargo test --manifest-path authoritative-server/Cargo.toml
cargo clippy --manifest-path authoritative-server/Cargo.toml \
  --all-targets --all-features -- -D warnings
UNCIV_V3_DATABASE_URL=postgres://unciv_test:unciv_test_password@127.0.0.1:55477/unciv_v3_test \
  cargo test --manifest-path authoritative-server/Cargo.toml --lib -- --ignored --test-threads=1
.\gradlew.bat :tests:test :server:test --no-daemon
```

## Authoritative friendly-unit swapping and movement modules

`SwapUnits(unitId, destination)` now crosses the complete API-v3 authority
boundary as a distinct closed command. It carries no actor, target-unit,
movement-cost, escort, or client-legality claims. Rust derives the actor from
membership, while the Kotlin worker validates the canonical turn, unit
ownership, map coordinate, current-turn reachability, compatible friendly
occupancy, movement points, and escort rules before calling shared
`swapMoveToTile(..., keepEscorting = true)` behavior.

Opened-v3 swap controls submit through the session/command bus and never call
the local swap method. The bus binds the stable unit ID and coordinate to the
current player projection, preserves idempotency keys across ambiguous retries,
and refreshes after accepted/stale outcomes. Local, hotseat, and legacy paths
retain their existing direct behavior.

The Rust movement path was split proactively before adding this command.
Handlers now live in `api/unit_movement.rs`, transaction orchestration in
`postgres/unit_movement.rs`, and private worker calls in
`worker/unit_movement.rs`. This reduced `api/commands.rs` from 692 to 646 lines,
`postgres/commands.rs` from 779 to 729, and `worker.rs` from 694 to 662. The new
focused modules own both ordinary movement and swaps; `main.rs` remains six
lines and every Rust source file remains below 800 lines.

Verification passed the canonical swap engine test, closed Rust and Kotlin
wire-shape tests, projection-bound command-bus test, explicit-open session
test, generated OpenAPI parity, 28 active Rust library tests, seven HTTP/
OpenAPI tests, formatting, strict all-target/all-feature Clippy with warnings
denied, and `git diff --check`. All eight database integration tests passed
serially against the pinned PostgreSQL 19 Beta 2 digest on port 55476; the
disposable container was then stopped and removed. The complete JDK 21
regression passed 809 shared tests and four server tests with zero failures/
errors and 13 intentional skips.

```text
cargo test --manifest-path authoritative-server/Cargo.toml
cargo clippy --manifest-path authoritative-server/Cargo.toml \
  --all-targets --all-features -- -D warnings
UNCIV_V3_DATABASE_URL=postgres://unciv_test:unciv_test_password@127.0.0.1:55476/unciv_v3_test \
  cargo test --manifest-path authoritative-server/Cargo.toml --lib -- --ignored --test-threads=1
.\gradlew.bat :tests:test :server:test --no-daemon
```

## Authoritative world-map unit movement

The existing `MoveUnit(unitId, destination)` command now owns the ordinary
world-map movement UI for explicitly opened v3 games. Overlay-button movement
and the single-tap setting both resolve the intended current-turn destination,
then submit it through `AuthoritativeMultiplayerSession` and the revisioned
command bus without calling local `MapUnit` movement. Multi-selected units are
submitted sequentially so each command observes the prior committed revision.

The client preflights only that the stable unit ID belongs to its projection
and the destination coordinate is explored. The Kotlin worker remains the
authority for membership-derived actor/turn, ownership, canonical map bounds,
reachability, movement points, terrain, occupancy, and the resulting position.
Accepted and stale outcomes mark the disposable local cache out of date.
Ambiguous retries retain the original command ID. Friendly-unit swapping is a
different state transition and now fails closed for v3 instead of invoking the
legacy local swap; persistent multi-turn movement orders remain unimplemented.

Verification passed the canonical engine movement tests, command-bus
stale/retry/rejection tests with projection-bound units/destinations, the
explicit-open session routing test, 27 active Rust library tests, all seven
HTTP/OpenAPI tests, generated-contract parity, formatting, strict all-target/
all-feature Clippy with warnings denied, and `git diff --check`. All eight
database integration tests passed serially against the pinned PostgreSQL 19
Beta 2 digest on port 55475; the disposable container was then stopped and
removed. The complete JDK 21 regression passed 805 shared tests and four server
tests with zero failures/errors and 13 intentional skips. `main.rs` remains six
lines and every Rust source file remains below 800 lines; the largest is
`postgres/commands.rs` at 779 lines.

```text
cargo test --manifest-path authoritative-server/Cargo.toml
cargo clippy --manifest-path authoritative-server/Cargo.toml \
  --all-targets --all-features -- -D warnings
UNCIV_V3_DATABASE_URL=postgres://unciv_test:unciv_test_password@127.0.0.1:55475/unciv_v3_test \
  cargo test --manifest-path authoritative-server/Cargo.toml --lib -- --ignored --test-threads=1
.\gradlew.bat :tests:test :server:test --no-daemon
```

## Authoritative citizen growth and focus policies

`SetAvoidGrowth(cityId, enabled)` and `SetCitizenFocus(cityId, focus)` now cross
the complete API-v3 boundary. Requests carry only the typed policy intent plus
revision/idempotency metadata; Rust derives the actor from the authenticated
membership and commits only the Kotlin worker's canonical result. The worker
validates current-turn city ownership, rejects puppet/resistance mutation,
checks focus selectability and religion compatibility, and runs the shared
population reassignment rules.

For explicitly opened v3 games, the Avoid Growth and every Citizen Focus
control submit through the authoritative session and never mutate the local
city. The command bus binds the city to the player projection and accepts only
focuses in projection v8's server-produced allowlist. Local, hotseat, saved-
game, legacy multiplayer, and API-v2 behavior is unchanged.

Rust keeps this work in the existing focused `api/city_population.rs`,
`postgres/city_population.rs`, and `worker/city_population.rs` modules.
`main.rs` remains six lines, and every Rust source file remains below 800 lines;
the largest is `postgres/commands.rs` at 779 lines.

Verification passed 27 active Rust library tests, all seven HTTP/OpenAPI tests,
generated-contract parity, formatting, strict all-target/all-feature Clippy
with warnings denied, the canonical Kotlin engine policy test, command-bus
projection gating and wire-shape tests, and `git diff --check`. All eight
database integration tests passed serially against the pinned PostgreSQL 19
Beta 2 digest on port 55474, after which the disposable container was stopped
and removed. The complete JDK 21 regression passed 804 shared tests and four
server tests with zero failures/errors and 13 intentional skips:

```text
UNCIV_V3_DATABASE_URL=postgres://unciv_test:unciv_test_password@127.0.0.1:55474/unciv_v3_test \
  cargo test --manifest-path authoritative-server/Cargo.toml --lib -- --ignored --test-threads=1
cargo clippy --manifest-path authoritative-server/Cargo.toml \
  --all-targets --all-features -- -D warnings
.\gradlew.bat :tests:test :server:test --no-daemon
```

## Projection v8 citizen-policy foundation

Projection v8 adds each owned city's canonical `avoidGrowth`, current
`citizenFocus`, and player-selectable focus allowlist. Focus values use a
closed snake-case enum shared by Kotlin command/projection code and mirrored by
the deny-unknown Rust projection DTO. A contract test proves every Unciv
`CityFocus` enum value has a corresponding wire value. `HappinessFocus` remains
round-trippable for legacy/AI state but is absent from the player-selectable
allowlist; `FaithFocus` is offered only when religion is enabled.

The Kotlin engine now has canonical `setAvoidGrowth` and `setCitizenFocus`
handlers. Both authenticate the current-turn city, reject puppet/resistance
mutation, change only the requested policy, and invoke the shared
`reassignPopulation()` rules. The focus handler rejects non-player-selectable
and religion-incompatible values. These handlers are not yet exposed through
the public Rust command route or client UI in this foundation slice.

Verification passed the shared projection-v8 Rust/Kotlin round trip, closed
enum parity, canonical policy/focus handler test, generated OpenAPI parity, 26
active Rust library tests, seven HTTP/OpenAPI tests, formatting, strict Clippy,
and `git diff --check`. All eight database integration tests passed serially
against `postgres:19beta2-alpine` on port 55473; the disposable container was
then stopped. The complete JDK 21 regression passed 802 shared tests and four
server tests with zero failures/errors and 13 intentional skips:

```text
UNCIV_V3_DATABASE_URL=postgres://unciv_test:unciv_test_password@127.0.0.1:55473/unciv_v3_test \
  cargo test --manifest-path authoritative-server/Cargo.toml --lib -- --ignored --test-threads=1
.\gradlew.bat :tests:test :server:test --no-daemon
```

## Authoritative citizen reset

The `ResetCitizens(cityId)` API-v3 command exactly mirrors the existing reset
button semantics without accepting focus, population, lock, yield, or actor
claims. The Kotlin worker validates the authenticated current-turn city and
calls `reassignPopulation(resetLocked = true)`, then verifies canonical locks
are empty and no population remains unassigned. Manual-specialist mode is not
silently changed, matching the existing local behavior.

For explicitly opened v3 games the reset control submits through the
revisioned command bus and never mutates the local city. Local, hotseat, saved-
game, legacy multiplayer, and API-v2 paths retain their existing behavior.
Rust HTTP, persistence, and worker code remains in the focused city-population
modules; every Rust source file remains below 800 lines and `main.rs` remains
six lines.

Focused verification:

```text
cargo test --manifest-path authoritative-server/Cargo.toml
# 26 library tests and 7 HTTP/OpenAPI tests passed; 8 DB tests ignored without a URL
cargo clippy --manifest-path authoritative-server/Cargo.toml --all-targets -- -D warnings
# passed
.\gradlew.bat :tests:test --tests 'com.unciv.logic.AuthoritativeGameExecutionContextTests.citizenResetClearsLocksAndReassignsCanonicalPopulation' --tests 'com.unciv.logic.multiplayer.authoritative.AuthoritativeGameCommandBusTests.citizenResetIsBoundToProjectedCity' --tests 'com.unciv.logic.multiplayer.authoritative.AuthoritativeGameCommandBusTests.citizenResetRequestContainsNoPolicyOrPopulationClaims' --no-daemon
# passed
```

All eight database integration tests passed serially against the sole pinned
PostgreSQL 19 Beta 2 digest. The disposable container was stopped and removed.
The complete JDK 21 regression passed 800 shared tests and four server tests
with zero failures/errors and 13 intentional shared skips.

## Typed perpetual construction selection

Perpetual city production now has its own `SetPerpetualConstruction` command
across the Kotlin client, Rust HTTP/OpenAPI boundary, PostgreSQL repository,
private worker protocol, and shared headless engine. This is intentionally not
folded into `QueueConstruction`: ordinary construction must append and increase
queue length, while a perpetual choice replaces the terminal `Nothing` or
stat-conversion entry. Keeping separate commands preserves both invariants.

The worker accepts only a canonical `PerpetualConstruction` name that is
currently buildable for an authenticated actor's owned city on their turn. It
rejects duplicate/no-op selection and arbitrary ordinary construction names.
The explicitly opened v3 city UI submits the typed command without mutating its
cached `GameInfo`; local, hotseat, and legacy behavior is unchanged.

Verification passed the closed actorless Rust contract/OpenAPI tests, focused
Kotlin engine/bus/session tests, four worker tests, and all eight persistence
tests against the sole pinned PostgreSQL 19 Beta 2 digest. The disposable
database container was stopped and automatically removed afterward.

The complete JDK 21 regression then passed 781 shared tests and four server
tests with zero failures/errors and 13 intentional skips. Rust passed 26
executable unit/API tests, strict Clippy with warnings denied, and formatting.
The command repository now loads canonical snapshot/manifest state and derives
the actor civilization through two shared typed helpers, removing the repeated
SQL/membership prelude from worker-backed gameplay operations.

## Authoritative city tile purchase

Single-tile acquisition now crosses the complete API-v3 boundary as
`BuyCityTile(cityId, x, y)`. The request deliberately omits actor identity and
price. Rust derives the account/civilization from authenticated membership; the
Kotlin worker resolves the coordinate in canonical map state, verifies the
owned current-turn city and shared `canBuyTile` rules, recomputes the gold cost,
and invokes `CityExpansionManager.buyTile`. Postconditions prove both canonical
ownership and the exact server-calculated treasury deduction.

For explicitly opened v3 games, `CityScreen.askToBuyTile` submits the command
without mutating cached `GameInfo`. The local ring-purchase context menu is
disabled because a multi-tile purchase needs its own bounded typed batch and
server-side sequential price calculation. Client display may still show an
estimated price, but it is never transmitted or trusted.

Focused tests cover canonical cost/ownership, the actorless and price-free wire
shape, projection-coordinate gating, and lost-response command-ID reuse. All
eight persistence tests passed serially against the sole pinned PostgreSQL 19
Beta 2 digest; the disposable database container was stopped and removed.

The complete JDK 21 regression passed 784 shared tests and four server tests
with zero failures/errors and 13 intentional skips. Rust passed 27 executable
unit/API tests, formatting, and strict Clippy with warnings denied. Player
projection construction choices now include canonically buildable perpetual
choices as well as ordinary buildings and units, closing the UI-to-command
discovery gap exposed while testing the preceding milestone.

Remaining production-queue gaps are add-to-top/all-cities batches, perpetual
construction policy, tile-targeted buildings, purchases, and richer projected
cost/progress data. This milestone does not change the broader anti-cheat
status: incomplete player projections and many other local gameplay mutation
paths still prevent a cheat-resistant completion claim.

## Authoritative tile-targeted construction

Buildings with `CreatesOneImprovement` now use the distinct typed
`QueueConstructionAtTile(cityId, constructionName, x, y)` command across the
Kotlin client, Rust HTTP/OpenAPI boundary, PostgreSQL repository, private
worker protocol, and shared headless engine. The request contains no actor or
client legality flag. Rust derives civilization membership; the worker resolves
the coordinate from canonical state, requires an owned current-turn city,
rechecks constructability and shared placement rules, appends through
`CityConstructions.addToQueue`, and verifies both the queue entry and canonical
tile marker before proposing a revision.

The explicitly opened v3 city screen retains the existing tile picker but
submits the selected building and coordinate asynchronously without mutating
its disposable `GameInfo`. Accepted/stale/rejected/lost-response outcomes use
the existing reconciliation model. Tile-targeted purchases remain fail closed,
while single-player, hotseat, and legacy multiplayer retain their existing
local behavior.

Focused verification covers the actorless closed wire shape, projected
city/construction/coordinate gating, canonical marker placement, invalid-tile
rejection, and the client/session compilation boundary. The Rust module review
also moved worker wire contracts and typed intent records into focused
`worker/protocol.rs`; `worker.rs` is now transport/execution focused at 664
lines, and every Rust source module is below 800 lines (`main.rs` remains six
lines and `lib.rs` 24).

Verification used JDK 21 and the sole pinned PostgreSQL 19 Beta 2 digest:

```text
cargo fmt --manifest-path authoritative-server/Cargo.toml
cargo test --manifest-path authoritative-server/Cargo.toml --all-targets --all-features
cargo clippy --manifest-path authoritative-server/Cargo.toml --all-targets --all-features -- -D warnings
.\gradlew.bat :server:test :tests:test --no-daemon --no-build-cache
UNCIV_V3_DATABASE_URL=postgres://... cargo test --manifest-path authoritative-server/Cargo.toml --lib -- --ignored --test-threads=1
git diff --check
```

Rust passed 28 executable unit/API tests (plus eight intentionally gated
database tests), formatting, generated-OpenAPI parity, and strict Clippy. The
database lane passed all eight tests serially against
`postgres:19beta2-alpine@sha256:bc62313e826eb44d5f608425b7665962b72820e686da017799e906604bfeb8a5`;
the disposable container was stopped and removed. The complete JVM regression
passed 790 tests (786 shared plus four server), with zero failures/errors and
13 intentional skips.

## Player-scoped city tile assignment projection

Projection v6 adds `assignableTiles` to each owned city. Each entry contains
only coordinate, worked, and locked state for an actor-owned, in-range,
yielding, non-blockaded tile that is not being worked by another city. This is
an explicit allowlist built by the Kotlin authoritative engine rather than a
redacted canonical tile, so terrain, resources, foreign assignments, and other
hidden canonical fields do not cross the public boundary. Rust uses a matching
deny-unknown-fields DTO and the shared v6 fixture; capability negotiation now
fails closed for older projection clients.

This is the required projection foundation for typed citizen assignment. The
typed command and UI routing are recorded in the later city-tile assignment
slice below.

## Authoritative city tile assignment vertical slice

API v3 now accepts the closed `SetCityTileAssignment` command with only a city
ID, canonical map coordinate, and `unworked`, `worked`, or `locked` target
state. Authentication and membership derive the actor; the request cannot
supply population, yields, ownership, price, or a client legality result. Rust
persists and revision-commits only the Kotlin worker result and contains no
city-allocation rules.

The Kotlin worker invokes `HeadlessGameEngine.setCityTileAssignment`, which
validates the canonical current-turn city, ownership/range, puppet and
resistance state, other-city workers, yield, blockade, and free population
before updating worked/locked state and recomputing city statistics. The
command bus additionally fails closed unless the coordinate occurs in that
city's projection-v6 `assignableTiles` allowlist.

For explicitly opened v3 games, city worked-icon click/double-click and the
tile table's lock/unlock controls submit this command and never mutate local
`workedTiles` or `lockedTiles`. Local, hotseat, saved-game, legacy multiplayer,
and unrelated API-v2 behavior retain their existing local paths. Specialist
allocation and city automation remain explicit command-coverage gaps.

The Rust implementation remains split by purpose: HTTP and persistence live in
focused `api/city_population.rs` and `postgres/city_population.rs` modules.
Every Rust source file remains below 800 lines; `main.rs` remains six lines and
contains no application logic.

Focused verification for this slice:

```text
cargo test --manifest-path authoritative-server/Cargo.toml
# 23 library tests and 7 HTTP/OpenAPI tests passed; 8 database tests ignored without a URL
cargo clippy --manifest-path authoritative-server/Cargo.toml --all-targets -- -D warnings
# passed
cargo run --manifest-path authoritative-server/Cargo.toml -- --write-openapi
# generated authoritative-server/openapi/api-v3.json
.\gradlew.bat :tests:test --tests 'com.unciv.logic.multiplayer.authoritative.AuthoritativeGameCommandBusTests.cityTileAssignmentIsBoundToTheProjectedCityTile' --tests 'com.unciv.logic.AuthoritativeGameExecutionContextTests' --no-daemon
# passed
.\gradlew.bat :tests:compileTestKotlin :server:compileTestKotlin --no-daemon
# passed
cargo test --manifest-path authoritative-server/Cargo.toml --lib -- --ignored --test-threads=1
# 8 passed against the pinned PostgreSQL 19 Beta 2 digest
.\gradlew.bat :tests:test :server:test --no-daemon
# 791 shared plus 4 server tests passed; 13 intentional shared skips
```

All eight database integration tests passed serially against
`postgres:19beta2-alpine@sha256:bc62313e826eb44d5f608425b7665962b72820e686da017799e906604bfeb8a5`.
The disposable database container was stopped and automatically removed after
the run. The complete JDK 21 regression passed 791 shared tests and four server
tests with zero failures/errors and 13 intentional shared skips.

Verification passed 29 active Rust unit/API tests (eight database tests remain
explicitly gated), generated-OpenAPI parity, the shared Kotlin/Rust v6 fixture
round trip, Kotlin foreign-secret projection coverage, and `git diff --check`.

The shared headless engine now also has a canonical
`setCityTileAssignment` boundary with the closed states `Unworked`, `Worked`,
and `Locked`. It rejects foreign/out-of-range/city-center/blockaded/zero-yield
tiles, tiles worked by another city, puppet/resisting cities, and assignment
without free population. Postconditions verify both worked and lock state, and
a focused headless fixture passes the locked-to-unworked transition. Public
API/worker/client routing remains the next buildable slice.

## Authoritative tile-targeted construction purchases

Tile-targeted buildings can now be purchased through the distinct typed
`PurchaseConstructionAtTile(cityId, constructionName, currencyName, x, y,
queueIndex?)` command. The Kotlin client sends no price, actor, or legality
claim. Rust derives the actor civilization from authenticated PostgreSQL
membership, and the private worker resolves canonical city, construction,
currency, queue entry, and map tile. It reruns shared purchase and placement
rules, recomputes the canonical cost, enforces an existing queued building's
canonical tile binding, and verifies that both the building and improvement
were committed before returning a proposal.

`CityScreen` now uses its existing tile picker and confirmation popup for v3
purchases. `BuyButtonFactory` submits either ordinary or tile-targeted purchase
through the authoritative session and never calls the local purchase method for
an explicitly opened v3 game. Local, hotseat, and legacy multiplayer behavior
is unchanged.

Verification used JDK 21 and the sole pinned PostgreSQL 19 Beta 2 digest. Rust
passed 29 active unit/API tests, generated-OpenAPI parity, formatting, and
strict Clippy with warnings denied. All eight database integration tests passed
serially against the pinned digest, and the disposable container was stopped
and removed. The complete JVM regression passed 792 tests (788 shared plus four
server), with zero failures/errors and 13 intentional skips. Focused engine
tests prove canonical cost deduction, building completion, and improvement
placement; wire/client tests prove projection gating and the absence of actor,
price, and client legality fields. Every Rust source module remains below 800
lines; the largest is `postgres/commands.rs` at 776 lines.

## Authoritative construction purchases and Rust module boundaries

Non-tile-targeted city purchases now cross the complete API-v3 authority
boundary. The public request carries only the city, construction, currency,
optional projected queue position, command ID, and expected revision. It has no
actor or client-computed price. Rust derives the account and civilization from
the authenticated membership, while the Kotlin worker loads canonical state,
recomputes the cost, validates currency/resources and purchase availability,
and calls the shared `CityConstructions.purchaseConstruction` logic. Explicitly
opened v3 city screens submit this command without applying a local purchase;
tile-targeted purchases remain unavailable until their typed coordinate/choice
contract exists.

The Rust control plane was also reorganized before further command expansion.
`main.rs` is a six-line entry point and `lib.rs` is a 24-line facade. HTTP
contracts, handlers, bootstrap, errors, OpenAPI and tests live in descriptive
`api/` modules. The former 2,000-line PostgreSQL file is split into focused
accounts, games, commands, outbox, security, repository-core and integration-
test modules; the largest is under 700 lines. Visibility remains narrow and no
transaction, authentication or worker boundary was weakened by the move.

Verification used JDK 21 and the sole pinned PostgreSQL 19 Beta 2 image:

```text
cargo fmt --manifest-path authoritative-server/Cargo.toml
cargo clippy --manifest-path authoritative-server/Cargo.toml \
  --all-targets --all-features -- -D warnings
cargo test --manifest-path authoritative-server/Cargo.toml
UNCIV_V3_DATABASE_URL=postgres://unciv_test:unciv_test_password@127.0.0.1:55457/unciv_v3_test \
  cargo test --manifest-path authoritative-server/Cargo.toml \
  postgres::integration_tests -- --ignored --test-threads=1
.\gradlew.bat tests:test \
  --tests com.unciv.logic.AuthoritativeGameExecutionContextTests \
  --tests com.unciv.logic.multiplayer.authoritative.AuthoritativeGameCommandBusTests \
  --tests com.unciv.logic.multiplayer.authoritative.AuthoritativeMultiplayerSessionTests
```

Rust passed 25 regular tests and all eight PostgreSQL integration tests. The
strict warnings-as-errors Clippy gate passed, as did the focused Kotlin
rule/client/session suite. The disposable PostgreSQL 19 Beta 2 container was
stopped and automatically removed after the database run.

The subsequent complete JDK 21 regression passed 778 shared tests and four
server tests with zero failures/errors and 13 intentional skips:

```text
.\gradlew.bat :server:test :tests:test --no-daemon --no-build-cache
```

## Authoritative unit disbanding

Unit disbanding now crosses the complete API-v3 authority boundary through the
typed `DisbandUnit(unitId)` command. The client request carries no actor,
civilization, gold value, ownership claim, transport result, or defeat result.
Rust derives membership and commits only the private Kotlin worker proposal;
the shared engine validates the canonical current turn, ownership, and
movement before calling `MapUnit.disband()`.

All consequential logic therefore remains server-owned: transported units are
evacuated or destroyed by shared movement rules, gold is derived from canonical
tile ownership and the pinned unit/ruleset, upkeep statistics are refreshed,
and civilization defeat is evaluated from canonical state. Deterministic tests
apply the command twice from the same real server-created snapshot and compare
hashes, while authorization tests reject a foreign account and an out-of-turn
owner without mutation.

For explicitly opened v3 games, the existing confirmation popup submits the
command through the session/command bus and never calls client-side
`MapUnit.disband()`. Local, hotseat, saved-game, legacy multiplayer, and
unrelated API-v2 paths preserve the existing direct behavior. Reconciliation
removes the unit from the owner's projection after the committed revision.

The Rust implementation uses new focused `api/unit_actions.rs`,
`postgres/unit_actions.rs`, and `worker/unit_actions.rs` modules. `main.rs`
remains six lines, and every Rust source file remains below 800 lines (largest:
`lib_tests.rs`, 741 lines).

Verification on 2026-07-21 passed:

```text
cargo test --manifest-path authoritative-server/Cargo.toml --all-targets
# 34 active library and 7 HTTP/OpenAPI tests passed; 8 DB tests gated without a URL
cargo clippy --manifest-path authoritative-server/Cargo.toml --all-targets --all-features -- -D warnings
# passed
cargo run --manifest-path authoritative-server/Cargo.toml -- --write-openapi
# regenerated authoritative-server/openapi/api-v3.json; parity test passed
UNCIV_V3_DATABASE_URL=postgres://unciv:unciv@127.0.0.1:55482/unciv \
  cargo test --manifest-path authoritative-server/Cargo.toml --all-targets -- --ignored --test-threads=1
# all 8 PostgreSQL integration tests passed
.\gradlew.bat :server:test :tests:test --no-daemon --no-build-cache
# 831 shared and 4 server tests passed; zero failures/errors; 13 intentional shared skips
```

The database lane used only
`postgres:19beta2-alpine@sha256:bc62313e826eb44d5f608425b7665962b72820e686da017799e906604bfeb8a5`.
The disposable container was verified by exact name, then stopped and removed.
The multiplayer client remains a rendering/input/projection cache rather than
an authority; AI civilizations and turn processing already execute in the
server worker, while the remaining coverage gaps are still tracked explicitly.

## Authoritative unit upgrades

Normal unit upgrades now cross the complete API-v3 authority boundary through
the bounded `UpgradeUnits(unitIds, targetUnitName)` command. A single upgrade
and Upgrade All share this contract; batches contain 1-100 distinct stable
unit IDs and commit atomically. If validation of any unit fails, Rust receives
a rejected worker result and PostgreSQL commits neither a snapshot nor a new
revision.

The request contains no actor, civilization, price, resource quantities,
technology claims, placement result, or replacement state. Rust derives the
account's civilization from authenticated game membership. The Kotlin worker
loads canonical state and independently validates the current turn, ownership,
movement, owned territory, embarkation state, equivalent upgrade target,
technology, resources, gold, canonical cost, and placement before invoking the
shared `performUpgrade` domain logic. Stable unit IDs survive replacement and
the committed projection supplies the resulting unit names and treasury.

For explicitly opened v3 games, `UnitUpgradeMenu` submits either Upgrade or
Upgrade All through the session and command bus without mutating local
`GameInfo`. Single-player, hotseat, saved games, legacy multiplayer, and
server-internal AI or unique-driven upgrades retain the existing shared Kotlin
domain behavior. Promotions remain an explicit command-coverage gap.

The Rust changes remain separated across focused API, PostgreSQL, and worker
unit-action modules. `main.rs` remains six lines, and all Rust source files
remain below 800 lines (largest: `lib_tests.rs`, 762 lines).

Verification on 2026-07-21 passed:

```text
cargo run --manifest-path authoritative-server/Cargo.toml -- --write-openapi
# regenerated api-v3.json and the checked-in parity test passed
cargo test --manifest-path authoritative-server/Cargo.toml --all-targets
# 35 active library and 7 HTTP/OpenAPI tests passed; 8 DB tests gated without a URL
cargo clippy --manifest-path authoritative-server/Cargo.toml --all-targets --all-features -- -D warnings
# passed
UNCIV_V3_DATABASE_URL=postgres://unciv:unciv@127.0.0.1:55483/unciv \
  cargo test --manifest-path authoritative-server/Cargo.toml \
  postgres::integration_tests -- --ignored --test-threads=1
# all 8 PostgreSQL integration tests passed
.\gradlew.bat :server:test :tests:test --no-daemon --no-build-cache
# 835 shared and 4 server tests passed; zero failures/errors; 13 intentional shared skips
```

The database lane used only
`postgres:19beta2-alpine@sha256:bc62313e826eb44d5f608425b7665962b72820e686da017799e906604bfeb8a5`.
The disposable container was verified by exact name and image, then removed.

## Authoritative unit promotions

Player-selected unit promotions now cross API v3 through the bounded
`PromoteUnit(unitId, promotionNames)` command. The selected list preserves the
existing promotion picker's multi-step path UX, but it is intent rather than a
legality claim: the Kotlin worker resolves the membership-derived civilization
and stable unit ID, then revalidates the canonical turn, ownership, movement,
attack count, promotion availability, prerequisites, incompatibilities,
XP/free-promotion rules, and triggered effects before every path step.

The client supplies no actor, XP balance/cost, prerequisite claim, unit state,
or resulting effects. It performs no local promotion for an explicitly opened
v3 game and closes the picker while the command reconciles. Local-only unit
renaming and city default-promotion controls are suppressed in that picker so
they cannot piggyback an untyped canonical mutation. Single-player, hotseat,
saved games, legacy multiplayer, and server-owned AI promotion logic continue
to use the shared Kotlin domain behavior directly.

Rust keeps the handler, repository orchestration, and worker transport in the
focused unit-action modules. Unit-action wire-contract tests were proactively
split from the general `lib_tests.rs` catch-all into
`lib_tests/unit_action_contracts.rs`; `main.rs` remains six lines, the general
test module fell from 782 to 566 lines, and every Rust source file remains below
800 lines.

Verification on 2026-07-21 passed:

```text
cargo run --manifest-path authoritative-server/Cargo.toml -- --write-openapi
# regenerated api-v3.json and the checked-in parity test passed
cargo test --manifest-path authoritative-server/Cargo.toml --all-targets
# 36 active library and 7 HTTP/OpenAPI tests passed; 8 DB tests gated without a URL
cargo clippy --manifest-path authoritative-server/Cargo.toml --all-targets --all-features -- -D warnings
# passed
UNCIV_V3_DATABASE_URL=postgres://unciv:unciv@127.0.0.1:55485/unciv \
  cargo test --manifest-path authoritative-server/Cargo.toml \
  postgres::integration_tests -- --ignored --test-threads=1
# all 8 PostgreSQL integration tests passed
.\gradlew.bat :server:test :tests:test --no-daemon --no-build-cache
# 839 shared and 4 server tests passed; zero failures/errors; 13 intentional shared skips
```

The database lane used only
`postgres:19beta2-alpine@sha256:bc62313e826eb44d5f608425b7665962b72820e686da017799e906604bfeb8a5`.
The disposable container was verified by exact name and image, then removed.
Unit renaming and persistent city default-promotion preferences remain distinct
command-coverage gaps rather than being accepted through this command.
Projection v12 adds the owning player's promotion names, current XP, next XP
cost, and currently available promotion names so a disposable client can
reconstruct the promotion UI. Visible foreign units receive empty/null values
for all four fields, enforced by the shared closed fixture and leak tests.

## Authoritative unit renaming and projection v13

Unit names now cross API v3 through `RenameUnit(unitId, instanceName)`. The
nullable name means clearing a custom name is explicit; non-null names are
bounded to 100 printable characters. The server derives the civilization from
authenticated membership, validates the canonical current turn and stable unit
ownership, mutates only the worker-loaded canonical unit, and commits through
the same revision/idempotency transaction as other gameplay commands.

`UnitRenamePopup` is the centralized mutation point used by the world-screen
unit label, empire overview, and promotion picker. It now delegates to
`WorldMapHolder.renameUnit`: local, hotseat, saved-game, and legacy modes retain
their synchronous behavior, while an explicitly opened v3 game submits the
typed command and does not alter local `GameInfo`. The saved city default-
promotion toggle is hidden for v3 until its separate typed command exists.

Projection v13 adds the owning unit's nullable `instanceName`. Visible foreign
units always receive null, so private custom names do not become a hidden-state
side channel. The v13 Rust/Kotlin closed fixture and canonical leak test enforce
that boundary.

Verification on 2026-07-21 passed:

```text
cargo run --manifest-path authoritative-server/Cargo.toml -- --write-openapi
# regenerated api-v3.json and the checked-in parity test passed
cargo test --manifest-path authoritative-server/Cargo.toml --all-targets
# 37 active library and 7 HTTP/OpenAPI tests passed; 8 DB tests gated without a URL
cargo clippy --manifest-path authoritative-server/Cargo.toml --all-targets --all-features -- -D warnings
# passed
UNCIV_V3_DATABASE_URL=postgres://unciv:unciv@127.0.0.1:55486/unciv \
  cargo test --manifest-path authoritative-server/Cargo.toml \
  postgres::integration_tests -- --ignored --test-threads=1
# all 8 PostgreSQL integration tests passed
.\gradlew.bat :server:test :tests:test --no-daemon --no-build-cache
# 843 shared and 4 server tests passed; zero failures/errors; 13 intentional shared skips
```

The database lane used only
`postgres:19beta2-alpine@sha256:bc62313e826eb44d5f608425b7665962b72820e686da017799e906604bfeb8a5`.
The disposable container was verified by exact name and image, then removed.
All Rust sources remain below 800 lines and `main.rs` remains a six-line entry
point; the largest source is `postgres/commands.rs` at 729 lines.

## Authoritative saved city promotion preferences and projection v14

The promotion picker's “Default promotions” option and the city construction
screen's “Use default promotions” toggle now remain fully server-owned in v3.
`PromoteUnit` carries only a boolean request to save the post-promotion result;
the worker requires the canonical unit to occupy an owned non-puppet city and
clones the canonical promotion state into that city atomically with the
promotion. The client never supplies saved promotions, XP, prerequisites, or
promotion counts.

Subsequent toggles use the closed
`SetCityUnitPromotionPreference(cityId, baseUnitName, enabled)` command. The
worker derives the actor from membership, requires the canonical current turn,
owned non-puppet city, real base unit, and an existing server-saved promotion
entry. Thus a client cannot invent a default promotion set or enable one that
was never canonically captured.

Projection v14 adds sorted owning-city preference summaries containing the base
unit name, enabled flag, and server-saved promotion names. These are nested only
under `ownCities`; no foreign city/private preference structure exists in the
closed projection. The promotion picker and city checkbox preserve their local
behavior outside explicitly opened v3 games.

Verification on 2026-07-21 passed:

```text
cargo run --manifest-path authoritative-server/Cargo.toml -- --write-openapi
# regenerated api-v3.json and the checked-in parity test passed
cargo test --manifest-path authoritative-server/Cargo.toml --all-targets
# 38 active library and 7 HTTP/OpenAPI tests passed; 8 DB tests gated without a URL
cargo clippy --manifest-path authoritative-server/Cargo.toml --all-targets --all-features -- -D warnings
# passed
UNCIV_V3_DATABASE_URL=postgres://unciv:unciv@127.0.0.1:55487/unciv \
  cargo test --manifest-path authoritative-server/Cargo.toml \
  postgres::integration_tests -- --ignored --test-threads=1
# all 8 PostgreSQL integration tests passed
.\gradlew.bat :server:test :tests:test --no-daemon --no-build-cache
# 846 shared and 4 server tests passed; zero failures/errors; 13 intentional shared skips
```

The database lane used only
`postgres:19beta2-alpine@sha256:bc62313e826eb44d5f608425b7665962b72820e686da017799e906604bfeb8a5`.
The disposable container was verified by exact name and image, then removed.

## Authoritative tile improvement orders and projection v15

The normal worker improvement picker now submits the closed
`SetTileImprovementOrder(unitId, improvementName?, queuedImprovementName?)`
command for explicitly opened v3 games. A null primary name means cancellation;
the client cannot supply a tile coordinate, actor, civilization, build time,
resource cost, movement amount, or legality result. Repair uses the same command
but remains a distinct server-validated ruleset operation. Local, hotseat,
legacy multiplayer, and headless AI/worker automation retain the existing
synchronous Kotlin behavior.

The Kotlin worker derives the owned unit and its canonical current tile, then
validates current turn, movement, city-center and one-time-improvement guards,
pinned-ruleset availability, builder capability, technology, resources,
terrain legality, and repair state. The optional second improvement is accepted
only as the canonically legal follow-up to a terrain-removal order; both build
durations and all stockpile effects come from the server-side engine. Starting,
replacing, cancelling, and repairing therefore commit through PostgreSQL CAS,
idempotency, immutable revision, and outbox handling like every other v3
command.

Projection v15 adds the ordered improvement queue and server-derived remaining
turns to owning-unit projections. Visible foreign units receive an empty queue,
which is enforced by the shared closed Rust/Kotlin fixture. Pillage and the
destination/path-based road-connection order remain explicit coverage gaps and
were not folded into this same-tile command.

Verification on 2026-07-21 passed:

```text
cargo run --manifest-path authoritative-server/Cargo.toml -- --write-openapi
# regenerated api-v3.json and the checked-in parity test passed
cargo test --manifest-path authoritative-server/Cargo.toml --all-targets
# 39 active library and 7 HTTP/OpenAPI tests passed; 8 DB tests gated without a URL
cargo clippy --manifest-path authoritative-server/Cargo.toml --all-targets --all-features -- -D warnings
# passed
UNCIV_V3_DATABASE_URL=postgres://unciv:unciv@127.0.0.1:55479/unciv \
  cargo test --manifest-path authoritative-server/Cargo.toml \
  postgres::integration_tests -- --ignored --test-threads=1
# all 8 PostgreSQL integration tests passed
.\gradlew.bat :server:test :tests:test --no-daemon --no-build-cache
# 849 shared and 4 server tests passed; zero failures/errors; 13 intentional shared skips
```

The database lane used only
`postgres:19beta2-alpine@sha256:bc62313e826eb44d5f608425b7665962b72820e686da017799e906604bfeb8a5`.
The disposable container was verified by exact name and image, then removed.
Every Rust source remains below 800 lines; the largest is
`postgres/commands.rs` at 729 lines, while `main.rs` remains a six-line entry
point.

## Authoritative road-connection orders and projection v16

Road destination selection now crosses API v3 through nullable-destination
`SetRoadConnectionOrder(unitId, destinationX?, destinationY?)`. Both null means
cancel; both coordinates present mean start or replace. The client supplies no
actor, civilization, road tier, movement result, improvement duration, or path.
Rust rejects half-present coordinates before worker execution, while the Kotlin
worker derives the membership civilization, owned unit, current turn, available
road technology, matching builder unique, canonical destination, and complete
path.

The worker stores its computed path before calling shared `RoadToAutomation`.
That keeps headless execution independent of a local `UncivGame` pathfinding
setting and makes later turns replay the server-selected route. Immediate
movement, road repair, and road construction also run server-side. The existing
Stop Automation control sends the same command with a null destination for an
active road order and uses shared cleanup. A corrected A* preview now requests
the clicked destination rather than the unit's current tile. Single-player,
hotseat, legacy multiplayer, and server-owned AI retain their shared Kotlin
paths.

Projection v16 exposes the destination and ordered road path only on the owning
unit. Visible foreign units receive null destination coordinates and an empty
path; the shared closed Rust/Kotlin fixture and canonical projection test enforce
that private-order boundary. Pillage remains the next worker-action gap.

Verification on 2026-07-21 passed:

```text
cargo run --manifest-path authoritative-server/Cargo.toml -- --write-openapi
# regenerated api-v3.json and checked-in parity passed
cargo test --manifest-path authoritative-server/Cargo.toml --all-targets
# 40 active library and 7 HTTP/OpenAPI tests passed; 8 DB tests gated without a URL
cargo clippy --manifest-path authoritative-server/Cargo.toml --all-targets --all-features -- -D warnings
# passed
UNCIV_V3_DATABASE_URL=postgres://unciv:unciv@127.0.0.1:55480/unciv \
  cargo test --manifest-path authoritative-server/Cargo.toml \
  postgres::integration_tests -- --ignored --test-threads=1
# all 8 PostgreSQL integration tests passed
.\gradlew.bat :server:test :tests:test --no-daemon --no-build-cache
# 853 shared and 4 server tests passed; zero failures/errors; 13 intentional shared skips
```

The database lane used only
`postgres:19beta2-alpine@sha256:bc62313e826eb44d5f608425b7665962b72820e686da017799e906604bfeb8a5`.
The disposable container was verified by exact name and image, then removed.

## Authoritative pillage command and projection v17

Direct multiplayer pillaging now uses the closed `PillageTile(unitId)` command.
The authenticated membership supplies the civilization, and the Kotlin worker
loads canonical state before deriving the unit's current tile, improvement or
road target, legality, deterministic loot, recipient city, healing, movement
cost, resource changes, notifications, and destroyed-on-pillage behavior. The
client cannot submit coordinates, a target type, loot, health, RNG, or an actor.

The former UI-owned mutation was extracted into the focused shared
`UnitPillage` domain service. Local play, AI automation, battle auto-pillage,
and the authoritative worker now execute that one implementation. Opened API-v3
games submit through the session and command bus; single-player, hotseat, and
legacy multiplayer continue synchronously through the same domain service.

Projection v17 adds improvement, road, and pillage state for currently visible
tiles. Explored tiles outside current visibility receive null values for all
four fields, preventing a refresh from revealing unseen map changes.

Verification for this slice:

- `cargo run --quiet -- --write-openapi`: generated contract updated.
- `cargo test --all-targets`: 41 Rust library tests passed, 8 explicit
  PostgreSQL tests skipped pending the required database URL, and 7 HTTP tests
  passed.
- Focused Gradle authoritative engine, projection contract, command-bus, and
  session tests passed, including deterministic hashes, foreign/out-of-turn and
  invalid-target rejection, projection reconciliation, and explicit-open
  routing.

Final gates passed:

- `cargo fmt --all -- --check` and
  `cargo clippy --all-targets --all-features -- -D warnings`: passed.
- `./gradlew :server:test :tests:test --no-daemon --no-build-cache`: passed;
  857 shared JVM tests completed with 13 intentional skips, plus 4 server tests.
- Exact PostgreSQL image
  `postgres:19beta2-alpine@sha256:bc62313e826eb44d5f608425b7665962b72820e686da017799e906604bfeb8a5`:
  all 8 serialized ignored integration tests passed, and the dedicated test
  container was removed afterward.
- `git diff --check`: passed. Every Rust source remains below 800 lines; the
  largest is `postgres/commands.rs` at 729 lines, and `main.rs` remains six
  lines.

## Authoritative city founding

API-v3 city founding now uses `FoundCity(unitId)`. The request contains no
coordinate, city name or ID, puppet/capital flag, population, construction,
cost, actor, or outcome. Membership supplies the civilization, and the private
Kotlin worker derives the unit's canonical current tile and applies the shared
settlement rules.

The reusable unit-action modifier executor was moved out of the world-screen
package into the focused, headless-safe `UnitActionModifierEffects` service.
`UnitCityFounding` now combines those canonical costs/limited-use/consumption
rules with the existing `CityFounder`. Human local play, server-owned AI, and
the authoritative worker therefore share city naming and stable ID generation,
capital/puppet behavior, settlement-distance and territory checks, starting
population/buildings, proximity and promise flags, triggered uniques, and unit
consumption. UI-only tutorial and confirmation behavior remains in the UI.

Opened authoritative games submit through the session and command bus; local,
hotseat, and legacy games continue synchronously. The accepted projection drops
the consumed founder and adds the server-generated city identity and visible
city state.

Verification:

- Deterministic repeated execution produced the same canonical hash and stable
  city ID; projection, foreign actor, out-of-turn owner, non-founder, closed
  Rust/Kotlin wire-shape, and explicit-open client-routing tests passed.
- `cargo test --all-targets`: 42 library tests passed, 8 explicit PostgreSQL
  tests skipped pending the database lane, and all 7 HTTP/OpenAPI tests passed.
- `cargo fmt --all -- --check` and
  `cargo clippy --all-targets --all-features -- -D warnings`: passed.
- `./gradlew :server:test :tests:test --no-daemon --no-build-cache`: passed;
  861 shared JVM tests completed with 13 intentional skips and zero failures,
  plus 4 worker protocol tests.
- All 8 PostgreSQL integration tests passed serially against exact image
  `postgres:19beta2-alpine@sha256:bc62313e826eb44d5f608425b7665962b72820e686da017799e906604bfeb8a5`.
The exact-name disposable container was removed afterward.

## Authoritative paradrop command

API-v3 paradrops now use the closed
`ParadropUnit(unitId, destinationX, destinationY)` command. Coordinates are
player intent only: authenticated membership supplies the civilization and the
private Kotlin worker derives the owned stable unit, current turn, canonical
paradrop uniques and conditionals, maximum range, current visibility, terrain
filter, passability, occupancy, movement availability, movement cost, and
post-drop attack state. The request contains no actor, range, visibility claim,
movement cost, attack result, path, or replacement state.

`UnitParadrop` is the focused shared rules boundary. It rebuilds derived
destination filters from canonical uniques and performs the existing Kotlin
movement mutation. Local play continues through the established UI path, while
opened authoritative games translate the selected destination into the typed
command and reconcile the returned projection. The previous behavior that sent
a prepared paradrop through the ordinary move endpoint has been removed.

Verification on 2026-07-21:

- Repeated execution from the same snapshot produced the same canonical hash,
  stable unit destination, consumed movement, and one recorded attack. Foreign
  actors, out-of-turn actors, and out-of-range destinations were rejected.
- Command-bus tests prove the client transmits only the stable unit ID and a
  currently visible destination; Rust wire-shape and generated OpenAPI tests
  prove the closed cross-language contract.
- `cargo test --all-targets`: 43 active Rust library tests and all 7 HTTP/OpenAPI
  tests passed; 8 database tests were explicitly gated from this invocation.
- All 8 PostgreSQL integration tests passed serially against only
  `postgres:19beta2-alpine@sha256:bc62313e826eb44d5f608425b7665962b72820e686da017799e906604bfeb8a5`.
  The exact-name disposable container was removed after the run.
- `./gradlew :server:test :tests:test --no-daemon --no-build-cache`: 864 shared
  JVM tests completed with 13 intentional skips and zero failures, plus all 4
  worker protocol tests.
- `cargo fmt --all -- --check`, warnings-as-errors `cargo clippy`, and
  `git diff --check` passed. `main.rs` and `lib.rs` remain thin; the largest Rust
  source is 729 lines and every Rust source remains below 800 lines.

Direct combat remains the next major authoritative unit-action gap.

## Authoritative normal unit attacks

Normal API-v3 unit combat now uses the closed
`AttackWithUnit(unitId, targetX, targetY)` command. The coordinate identifies
player intent, not a trusted defender or outcome. Authenticated membership
supplies the civilization, and the Kotlin worker reloads canonical state before
deriving ownership, current turn, attack availability, war status, visibility,
target combatant, reachable attack-from tiles, movement and siege setup.

`UnitAttackExecutor` orders otherwise-equivalent attack-from choices by
remaining movement and canonical coordinates, then delegates the complete
mutation to the existing Kotlin `Battle` engine. The server therefore owns
combat RNG, damage, interception, withdrawal, civilian and military capture,
unit destruction, city defeat/conquest for unit attacks, post-combat movement,
XP, promotions, triggered uniques, diplomacy effects, pillage interactions,
notifications, and stable state hashing. Rust implements no battle rules.

Both right-click attacks and the battle-panel attack control submit the typed
command for explicitly opened authoritative games before any local mutation.
Single-player, hotseat, legacy multiplayer, and server-owned AI retain the
existing local/shared Kotlin execution paths. City-originated bombardment and
empty-tile nuclear targeting remain separate explicit command gaps; they are
not falsely represented as completed by this unit-attack command.

Verification on 2026-07-21:

- Repeated unit combat from the same canonical snapshot produced the same state
  hash and projected attacker state. Foreign accounts, out-of-turn actors, and
  friendly/non-enemy targets were rejected without a successful engine result.
- Command-bus and session tests prove only explicitly opened games route the
  attack and the payload excludes paths, defender identity, damage, random
  values, movement/setup claims, actor identity, and outcomes.
- `cargo test --all-targets`: 44 active Rust library tests and all 7 HTTP/OpenAPI
  tests passed; 8 database tests were explicitly gated from that invocation.
- All 8 serialized PostgreSQL integration tests passed against only
  `postgres:19beta2-alpine@sha256:bc62313e826eb44d5f608425b7665962b72820e686da017799e906604bfeb8a5`.
  The exact-name disposable test container was removed afterward.
- `./gradlew :server:test :tests:test --no-daemon --no-build-cache`: 868 shared
  JVM tests completed with 13 intentional skips and zero failures, plus all 4
  worker protocol tests.
- Rust formatting, warnings-as-errors Clippy, generated OpenAPI parity,
  `git diff --check`, and module-size review passed. `main.rs` remains 6 lines,
  `lib.rs` remains 27, and every Rust source remains below 800 lines (largest:
  `postgres/commands.rs`, 729 lines).

City bombardment is the next combat command gap, followed by explicit nuclear
strike targeting and air-sweep handling.

## Authoritative city bombardment

API-v3 city attacks now use the closed
`BombardWithCity(cityId, targetX, targetY)` command. The coordinates express
target intent only. Authenticated membership supplies the civilization, and
the private Kotlin worker reloads canonical state before deriving city
ownership, current turn, attack availability, range, line of sight, visibility,
war status, and the target combatant.

The existing Kotlin `TargetHelper` and `Battle` engine remain the sole rules
implementation. The server therefore owns defender selection, combat RNG,
damage, destruction, XP and triggered effects, diplomacy consequences,
notifications, the city's spent-attack state, and the resulting canonical
hash. Rust remains a control plane and contains no city-combat rules.

The battle-panel attack control submits this typed command for explicitly
opened authoritative games before any local mutation. Single-player, hotseat,
legacy multiplayer, and server-owned AI continue through the shared Kotlin
rules paths. The client request contains no actor identity, defender identity,
range or visibility claims, damage, random values, or result state.

Verification on 2026-07-22:

- Repeated city bombardment from the same canonical snapshot produced the same
  canonical hash and projected damage. Foreign accounts, out-of-turn actors,
  and invalid targets were rejected.
- Command-bus and session tests prove only an owned projected city and visible
  target can be submitted, only explicitly opened games route the command, and
  the wire payload excludes server-derived rules and outcomes.
- `cargo test --all-targets`: 45 active Rust library tests and all 7
  HTTP/OpenAPI tests passed; 8 database tests were explicitly gated from that
  invocation. Generated OpenAPI parity also passed.
- All 8 serialized PostgreSQL integration tests passed against only
  `postgres:19beta2-alpine@sha256:bc62313e826eb44d5f608425b7665962b72820e686da017799e906604bfeb8a5`
  on port 55443. The exact-name disposable container was removed and verified
  absent afterward.
- `./gradlew :server:test :tests:test --no-daemon --no-build-cache`: 872 shared
  JVM tests completed with 13 intentional skips and zero failures, plus all 4
  worker protocol tests.
- Rust formatting, warnings-as-errors Clippy, `git diff --check`, NUL-byte
  scanning, and module-size review passed. `main.rs` remains 6 lines, `lib.rs`
  remains 27, and every Rust source remains below 800 lines (largest:
  `postgres/commands.rs`, 729 lines).

Explicit empty-tile nuclear strike targeting and air-sweep handling are the
next combat command gaps.

## Authoritative nuclear strikes

API-v3 nuclear attacks now use the closed
`LaunchNuclearStrike(unitId, targetX, targetY)` command. This intentionally
supports explored empty-tile targets: the coordinates identify player intent
and never claim a defender, blast victim, diplomatic consequence, or outcome.
Authenticated membership supplies the civilization, and the private Kotlin
worker reloads canonical state before deriving unit ownership, current turn,
nuclear capability, attack availability, exploration, range, and every
potentially affected civilization.

The focused `NuclearStrikeExecutor` delegates the mutation to the existing
Kotlin `Nuke` engine. That shared engine remains the sole owner of interception,
war declarations, blast radius, strategic-resource modifiers, deterministic
damage and population RNG, unit and city destruction, fallout and pillaging,
notifications, diplomatic penalties, attack consumption, and self-destruction.
Rust contains no nuclear rules; it only authenticates, dispatches, and commits
the resulting revision atomically.

The nuclear battle-panel action submits this command for explicitly opened
authoritative games before any local mutation or animation. Authoritative UI
does not use hidden blast-state guesses to veto the intent; the server decides.
Single-player, hotseat, legacy multiplayer, and server-owned AI continue to use
the same Kotlin nuclear engine through their existing paths.

Verification on 2026-07-22:

- Repeated strikes against an explored empty tile produced the same canonical
  hash, consumed the missile, and applied identical canonical blast damage.
  Foreign accounts, out-of-turn actors, ordinary units, and unexplored targets
  were rejected.
- Command-bus and session tests prove only an owned projected unit and explored
  coordinate are submitted, only explicitly opened games route the command,
  and the payload excludes range, victims, blast state, random values, actor
  identity, and outcomes.
- `cargo test --all-targets`: 46 active Rust library tests and all 7
  HTTP/OpenAPI tests passed; 8 database tests were explicitly gated from that
  invocation. Generated OpenAPI parity passed.
- All 8 serialized PostgreSQL integration tests passed against only
  `postgres:19beta2-alpine@sha256:bc62313e826eb44d5f608425b7665962b72820e686da017799e906604bfeb8a5`
  on port 55444. The exact-name disposable container was removed and verified
  absent afterward.
- `./gradlew :server:test :tests:test --no-daemon --no-build-cache`: 876 shared
  JVM tests completed with 13 intentional skips and zero failures, plus all 4
  worker protocol tests.
- Rust formatting, warnings-as-errors Clippy, `git diff --check`, NUL-byte
  scanning, and module-size review passed. `main.rs` remains 6 lines, `lib.rs`
  remains 27, and every Rust source remains below 800 lines (largest:
  `postgres/commands.rs`, 729 lines).

Air-sweep targeting is the next combat command gap.

## Authoritative air sweeps

API-v3 air sweeps now use the closed `AirSweep(unitId, targetX, targetY)`
command. The coordinates identify intent only. Authenticated membership
supplies the civilization, and the private Kotlin worker reloads canonical
state before deriving ownership, current turn, the `CanAirsweep` capability,
attack availability, canonical range, potential enemy interceptors, and the
resulting combat.

The focused `AirSweepExecutor` establishes the transient sweep posture only
inside canonical execution and delegates to the existing Kotlin
`AirInterception` engine. Interceptor filtering and priority, attack and
movement consumption, air-versus-air damage, XP, destruction, notifications,
and posture cleanup are server-owned. Equal-priority interceptor shuffling now
uses the canonical state-based random stream instead of process-global
`Random.Default`, making retries and snapshot replays deterministic while
preserving randomized civilization selection.

The battle-panel control submits this command for explicitly opened
authoritative games before any local mutation. Authoritative UI does not veto
the intent using client-derived eligibility or range; the server decides.
Single-player, hotseat, legacy multiplayer, and server-owned AI retain their
existing paths through the same Kotlin engine.

Verification on 2026-07-22:

- Repeated air sweeps from the same canonical snapshot selected and resolved
  the same interceptor, produced the same canonical hash and projected health,
  consumed one attack, and cleared the transient sweep posture. Foreign
  accounts, out-of-turn actors, ordinary units, same-tile targets, and
  out-of-range targets were rejected.
- Command-bus and session tests prove only an owned projected unit and target
  coordinate are submitted, only explicitly opened games route the command,
  and the payload excludes interceptor identity, range claims, random values,
  damage, actor identity, and outcomes.
- `cargo test --all-targets`: 47 active Rust library tests and all 7
  HTTP/OpenAPI tests passed; 8 database tests were explicitly gated from that
  invocation. Generated OpenAPI parity passed.
- All 8 serialized PostgreSQL integration tests passed against only
  `postgres:19beta2-alpine@sha256:bc62313e826eb44d5f608425b7665962b72820e686da017799e906604bfeb8a5`
  on port 55445. The exact-name disposable container was removed and verified
  absent afterward.
- `./gradlew :server:test :tests:test --no-daemon --no-build-cache`: 880 shared
  JVM tests completed with 13 intentional skips and zero failures, plus all 4
  worker protocol tests.
- Rust formatting, warnings-as-errors Clippy, `git diff --check`, NUL-byte
  scanning, and module-size review passed. `main.rs` remains 6 lines, `lib.rs`
  remains 27, and every Rust source remains below 800 lines (largest:
  `postgres/commands.rs`, 729 lines).

The next milestone is a fresh command-coverage audit rather than assuming the
combat surface is complete.

## Authoritative building sales

The coverage audit identified city building sale as a reachable canonical
mutation that still ran directly in the client. API v3 now uses the closed
`SellBuilding(cityId, buildingName)` command. The client supplies identity
only; it does not claim a refund, ownership, sellability, free-building state,
puppet state, or whether the per-turn sale limit is available.

The private Kotlin worker reloads canonical state and derives authenticated
civilization ownership, current turn, building existence and sellability,
whether the building is present or free, puppet restrictions, the one-sale
limit, the canonical refund, population reassignment, city-stat refresh, and
civilization resource-cache changes through the existing game engine. The
Rust service remains a control plane and sole committer; its new API, worker,
and persistence code is split into focused `city_economy` modules rather than
expanding the existing large command modules.

The city screen submits the command before any local mutation for explicitly
opened authoritative games. Single-player, hotseat, legacy multiplayer, and
server-owned AI keep their existing Kotlin-engine path.

Verification on 2026-07-22:

- Focused engine tests prove the worker derives the 10-percent canonical
  refund, removes the building, records the per-turn limit, and rejects both
  foreign-city and puppet-city sales. The command-bus contract proves the
  request excludes refund, price, actor, and eligibility claims.
- `./gradlew :tests:test :server:test --no-daemon`: 883 shared JVM tests
  completed with 13 intentional skips and zero failures, plus all 4 worker
  protocol tests.
- `cargo test`: 48 active Rust library tests and all 7 HTTP/OpenAPI tests
  passed; generated OpenAPI parity passed. The 8 database tests were then run
  separately and all passed against only
  `postgres:19beta2-alpine@sha256:bc62313e826eb44d5f608425b7665962b72820e686da017799e906604bfeb8a5`
  on port 55444. The exact-name disposable container was removed and verified
  absent afterward.
- Rust formatting, warnings-as-errors Clippy, NUL-byte scanning, and module
  size review passed. `main.rs` remains 6 lines, `lib.rs` remains 27, and every
  Rust source remains below 800 lines (largest: `postgres/commands.rs`, 729
  lines).

The remaining audit surface is now concentrated in larger protocol families:
diplomacy and trade, religion choices and religious unit actions, diplomatic
votes and great-person choices, and city conquest governance such as annexing
or razing. These require dedicated projections and typed intent designs rather
than extending a generic mutation command.

## Authoritative ordinary city governance

API v3 now models owner-controlled city governance with the closed
`SetCityGovernance(cityId, action)` command and the actions `annex`,
`start_razing`, and `stop_razing`. This command does not cover the distinct
post-capture puppet/liberate/destroy decision, which still requires a projected
pending-decision contract because it can change ownership and diplomacy.

The private Kotlin worker resolves authenticated ownership and current turn,
then a focused `CityGovernanceExecutor` derives puppet state, the civilization's
annex restriction, capital/holy-city destruction rules, and current razing
state from the canonical snapshot. Only the worker calls the existing Kotlin
annex logic or changes canonical razing state. The city screen submits intent
before local mutation in authoritative games and preserves the legacy path for
single-player, hotseat, saves, and API-v2 multiplayer.

Player projection version 18 adds `isPuppet`, `isBeingRazed`, and the
server-derived closed `availableGovernanceActions` list to each owned city.
The shared Rust/Kotlin fixture moved to `player-projection-v18.fixture.json` and
continues to reject unknown fields. Rust API, worker, and persistence logic is
isolated in focused `city_governance` modules.

Verification on 2026-07-22:

- Focused engine tests cover the canonical annex, start-razing, and
  stop-razing lifecycle and its projected actions, plus invalid-state,
  foreign-ownership, and out-of-turn rejection.
- Command-bus tests prove the payload excludes actor and rule claims. Session
  tests prove commands route only for explicitly opened games and a lost
  response retries with the same idempotency key.
- `./gradlew :tests:test :server:test --no-daemon`: 887 shared JVM tests
  completed with 13 intentional skips and zero failures, plus all 4 worker
  protocol tests.
- `cargo test`: 49 active Rust library tests and all 7 HTTP/OpenAPI tests
  passed, including generated OpenAPI parity and the version-18 projection
  fixture.
- All 8 serialized PostgreSQL integration tests passed against only
  `postgres:19beta2-alpine@sha256:bc62313e826eb44d5f608425b7665962b72820e686da017799e906604bfeb8a5`
  on port 55443. The exact-name disposable container was removed and verified
  absent afterward.
- Rust formatting, warnings-as-errors Clippy, `git diff --check`, NUL-byte
  scanning, and module-size review passed. `main.rs` remains 6 lines, `lib.rs`
  remains 27, and every Rust source remains below 800 lines (largest:
  `postgres/commands.rs`, 729 lines).

Post-capture city disposition is the next city-governance gap. It needs an
explicit player-scoped pending decision with server-derived available choices
before the client can safely submit puppet, annex, raze, liberate, or destroy.

## Authoritative post-capture city disposition

API v3 now preserves human post-capture decisions in headless authoritative
execution instead of falling through to AI city-conquest automation. A
captured city remains represented by the canonical `CityConquered` pending
alert until the authenticated current-turn player submits the closed
`ResolveCityDisposition(cityId, action)` command. The available actions are
`liberate`, `annex`, `puppet`, `raze`, and `destroy`, filtered from canonical
founder, ownership, one-city-challenge, annex-restriction, capital, holy-city,
and destruction state.

Player projection version 19 adds the player-scoped
`pendingCityDispositions` list with only city ID, display name, and the closed
available-action list. The private Kotlin worker revalidates the exact pending
decision, authenticated civilization assignment, current turn, and selected
action, then delegates ownership transfer, liberation, annexation, puppeting,
razing, or destruction to the existing Kotlin engine. Only after successful
execution does it consume the alert. Rust remains the public control plane and
sole revisioned committer; API, worker, and persistence routing live in focused
`city_disposition` modules.

The conquest popup submits only city ID and the selected closed action for an
explicitly opened authoritative game. It performs no local canonical mutation,
keeps ambiguous failures retryable with the same command ID, and closes only
after an accepted commit or stale-state refresh. Single-player, hotseat,
saved-game, legacy multiplayer, and server-owned AI behavior retain their
existing Kotlin-engine paths.

Verification on 2026-07-22:

- Focused engine tests prove pending decisions are player-projected, canonical
  annex resolution consumes the alert only after success, and replay after
  consumption is rejected. Command-bus tests prove the payload excludes actor,
  original-owner, annex, and raze claims. Session tests prove commands route
  only for explicitly opened games and a lost response retries the same
  idempotency key.
- `./gradlew :tests:test :server:test --no-daemon`: 890 shared JVM tests
  completed with 13 intentional skips and zero failures, plus all 4 worker
  protocol tests.
- `cargo test --all-features`: 50 active Rust library tests and all 7
  HTTP/OpenAPI tests passed; generated OpenAPI parity passed with projection
  version 19.
- All 8 serialized PostgreSQL integration tests passed against only
  `postgres:19beta2-alpine@sha256:bc62313e826eb44d5f608425b7665962b72820e686da017799e906604bfeb8a5`
  on port 55442. The exact-name disposable container was removed and verified
  absent afterward.
- Rust formatting, warnings-as-errors Clippy, `git diff --check`, NUL-byte
  scanning, and module-size review passed. `main.rs` remains 6 lines, `lib.rs`
  remains 27, and every Rust source remains below 800 lines (largest:
  `postgres/commands.rs`, 729 lines).

The next coverage audit should continue across diplomacy and trade, religion,
diplomatic votes, and great-person choices without widening this command into
a generic client-authored mutation surface.

## Authoritative diplomatic victory voting

The coverage audit found that `DiplomaticVotePickerScreen` still called
`Civilization.diplomaticVoteForCiv()` directly, allowing a client to write the
canonical vote map. API v3 now uses the closed
`CastDiplomaticVote(candidateCivilizationId?)` command. A null candidate means
abstention; the request contains no voter identity, known-civilization claims,
alive/major-civilization claims, vote counts, or result calculation.

Player projection version 20 adds the player-scoped
`diplomaticVoteCandidates` allowlist. It is empty unless the actor has a
canonical pending vote and otherwise contains only known, alive major
civilizations other than the voter. The existing `cast_diplomatic_vote`
pending-turn action distinguishes a real abstention opportunity from absence
of a vote.

The private Kotlin worker reloads canonical state and validates authenticated
civilization assignment, current turn, vote timing, spectator state, duplicate
voting, and the projected candidate allowlist before calling the existing
Kotlin vote mutation. AI players continue to vote inside server-owned turn
automation using deterministic state-based randomness. Rust remains the public
control plane and sole revisioned committer; its API, worker, and persistence
routing is isolated in focused `diplomacy` modules.

The picker submits the typed command before any local mutation for explicitly
opened authoritative games. Ambiguous failures remain on the same command ID
for safe retry. Single-player, hotseat, saved-game, legacy multiplayer, and AI
paths retain their existing Kotlin-engine behavior.

Verification on 2026-07-22:

- Focused engine tests cover the projected candidate allowlist, successful
  canonical voting, duplicate rejection, unknown-candidate rejection, foreign
  account rejection, and out-of-turn rejection. Command-bus tests prove the
  payload contains only the optional candidate, while session tests prove an
  explicitly opened game is required and a lost response retries the same
  idempotency key.
- `./gradlew :tests:test :server:test --no-daemon`: 894 shared JVM tests
  completed with 13 intentional skips and zero failures, plus all 4 worker
  protocol tests.
- `cargo test --all-features`: 51 active Rust library tests and all 7
  HTTP/OpenAPI tests passed, including generated OpenAPI parity and the shared
  projection-v20 fixture.
- All 8 serialized PostgreSQL integration tests passed against only
  `postgres:19beta2-alpine@sha256:bc62313e826eb44d5f608425b7665962b72820e686da017799e906604bfeb8a5`
  on port 55441. The exact-name disposable container was removed and verified
  absent afterward.
- Rust formatting, warnings-as-errors Clippy, `git diff --check`, NUL-byte
  scanning, and module-size review passed. `main.rs` remains 6 lines, `lib.rs`
  remains 27, and every Rust source remains below 800 lines.

Trade negotiation, religion choices and religious unit actions, and
great-person selection remain distinct command families for the continuing
coverage audit.

## Authoritative free great-person selection

The coverage audit found that `GreatPersonPickerScreen` directly spawned a
unit, decremented `freeGreatPeople`, and changed the Maya long-count pool. API
v3 now uses the closed `ChooseGreatPerson(unitName)` command. The request does
not claim actor identity, capital or placement location, whether the choice is
Maya-restricted, remaining free choices, or placement success.

Player projection version 21 adds the `pick_great_person` pending-turn action
and the player-scoped `selectableGreatPeople` allowlist. A focused
`GreatPersonChoiceExecutor` derives the choice set from the pinned ruleset,
civilization replacements, game settings, free-choice count, city presence,
and Maya's remaining long-count pool. Projection and worker execution share
that implementation.

The private Kotlin worker revalidates authenticated civilization assignment,
current turn, the pending choice, and selected unit. It then uses the existing
state-seeded Kotlin unit-placement engine, consumes the free-choice and Maya
counters only after placement succeeds, and retains the pending choice if no
canonical placement is possible. AI players continue to select and place free
great people inside server-owned deterministic turn automation. Rust remains
the public control plane and sole revisioned committer, with API, worker, and
persistence routing split into focused `great_people` modules.

The picker submits only the projected unit name for an explicitly opened
authoritative game and performs no local spawn or counter mutation. Ambiguous
responses retain the same command ID. Single-player, hotseat, saved-game,
legacy multiplayer, and server-owned AI paths retain their existing Kotlin
engine behavior.

Verification on 2026-07-22:

- Focused engine tests cover projected choices, successful state-seeded unit
  placement, post-success counter consumption, replay rejection, Maya pool
  restriction, foreign-account rejection, out-of-turn rejection, and
  fail-closed unplaceable naval choices that preserve the pending counter.
  Command-bus tests prove only the unit name crosses the boundary; session
  tests prove an opened game is required and lost responses retry the same
  idempotency key.
- `./gradlew :tests:test :server:test --no-daemon`: 898 shared JVM tests
  completed with 13 intentional skips and zero failures, plus all 4 worker
  protocol tests.
- `cargo test --all-features`: 52 active Rust library tests and all 7
  HTTP/OpenAPI tests passed, including generated OpenAPI parity and the shared
  projection-v21 fixture.
- All 8 serialized PostgreSQL integration tests passed against only
  `postgres:19beta2-alpine@sha256:bc62313e826eb44d5f608425b7665962b72820e686da017799e906604bfeb8a5`
  on port 55440. The exact-name disposable container was removed and verified
  absent afterward.
- Rust formatting, warnings-as-errors Clippy, `git diff --check`, NUL-byte
  scanning, and module-size review passed. `main.rs` remains 6 lines, `lib.rs`
  remains 27, and every Rust source remains below 800 lines.

Trade negotiation and religion choices/actions remain the major uncovered
player-command families for the continuing coverage audit.

## Authoritative religious-unit actions

API v3 now routes prophet founding/enhancement, missionary spreading, and
inquisitor heresy removal through the closed
`UseReligiousUnit(unitId, action)` command. Its action enum contains only
`found_religion`, `enhance_religion`, `spread_religion`, and `remove_heresy`.
The request cannot claim a target city,
religion, pressure amount, remaining charges, diplomatic effect, actor, or
outcome.

Player projection version 22 adds an `availableReligiousActions` allowlist to
each owned projected unit. Visible foreign units always expose an empty list.
A focused `ReligiousUnitActionExecutor` shares the existing Kotlin rule path
used by server-owned AI: the worker derives the unit's canonical tile and city,
religion, followers, pressure, conversion notification, diplomacy flag,
action-side effects, charge consumption, and unit destruction.

The private worker revalidates authenticated civilization ownership, current
turn, unit identity, and current rule eligibility. Rust remains a rule-free
public control plane and sole revisioned committer, with focused `religion`
modules for API routing, persistence, and worker transport. For explicitly
opened v3 games the unit-action table submits the typed command and returns
before the legacy local action lambda can run. Single-player, hotseat, saves,
legacy multiplayer, and server AI keep their existing Kotlin behavior.

Verification on 2026-07-22:

- Projection and command-bus tests cover the owned action allowlist, foreign
  omission, allowlist binding, and a payload that excludes target, pressure,
  charges, religion, and actor claims. The Rust closed-contract test rejects
  all such forged fields. The session test proves an explicitly opened game is
  required and a lost response retries the same idempotency key.
- `./gradlew :tests:test :server:test --no-daemon`: 904 JVM tests completed
  with 13 intentional skips and zero failures, including all worker protocol
  tests.
- `cargo test`: 53 active Rust library tests and all 7 HTTP/OpenAPI tests
  passed; 8 database-only tests were intentionally skipped in this lane.
  Generated OpenAPI parity and the shared projection-v22 fixture passed.
- All 8 serialized PostgreSQL integration tests passed against only
  `postgres:19beta2-alpine@sha256:bc62313e826eb44d5f608425b7665962b72820e686da017799e906604bfeb8a5`
  on port 55439. The exact-name disposable container was removed and verified
  absent afterward.
- Rust formatting, warnings-as-errors Clippy, and module-size review passed.
  `main.rs` remains 6 lines, `lib.rs` remains 27, and the largest Rust source
  is 745 lines.

Trade negotiation and multi-step religion belief choices remain the major
uncovered player-command families for the continuing coverage audit.

## Authoritative religion and belief choices

API v3 now covers pantheon founding/expansion, religion founding, religion
enhancement, and free-belief reform with the closed
`ChooseReligiousBeliefs(beliefNames, religionIconName?, religionDisplayName?)`
command. The request cannot claim belief types, slot counts, availability,
faith cost, free-belief grants, holy-city identity, actor identity, triggers,
or resulting religion state.

Player projection version 23 adds one player-scoped `religionChoice` object.
It contains the exact required belief-slot types, conditionally allowed and
globally unclaimed belief names and types, unused religion icons when founding,
and whether founding identity is required. A focused
`ReligionChoiceExecutor` derives this projection and re-derives it during
worker execution, so stale or forged selections fail closed.

The Kotlin worker first owns prophet expenditure through the closed religious
unit action, deriving the founding city and pending choice state. It then
validates authenticated civilization assignment, current turn, pending choice
mode, exact and wildcard slot coverage, distinct belief
selection, global belief uniqueness, civilization conditionals, icon
availability, reserved and duplicate names, and whether identity fields are
permitted. It then calls the existing Kotlin religion manager to charge faith
or consume free beliefs, establish the holy city, apply pressure and beliefs,
run triggers, update stats, and advance the religion state. All AI religious
choices remain in server-owned deterministic turn automation.

For explicitly opened v3 games both belief picker variants submit the typed
command and return before their legacy local mutation callbacks. Ambiguous
responses retain the same command ID. Single-player, hotseat, saves, and
legacy multiplayer retain their existing picker and Kotlin-engine behavior.
Rust remains rule-free and the only revisioned committer; religion API,
persistence, and worker transport remain in the focused `religion` modules.

Verification on 2026-07-22:

- Focused engine tests cover projected and committed pantheon selection, full
  religion founding identity, belief slots, holy-city derivation, unavailable
  selections, foreign-account rejection, and out-of-turn rejection.
  Projection and command-bus tests cover the v23 closed view and request shape;
  the session test proves explicit-open routing and same-key retry after a lost
  response. Rust rejects forged actor, slot, grant, cost, and holy-city fields.
- `./gradlew :tests:test :server:test --no-daemon`: 909 JVM tests completed
  with 13 intentional skips and zero failures, including all worker protocol
  tests.
- `cargo test`: 54 active Rust library tests and all 7 HTTP/OpenAPI tests
  passed; 8 database-only tests were intentionally skipped in this lane.
  Generated OpenAPI parity and the shared projection-v23 fixture passed.
- All 8 serialized PostgreSQL integration tests passed against only
  `postgres:19beta2-alpine@sha256:bc62313e826eb44d5f608425b7665962b72820e686da017799e906604bfeb8a5`
  on port 55438. The exact-name disposable container was removed and verified
  absent afterward.
- Rust formatting and warnings-as-errors Clippy passed. `main.rs` remains 6
  lines, `lib.rs` remains 28, and the largest Rust source is 763 lines.

Trade negotiation remains the major uncovered player-command family for the
continuing coverage audit.

## Authoritative bilateral trade negotiation

API v3 now covers offering, retracting, accepting, declining, and atomically
countering bilateral major-civilization trades. Player projection version 24
contains each known eligible partner's server-derived available offers and the
authenticated player's incoming requests. Incoming requests use stable opaque
SHA-256 identifiers derived by the Kotlin worker; clients never upload a
`TradeRequest`, accepted transfer result, evaluation, actor, or `GameInfo`.

The focused `TradeCommandExecutor` re-derives partner eligibility, current
turn, request ownership, offer kind/name, canonical maximum amount, duration,
and current treaty/resource/city/war availability. Acceptance invokes the
existing Kotlin `TradeLogic`, so gold, resources, cities, treaties, diplomacy,
notifications, and symmetric active-trade state use the same rules as
single-player and server-owned AI. Counteroffers consume the referenced
incoming request and create the reverse request in one canonical revision.

For explicitly opened v3 games, `TradeTable` and `TradePopup` submit through
the authoritative session and return before every legacy add/remove/accept or
decline mutation. Lost responses remain retryable with the original command
ID through the common command bus. Local games, hotseat, saves, and legacy
multiplayer retain their existing paths. Rust remains rule-free and uses
focused `api/trade.rs`, `postgres/trade.rs`, and `worker/trade.rs` modules.

Verification on 2026-07-22:

- Focused Kotlin engine tests prove canonical projection and acceptance,
  symmetric active trades, atomic counteroffers, forged-amount rejection,
  foreign-account rejection, and consumed-request replay rejection.
- Rust closed-contract tests reject extra actor, evaluation, accepted-result,
  canonical-game, and client-supplied trade payloads on decision commands.
- `cargo test` passed 57 active library tests and all 7 HTTP/OpenAPI tests; 8
  database-only tests were intentionally skipped in this lane. Generated
  OpenAPI parity and the shared projection-v24 fixture passed.
- `cargo fmt` and warnings-as-errors `cargo clippy --all-targets` passed.
- `./gradlew :tests:test :server:test --no-daemon` completed 912 JVM tests
  with 13 intentional skips and zero failures.
- All 8 serialized PostgreSQL integration tests passed against only
  `postgres:19beta2-alpine@sha256:bc62313e826eb44d5f608425b7665962b72820e686da017799e906604bfeb8a5`
  on port 55437. The exact-name disposable container was removed and verified
  absent afterward.
- `git diff --check` and module-size review passed. `main.rs` remains 6 lines,
  `lib.rs` remains 28, and the largest Rust source is 790 lines.

The broader command-coverage audit continues with diplomacy, city-state,
espionage, and remaining special-action families.

## Authoritative major-civilization diplomacy

API v3 now covers direct war declarations, denunciations, friendship offers,
the four diplomatic demands, and accept/refuse responses to friendship and
demand prompts. Peace continues through the already-authoritative bilateral
trade path. Projection version 25 exposes only known eligible major partners,
server-derived action allowlists, and the authenticated civilization's
actionable prompts under stable opaque SHA-256 identifiers.

The focused Kotlin `DiplomacyCommandExecutor` re-derives the authenticated
actor, current turn, counterpart eligibility, treaty cooldowns, relationship
rules, pending alert identity and type, and demand availability before calling
the existing diplomacy engine. Clients cannot provide a war reason, duration,
modifier, acceptance result, actor identity, or canonical state. All AI
diplomacy remains inside server-owned turn automation.

For explicitly opened v3 games, the major-civilization diplomacy screen and
actionable friendship, demand, and denouncement alert choices submit typed
commands and return before their legacy mutations. Single-player, hotseat,
saves, legacy multiplayer, and local AI paths retain their existing Kotlin
behavior. City-state protection and allied-war prompts remain tracked under
the separate city-state milestone.

Verification on 2026-07-22:

- Focused Kotlin engine tests prove canonical war, denunciation, friendship,
  demand, and prompt response behavior, including forged prompt, foreign actor,
  and out-of-turn rejection. Projection, command-bus, and session tests cover
  the v25 closed view, projection-bound requests, explicit-open routing, and
  common same-idempotency-key retry behavior.
- `./gradlew :tests:test :server:test --no-daemon` completed 914 JVM tests with
  13 intentional skips and zero failures, including all worker protocol and
  existing local-game tests.
- `cargo test` passed 59 active library tests and all 7 HTTP/OpenAPI tests; 8
  database-only tests were intentionally skipped in that lane. Generated
  OpenAPI parity and the shared projection-v25 fixture passed.
- `cargo fmt` and warnings-as-errors
  `cargo clippy --all-targets --all-features -- -D warnings` passed.
- All 8 serialized PostgreSQL integration tests passed against only
  `postgres:19beta2-alpine@sha256:bc62313e826eb44d5f608425b7665962b72820e686da017799e906604bfeb8a5`
  on port 55436. The disposable `unciv-v3-diplomacy-pg19b2` container was
  removed and verified absent afterward.
- `git diff --check` and module-size review passed. `main.rs` is 6 lines,
  `lib.rs` is 28 lines, and the largest Rust source is 757 lines after moving
  shared intent types out of the worker protocol module.

The coverage audit continues with authoritative city-state interactions,
espionage, and remaining special-action families.

## Authoritative city-state gifts, protection, and tribute

API v3 now covers the core city-state interactions for gifting 250/500/1000
gold, pledging or withdrawing protection, and demanding either canonical gold
tribute or a worker. Projection version 26 exposes only known living
city-states and the authenticated player's currently legal/affordable action
allowlist; military-pressure calculations, influence internals, RNG state,
treasury outcomes, and actor identity remain server-only.

The focused Kotlin `CityStateCommandExecutor` re-derives current turn, account
assignment, contact, war state, gift denomination, treasury, protection
cooldowns, influence, tribute willingness, nearby canonical military force,
city population, recent bullying, and worker availability before invoking the
existing `CityStateFunctions`. Worker tribute continues to use the existing
state-derived server RNG. Rust transports typed intent but implements no game
rule and remains the sole revisioned committer.

For explicitly opened v3 games, gold-gift, protection, and tribute buttons
submit through the authoritative session and return before legacy mutation.
Single-player, hotseat, saves, legacy multiplayer, and local/server-owned AI
retain their existing Kotlin behavior. Improvement gifts, diplomatic marriage,
city-state peace, and protector response prompts remain separate follow-up
coverage rather than being overstated as complete.

Verification on 2026-07-22:

- Focused Kotlin tests prove projection-bound gift denominations, canonical
  treasury/influence effects, protection legality, forged-amount rejection,
  and out-of-turn rejection. Rust closed-contract tests reject claimed actor,
  willingness, influence, treasury, and outcome fields.
- `./gradlew :tests:test :server:test --no-daemon` completed 915 JVM tests with
  13 intentional skips and zero failures.
- `cargo test` passed 60 active library tests and all 7 HTTP/OpenAPI tests; 8
  database-only tests were intentionally skipped in that lane. Generated
  OpenAPI parity and the shared projection-v26 fixture passed.
- `cargo fmt` and warnings-as-errors
  `cargo clippy --all-targets --all-features -- -D warnings` passed.
- All 8 serialized PostgreSQL integration tests passed against only
  `postgres:19beta2-alpine@sha256:bc62313e826eb44d5f608425b7665962b72820e686da017799e906604bfeb8a5`
  on port 55435. The disposable `unciv-v3-city-state-pg19b2` container was
  removed and verified absent afterward.
- `git diff --check` and module-size review passed. `main.rs` remains 6 lines,
  `lib.rs` remains 28 lines, and the largest Rust source is 781 lines.

The next city-state slice covers improvement gifts, diplomatic marriage,
city-state peace, and protector-response prompts before moving to espionage.

## Authoritative city-state improvements, war, and peace

API v3 now covers resource-improvement gifts selected from exact server-derived
tile/improvement choices and direct city-state peace. The audit also found and
fixed a prior boundary defect: the city-state UI used the authoritative war
route, but its canonical executor and client preflight accepted only major
civilizations. Projection version 27 now exposes city-state war/peace legality,
and the same typed `DeclareWar` command safely supports known living major or
city-state targets.

The Kotlin worker derives the city-state's non-bonus visible resource tiles,
current improvement state, matching ruleset improvements, build legality,
influence threshold, actor treasury, and fixed canonical cost. Peace rechecks
current war, the city-state ally's war, declaration cooldown, and immutable
relationship rules before invoking the existing symmetric peace trade logic.
Clients cannot supply cost, resource, influence, treaty duration, war reason,
or resulting tile/diplomacy state.

For explicitly opened v3 games, improvement-gift and city-state peace buttons
return before legacy mutations. The shared declare-war button now preflights
against either the major-civilization or city-state projection. Local, hotseat,
saves, legacy multiplayer, and AI continue using the shared Kotlin engine.

Verification on 2026-07-22:

- Focused Kotlin tests prove canonical city-state war and peace plus projection
  allowlists and cooldown handling. Rust closed-contract coverage rejects a
  client-supplied improvement cost and other rule/outcome claims.
- `./gradlew :tests:test :server:test --no-daemon` completed 916 JVM tests with
  13 intentional skips and zero failures.
- `cargo test` passed 60 active library tests and all 7 HTTP/OpenAPI tests; 8
  database-only tests were intentionally skipped in that lane. Generated
  OpenAPI parity and the shared projection-v27 fixture passed.
- `cargo fmt` and warnings-as-errors
  `cargo clippy --all-targets --all-features -- -D warnings` passed.
- All 8 serialized PostgreSQL integration tests passed against only
  `postgres:19beta2-alpine@sha256:bc62313e826eb44d5f608425b7665962b72820e686da017799e906604bfeb8a5`
  on port 55434. The disposable `unciv-v3-city-state-peace-pg19b2` container
  was removed and verified absent afterward.
- `git diff --check` and module-size review passed. `main.rs` remains 6 lines,
  `lib.rs` remains 28 lines, and the largest Rust source is 799 lines.

Diplomatic marriage and protector-response prompts remain the final inventoried
city-state mutations before the coverage audit advances to espionage.

## Authoritative diplomatic marriage

API v3 diplomatic marriage is now an intent-only command. Projection version
28 exposes a cost only when the canonical Kotlin engine confirms the actor's
unique, alliance, marriage cooldown, treasury, and the city-state's living city
inventory. The request contains only the city-state ID; the worker derives the
price, transfers units and cities through existing `CityStateFunctions`, and
creates the exact captured-city disposition alerts. Clients cannot claim a
price, captured city list, or annex/puppet outcome.

The v3 city-state screen returns after submitting `MarryCityState`, before any
legacy mutation. Local, hotseat, saves, legacy multiplayer, and server-owned AI
continue using the same Kotlin rules. Rust worker wire DTOs were also moved out
of `worker/protocol.rs` into `worker/intents.rs`, reducing the protocol module
from 799 to 634 lines while keeping façades logic-free.

Verification on 2026-07-22:

- The focused Kotlin test proves server-derived cost, exact treasury deduction,
  canonical city transfer, and server-created disposition alerts. Rust closed
  contracts reject forged cost, captured-city, and disposition fields.
- `./gradlew :tests:test :server:test --no-daemon` completed 917 JVM tests with
  13 intentional skips and zero failures.
- `cargo test` passed 60 active library tests and all 7 HTTP/OpenAPI tests; 8
  database-only tests were intentionally skipped in that lane. Generated
  OpenAPI parity and the shared projection-v28 fixture passed.
- `cargo fmt` and warnings-as-errors
  `cargo clippy --all-targets --all-features -- -D warnings` passed.
- All 8 serialized integration tests passed against only
  `postgres:19beta2-alpine@sha256:bc62313e826eb44d5f608425b7665962b72820e686da017799e906604bfeb8a5`
  on port 55433; the server reported `PostgreSQL 19beta2`. The disposable
  `unciv-v3-marriage-pg19b2` container was removed and verified absent.

Protector-response prompts are now the final inventoried city-state mutation.

## Authoritative city-state protector responses

Projection version 29 now exposes bullied-protected, attacked-protected, and
attacked-allied city-state alerts only to the affected authenticated player.
Each prompt has an opaque stable ID, the involved visible civilizations, and a
closed allowlist of currently legal responses. The client submits only that ID
and `declare_war`, `condemn`, or `withdraw_protection`.

The Kotlin worker re-resolves the pending alert and both civilizations before
applying existing rules. It owns the special protected/allied-city-state war
reason, the city-state influence reward, the diplomatic condemnation modifier,
forced pledge withdrawal, notifications, and prompt consumption. Relationship-
immutable games do not expose the war choice. All three v3 alert buttons return
before legacy mutation; non-v3 modes retain their existing behavior.

Verification on 2026-07-22:

- The focused headless-engine test covers all three outcomes, projection-bound
  response legality, special war/influence effects, condemnation, pledge
  withdrawal, and prompt consumption. Rust closed contracts reject forged war
  reasons and influence outcomes.
- `./gradlew :tests:test :server:test --no-daemon` completed 918 JVM tests with
  13 intentional skips and zero failures.
- `cargo test` passed 60 active library tests and all 7 HTTP/OpenAPI tests; 8
  database-only tests were intentionally skipped in that lane. Generated
  OpenAPI parity and the shared projection-v29 fixture passed.
- `cargo fmt` and warnings-as-errors
  `cargo clippy --all-targets --all-features -- -D warnings` passed.
- All 8 serialized integration tests passed against only
  `postgres:19beta2-alpine@sha256:bc62313e826eb44d5f608425b7665962b72820e686da017799e906604bfeb8a5`
  on port 55432; the server reported `PostgreSQL 19beta2`. The disposable
  `unciv-v3-protector-pg19b2` container was removed and verified absent.

The inventoried city-state interaction row is complete. The command-coverage
audit now advances to espionage and remaining private-choice surfaces.

## Authoritative espionage player controls

Projection version 30 exposes only the authenticated civilization's spies:
their server-owned identity, rank, current assignment, closed action, remaining
turns, legal explored and unoccupied destinations, and exact move/coup
allowlists. Dead spies receive no destination allowlist. No foreign spy roster
or hidden city information is projected.

API v3 now accepts only two espionage intents: move one owned spy to a projected
city or the hideout, and stage or cancel a canonically legal coup. The focused
Kotlin executor re-resolves the spy, destination, current turn, living state,
and coup legality before calling the existing `Spy` domain methods. Technology
theft, election rigging, coup resolution, counterintelligence, progression, all
AI espionage, and every random outcome remain in server-owned turn execution.
Clients cannot submit an actor, target civilization, duration, probability,
influence, random seed, or outcome.

Every player-authored mutation in `EspionageOverviewScreen.kt` now returns after
submitting through the authoritative session for an opened v3 game. Local,
hotseat, saves, legacy multiplayer, and server-owned AI continue through the
shared Kotlin engine.

Verification on 2026-07-22:

- The focused headless-engine test proves projection-bound movement, hideout
  return, coup staging/cancellation, exact canonical actions and durations, and
  out-of-turn rejection. Rust closed-contract tests reject forged rule and
  outcome fields.
- `./gradlew :tests:test :server:test --no-daemon` completed 919 JVM tests with
  13 intentional skips and zero failures.
- `cargo test --all-targets` passed 62 active library tests and all 7
  HTTP/OpenAPI tests; 8 database-only tests were intentionally skipped in that
  lane. Generated OpenAPI parity and the shared projection-v30 fixture passed.
- `cargo fmt --check` and warnings-as-errors `cargo clippy --all-targets -- -D
  warnings` passed.
- All 8 serialized integration tests passed against only
  `postgres:19beta2-alpine@sha256:bc62313e826eb44d5f608425b7665962b72820e686da017799e906604bfeb8a5`
  on port 55471; the live server reported `19beta2`. The disposable
  `unciv-v3-espionage-pg19` container was removed afterward.
- Module-size review found a 729-line largest Rust source. `main.rs` remains 6
  lines and `lib.rs` remains a 28-line façade.

The inventoried player-authored espionage row is complete. The coverage audit
now advances to remaining private-choice surfaces and lifecycle operations.

## Authoritative mod-defined event choices

Projection version 31 exposes each pending mod event only to its authenticated
civilization. It contains an opaque prompt ID, presentation text, optional
owned-unit context, and opaque currently available choices. Trigger uniques,
condition implementation, random inputs, and outcomes are never accepted from
or exposed as authority to the client.

`ResolveEventChoice` carries only the projected prompt and choice IDs. The
private Kotlin worker re-resolves the canonical popup alert, pinned ruleset
event, owned unit context, current availability conditions, and exact choice,
then calls the existing `EventChoice.triggerChoice` implementation and consumes
the alert. Nested effects and RNG therefore execute in the same server-owned
engine used by AI. Replay after consumption fails closed.

For opened v3 games, `RenderEvent` remains presentation-only and skips its
legacy local trigger callback. `AlertPopup` submits the opaque choice through
the authoritative session and reconciles afterward. Tutorials, local games,
hotseat, saves, legacy multiplayer, and server AI retain existing behavior.

Verification on 2026-07-22:

- The focused Kotlin test constructs a two-choice event, proves opaque
  player-scoped projection, executes the selected golden-age effect only in the
  canonical worker, consumes the alert, and rejects replay.
- Rust closed-contract coverage rejects client-supplied actor, event, unit,
  choice index, unique effects, RNG, and outcome fields. A dedicated wire test
  verifies exact camel-case field names expected by Kotlin.
- `./gradlew :tests:test :server:test --no-daemon` completed 920 JVM tests with
  13 intentional skips and zero failures.
- `cargo test --all-targets` passed 64 active library tests and all 7
  HTTP/OpenAPI tests; 8 database-only tests were intentionally skipped in the
  non-database lane. Generated OpenAPI parity, the dedicated Kotlin wire-name
  regression, and projection-v31 fixture coverage passed.
- `cargo fmt --check` and warnings-as-errors `cargo clippy --all-targets -- -D
  warnings` passed.
- All 8 serialized PostgreSQL integration tests passed against only
  `postgres:19beta2-alpine@sha256:bc62313e826eb44d5f608425b7665962b72820e686da017799e906604bfeb8a5`
  on port 55470; the live server reported `19beta2`. The disposable
  `unciv-v3-event-choice-pg19` container was removed afterward.
- Module-size review found a 733-line largest Rust source. `main.rs` remains 6
  lines and `lib.rs` remains a 28-line façade.

The mod-event choice leak is closed. The audit now advances to remaining unit
special actions and multiplayer lifecycle mutations.

## Authoritative direct great-person actions

Projection version 32 adds a closed allowlist of currently legal direct
great-person actions to each owned unit. The public command contains only the
stable unit ID and one of `hurry_research`, `hurry_policy`, `hurry_wonder`,
`hurry_building`, or `conduct_trade_mission`.

The private Kotlin worker re-resolves current-turn ownership and invokes the
existing shared `UnitActionsGreatPerson` implementation through a focused
executor. It therefore owns research and culture yields, construction checks
and production, speed and trade-mission modifiers, city-state ownership and
war checks, gold, influence, notifications, construction completion, and unit
consumption. Rust validates only the closed shape and never reproduces rules.

`UnitActionsTable` intercepts all five action types for opened v3 games before
their local callbacks. Local, hotseat, saves, legacy multiplayer, and AI retain
the same Kotlin implementations.

Verification on 2026-07-22:

- The focused Kotlin test proves action projection, server-calculated research,
  canonical unit consumption, and replay rejection for an owned Great
  Scientist. The closed Rust contract rejects claimed targets, yields,
  influence, consumption, and outcomes.
- `./gradlew :tests:test :server:test --no-daemon` completed 921 JVM tests with
  13 intentional skips and zero failures.
- `cargo test --all-targets` passed 65 active library tests and all 7
  HTTP/OpenAPI tests; 8 database tests were intentionally skipped in that lane.
  Generated OpenAPI parity and the projection-v32 fixture passed.
- `cargo fmt --check` and warnings-as-errors `cargo clippy --all-targets -- -D
  warnings` passed.
- All 8 serialized PostgreSQL integration tests passed against only
  `postgres:19beta2-alpine@sha256:bc62313e826eb44d5f608425b7665962b72820e686da017799e906604bfeb8a5`
  on port 55469; the live server reported `19beta2`. The disposable
  `unciv-v3-great-person-pg19` container was removed afterward.
- Module-size review found a 743-line largest Rust source. `main.rs` remains 6
  lines and `lib.rs` remains a 28-line façade.

The five inventoried direct great-person mutations are authoritative. The unit
special-action audit now advances to gift, transform, airlift, and generic
trigger-unique paths.

## Authoritative unit gifting

Projection version 33 exposes only whether each owned unit can currently be
gifted. `GiftUnit` carries only its stable unit ID: the client cannot name the
recipient or claim diplomacy, influence, destruction, or ownership outcomes.

The private Kotlin worker derives the recipient from the canonical current
tile and rechecks turn ownership, movement, transport state, war and friendly
territory, city-state military restrictions, and applicable unit uniques. It
then applies the existing five-point city-state influence or major-civilization
`GaveUsUnits` modifier and either transfers ownership or consumes a great
person gifted to a city-state. Rust remains the revisioned control plane and
does not reproduce these game rules.

For opened v3 games, `UnitActionsTable` submits the gift before the legacy
local callback can run and reconciles the resulting projection. Local games,
hotseat, saves, legacy multiplayer, and server AI retain the shared Kotlin
behavior.

Verification on 2026-07-22:

- The focused Kotlin test proves server-derived city-state targeting,
  projection availability, canonical influence, ownership transfer, and replay
  rejection. The closed Rust contract rejects actor, recipient, influence,
  diplomacy modifier, destruction, and outcome claims.
- `./gradlew :tests:test --no-daemon` completed 918 core JVM tests with 13
  intentional skips and zero failures. The focused `:server:test` lane passed
  all 4 private-worker protocol tests, for 922 JVM tests total.
- `cargo test --lib` passed 67 active library tests; all 7 HTTP/OpenAPI tests
  passed separately. The generated OpenAPI contract and projection-v33 fixture
  both match their checked-in contracts.
- `cargo fmt --check` and warnings-as-errors `cargo clippy --all-targets -- -D
  warnings` passed.
- All 8 serialized PostgreSQL integration tests passed against only
  `postgres:19beta2-alpine@sha256:bc62313e826eb44d5f608425b7665962b72820e686da017799e906604bfeb8a5`
  on port 55468; the live server reported `19beta2`. The disposable
  `unciv-v3-unit-gift-pg19b2` container was removed and cleanup verified.
- Module-size review found a 752-line largest Rust source. `main.rs` remains 6
  lines and `lib.rs` remains a 28-line facade.

The unit-gift mutation is authoritative. The unit special-action audit now
advances to transform, airlift, and generic trigger-unique paths.

## Authoritative mod-defined unit transformations

Projection version 34 exposes each currently executable transform as an opaque
action ID plus its presentation target name. `TransformUnit` carries only the
stable unit ID and one selected projected action ID; clients cannot claim the
target, source definition, resources, placement, movement, side effects, or
result. Distinct legal uniques remain independently selectable even when they
share a target name.

The private Kotlin worker re-resolves current-turn ownership and the exact
canonical `CanTransform` action. It invokes the existing shared
`UnitActionsFromUniques` callback, which owns availability conditionals,
resource deltas, embarkation, placement or resurrection, stable unit identity,
movement clamping, and all mod-defined side effects. Rust validates the closed
intent and exclusively commits the resulting revision without implementing
game rules.

For opened v3 games, `UnitActionsTable` submits the transformation before its
legacy callback can execute locally. Local games, hotseat, saves, legacy
multiplayer, and server AI continue through the same Kotlin implementation.

Verification on 2026-07-22:

- A focused synthetic-mod Kotlin test proves two distinct opaque actions with
  the same Warrior-to-Scout target remain selectable, alongside canonical
  execution, stable unit ID, and replay rejection.
  Rust rejects actor, source, resource, movement, side-effect, placement, and
  outcome claims, and a wire test verifies Kotlin-compatible field names.
- `./gradlew :tests:test :server:test --no-daemon` completed 923 JVM tests with
  13 intentional skips and zero failures.
- `cargo test --lib` passed 69 active library tests and all 7 HTTP/OpenAPI tests
  passed. Generated OpenAPI parity and the projection-v34 fixture passed.
- `cargo fmt --check`, warnings-as-errors `cargo clippy --all-targets -- -D
  warnings`, and `git diff --check` passed.
- All 8 serialized PostgreSQL integration tests passed against only
  `postgres:19beta2-alpine@sha256:bc62313e826eb44d5f608425b7665962b72820e686da017799e906604bfeb8a5`
  on port 55466; the live server reported `19beta2`. The disposable
  `unciv-v3-unit-transform-id-pg19b2` container was removed and cleanup verified.
- Module-size review found a 762-line largest Rust source. `main.rs` remains 6
  lines and `lib.rs` remains a 28-line facade.

Mod-defined unit transformation is authoritative. The unit special-action
audit now advances to airlift and generic trigger-unique paths.

## Authoritative generic unit trigger actions

The follow-up airlift audit found no independent player-authored `UnitAction`
or rules mutation: air rebasing uses the already-authoritative unit movement
path. No duplicate command surface was added.

Projection version 35 exposes every currently executable generic mod-defined
unit trigger as an opaque action ID and presentation title. The
`TriggerUnitUnique` command contains only the stable unit ID and selected
opaque action ID. It cannot name a unique or claim effects, multipliers, costs,
unit consumption, randomness, or outcomes. Duplicate identical uniques remain
independently addressable.

The private Kotlin worker re-resolves authenticated current-turn ownership and
the exact canonical action, then invokes the existing
`UnitActionsFromUniques` callback. Trigger conditions, movement and stat costs,
limited uses, multipliers, random effects, consumption, and side effects all
remain inside the shared engine used by local play and server AI. Opened v3
games intercept the UI action before its legacy local callback; single-player,
hotseat, saves, legacy multiplayer, and AI behavior are unchanged.

Verification on 2026-07-22:

- A focused synthetic-mod Kotlin test projects two identical gold trigger
  actions with distinct opaque IDs, executes the selected second action in the
  canonical worker, proves server-owned gold and movement effects, and rejects
  replay. Rust rejects actor, unique, effect, multiplier, cost, consumption,
  and outcome claims; the worker wire-name test passes.
- `./gradlew :tests:test :server:test --no-daemon` completed 924 JVM tests with
  13 intentional skips and zero failures.
- `cargo test --lib` passed 71 active library tests and all 7 HTTP/OpenAPI tests
  passed. Generated OpenAPI parity and the projection-v35 fixture passed.
- `cargo fmt --check`, warnings-as-errors `cargo clippy --all-targets -- -D
  warnings`, and `git diff --check` passed.
- All 8 serialized PostgreSQL integration tests passed against only
  `postgres:19beta2-alpine@sha256:bc62313e826eb44d5f608425b7665962b72820e686da017799e906604bfeb8a5`
  on port 55465; the live server reported `19beta2`. The disposable
  `unciv-v3-unit-trigger-pg19b2` container was removed and cleanup verified.
- Module-size review found a 772-line largest Rust source. `main.rs` remains 6
  lines and `lib.rs` remains a 28-line facade.

The inventoried generic trigger-unique mutation surface is authoritative. The
coverage audit now advances to any remaining lifecycle and non-unit mutation
gaps.

## Authoritative self-resignation

API v3 now provides a closed, zero-payload `Resign` command. The authenticated
account and its civilization come only from server membership. The private
Kotlin worker verifies that assignment, transfers the civilization to AI,
clears its player identity, emits the shared public resignation notification,
and, when necessary, advances the current turn through
`GameInfo.nextTurn(executionContext)` so all AI and rotation remain server-side.

Rust commits the worker snapshot, command journal, immutable revision, head,
and outbox event while removing the resigning account's membership in the same
PostgreSQL transaction. A retry with the same command ID still returns the
original accepted result after membership removal. The client treats acceptance
as terminal and closes its local authoritative game instead of attempting a
player projection it is no longer authorized to read. A v3 force-resign attempt
fails closed until its separate server-owned inactivity policy is implemented;
it cannot fall through to the legacy whole-save mutation.

Verification on 2026-07-22:

- Focused Kotlin tests prove the canonical civilization becomes AI, its player
  ID is removed, a current-turn resignation advances to the next human, and the
  client does not fetch a post-resignation projection.
- `./gradlew :tests:test :server:test --no-daemon` completed 926 JVM tests with
  13 intentional skips and zero failures.
- Rust tests prove the command rejects client-supplied actor/civilization fields
  and the private worker wire names match Kotlin. The active Rust library lane
  passed 73 tests and all 7 API/OpenAPI tests passed.
- `cargo fmt --check`, warnings-as-errors `cargo clippy --all-targets -- -D
  warnings`, and `git diff --check` passed.
- All 9 serialized PostgreSQL integration tests passed against only
  `postgres:19beta2-alpine@sha256:bc62313e826eb44d5f608425b7665962b72820e686da017799e906604bfeb8a5`
  on port 55464; the live server reported `19beta2`. The disposable
  `unciv-v3-resign-pg19b2` container was removed and cleanup verified.
- Module-size review found a 781-line largest Rust source. `main.rs` remains 6
  lines and `lib.rs` remains a 28-line facade; lifecycle logic lives in focused
  API, PostgreSQL, and worker modules.

Self-resignation is authoritative. Force-resignation remains the next lifecycle
milestone and must use durable server time/inactivity evidence, never a client
preview timestamp.

## Authoritative force-resignation

API v3 now provides a separate closed, zero-payload `ForceResign` command. Rust
permits it only for the authenticated game owner and supplies only that owner's
membership-derived civilization to the private worker. The client cannot name
a target, submit elapsed time, choose an allowance, or claim a current player.

Kotlin selects the canonical current civilization, requires it to be a distinct
human player, and compares the server execution clock against the snapshot's
canonical `currentTurnStartTime` and that civilization's canonical
`playerMinutesBeforeForceResign`. An early request is rejected without changing
the canonical hash. An eligible request transfers the player to AI, clears the
identity, emits the shared force-resignation notification, and runs canonical
AI/turn rotation. The worker returns the selected civilization identity only to
the private Rust control plane.

Rust atomically commits the snapshot, journal, immutable revision, head, and
outbox while deleting exactly the worker-identified civilization membership.
The owner's membership remains intact, and response-loss retries with the same
command ID return the original result without re-running the worker.

Verification on 2026-07-22:

- Focused Kotlin tests prove exact-boundary acceptance, early rejection without
  mutation, canonical target selection, identity clearing, and server-side turn
  progression. The command-bus test proves the client wire payload contains no
  actor, target, or inactivity claim and reconciles the accepted projection.
- `./gradlew :tests:test :server:test --no-daemon` completed 929 JVM tests with
  13 intentional skips and zero failures.
- Rust passed 75 active library tests and all 7 API/OpenAPI tests. Closed-command
  and worker wire tests cover the new operation.
- All 10 serialized PostgreSQL integration tests passed against only
  `postgres:19beta2-alpine@sha256:bc62313e826eb44d5f608425b7665962b72820e686da017799e906604bfeb8a5`
  on port 55463; the live server reported `19beta2`. The disposable
  `unciv-v3-force-resign-pg19b2` container was removed and cleanup verified.
- Module-size review found a 794-line largest Rust source. `main.rs` remains 6
  lines and `lib.rs` remains a 28-line facade; force-resignation reuses the
  focused lifecycle modules rather than adding bootstrap logic.

The inventoried resign/force-resign lifecycle is authoritative. The coverage
audit now advances to spectator membership and projection policy.

## Owner-controlled spectators and public-only projection

API v3 now has an explicit spectator policy instead of reusing player joins or
player projections. Only the authenticated game owner may add an enabled
account as a spectator, the target cannot already hold another membership, and
repeating the same add is idempotent. A spectator has no civilization, cannot
read the player projection or open a gameplay command bus, and may remove only
their own spectator membership.

Spectator reads use a separate private-worker operation and projection schema.
The Kotlin engine derives a sorted public summary containing only the turn,
current civilization ID, and each major civilization's public identity,
human-control, and defeat state. Map state, units, cities, yields, resources,
diplomacy, notifications, private orders, and canonical `GameInfo` bytes are
not fields in the closed DTO. Rust authenticates the spectator membership,
validates the canonical snapshot and manifest, hashes the public projection,
and returns it through a dedicated endpoint. Membership changes emit durable
outbox events but do not create fake gameplay revisions.

Verification on 2026-07-22:

- Focused Kotlin tests prove private sentinel data and forbidden canonical
  field families cannot occur in serialized spectator output. A client-session
  test proves add/read/leave use only spectator endpoints and never invoke the
  player projection.
- `./gradlew :tests:test :server:test --no-daemon` completed 931 JVM tests with
  13 intentional skips and zero failures.
- Rust passed 77 active library tests and all 7 HTTP/OpenAPI tests. Closed DTO,
  generated OpenAPI parity, and Kotlin-compatible private worker wire tests
  passed.
- All 11 serialized PostgreSQL integration tests passed against only
  `postgres:19beta2-alpine@sha256:bc62313e826eb44d5f608425b7665962b72820e686da017799e906604bfeb8a5`
  on port 55462; the live server reported `19beta2`. The disposable
  `unciv-v3-spectator-pg19b2` container was removed and cleanup verified.
- `cargo fmt --check`, warnings-as-errors `cargo clippy --all-targets -- -D
  warnings`, and `git diff --check` passed. `main.rs` remains 6 lines,
  `lib.rs` remains a 28-line facade, and every Rust source remains below 800
  lines (largest: `worker.rs`, 795 lines).

The owner-invited public-summary spectator lifecycle is authoritative and
leak-tested. The coverage audit now advances to game administration and the
remaining partial command/projection families.

## Authoritative owner kick

API v3 now exposes a closed `KickMember` command for the authenticated game
owner. The public request names only the account plus the standard command ID,
expected revision, and observed hash; it cannot claim an actor, target account
ID, civilization, AI result, turn effect, or membership mutation. PostgreSQL
normalizes the username, verifies the owner role, and resolves the target's
player membership and civilization before invoking the private worker.

The Kotlin engine revalidates the membership-derived owner and target against
canonical `GameInfo`, rejects self or non-human targets, transfers the selected
civilization to server AI, clears its player identity, emits the shared public
force-resignation notification, and advances canonical AI/turn rotation when
the kicked civilization is current. Rust then atomically commits the immutable
snapshot, revision, command journal, head, and outbox while deleting exactly
that civilization's membership. A lost-response retry uses the same command ID
and returns the prior result even though the membership is already gone.

Verification on 2026-07-22:

- Focused Kotlin tests prove canonical target selection, AI handoff, identity
  clearing, current-turn advancement, and replay rejection. The command-bus
  test proves ambiguous retry reuses one command ID and the client sends no
  actor or civilization claim.
- `./gradlew :tests:test :server:test --no-daemon` completed 933 JVM tests with
  13 intentional skips and zero failures.
- Rust passed 79 active library tests and all 7 HTTP/OpenAPI tests, including
  closed-command and Kotlin-compatible private worker wire contracts.
- All 12 serialized PostgreSQL integration tests passed against only
  `postgres:19beta2-alpine@sha256:bc62313e826eb44d5f608425b7665962b72820e686da017799e906604bfeb8a5`
  on port 55461; the live server reported `19beta2`. The disposable
  `unciv-v3-kick-pg19b2` container was removed and cleanup verified.
- Warnings-as-errors `cargo clippy --all-targets -- -D warnings` and the broad
  JVM suite passed. Lifecycle request DTOs were proactively split into
  `api/lifecycle_contracts.rs`; `main.rs` remains 6 lines, `lib.rs` remains a
  28-line facade, and every Rust source remains below 800 lines.

Owner kicking is authoritative, retry-safe, and synchronized with canonical
server AI state. Ownership transfer and close/archive status remain the next
game-administration slices.

## Retry-safe ownership transfer and game lifecycle

Migration `0008_game_administration.sql` adds the closed `active`, `closed`, and
`archived` lifecycle plus a durable administration-operation ledger. A partial
unique index permits exactly one owner per game. Ownership transfer resolves an
enabled player membership from a normalized username, demotes the authenticated
owner, promotes the selected player, and records one atomic outbox/audit boundary.
Repeating the same operation ID and payload returns success; reuse with another
actor, operation, or payload fails closed.

Close and archive use the same retry contract. Close is valid only from active;
archive is valid only from closed. Both lock the game row shared by canonical
commit CAS. Worker loading rejects a non-active game early, and the final commit
transaction independently rechecks active status, so a worker result racing a
close cannot create a post-close revision. Closed games retain member-scoped
read projections; archived games remain in discovery/metadata but deny player
and spectator projections. Lifecycle status is carried in Rust OpenAPI and the
Kotlin client contracts, and a successful local archive closes the command bus.

The outbox audit also fixed a pre-existing delivery defect: spectator,
membership, and lifecycle events do not contain a new canonical hash because
they do not fabricate gameplay revisions. The dispatcher is now topic-aware,
recovers the hash from the referenced immutable revision, and emits an
authenticated `resync_required` hint instead of retrying those events forever.

Verification on 2026-07-22:

- PostgreSQL tests prove exact one-owner transfer, normalized/idempotent retry,
  changed-payload rejection, old-owner denial, active-to-closed-to-archived
  ordering, worker and commit race gating, stable revision zero, archived
  discovery without projection, durable operation counts, topic-aware outbox
  claiming, and canonical hash recovery.
- A Kotlin client-session test proves caller-stable operation IDs reach transfer,
  close, and archive unchanged and that archive removes the opened local game.
- `./gradlew :tests:test :server:test --no-daemon` completed 934 JVM tests with
  13 intentional skips and zero failures.
- Rust passed 80 active library tests and all 7 HTTP/OpenAPI tests, including
  generated contract parity and control-plane resync classification.
- All 13 serialized PostgreSQL integration tests passed against only
  `postgres:19beta2-alpine@sha256:bc62313e826eb44d5f608425b7665962b72820e686da017799e906604bfeb8a5`
  on port 55460; the live server reported `19beta2`. The disposable
  `unciv-v3-admin-final-pg19b2` container was removed and cleanup verified.
- `cargo fmt --check`, warnings-as-errors `cargo clippy --all-targets -- -D
  warnings`, broad JVM verification, and `git diff --check` passed. `main.rs`
  remains 6 lines, `lib.rs` remains a 28-line facade, and every Rust source is
  below 800 lines.

The server-side game-administration lifecycle is authoritative, serialized,
and retry-safe. Production UI wiring remains explicit; the coverage audit now
returns to the remaining gameplay/projection and full-v3 lifecycle gaps.

## Owner-authorized player invitations and multi-revision join

Migration `0009_player_invitations.sql` replaces game-ID knowledge as join
authority with durable owner-created invitations. An active game's owner names
an enabled account and supplies a retry-stable invitation ID. Exact retries are
successful even after consumption, changed reuse fails closed, targets already
holding membership cannot be invited, and a partial unique index permits only
one pending invitation per game/account while allowing a future reinvitation
after a player has left.

Authenticated targets can list only their own pending invitations. Each entry
contains the game ID, invitation ID, inviter, current revision, and canonical
hash required to construct a revision-bound join command; it contains no
snapshot or player projection. Join checks the invitation before invoking the
private Kotlin worker, then locks and rechecks the same invitation in the final
PostgreSQL transaction. Invitation consumption, assigned membership, canonical
snapshot, journal, immutable revision, head CAS, and outbox commit together.

The audit also removed a revision-zero-only join restriction that prevented a
second player from joining. Every invited player now joins the actual current
revision, while Kotlin remains the sole component that selects an unclaimed
canonical civilization and mutates `GameInfo`. The Kotlin transport/session
preserves caller-stable invitation and command IDs and uses only the revision
and hash projected by invitation discovery.

Verification on 2026-07-22:

- PostgreSQL tests prove normalized exact retry, changed-payload and non-owner
  denial, target-only discovery, pre-worker denial, final-transaction denial,
  atomic consumption at the committed revision, replay after consumption,
  existing-member rejection, and two sequential players joining revisions zero
  and one.
- A focused Kotlin session test proves invitation discovery and acceptance pass
  the server-projected revision/hash and caller-stable IDs without a client
  civilization choice.
- `./gradlew :tests:test :server:test --no-daemon` completed 935 JVM tests with
  13 intentional skips and zero failures.
- Rust passed 80 active library tests and all 7 HTTP/OpenAPI tests. The checked-in
  OpenAPI document includes both authenticated invitation endpoints and closed
  request contracts.
- All 14 serialized PostgreSQL integration tests passed against only
  `postgres:19beta2-alpine@sha256:bc62313e826eb44d5f608425b7665962b72820e686da017799e906604bfeb8a5`
  on port 55459; the live server reported `19beta2`. The disposable
  `unciv-v3-invitations-pg19b2` container was removed and cleanup verified.
- `cargo fmt --check`, warnings-as-errors `cargo clippy --all-targets -- -D
  warnings`, and `git diff --check` passed. `main.rs` remains 6 lines, `lib.rs`
  remains a 28-line facade, and every Rust source remains below 800 lines.

Player join is now explicitly owner-authorized, target-discoverable, retry-safe,
and atomic at any current revision. Production player-setup UI wiring remains
tracked; the coverage audit proceeds to the remaining full-v3 lifecycle and
projection-only UI gaps.

## Bounded canonical snapshot compression

Migration `0010_snapshot_codec.sql` closes the stored codec set to `identity`
and `zstd`. Existing identity rows remain readable for in-place migration and
restore compatibility, while every newly created or committed canonical
snapshot is encoded with pinned Rust `zstd` crate version 0.13.3 at level 3.
Compression changes storage representation only: the Kotlin worker continues to
receive the exact canonical UTF-8 `GameInfo` JSON, and revisions/projections keep
hashing those uncompressed canonical bytes.

The storage boundary now records and verifies two independent hashes. The
payload hash authenticates the compressed bytes stored in PostgreSQL; the
canonical state hash authenticates the bounded decompressed bytes and must match
both the snapshot and immutable revision. Compressed and uncompressed sizes are
independently capped at 16 MiB in Rust and PostgreSQL. Decompression uses a
streaming reader capped at 16 MiB plus one sentinel byte and limits the zstd
back-reference window to 16 MiB, so a small frame cannot allocate or emit an
unbounded canonical snapshot.

Any unsupported codec, invalid frame, size mismatch, payload-hash mismatch,
canonical-hash mismatch, invalid UTF-8, or decompression overflow marks the
immutable snapshot corrupt and quarantines the game. The server never falls
back to client bytes or rewrites the canonical head.

Verification on 2026-07-22:

- Codec unit tests prove zstd round-trip behavior, distinct compressed and
  canonical hashes, strict legacy-identity size checks, unknown-codec rejection,
  and bounded failure when a highly compressed frame expands beyond its claim.
- The PostgreSQL corruption test proves new revision-zero snapshots are stored
  as zstd with correct independent sizes/canonical hash, then replaces the frame
  with invalid zstd bytes whose outer payload hash is valid. Canonical validation
  still quarantines the game without advancing its head.
- All 14 serialized PostgreSQL integration tests passed against only
  `postgres:19beta2-alpine@sha256:bc62313e826eb44d5f608425b7665962b72820e686da017799e906604bfeb8a5`
  on port 55458; the live server reported `19beta2`. The disposable
  `unciv-v3-zstd-pg19b2` container was removed and cleanup verified.
- Rust passed 83 active library tests and all 7 HTTP/OpenAPI tests. `cargo fmt
  --check` and warnings-as-errors `cargo clippy --all-targets -- -D warnings`
  passed. The broad 935-test JVM/server regression suite remained green with 13
  intentional skips.

Canonical snapshots are now compressed with bounded, fail-closed decoding.
Prior-snapshot journal recovery and retention/compaction remain separately
tracked because neither may weaken immutable history or command idempotency.

## Read-only canonical reconciliation tooling

The standalone `unciv-v3-reconcile` binary audits authoritative PostgreSQL
state without starting the public listener, contacting the Kotlin worker,
applying migrations, quarantining games, or attempting repair. It is a thin
binary over a focused operations entry point and a streaming repository audit;
operator logic does not enter `main.rs`, `lib.rs`, or API bootstrap code.

The structural pass detects invalid heads, missing and orphaned snapshots,
broken parent chains, missing and orphaned command links, missing/duplicate and
orphaned revision-commit outbox events, duplicate civilization memberships,
invalid owner cardinality, and quarantined games. A second streaming pass
validates every stored snapshot's closed codec, compressed/uncompressed sizes,
protocol, validation status, payload hash, canonical hash, revision hash, and
UTF-8 after bounded decompression. Reports contain fixed finding text plus
game/revision identifiers only; they exclude canonical snapshots, commands,
accounts, credentials, and outbox payloads.

Detailed output is capped at 1,000 findings while the total count continues,
making memory use bounded for a damaged database. Exit code zero means clean,
two means findings, and one means configuration/connection/query failure. The
tool deliberately requires a separately supplied database URL and does not run
migrations, allowing operators to execute it with a read-only database role.

Verification on 2026-07-22:

- The PostgreSQL integration test first proves a valid canonical database has
  zero findings, then introduces a broken chain/command link, missing commit
  event, orphan command/snapshot/commit event, zero-owner game, quarantine, and
  invalid zstd payload. Every expected category is reported.
- Row counts, quarantine reason, and snapshot validation status are captured
  before and after reconciliation and remain byte-for-byte equal, proving the
  audit performs no repair or quarantine mutation.
- The compiled CLI returned exit code 2 and a redacted ten-finding JSON report
  for damaged state, then exit code 0 and an empty report after the complete
  serialized suite reset the database to valid state.
- All 15 serialized PostgreSQL integration tests passed against only
  `postgres:19beta2-alpine@sha256:bc62313e826eb44d5f608425b7665962b72820e686da017799e906604bfeb8a5`
  on port 55457; the live server reported `19beta2`. The disposable
  `unciv-v3-reconcile-final-pg19b2` container was removed and cleanup verified.

Detection is now operational and fail-closed. Deterministic reviewed repair,
prior-snapshot journal recovery, and retention/compaction remain tracked as
separate work because the audit must never silently rewrite canonical history.

## Multi-replica CAS and database-connection fault proof

The PostgreSQL suite now creates two `PostgresGameRepository` instances with
independent connection pools and distinct application identities, matching two
Rust service replicas rather than cloning one in-memory test double. Both submit
different, valid worker proposals for the same game and expected revision only
after a shared barrier releases them concurrently.

PostgreSQL's game-row lock serializes the final authority boundary. Exactly one
replica commits the compressed snapshot, immutable revision, command journal,
head, and revision outbox event; the other receives a stale conflict identifying
canonical revision one. Counts prove there is exactly one row at every commit
boundary. Replaying the winner's command ID through the other pool returns the
original acceptance despite a deliberately different retry proposal, modeling
a response lost after commit without re-executing canonical state.

A second test holds the canonical game lock, starts a commit through a separately
named victim pool, observes that backend waiting on the PostgreSQL lock, and
terminates it with `pg_terminate_backend`. The commit returns a storage failure.
After releasing the controlling lock, revision, snapshot, journal, and outbox
counts all remain zero beyond revision zero. Retrying the identical command ID
through the recovered victim pool commits revision one, and the reconciliation
tool reports no findings.

Verification on 2026-07-22:

- The two focused tests pass without timing sleeps for the race: a barrier starts
  both replicas together, while `pg_stat_activity` lock evidence identifies the
  exact backend selected for controlled termination.
- All 17 serialized PostgreSQL integration tests passed against only
  `postgres:19beta2-alpine@sha256:bc62313e826eb44d5f608425b7665962b72820e686da017799e906604bfeb8a5`
  on port 55456; the live server reported `19beta2`. The disposable
  `unciv-v3-replica-fault-pg19b2` container was removed and cleanup verified.

Independent-replica revision CAS and a commit-boundary database-connection fault
are now proven. Actual database/service failover plus Rust-process, Kotlin-worker,
HTTP-response, and outbox-boundary termination tests remain explicitly tracked.

## Server-derived research queue append

Authoritative research selection now preserves the normal picker distinction
between replacing a research path and appending a destination. The client sends
only one technology name plus an append boolean. It never authors prerequisite
names or an ordered queue. The Kotlin worker resolves the destination through
the pinned ruleset, derives its canonical prerequisite path, removes entries
already queued, rejects a no-op append, and returns the resulting canonical
snapshot for Rust's revision commit.

Player projection version 36 adds `appendableTargets` alongside the existing
replace targets. This lets the command bus fail closed before submission while
leaving legality authoritative on the worker. Normal picker selection replaces
the path; the existing right-click interaction appends it. Single-player,
hotseat, saves, legacy multiplayer, and server-owned AI keep using the shared
Kotlin behavior directly.

The Rust research worker logic was also split from `worker.rs` into the focused
`worker/research.rs` module. Public façades remain logic-free and no duplicate
Rust rules implementation was introduced.

Verification on 2026-07-22:

- Focused Kotlin execution, command-bus, and Rust/Kotlin projection-contract
  tests pass, including missing-prerequisite derivation, preservation of the
  active queue, rejection of an already-queued append, and state-hash stability
  after rejection.
- Rust's 83 active library tests and 7 HTTP/OpenAPI tests pass; the generated
  OpenAPI and projection-v36 fixture are synchronized. `cargo fmt --check` and
  warnings-as-errors `cargo clippy --all-targets -- -D warnings` pass.
- `./gradlew :tests:test :server:test --no-daemon` passes the broad 935-test JVM
  and server suite with 13 intentional skips.
- All 17 serialized PostgreSQL integration tests pass against only
  `postgres:19beta2-alpine@sha256:bc62313e826eb44d5f608425b7665962b72820e686da017799e906604bfeb8a5`
  on port 55455; the live binary reported `PostgreSQL 19beta2`. The disposable
  `unciv-v3-research-pg19b2` container, network, and volume were removed and
  cleanup was verified.

Research queue removal/reordering plus researched history, future turn
estimates, and public research events remain explicitly tracked in
`missing_multiplayer.md`.

## Projection-owned research progress and picker legality

Player projection version 37 adds one closed queue entry for each canonical
research item. Every entry contains only its technology name, accumulated
science, and the cost calculated by the server's shared `TechManager`; overflow
science is projected separately. The projection contract requires queue names
and entries explicitly, so Rust and Kotlin reject unknown or incomplete shapes.
Rust additionally rejects a worker projection when current technology, queue
order, entry names, or nonnegative science/overflow/costs are
internally inconsistent.

For opened API-v3 games, `TechPickerScreen` now starts fail-closed, loads the
synchronized player projection, and enables replace, append, or free-technology
choices only from its matching allowlist. It replaces its displayed queue with
the projected queue, removes locally calculated turn estimates, and shows
queued progress/cost using only projected server values. Selection still sends
only a technology destination and append intent; all prerequisite and mutation
logic remains in the Kotlin worker. Local, hotseat, saves, legacy multiplayer,
and server-owned AI keep their existing shared-engine path.

The source audit found no removal button, order-indicator action, or drag reorder
in the current research picker. Consequently this milestone does not invent an
arbitrary queue upload or index mutation. A future removal/reprioritization
operation remains tracked and must define server-owned prerequisite-preserving
semantics before adding a UI.

Verification on 2026-07-22:

- Deterministic tests cover canonical queue-entry order, stored science, server
  cost, overflow, strict projection-v37 Rust/Kotlin round trips, authoritative
  session/bus regressions, and the server worker suite.
- Rust's 84 active library tests and 7 HTTP/OpenAPI tests pass, including
  generated OpenAPI parity. `cargo fmt --check` and warnings-as-errors
  `cargo clippy --all-targets -- -D warnings` pass.
- `./gradlew :tests:test :server:test --no-daemon` passes the broad 935-test JVM
  and server suite with 13 intentional skips.
- All 17 serialized PostgreSQL integration tests pass against only
  `postgres:19beta2-alpine@sha256:bc62313e826eb44d5f608425b7665962b72820e686da017799e906604bfeb8a5`
  on port 55455; the live binary reported `PostgreSQL 19beta2`. The first start
  attempt on Windows-reserved port 55454 was removed cleanly before retry. The
  successful disposable `unciv-v3-research-projection-pg19b2` container,
  network, and volume were also removed and cleanup was verified.

## Projection-owned researched history and turn estimates

Player projection version 38 adds the authenticated civilization's sorted
researched-technology names and a nullable numeric turn estimate to each queued
research entry. `null` means current science income cannot advance that item;
zero and positive estimates remain numeric protocol data. Display localization
and the infinity glyph stay client presentation only.

The numeric calculation lives in `TechManager.estimatedTurnsToTech`, while the
existing `turnsToTech` display method delegates to it. This keeps one Kotlin
domain rule for single-player, legacy UI, the headless worker, and projections.
No Rust research rule or client-side authoritative calculation was introduced.

For an opened v3 game, `TechPickerScreen` no longer eagerly calculates every
technology's local researchability or turn estimate. Before the projection
arrives it renders no researched state or estimate and keeps submission
disabled. Afterward it derives researched coloring, queue estimates, and legal
replace/append/free highlighting only from projection v38. Connecting-line
state also uses projected researched history instead of the disposable local
`TechManager` cache.

Rust validates that researched names are sorted, queue entries align exactly
with queue names, and all projected stored science, costs, overflow, and numeric
estimates are nonnegative. A malformed private-worker projection fails as a
protocol error before it can cross the public API boundary.

Verification on 2026-07-22:

- Deterministic tests cover shared-domain estimate parity, researched history,
  queue estimates, strict projection-v38 Rust/Kotlin round trips, malformed
  semantic rejection, session/bus regressions, and server-worker behavior.
- Rust's 84 active library tests and 7 HTTP/OpenAPI tests pass, including
  generated OpenAPI parity. `cargo fmt --check` and warnings-as-errors
  `cargo clippy --all-targets -- -D warnings` pass.
- `./gradlew :tests:test :server:test --no-daemon` passes the broad 935-test JVM
  and server suite with 13 intentional skips.
- All 17 serialized PostgreSQL integration tests pass against only
  `postgres:19beta2-alpine@sha256:bc62313e826eb44d5f608425b7665962b72820e686da017799e906604bfeb8a5`
  on port 55455; the live binary reported `PostgreSQL 19beta2`. The disposable
  `unciv-v3-research-history-pg19b2` container, network, and volume were removed
  and cleanup was verified.

## Authoritative research-completion prompts and projection v39

Player projection version 39 adds only the authenticated civilization's
pending `TechResearched` alerts as closed `completionPrompts`. Each prompt
contains an opaque SHA-256 identity and the public technology name. Other popup
alerts do not cross this projection field, and the client cannot supply a
technology, reward, research state, alert position, or completion outcome.

`AcknowledgeResearchCompletion(promptId)` now crosses the complete v3 boundary:
the opened client session preflights the opaque projected ID, Rust derives the
actor civilization from PostgreSQL membership, and the private Kotlin worker
re-derives the exact canonical alert for the current actor before consuming it.
Rust then uses the normal idempotent, revision-CAS, immutable-snapshot, journal,
and outbox commit path. A stale revision refreshes from the server; a duplicate
command ID reuses its committed result; an invalid, foreign, or already-consumed
prompt cannot mutate state.

For explicitly opened v3 games, closing a technology-completion `AlertPopup`
submits the typed acknowledgment before removing the disposable local popup.
Rejected or lost responses leave the prompt open for a safe retry with the same
command identity. Single-player, hotseat, saves, legacy multiplayer, and
server-owned AI retain the existing shared Kotlin behavior.

Verification on 2026-07-22:

- Deterministic engine tests prove actor authorization, opaque prompt shape,
  exclusion/preservation of unrelated alerts, one-shot consumption, and state
  hash stability after rejected replay. Command-bus and session tests prove
  projection preflight, typed transport, open-game gating, and reconciliation.
- Strict Rust/Kotlin projection-v39 fixture tests, the closed Rust command test,
  Kotlin/Rust worker-wire coverage, generated OpenAPI parity, all 86 active Rust
  library tests, and all 7 HTTP/OpenAPI tests pass. `cargo fmt --check` and
  warnings-as-errors `cargo clippy --all-targets -- -D warnings` pass.
- `./gradlew :tests:test :server:test --no-daemon` passes 940 JVM tests with 13
  intentional skips and zero failures or errors.
- All 17 serialized PostgreSQL integration tests pass against only
  `postgres:19beta2-alpine@sha256:bc62313e826eb44d5f608425b7665962b72820e686da017799e906604bfeb8a5`
  on port 55455; the live binary reported `PostgreSQL 19beta2`. The disposable
  `unciv-v3-research-events-pg19b2` container, network, and volume were removed,
  and cleanup was verified.

Research queue removal/reordering and the broader projection-only world remain
in `missing_multiplayer.md`; public completion events and projected picker mode
selection are closed.

## Projection-owned turn navigation and server automated-order phase

The end-turn readiness audit found that `MoveSpies` was incorrectly classified
as canonical. Its `dismissedShouldMoveSpies` flag is transient UI state and is
not serialized into an immutable snapshot, so a worker reload could never
preserve dismissal. API v3 no longer projects or rejects end turn for this
reminder. Actual spy movement and coup decisions remain canonical typed
commands; idle-spy navigation remains optional presentation.

The audit also found local gameplay execution in both the next-turn automated
unit phase and the autoplay context menu. For opened v3 games these client paths
are now fail-closed. Immediately before `GameInfo.nextTurn`, the private Kotlin
worker marks and executes the authenticated civilization's pending serialized
unit orders using the existing shared `MapUnit.doAction` behavior. The result is
part of the same worker proposal and PostgreSQL revision commit as end turn, so
the client never executes or uploads those actions. Whole-turn, military,
civilian, and economy autoplay remain available only to local/hotseat/legacy
games; a future v3 version must make them explicit server-owned AI operations.

Opened-v3 next-turn navigation now reads only an already-synchronized immutable
projection from a thread-safe session cache. Synchronizing, waiting, production,
technology, policy, religion, and diplomatic-vote states no longer consult the
disposable local rules model. The free-technology picker mode and great-person
auto-open/choice allowlist likewise come from projected server choices. The
cache accessor performs no network operation on the render thread; command
submission still refreshes and validates through the serialized command bus.

Verification on 2026-07-22:

- Deterministic worker-engine tests prove the automated-order phase is executed
  by the server and produces the same canonical hash from identical snapshots.
  An espionage-enabled fixture proves an idle-spy reminder is visible locally
  but absent from canonical readiness and cannot block end turn.
- Session tests prove the render-safe cache is absent before open and exposes
  only a synchronized server projection afterward.
- `./gradlew :tests:test :server:test --no-daemon` passes 942 JVM tests with 13
  intentional skips and zero failures or errors. Rust's 86 active library tests
  and 7 HTTP/OpenAPI tests, `cargo fmt --check`, and warnings-as-errors Clippy
  also pass.
- All 17 serialized PostgreSQL integration tests pass against only
  `postgres:19beta2-alpine@sha256:bc62313e826eb44d5f608425b7665962b72820e686da017799e906604bfeb8a5`
  on port 55455; the live binary reported `PostgreSQL 19beta2`. The disposable
  `unciv-v3-turn-readiness-pg19b2` container, network, and volume were removed,
  and cleanup was verified.

## Projection-owned movement and swap legality (projection v40)

Player projection version 40 adds two bounded coordinate allowlists to each
owned unit: exact destinations reachable in the current turn and compatible
friendly swap destinations. They are populated only for the authenticated
current actor. Visible foreign units and out-of-turn owned units always receive
empty lists. Explored-but-not-visible tiles are excluded so hidden blockers do
not become an oracle. Genuinely unexplored tiles retain the shared engine's
uniform assume-passable treatment, preserving normal fog exploration without
consulting hidden terrain or occupancy.

The Rust boundary now rejects malformed movement metadata before public API
delivery: foreign or out-of-turn options, duplicates/non-sorted lists, current
unit coordinates, and destinations on explored hidden tiles are protocol
errors. Exact `MoveUnit` and `SwapUnits` client preflight is bound to the
selected projected unit's allowlist. `MoveUnitToward` accepts only an owned unit
with movement during the actor's turn and an explored final target; the private
worker still derives and validates the complete route.

For an explicitly opened v3 game, tile highlights, tile selection, right-click,
single-tap, move overlays, swap-mode availability, and command submission now
consume the synchronized projection. The client no longer computes a path or
first-turn destination before sending movement intent. If synchronization is
missing or retrying, the adapter fails closed rather than falling back to the
disposable local rules model. Local, hotseat, saves, legacy multiplayer, and
server-owned AI continue using the shared Kotlin engine unchanged.

The audit also made a remaining boundary explicit in `missing_multiplayer.md`:
combat outcomes are authoritative, but attack/bombard/nuclear/air-sweep target
discovery and preview still consult client rule objects. That work is not
misreported as complete.

Verification on 2026-07-22:

- Deterministic engine tests compare every projected destination with shared
  `UnitMovement` results, require swaps to remain visible, allow only visible or
  uniformly unknown exact movement, exclude foreign legality, and clear all
  lists out of turn. Command-bus/session tests cover exact allowlist rejection,
  explored multi-turn intent, explicit-open routing, and swap allowlists.
- Strict Rust/Kotlin projection-v40 fixtures round-trip exactly. Rust semantic
  tests reject hidden, duplicate, foreign, and out-of-turn movement metadata.
  The generated OpenAPI contract includes the new closed DTO and matches the
  checked-in artifact.
- `./gradlew :tests:test :server:test --no-daemon` passes 944 JVM tests with 13
  intentional skips and zero failures or errors. Rust passes 87 active library
  tests and 7 HTTP/OpenAPI tests. `cargo fmt --check`, warnings-as-errors
  `cargo clippy --all-targets --all-features -- -D warnings`, and
  `git diff --check` pass.
- All 17 serialized PostgreSQL integration tests pass against only
  `postgres:19beta2-alpine@sha256:bc62313e826eb44d5f608425b7665962b72820e686da017799e906604bfeb8a5`
  on port 55456; the live binary reported `PostgreSQL 19beta2`. The disposable
  `unciv-v3-movement-pg19b2` container, network, and volume were removed, and
  cleanup was verified.
- The broad gates initially caught a stale OpenAPI artifact and two v39-style
  session fixtures. Both were corrected and the complete gates were rerun to a
  clean result; no compile, test, formatting, Clippy, or database error remains
  deferred.

## Projection-owned combat targeting (projection v41)

Player projection version 41 adds bounded, sorted target metadata for the
authenticated current actor: exact unit attack targets with their server-chosen
attack-from coordinate, exact city bombardment targets, nuclear target
candidates, and air-sweep targets. Foreign units and cities and out-of-turn
owned entities always receive empty combat lists. Exact attacks require both
the target and attack-from tile to be visible.

Nuclear coordinates intentionally remain candidates rather than a hidden-state
legality oracle. The projection includes only explored in-range coordinates and
does not call the full nuclear-legality rule, because filtering by unseen blast
occupants or diplomacy would reveal private state. The private worker still
revalidates the canonical unit, range, blast victims, diplomacy, and every
effect before Rust commits a revision.

For explicitly opened v3 games, map highlights, right-click actions, city
bombard selection, command-bus preflight, and the battle confirmation panel now
read the cached projection. They do not call client combat-targeting, nuclear
legality, or damage simulation before submission. Unknown projected map
coordinates fail closed. Local, hotseat, saved, legacy/API-v2, and server-owned
AI paths continue using the shared Kotlin rules engine. Public damage/strength
preview metrics remain explicitly listed in `missing_multiplayer.md`.

The Rust semantic validator was split into the focused
`projection_validation.rs` module before adding these rules; `main.rs` and
`lib.rs` remain thin, and `projection.rs` remains below 800 lines.

Verification on 2026-07-22:

- Deterministic engine tests cover normal attack/attack-from, city bombardment,
  nuclear confidentiality under a hidden blast victim, air-sweep targets, and
  foreign/out-of-turn empty lists. Bus and session tests reject coordinates
  absent from the corresponding projected allowlist.
- The strict Rust/Kotlin projection-v41 fixture round-trips exactly. Rust rejects
  hidden, duplicate, foreign, out-of-turn, unsorted, or oversized combat
  metadata. Generated OpenAPI parity, all 88 active Rust library tests, all 7
  HTTP/OpenAPI tests, `cargo fmt --check`, and warnings-as-errors Clippy pass.
- `./gradlew :tests:test :server:test --no-daemon` passes 945 JVM/server tests
  with 13 intentional skips and zero failures or errors.
- All 17 serialized PostgreSQL integration tests pass against only
  `postgres:19beta2-alpine@sha256:bc62313e826eb44d5f608425b7665962b72820e686da017799e906604bfeb8a5`
  on port 55457; the live binary reported `PostgreSQL 19beta2`. The disposable
  `unciv-v3-combat-pg19b2` container, network, and volume were removed and
  cleanup was verified.
- A restart-resume compile exposed an out-of-order UI local variable. It was
  corrected immediately; the complete focused and broad gates were rerun, and
  no compile, test, formatting, Clippy, OpenAPI, or database error remains
  deferred.

## Authoritative siege setup and projection v42

The persistent-unit-order audit found that the Set Up action still wrote a
siege unit's canonical action and consumed movement directly in the opened-v3
client. The existing closed `SetUnitPosture` operation now includes a `setup`
variant. Rust accepts no raw action string; the private Kotlin worker derives
the actor and unit, requires the current turn, movement, the canonical
`MustSetUp` unique, non-embarked state, and a unit not already set up, then uses
the shared setup action and movement cost before PostgreSQL commits the result.

The normal unit-action UI now routes setup through the authoritative session.
Local, hotseat, saved, legacy/API-v2, and server AI paths retain the same shared
Kotlin behavior. Projection v42 exposes setup through the existing closed
owner-only posture field; foreign units still receive no private posture.

The same audit classified paradrop and air-sweep toggles as disposable target-
selection presentation state, while identifying escort formation as the next
real gap. Its legacy flag is cache-only and cannot survive worker snapshot
reloads. API v3 therefore needs an atomic typed paired-movement intent rather
than persisting or trusting that cache flag; this remains explicit in
`missing_multiplayer.md`.

Verification on 2026-07-22:

- A deterministic engine test proves identical setup hashes from identical
  snapshots, exact movement consumption, owner projection, and rejection for a
  unit without the setup unique. Rust and Kotlin wire tests prove the closed
  `setup` spelling and exclude arbitrary action strings.
- The projection-v42 fixture, generated OpenAPI parity, all 88 active Rust
  library tests, all 7 HTTP/OpenAPI tests, `cargo fmt --check`, and warnings-as-
  errors Clippy pass.
- `./gradlew :tests:test :server:test --no-daemon` passes 946 JVM/server tests
  with 13 intentional skips and zero failures or errors.
- All 17 serialized PostgreSQL integration tests pass against only
  `postgres:19beta2-alpine@sha256:bc62313e826eb44d5f608425b7665962b72820e686da017799e906604bfeb8a5`
  on port 55458; the live binary reported `PostgreSQL 19beta2`. The disposable
  `unciv-v3-setup-pg19b2` container, network, and volume were removed and
  cleanup was verified.
- The first setup fixture incorrectly assumed the generated game already had a
  capital, causing a null test fixture rather than a product failure. The test
  now places the siege unit near an existing owned unit; the exact test and all
  broad gates pass, so no error is deferred.

## Atomic authoritative escort movement

Exact `MoveUnit` now accepts an optional stable escort-unit ID. This is not a
client claim that a formation is legal: projection preflight requires both
owned units to be co-located and to publish the clicked exact destination, then
the private worker independently requires distinct IDs, authenticated ownership,
co-location, one civilian and one military unit, non-air types, current-turn
movement, reachability, and entry legality for both units. It invokes shared
escort movement only inside that worker proposal and clears the cache-only
formation flag before building the returned projection.

The operation is atomic at the existing revision-CAS boundary: both units reach
the destination in one immutable snapshot or no revision commits. Older single-
unit requests omit the optional field and retain their wire behavior. Opened-v3
formation remains disposable client presentation state; clicking an exact
projected destination submits the pair, while a multi-turn escort request fails
closed because durable paired-order semantics are still explicitly missing.
Single-player, hotseat, saves, legacy/API-v2, and server AI retain shared engine
behavior.

Verification on 2026-07-22:

- Deterministic engine tests prove both units move together, identical snapshots
  produce identical hashes, and a self-escort is rejected. Command-bus tests
  require two co-located projected units with the same exact destination;
  session tests preserve the companion ID and reconcile both projected units.
- Rust public-command and worker-wire tests cover absent and present optional
  escort IDs and the exact Kotlin camel-case field. Generated OpenAPI parity,
  all 89 active Rust library tests, all 7 HTTP/OpenAPI tests,
  `cargo fmt --check`, and warnings-as-errors Clippy pass.
- `./gradlew :tests:test :server:test --no-daemon` passes 949 JVM/server tests
  with 13 intentional skips and zero failures or errors.
- All 17 serialized PostgreSQL integration tests pass against only
  `postgres:19beta2-alpine@sha256:bc62313e826eb44d5f608425b7665962b72820e686da017799e906604bfeb8a5`
  on port 55459; the live binary reported `PostgreSQL 19beta2`. The disposable
  `unciv-v3-escort-pg19b2` container, network, and volume were removed and
  cleanup was verified.
- The first broad Rust run correctly failed generated OpenAPI parity after the
  request shape changed. The artifact was regenerated and the entire Rust suite
  rerun cleanly; no compile, test, formatting, Clippy, OpenAPI, or database
  error remains deferred.

## Durable authoritative escort orders (projection v43)

`MoveUnitToward` now accepts the same optional stable companion ID as exact
movement. The canonical snapshot persists that relationship as nullable
`movementEscortUnitId` on the route-owning unit; it does not serialize or trust
the legacy transient escort cache. On each authoritative automated-order phase,
the Kotlin engine resolves both owned units, revalidates co-location, non-air
types, one civilian plus one military unit, movement, and route reachability,
then reconstructs the shared-engine escort only for the atomic step. Arrival,
blocking, replacement, exact movement, swapping, or cancellation clears stale
pair metadata. Cancellation through either member clears the same canonical
order.

Projection v43 exposes the companion ID only beside the owning player's private
movement destination. Foreign projections reject or omit it, exact destination
choices for an active pair are intersected across both canonical units, and
swap choices are suppressed until the pair is cancelled or replaced. The
opened-v3 world map can therefore submit a long-distance formation without
running client rules or depending on cache state. Single-player, hotseat,
saved-game, legacy/API-v2, and server AI paths continue using the shared Kotlin
engine; older saves deserialize the additive nullable field as absent.

Verification on 2026-07-22:

- A deterministic engine test creates a paired route, serializes and reloads
  the canonical snapshot, proves owner projection and co-located server-side
  execution, and proves cancellation through the companion leaves no dangling
  route. Foreign sentinel tests prove the private pair ID is absent.
- Kotlin command-bus/session tests preserve the companion through preflight,
  request serialization, explicit-open submission, and retry state. Rust tests
  cover omitted/present public IDs, camel-case worker transport, and reject
  self, missing, destination-less, and foreign projection metadata.
- `./gradlew :tests:test :server:test --no-daemon` passes 950 JVM/server tests
  with 13 intentional skips. Rust passes 90 active library tests and all 7
  HTTP/OpenAPI tests; formatting and warnings-as-errors Clippy pass.
- All 17 serialized integration tests pass against only the pinned PostgreSQL
  19 Beta 2 digest on port 55460, whose live server reported `PostgreSQL
  19beta2`. The disposable container, network, and volume were removed and
  verified. No compile, test, formatting, Clippy, OpenAPI, or database error is
  deferred.

## Canonical combat confirmation previews (projection v44)

Visible unit-attack and city-bombard target entries now carry the exact bounded
display data needed by the confirmation panel: base and effective strengths,
sorted modifier labels and percentages, current/max health, deterministic
minimum/maximum remaining-health estimates, and closed capture/occupation
outcomes. `CombatPreviewProjection` derives those fields with the same shared
`BattleDamage` functions used by canonical execution. The client cannot submit
any preview value and no Rust combat-rule implementation was introduced.

The opened-v3 `BattleTable` reads only the synchronized projection for direct
combat confirmation. It no longer consults local defender state, strength,
modifiers, or damage calculations. Preview metadata exists only on target
coordinates already proven visible and attackable by the private worker, is
bounded to 64 modifiers per combatant and 200 characters per label, and is
absent from foreign and out-of-turn action lists. Nuclear candidates still do
not disclose blast victims, and air sweeps still do not disclose interceptors;
their deliberately coarse confirmation avoids a hidden-state oracle.

Projection v44 has one strict Rust/Kotlin fixture and semantic validation for
visibility, ordering, health bounds, modifier bounds, outcome/damage
exclusivity, foreign data, and out-of-turn data. Deterministic engine tests
compare repeated canonical previews and prove city bombard/ordinary combat
ranges.

Verification on 2026-07-22:

- `./gradlew :tests:test :server:test --no-daemon` passes 950 JVM/server tests
  with 13 intentional skips and zero failures or errors.
- `cargo test --all-targets` passes 90 active Rust library tests and all 7
  HTTP/OpenAPI tests. `cargo fmt --all -- --check`, warnings-as-errors
  `cargo clippy --all-targets --all-features -- -D warnings`, generated OpenAPI
  parity, and `git diff --check` pass.
- All 17 serialized integration tests pass against only
  `postgres:19beta2-alpine@sha256:bc62313e826eb44d5f608425b7665962b72820e686da017799e906604bfeb8a5`
  on port 55461; the live server reported `PostgreSQL 19beta2`. The disposable
  `unciv-v3-combat-preview-pg19b2` container, network, and volume were removed
  and cleanup was verified.
- The broad gate caught nullable projected health fields being passed directly
  to non-null UI helpers after adding the closed `no_estimate` outcome. The UI
  now fails closed with `requireNotNull` only in the ordinary damage branch;
  the full gate was rerun cleanly, so no error is deferred.

## Non-oracular nuclear and air-sweep confirmations (projection v45)

Special-combat confirmation is now projection-only without turning target
selection into a hidden-state oracle. Each nuclear candidate carries the
canonical weapon blast radius and a closed `hidden_until_commit` effect
disclosure. Each air-sweep candidate carries only the acting player's base
strength, bounded sorted sweep modifiers, current/max health, and a closed
`hidden_until_commit` interceptor disclosure. No victim, interceptor,
diplomatic consequence, random value, damage estimate, or outcome is included.

The opened-v3 battle panel renders those fields and explicitly explains that
the private Kotlin worker resolves affected entities, diplomacy, interception,
and outcomes on commit. It does not call `Nuke`, `AirInterception`,
`BattleDamage`, or local target rules. Candidate coordinates remain deliberately
coarse: nuclear targets depend only on own capability/range and exploration;
air-sweep targets depend only on own capability/range. The Rust public boundary
accepts only the closed projection-v45 shapes, bounds radii, health, modifier
counts/labels, ordering, ownership, and turn availability, and rejects unknown
fields or enum values.

Verification on 2026-07-22:

- Focused Kotlin engine tests prove the nuclear candidate list and metadata are
  identical before and after adding a hidden blast victim, and the air-sweep
  list and metadata are identical before and after adding hidden interceptors.
  Session and command-bus tests preserve projection-only preflight and submit
  only unit ID plus target coordinates.
- The shared projection-v45 fixture round-trips exactly in Kotlin and Rust.
  Rust semantic tests reject duplicate, foreign, out-of-turn, oversized, and
  invalid-health metadata. Generated OpenAPI was regenerated and its checked-in
  parity test passes.
- The focused Gradle suite covering the engine, projection contract, command
  bus, and session passes. `cargo test --all-targets` passes 90 active library
  tests and 7 HTTP/OpenAPI tests, with 17 database tests intentionally ignored
  until the explicit PostgreSQL lane.
- `./gradlew :tests:test :server:test --no-daemon` passes 950 JVM/server
  tests with 13 intentional skips and zero failures or errors. Rust passes 90
  active library tests and all 7 HTTP/OpenAPI tests; `cargo fmt --check` and
  warnings-as-errors Clippy pass.
- All 17 serialized integration tests pass against only
  `postgres:19beta2-alpine@sha256:bc62313e826eb44d5f608425b7665962b72820e686da017799e906604bfeb8a5`
  on port 55462; the live server reported `PostgreSQL 19beta2`. The first
  disposable run used the obsolete pre-18 data-directory mount and correctly
  failed startup. It was recreated with `/var/lib/postgresql`, matching the
  checked-in compose file, then the full database lane passed. Disposable
  container, network, and volume cleanup was verified.
- Adding the projection-v45 types briefly pushed `projection.rs` to 825 lines.
  Combat wire types now live in focused `projection_combat.rs`; `projection.rs`
  is below the 800-line guardrail, while `main.rs` and `lib.rs` remain thin.
  No compile, test, formatting, Clippy, OpenAPI, database, or disposable-resource
  error is deferred.

## Authoritative city economy and projection v46

Opened-v3 city production, construction purchases, and tile acquisition now
consume a closed server-derived city-economy view. Each owned city projects its
ordered queue with stored production, canonical production cost and estimated
turns; sorted queueable construction/perpetual choices; exact create-one-
improvement placement targets; per-currency canonical purchase cost, actor
reserve, allowed state, tile requirement and legal targets; explored tile
ownership plus actor-safe owning/working-city IDs and worked/locked state; and
exact city-tile purchase costs and affordability. The client submits only the
existing typed identity/coordinate intent and cannot send prices, balances,
queueability, ownership, placement rules, or legality claims.

`CityConstructionsTable`, `BuyButtonFactory`, `CityScreen`, and
`CityScreenTileTable` now use projection v46 for opened-v3 availability,
progress, turn display, purchase confirmation, tile labels, highlighting and
preflight. The command bus fails closed when a requested choice is absent or
has changed. The private Kotlin worker still re-derives every rule and price;
manual production selection now explicitly rejects puppet cities. Local,
hotseat, saved, legacy/API-v2, and server-AI execution retain shared Kotlin
behavior.

Rust accepts the additive v46 DTOs through a focused
`projection_city_economy.rs` module and validates bounds, sorting, queue
correspondence, queueable-name correspondence, progress/cost shapes, purchase
shapes, explored coordinates, own-city-only identifiers, tile-state
consistency, and unique purchase targets before exposing a worker result. The
shared Kotlin/Rust fixture moved from v45 to v46. Optional add-to-top/all-city/
batch controls and public wonder-effect feeds remain explicit in
`missing_multiplayer.md`; single construction purchases and single-tile
interactions are complete for the inventoried opened-v3 UI.

Verification on 2026-07-22:

- Focused Kotlin engine, projection-contract, command-bus, and session tests
  prove canonical queue/cost/target derivation, projection-only preflight,
  retry identity, and price-free public requests. The broad
  `./gradlew :tests:test :server:test --no-daemon` gate passes 950 tests with 13
  intentional skips and zero failures or errors.
- `cargo test --all-targets --no-fail-fast` passes 95 active library tests and
  all 7 HTTP/OpenAPI tests. `cargo fmt --all -- --check`, warnings-as-errors
  `cargo clippy --all-targets --all-features -- -D warnings`, regenerated
  OpenAPI parity, and `git diff --check` pass.
- All 17 serialized PostgreSQL integration tests pass against only
  `postgres:19beta2-alpine@sha256:bc62313e826eb44d5f608425b7665962b72820e686da017799e906604bfeb8a5`
  on port 55463; the live server reported `PostgreSQL 19beta2`. The disposable
  container, network, and volume were removed and cleanup was verified.
- The first broad JVM run found five stale session fixtures that still modeled
  construction legality with legacy name lists; they were upgraded to v46 and
  the entire gate reran cleanly. A Cargo verification was also invoked once
  from the repository root, correctly reported no `Cargo.toml`, then reran from
  `authoritative-server` and passed. No product, test, formatting, Clippy,
  OpenAPI, database, or disposable-resource error is deferred.
- Rust façades remain thin (`main.rs` 6 lines and `lib.rs` under 40 lines).
  City-economy wire types were split before `projection.rs` could become a god
  file; the largest Rust source remains 783 lines.

## Authoritative construction context actions and projection v47

Opened-v3 city production now supports every inventoried construction context
operation without a client-side mutation loop. Projection v47 attaches a
sorted closed `ConstructionQueueAction` allowlist to the applicable queue entry
or construction option. The six actions are move to top, move to end, add to
top, add to all cities, add or move to top in all cities, and remove from all
cities. The client renders only those advertised actions and submits the anchor
city, construction identity, optional exact queue index, and closed action.

The private Kotlin worker derives the eligible target-city set from canonical
state, revalidates ownership, turn, puppet/resistance/razed state,
constructability, wonders, tile-target restrictions, and the anchor queue
identity, then applies the bounded multi-city proposal atomically. The public
request cannot supply actor identity, city lists, legality, per-city results, or
outcomes. The Rust repository path remains focused in
`postgres/construction_queues.rs`; no rules were duplicated in Rust. Local,
hotseat, saved, legacy/API-v2, and server-owned AI paths retain the shared Kotlin
engine behavior. The remaining city-production projection gap is the public
wonder completion/effect event feed, not queue control.

Verification on 2026-07-22:

- Deterministic Kotlin tests exercise projected action availability, atomic
  all-city add/move/remove behavior, rejection when a repeated operation can no
  longer change canonical state, and identical canonical hashes from two fresh loads of the
  same valid ruleset-backed snapshot. Command-bus and session tests prove
  projection-only preflight and stable retry identity without actor, city-list,
  or outcome claims.
- `./gradlew :tests:test :server:test --no-daemon` passes 953 JVM/server tests
  with 13 intentional skips and zero failures or errors.
- `cargo test --all-targets --no-fail-fast` passes 96 active library tests and
  all 7 HTTP/OpenAPI tests. `cargo fmt --all -- --check`, warnings-as-errors
  `cargo clippy --all-targets --all-features -- -D warnings`, regenerated
  OpenAPI parity, and `git diff --check` pass.
- All 17 serialized PostgreSQL integration tests pass against only
  `postgres:19beta2-alpine@sha256:bc62313e826eb44d5f608425b7665962b72820e686da017799e906604bfeb8a5`
  on port 55464; the live server reported `PostgreSQL 19beta2`. The disposable
  container, network, and volume were removed and verified absent.
- The broad gates caught and resolved a stale generated OpenAPI artifact, an
  enum/object schema assertion mismatch, and an invalid synthetic-nation
  snapshot in the first determinism fixture. The fixture now uses a valid
  server-created ruleset-backed game, and every affected gate was rerun cleanly;
  no compile, test, formatting, Clippy, OpenAPI, database, or cleanup error is
  deferred.
- Rust entry façades remain nearly logic-free (`main.rs` 6 lines and `lib.rs` 38
  lines). City-economy HTTP request DTOs were split into the descriptive
  `api/city_economy_contracts.rs` module, and focused construction persistence
  lives in `postgres/construction_queues.rs`; the largest Rust source is 781
  lines, safely below the 800-line guardrail.

## Durable public wonder events and projection v48

World-wonder completion is now a durable canonical engine event rather than a
client inference from mutable notification text. Shared
`CityConstructions.completeConstruction` records the canonical completion turn,
wonder, builder, city identity, and coordinates in `GameInfo`; clone and save
serialization preserve the journal, so worker reloads and fresh-device
reconciliation do not lose it. Existing single-player, hotseat, saved-game,
legacy multiplayer, and server-owned AI completion paths continue to use the
same shared Kotlin construction engine.

Projection v48 exposes a sorted, bounded `wonderEvents` feed containing the
public wonder identity, canonical completion turn, and ruleset-derived effect
summary. The builder civilization is absent unless the authenticated actor owns
or knows it. City identity, name, and coordinates are an all-or-none group and
remain absent until that player has explored the completion tile. The canonical
event record itself never crosses the Rust public boundary. Rust accepts the
wire shape through the focused `projection_wonder_events.rs` module and rejects
future turns, reordered or oversized feeds, partial location disclosure, and
oversized identities/descriptions before returning a worker projection.

Verification on 2026-07-26:

- Focused Kotlin tests prove canonical event creation, clone durability, known
  versus unknown builder disclosure, and exploration-gated location disclosure.
  The shared Kotlin/Rust projection fixture moved from v47 to v48 and round
  trips semantically in both runtimes.
- `./gradlew :tests:test :server:test --no-daemon` passes 955 JVM/server tests
  with 13 intentional skips and zero failures or errors.
- `cargo test --all-targets --no-fail-fast` passes 99 active library tests and
  all 7 HTTP/OpenAPI tests. `cargo fmt --all -- --check`, warnings-as-errors
  `cargo clippy --all-targets --all-features -- -D warnings`, regenerated
  OpenAPI parity, and `git diff --check` pass.
- All 17 serialized PostgreSQL integration and controlled replica-fault tests
  pass against only
  `postgres:19beta2-alpine@sha256:bc62313e826eb44d5f608425b7665962b72820e686da017799e906604bfeb8a5`
  on port 55465; the live binary reported `PostgreSQL 19beta2`. The disposable
  container was stopped with `--rm` and verified absent.
- The first focused test compile found a cross-module test reaching an internal
  helper and was changed to exercise the public projection builder. Initial
  Cargo/OpenAPI invocations also exposed the required crate working directory
  and explicit binary selector, and the first database readiness probe expected
  a Compose-only healthcheck. Each invocation was corrected and every affected
  gate reran cleanly; the unavailable root `ktlintFormat` task changed no files.
  No compile, test, format, Clippy, OpenAPI, database, or cleanup error remains.
- Rust façades remain declaration-only (`main.rs` 6 lines and `lib.rs` 41
  lines). Wonder DTOs and semantic validation live in a focused 44-line module;
  the largest Rust source remains 781 lines.

## Bounded research-queue control and projection v49

Projection v49 adds a sorted closed action allowlist to every exact research
queue entry. The available operations are move to top, move up, move down, move
to end, and remove. The opened-v3 technology picker renders only those
projected context actions and submits the technology identity, exact current
index, and closed action; it never mutates or uploads a queue.

The private Kotlin worker derives the proposed queue from canonical state and
rejects stale identity, wrong-turn or wrong-member requests, unknown
technologies, and any move or removal that breaks prerequisite order. Repeatable
technologies remain supported. The Rust control plane validates the bounded
wire shape, persists through the focused `postgres/research.rs` path, and
commits with the existing revision/hash CAS and idempotency guarantees. Local,
hotseat, saved, legacy/API-v2, and server-owned AI behavior remains in the
shared Kotlin engine.

Verification on 2026-07-26:

- Focused Kotlin tests cover projected action availability, all bounded
  mutation semantics, prerequisite-safe rejection, exact queue identity,
  authorization, unchanged state after rejection, deterministic canonical
  hashing, projection-only command preflight, and lost-response retry with the
  same command ID. The shared Kotlin/Rust fixture moved from v48 to v49 and
  round-trips semantically in both runtimes.
- `./gradlew :tests:test :server:test --no-daemon` passes 959 JVM/server tests
  with 13 intentional skips and zero failures or errors.
- `cargo test --all-targets --no-fail-fast` passes 101 active library tests and
  all 7 HTTP/OpenAPI tests, with the 17 explicitly configured database tests
  ignored in that non-database run. `cargo check --all-targets`,
  `cargo fmt --all -- --check`, warnings-as-errors
  `cargo clippy --all-targets -- -D warnings`, and regenerated OpenAPI parity
  pass.
- All 17 serialized PostgreSQL integration and controlled replica-fault tests
  pass against only
  `postgres:19beta2-alpine@sha256:bc62313e826eb44d5f608425b7665962b72820e686da017799e906604bfeb8a5`
  on port 55488. The disposable `--rm` container was stopped and verified
  absent.
- Early compilation caught and fixed the missing Rust schema derive path, a
  displaced trade-intent import, and a LibGDX inherited-name collision.
  Focused tests then exposed and corrected two invalid test-fixture
  assumptions without weakening production validation. No compile, test,
  format, Clippy, OpenAPI, database, or cleanup error remains deferred.
- Rust entry façades remain nearly logic-free (`main.rs` 6 lines and `lib.rs`
  41 lines). New HTTP and persistence logic lives in focused `api/research.rs`
  and `postgres/research.rs` modules; the largest changed Rust implementation
  remains below the 800-line guardrail.

## Policy and ideology authority plus projection v50

The policy inventory confirmed that Unciv models ordinary policies, policy
branch starts, ideology starts, tenets, and mod-defined policy branches through
the same data-driven `Policy` and `PolicyBranch` types. The existing closed
`AdoptPolicy(policyName)` command therefore already routes every supported
choice through the private Kotlin worker and canonical
`PolicyManager.canAdoptPolicy`, `isAdoptable`, and `adopt` rules. Rust does not
contain policy, ideology, prerequisite, cost, exclusion, trigger, or branch
completion logic.

Projection v50 closes the remaining public presentation gap by adding each
known living major civilization's sorted adopted policy-branch names to its
diplomacy projection. This includes public ideology identity without exposing
private culture, progress, free-policy balance, unavailable choices, or
unadopted policies. Rust rejects duplicate, reordered, oversized, empty, or
overlong branch disclosures before serving the projection.

Verification on 2026-07-26:

- Focused Kotlin tests prove canonical selection of the real Freedom ideology,
  mutual exclusion of Autocracy and Order, subsequent Constitution-tenet
  adoption, arbitrary mod-defined branch choices without hardcoded names,
  wrong-account rejection with an unchanged state hash, end-turn readiness
  resolution, and known-civilization public ideology disclosure. The shared
  Kotlin/Rust fixture moved from v49 to v50 and round-trips semantically in both
  runtimes.
- Focused Rust tests prove public branch lists are bounded, sorted, unique, and
  name-bounded. Worker projection validation now fails closed on malformed
  diplomacy/policy disclosure.
- `./gradlew :tests:test :server:test --no-daemon` passes 961 JVM/server tests
  with 13 intentional skips and zero failures or errors.
- `cargo test --all-targets --no-fail-fast` passes 102 active library tests and
  all 7 HTTP/OpenAPI tests, with the 17 explicitly configured database tests
  ignored in that non-database run. `cargo check --all-targets`,
  `cargo fmt --all -- --check`, warnings-as-errors
  `cargo clippy --all-targets -- -D warnings`, and regenerated OpenAPI parity
  pass.
- All 17 serialized PostgreSQL integration and controlled replica-fault tests
  pass against only
  `postgres:19beta2-alpine@sha256:bc62313e826eb44d5f608425b7665962b72820e686da017799e906604bfeb8a5`
  on port 55489. The disposable `--rm` container was stopped and verified
  absent.
- An initial focused Cargo command used two unsupported positional filters and
  was rerun with a valid single filter. The first public-disclosure test also
  reached a UI tutorial side effect in a headless fixture; it now constructs
  the same bilateral diplomacy managers directly. Every affected gate was
  rerun cleanly, and no compile, test, format, Clippy, OpenAPI, database, or
  cleanup error remains deferred.
- Rust entry façades remain nearly logic-free (`main.rs` 6 lines and `lib.rs`
  43 lines). The new semantic test lives in the focused
  `projection_policy_tests.rs` module; the largest Rust source remains below
  the 800-line guardrail.

## Canonical end-turn readiness audit

The canonical readiness list has been re-audited after completing research and
policy/ideology control. It contains only state-changing or one-shot choices
that must be resolved before turn advancement:

| Blocker | Player-scoped projection | Authoritative resolution |
| --- | --- | --- |
| Construction | Empty non-puppet city queue plus queueable construction options | `QueueConstruction`, tile-targeted queueing, or perpetual construction |
| Technology/free technology | Research targets, queue metadata, and free-tech choices | `SetResearchPath` or `ChooseFreeTechnology` |
| Policy/ideology | Exact selectable policy names | `AdoptPolicy` |
| Pantheon/founding/enhancement/reform | Required belief-slot types, eligible beliefs, and founding identity choices | `ChooseReligiousBeliefs` |
| Diplomatic vote | Eligible candidates; null remains explicit abstention | `CastDiplomaticVote` |
| Great person | Exact placeable unit names | `ChooseGreatPerson` |

`HeadlessGameEngine.endTurn` re-derives this list from canonical state and
rejects the command before automated orders or `GameInfo.nextTurn` when any
entry remains. Idle-unit, automation, and spy-movement reminders are
presentation conveniences and cannot block canonical v3 advancement. Rust now
rejects duplicate, reordered, legacy `move_spies`, or unresolvable readiness
lists before serving a worker projection.

Verification on 2026-07-26:

- A focused Kotlin matrix proves every blocker above, including all four
  religious modes, rejects `EndTurn` without changing the canonical hash or
  current player and disappears only after its authoritative worker command.
- A focused Rust semantic test proves the closed blocker list is ordered,
  unique, excludes the retired spy reminder, and has matching projected choices.
- `./gradlew :tests:test :server:test --no-daemon` passes 965 JVM/server tests
  with 13 intentional skips and zero failures or errors.
- `cargo test --all-targets --no-fail-fast` passes 103 active library tests and
  all 7 HTTP/OpenAPI tests, with the 17 explicitly configured database tests
  ignored in that non-database run. `cargo check --all-targets`,
  `cargo fmt --all -- --check`, and warnings-as-errors
  `cargo clippy --all-targets -- -D warnings` pass.
- All 17 serialized PostgreSQL integration and controlled replica-fault tests
  pass against only
  `postgres:19beta2-alpine@sha256:bc62313e826eb44d5f608425b7665962b72820e686da017799e906604bfeb8a5`
  on port 55490. The disposable `--rm` container was stopped and verified
  absent.
- The diplomatic-vote fixture initially lacked another living major
  civilization and therefore correctly produced no blocker. A first attempt
  placed that civilization inside tutorial contact range, exposing a headless
  UI side effect; the final legal fixture places its living unit outside
  contact range. The full affected matrix and broad gates reran cleanly, with
  no error deferred.
- Rust entry façades remain nearly logic-free (`main.rs` 6 lines and `lib.rs`
  45 lines). Readiness validation and its focused test remain separate from
  bootstrap code; the largest Rust source remains below 800 lines.

## Authoritative multi-tile city acquisition and projection v51

Opened-v3 city border context-menu purchases now cross the complete authority
boundary as `BuyCityTileBatch(cityId, ring)`. The public request contains only
the stable owned-city identity and a ring bounded to 1 through 32. It cannot
claim the actor, target tiles, order, prices, affordability, legality, or
result. Rust derives membership and serializes the canonical commit; it does
not contain Unciv tile-acquisition rules.

The private Kotlin worker deterministically derives every complete contiguous
unowned proposal in coordinate order, recomputes the shared escalating
`CityExpansionManager` cost for that sequence, rejects unavailable or
unaffordable batches before mutation, and verifies every resulting owner and
the exact treasury delta. Projection v51 publishes only the proposal ring,
tile count, canonical total cost, and affordability. The city UI confirms from
that disposable projection, sends the bounded intent, and reconciles after the
commit. A lost response retries the exact command ID and payload. Local,
hotseat, save, legacy multiplayer, and server-owned AI paths continue to use
the shared Kotlin engine behavior.

Verification on 2026-07-26:

- Focused Kotlin tests prove the projected batch is complete, affordable,
  atomically applied with the exact tile-count and gold delta, absent after
  purchase, and rejected on stale repeat, wrong account, insufficient funds,
  or an out-of-range ring without changing the canonical state hash. Command
  bus tests prove lost-response retry reuses the exact idempotency key and that
  serialized intent contains no tiles, price, actor, or result.
- The shared Kotlin/Rust projection fixture moved from v50 to v51 and
  round-trips semantically in both runtimes. Rust rejects duplicate,
  reordered, out-of-range, undersized, oversized, or malformed projected batch
  options, and closed-contract tests enforce the bounded intent.
- `./gradlew :tests:test :server:test --no-daemon` passes 968 JVM/server tests
  with 13 intentional skips and zero failures or errors.
- `cargo test --all-targets --no-fail-fast` passes 105 active library tests and
  all 7 HTTP/OpenAPI tests, with the 17 explicitly configured database tests
  ignored in that non-database run. `cargo check --all-targets`,
  `cargo fmt --all -- --check`, warnings-as-errors
  `cargo clippy --all-targets -- -D warnings`, regenerated OpenAPI parity, and
  `git diff --check` pass.
- All 17 serialized PostgreSQL integration and controlled replica-fault tests
  pass against only
  `postgres:19beta2-alpine@sha256:bc62313e826eb44d5f608425b7665962b72820e686da017799e906604bfeb8a5`
  on port 55491. The disposable `--rm` container was stopped and verified
  absent.
- Early compilation found one new test assigning through the private Kotlin
  gold setter; the fixture now uses the public shared-domain adjustment API,
  and the focused plus full gates reran cleanly. No compile, test, format,
  Clippy, OpenAPI, database, or cleanup error remains deferred.
- Rust entry façades remain nearly logic-free (`main.rs` 6 lines and `lib.rs`
  45 lines). New HTTP, persistence, worker-wire, and contract-test logic lives
  in focused city-tile modules. The largest Rust source is 781 lines, below the
  800-line guardrail.

## Authoritative capital-project unit consumption and projection v52

Opened-v3 `AddInCapital` actions now cross the authority boundary as
`AddUnitToCapitalProject(unitId)`. The public request contains only the stable
owned-unit identity. It cannot claim the actor, capital, project, spaceship
part, unit-consumption result, or counter delta. Rust derives membership and
serializes the canonical commit; it contains no Unciv project rules.

The private Kotlin worker re-derives the current actor, owned unit, capital
center, `AddInCapital` unique, nonblank project name, exact unit-name
spaceship-part key, unit consumption, and resulting counter change. Projection
v52 publishes the project label only for a legal owned current-turn unit.
Foreign and out-of-turn unit projections expose none of the related action
metadata. The same focused executor is used by local/hotseat behavior, while an
opened-v3 unit action submits through the command bus and reconciles after the
server commit.

Verification on 2026-07-26:

- Focused Kotlin tests prove projection, exact part increment, unit
  consumption, hash change, wrong-account rejection, out-of-turn omission, and
  noncapital rejection. Command-bus tests prove a lost response retries the
  identical idempotency key and closed payload.
- The shared Kotlin/Rust fixture moved from v51 to v52. Rust validates bounded
  owner-only current-turn disclosure, the closed request shape, and the exact
  Kotlin/Rust worker operation name.
- `./gradlew :tests:test :server:test --no-daemon` passes 971 JVM/server tests
  with 13 intentional skips and zero failures or errors.
- `cargo test --all-targets --no-fail-fast` passes 108 active library tests and
  all 8 HTTP/OpenAPI tests, with the 17 explicitly configured database tests
  ignored in that non-database run. `cargo check --all-targets`,
  `cargo fmt --all -- --check`, warnings-as-errors
  `cargo clippy --all-targets -- -D warnings`, regenerated OpenAPI parity, and
  the capability-to-command-route parity test pass.
- All 17 serialized PostgreSQL integration and controlled replica-fault tests
  pass against only
  `postgres:19beta2-alpine@sha256:bc62313e826eb44d5f608425b7665962b72820e686da017799e906604bfeb8a5`
  on port 55492. The disposable `--rm` container was stopped and verified
  absent.
- The first focused compile found a test-only visibility error in the retry
  fixture. It was corrected immediately, and the focused and broad suites
  reran cleanly; no compile, test, format, Clippy, OpenAPI, database, or cleanup
  error remains deferred.
- Rust entry façades remain nearly logic-free (`main.rs` 6 lines and `lib.rs`
  47 lines). Capital-project HTTP, persistence, and worker logic lives in
  focused modules; the largest Rust source remains 781 lines.

## Authoritative instant improvements and projection v53

Opened-v3 water-resource and general/mod-defined instant improvement actions
now cross the authority boundary as
`CreateInstantImprovement(unitId, actionId)`. The public request contains only
the stable owned-unit identity and a 64-character opaque identity selected from
the authenticated projection. It cannot claim the actor, tile, improvement,
resource or movement cost, unit consumption, unique side effects, or result.

The private Kotlin worker regenerates the currently executable shared
`CreateImprovement` actions from canonical state and invokes only the exact
opaque match. This re-derives water/resource eligibility, improvement filters,
permanent build problems, available resources, movement, conditionals,
consumption, and side effects. Projection v53 publishes bounded action IDs and
presentation titles only for owned current-turn units. It publishes nothing to
foreign or out-of-turn unit projections. Multiple improvements produced by one
mod-defined filter receive distinct IDs, and the UI maps the selected button
using both its canonical unique and title. An opened-v3 mapping failure returns
without invoking the local callback.

Verification on 2026-07-26:

- Focused Kotlin tests prove exact general improvement execution, work-boat
  water-resource improvement and consumption, distinct multi-option mod-filter
  identities, forged-ID rejection, wrong-account rejection, out-of-turn
  omission, canonical hash changes, and no mutation on rejection.
- Command-bus tests prove lost-response retry reuses the exact idempotency key
  and payload. Kotlin and Rust closed-contract tests prohibit client-authored
  tiles, improvement names, costs, consumption, side effects, actor, or
  outcome.
- The shared Kotlin/Rust fixture moved from v52 to v53. Rust rejects malformed,
  duplicate, oversized, foreign, or out-of-turn instant-improvement
  disclosures, and verifies the exact Kotlin/Rust worker operation name.
- `./gradlew :tests:test :server:test --no-daemon` passes 976 JVM/server tests
  with 13 intentional skips and zero failures or errors.
- `cargo test --all-targets --no-fail-fast` passes 111 active library tests and
  all 8 HTTP/OpenAPI tests, with the 17 explicitly configured database tests
  ignored in that non-database run. `cargo check --all-targets`,
  `cargo fmt --all -- --check`, warnings-as-errors
  `cargo clippy --all-targets -- -D warnings`, regenerated OpenAPI parity, and
  capability-to-route parity pass.
- All 17 serialized PostgreSQL integration and controlled replica-fault tests
  pass against only
  `postgres:19beta2-alpine@sha256:bc62313e826eb44d5f608425b7665962b72820e686da017799e906604bfeb8a5`
  on port 55493. The clean disposable `--rm` container was stopped and verified
  absent.
- The first database invocation through the context-compression command
  wrapper intermittently exhausted its SQLx pool, and a retry against the
  already-mutated fault-test database reproduced varying timeouts. The exact
  gate was rerun directly against a newly initialized pinned container and all
  17 tests passed in 7.02 seconds. No failed container or database remains.
- Rust entry façades remain nearly logic-free (`main.rs` 6 lines and `lib.rs`
  47 lines). New HTTP, persistence, and worker logic is split into descriptive
  focused modules; the largest Rust sources are 787 lines, below the 800-line
  guardrail.

## Fail-closed opaque unit-action routing audit

The source-level mutation audit found that opened-v3 transformation and generic
trigger buttons differed from instant improvements in one critical failure
case. If the selected local action could not map to an opaque identity in the
cached player projection, both paths fell through to `UnitAction.action` and
could execute their legacy local mutation callback. The server handlers were
authoritative, but the client-side routing failure itself was not fail closed.

`UnitActionsTable` now routes instant improvements, transformations, and generic
triggered uniques through one bounded opaque-action router. For an explicitly
opened v3 game the router always consumes those action types. It submits only a
successfully mapped opaque identity; a missing mapping performs no command and
returns without invoking the legacy callback. Non-v3 games and unrelated unit
actions still use their existing local behavior. This is a routing hardening
milestone and does not mark the broader world/city/unit/UI mutation audit
complete.

Verification on 2026-07-26:

- Three focused routing tests prove all three opaque action types fail closed
  on a missing identity, a mapped transformation submits exactly its opaque
  identity, and legacy/unrelated actions retain their local route.
- `./gradlew :tests:test :server:test --no-daemon` passes 979 JVM/server tests
  with 13 intentional skips and zero failures or errors.
- Rust remains unchanged and passes 111 active library tests plus all 8
  HTTP/OpenAPI tests. `cargo fmt --all -- --check`, warnings-as-errors
  `cargo clippy --all-targets --all-features -- -D warnings`, and regenerated
  OpenAPI parity pass.
- All 17 serialized PostgreSQL integration and controlled replica-fault tests
  pass in 6.61 seconds against only
  `postgres:19beta2-alpine@sha256:bc62313e826eb44d5f608425b7665962b72820e686da017799e906604bfeb8a5`.
  The fresh disposable container was forcibly removed by the test harness.
- The first focused compile overlapped the companion-object edit and observed
  the temporary duplicate declaration; the rerun then exposed JUnit-version
  and cross-module test-visibility mistakes. Both were corrected immediately,
  and every focused and broad gate was rerun cleanly. Two initially underspecified
  Cargo commands were also replaced by explicit binary-target invocations. No
  compile, test, format, Clippy, OpenAPI, database, or cleanup error remains
  deferred.
- Rust entry façades remain nearly logic-free (`main.rs` 6 lines and `lib.rs`
  47 lines), and the largest Rust source remains 787 lines.

## Typed server-seeded revision-zero creation

The production-lifecycle audit found that the authenticated v3 create route
selected a pinned ruleset manifest but still passed the private worker the
opaque string `"{}"`. Kotlin deserialized that string into `GameSetupInfo`, so
the default map seed came from the worker clock. No public save was accepted,
but the boundary still carried an untyped setup blob and did not make the
control plane explicitly own creation randomness.

The Rust control plane now generates an independent signed 64-bit map seed from
the operating system CSPRNG after authentication. It is not derived from the
public game UUID, so knowing a game ID cannot reproduce hidden map generation;
entropy failure aborts before any canonical game row is written.
`WorkerOperation::CreateGame` contains only that typed `serverSeed`; it has no
setup JSON, snapshot, `GameInfo`, actor, result, or client random value. The
Kotlin worker constructs a fresh default `GameSetupInfo`, applies the exact
pinned base ruleset and mod names to both game and map parameters, installs the
server seed, forces online mode without a legacy server URL, assigns the
authenticated account to the first human slot, and invokes the shared
`GameStarter`.

This closes the opaque/default-seed prerequisite but does not complete
production creation. The public API still needs bounded server-validated setup
choices and manifest discovery, and `NewGameScreen` still needs to create/open
v3 games from the returned revision-zero projection instead of constructing
and uploading a local save.

Verification on 2026-07-26:

- The Kotlin worker test creates an actual canonical game using an installed
  content-addressed manifest and proves its serialized map seed equals the
  typed server seed, the pinned base ruleset is retained, online mode is forced,
  and only the authenticated owner receives the initial human civilization.
- The Rust wire test proves `create_game` serializes only `type` and
  `serverSeed`, with no legacy `setup` or `snapshot` field.
- `./gradlew :tests:test :server:test --no-daemon` passes 980 JVM/server tests
  with 13 intentional skips and zero failures or errors.
- Rust passes 112 active library tests and all 8 HTTP/OpenAPI tests.
  `cargo fmt --all -- --check`, warnings-as-errors
  `cargo clippy --all-targets --all-features -- -D warnings`, and regenerated
  OpenAPI parity pass.
- All 17 serialized PostgreSQL integration and controlled replica-fault tests
  pass in 6.86 seconds against only
  `postgres:19beta2-alpine@sha256:bc62313e826eb44d5f608425b7665962b72820e686da017799e906604bfeb8a5`.
  The fresh disposable container was removed by the test harness.
- Focused verification first exposed nullable test-fixture fields and then the
  worker runtime's required headless `UncivGame` bootstrap. The fixture now
  mirrors the production headless bootstrap; the focused and complete suites
  reran cleanly. No compile, test, format, Clippy, OpenAPI, database, or cleanup
  error remains deferred.
- Rust entry façades remain nearly logic-free (`main.rs` 6 lines and `lib.rs`
  47 lines). The largest Rust source is 788 lines, below the 800-line
  guardrail.

## Server-manifest staged multiplayer lobby

Implemented on 2026-07-29:

- Production creation now starts from the authenticated server's paginated,
  content-addressed manifest inventory. The client selects one exact server
  base-plus-mod identity and pins the setup screen to it, eliminating the
  inherited single-player ruleset mismatch that previously surfaced as
  `No installed API v3 ruleset manifest matches this setup`.
- The closed public, Rust worker, and Kotlin engine setup contracts now carry
  the generated-map seed, mirroring, biome/coast scale, elevation,
  temperature, vegetation, rare-feature, resource-richness, and water controls
  in addition to map type/shape/size, resources, difficulty, speed, era,
  civilization counts, turn limit, victories, and rule toggles. Every numeric
  value is independently bounded by Rust and Kotlin before `GameStarter`.
- Multiplayer is a labeled two-stage flow. Step one is the complete bounded
  match setup; step two is a dedicated real-time room. The desktop room uses
  side-by-side player/faction and full-setting panels, while narrow Android
  layouts stack the same panels in one scroll view. Join, ready, and start
  changes use WebSocket resynchronization hints plus authenticated HTTP
  reconciliation and a polling fallback.
- Lobby responses now include the validated friendly base-ruleset and mod
  names, while continuing to retain the immutable manifest hash. Production
  single-player creation no longer offers the legacy `Online Multiplayer`
  switch, and startup no longer falls back to a legacy server when API v3
  negotiation fails. Compatibility-only legacy save code remains isolated for
  existing saves and imports.

Focused verification on 2026-07-29:

- The client/session/routing and private-worker Gradle slice passes 87 tests;
  the dedicated production routing class also passes after the responsive
  layout update.
- Rust setup, notifications, generated HTTP/OpenAPI, and all-target compilation
  gates pass. The API gate passes all 29 tests after regenerating the checked-in
  contract.
- Five serialized lobby and retry-safe creation tests pass against
  `PostgreSQL 19beta2` using only
  `postgres:19beta2-alpine@sha256:bc62313e826eb44d5f608425b7665962b72820e686da017799e906604bfeb8a5`.
  The lobby tests now assert friendly manifest names as well as password,
  capacity, unique-faction, stale revision, readiness, and owner-start
  behavior.
- The broad JVM gate passes 1,138 core/client tests with 16 intentional
  skips and 59 server tests with one intentional skip. The complete Rust
  all-target/all-feature suite passes 190 active library tests, all 29
  HTTP/OpenAPI tests, and every ordinary binary/policy test; formatting and
  warnings-as-errors Clippy pass. Android debug/release APK and release bundle
  assembly pass against the dedicated AGP-8.13-compatible SDK, and
  `:desktop:dist` plus `:desktop:packrWindows64` produce a portable archive
  whose packaged JAR contains `AuthoritativeLobbyScreen`. The portable
  executable remains alive through a 12-second smoke, and the aligned,
  testing-certificate-signed release APK verifies v1/v2/v3 signatures,
  installs, and resumes `AndroidLauncher` on the connected emulator.

Failure repair:

1. The first focused production-routing run found a dead private legacy
   `Online Multiplayer` checkbox builder. The obsolete builder was removed and
   the exact Gradle slice passed on rerun.
2. The first PostgreSQL lobby run stopped at migration 24 because the
   disposable database lacked the project runtime roles. The project-owned
   bootstrap-role fixture was applied to the same PostgreSQL 19 Beta 2
   instance; the exact two-test command then passed.
3. The friendly-name query exposed that the shared test fixture used an empty
   manifest unlike production. The fixture now stores a valid closed worker
   manifest; focused lobby and creation tests pass without weakening
   fail-closed parsing.
4. The first HTTP gate detected expected checked-in OpenAPI drift after the
   schema expansion. The project generator updated the contract and the exact
   29-test API gate passed.
5. The first complete Clippy run rejected a manually implemented
   `MirroringType` default. The enum now derives `Default` with an explicit
   default variant; the same complete formatting/test/Clippy command passes.
6. The first public post-deployment creation smoke proved manifest selection
   and revision-zero creation, then exposed that deleting the owner account
   left its lobby active and permanently ownerless. Account disable/delete now
   closes every active owned game in the same database transaction before
   credentials and sessions are revoked, retains the immutable membership
   history, and emits a resynchronization hint. The focused PostgreSQL 19 Beta
   2 lifecycle test covers both disable and delete; the disposable smoke lobby
   was explicitly closed.
7. The first replacement SBOM preflight rejected a revision-suffixed Syft
   document name. No service or active bundle changed. Regenerating with the
   policy-required exact `unciv-authoritative-v3-release-bundle` identity made
   the same `verify-sbom` gate pass. The resulting 45-artifact bundle
   `cca314784c98039fe94e7f55e6a096d0e938df6009e240b30d3663b3207f0283`
   verifies and is active on `unciv.rusticstack.com`.
8. The first external readiness request used the nonexistent prefixed paths
   `/api/v3/ready` and `/api/v3/health`, which correctly returned `404`.
   Repeating the probe against the closed contract's `/readyz` and `/healthz`
   routes returned protocol 4 with PostgreSQL and the private worker ready.
9. The first operator database assertion was rejected by local PowerShell/SSH
   quoting before it could produce a result. Passing the same read-only SQL
   through `psql` standard input avoided cross-shell interpolation and proved
   that HTTPS smoke game `207b7f0d-5203-45b7-9583-67120356c942` was
   automatically `closed`, its account was deleted and disabled, and its
   immutable owner membership remained present.

Production qualification on 2026-07-29:

- Revision `c586c37d139d202baec7c9c71a74ba77dda84d81` is pushed to
  `origin/master`. Its Rust release binaries were rebuilt on the VPS; the
  unchanged Kotlin worker and desktop artifacts remain byte-identical to the
  preceding lobby release.
- Syft 1.49.0 ran from pinned image
  `anchore/syft:v1.49.0@sha256:13b53ebabe3d215268c90cf8fb9b875f0183908245f376fd4b3a2cb69d21d484`.
  The bundle verifier accepted the SBOM and the resulting bundle before
  activation.
- Compose migration and ruleset acquisition jobs exited successfully, the
  worker became healthy, and the API started. Public HTTPS readiness reports
  protocol 4, PostgreSQL ready, and engine worker ready.
- A fresh external account could authenticate, list the installed
  `Civ V - Vanilla` manifest, create an active revision-zero lobby, and delete
  itself. The transactional database assertion above proves the lifecycle
  repair in production without leaving another active disposable lobby.

## Protocol-4 client detection and scaled desktop text (2026-07-29)

- The production server correctly advertised public protocol 4, but
  `ApiVersion.detect()` still compared capabilities to the obsolete literal 3.
  Desktop therefore classified the healthy HTTPS service as unknown and showed
  only `Status: Failed`. Detection now delegates to the typed capability
  predicate backed by `CommandEnvelope.CURRENT_PROTOCOL_VERSION`; a regression
  test proves the current protocol is accepted and both an older protocol and
  whole-state upload are rejected.
- Session restoration now retains a bounded failure explanation for the
  multiplayer screen. Failed detection no longer leaves a permanent
  `Working...` label, so network, protocol, and restoration failures are
  actionable without exposing tokens.
- Desktop Java2D glyph generation previously enabled generic shape
  antialiasing, which does not select text antialiasing. It now explicitly uses
  grayscale text antialiasing, fractional metrics, quality rendering, and
  quality alpha interpolation before libGDX packs glyphs into its existing
  linearly filtered texture. A focused desktop test proves rendered glyphs
  contain both opaque interiors and partially transparent antialiased edges.
- Focused API detection, session lifecycle, and desktop glyph tests pass. The
  complete `:tests:test :server:test --no-parallel` run then passed in 3m53s,
  and `:desktop:dist :android:assembleDebug` passed in 2m20s. The independent
  release APK gate, including release-vital lint, passed in 1m50s. Rust's
  four-test VPS Compose contract, formatting, and warnings-as-errors Clippy all
  pass; `docker compose --env-file .env.vps.example -f compose.vps.yaml config
  --quiet` accepts the final topology.
- The project Packr task could not run because its ignored
  `packr-all-4.0.0.jar` and `jdk-windows-64.zip` inputs are absent. This was
  isolated from compilation and repaired at the artifact owner by using the
  installed Java 21 `jpackage` app-image producer against the same qualified
  `Unciv.jar`. The resulting self-contained executable remained alive through
  a 12-second clean smoke launch.
- Direct-install artifacts are in
  `deploy/Unciv-V3-connect-font-20260729`: the debug-signed APK is
  `9363498C94B5327728920F313691FABEA29D379A0C279B80FB5247543C128F9C`
  (SHA-256), and the portable Windows ZIP is
  `FE9141E5BDAB2801D838A004695F33AFBCA689F297F05A53F6479C314E71B0E5`.
  Android's v1 and v2 signature verification passes. The release build itself
  also passes, but remains unsigned because no production signing identity is
  stored in the repository.

## Oracle ARM64 pilot deployment (2026-07-29)

- The attested bundle inputs were rebuilt from commit
  `0508a55febf3fe5c8f0a7f56a4e4ff11b557f7e1` on the target four-core ARM64
  Ubuntu 24.04 VPS. The closed bundle verifier accepted bundle ID
  `1c9f8c6888d0b81e6418e63dfd27d0ed5b8e1d6634fde2569646828a8f233b08`.
  Syft 1.49.0 image digest
  `sha256:13b53ebabe3d215268c90cf8fb9b875f0183908245f376fd4b3a2cb69d21d484`
  produced the embedded SPDX inventory.
- PostgreSQL runs only from
  `postgres:19beta2-alpine@sha256:bc62313e826eb44d5f608425b7665962b72820e686da017799e906604bfeb8a5`.
  The migration job exited zero at exact migration head 30, the ruleset
  acquisition job atomically activated the vanilla manifest, and the packaged
  worker and Rust API reported ready.
- External `GET https://unciv.rusticstack.com/readyz` returns HTTP 200 with
  PostgreSQL and worker ready. Caddy obtained and manages a Let's Encrypt
  certificate for that exact host, adds HSTS and the hardened response headers,
  actively probes `/readyz`, and redirects both domain HTTP and the former raw
  IP endpoint to HTTPS. An external disposable-account smoke registered,
  logged in, listed the V3 lobby page, and deleted the account successfully
  through TLS. The existing `https://ci.rusticstack.com` endpoint still returns
  HTTP 200 after each validated Caddy reload.
- First deployment attempts exposed three bounded Compose defects rather than
  gameplay defects: PostgreSQL automatically executed a mounted role SQL file
  a second time without psql variables; the migrator was given
  `UNCIV_V3_DATABASE_URL` instead of its closed
  `UNCIV_V3_MIGRATION_DATABASE_URL`; and the worker had no acquired ruleset
  tree as its working directory. The production Compose contract, bootstrap
  script, environment example, runbook, and focused regression test now encode
  the corrected boundaries. The initial empty failed database volume was
  removed before any account or game existed.
- The public endpoint is now `https://unciv.rusticstack.com`; the temporary
  plaintext API route is closed. The certificate covers only that hostname and
  was issued on 2026-07-29 with automatic renewal managed by Caddy. The worker
  and API now use Linux host networking while binding only `127.0.0.1:43170`
  and `127.0.0.1:3100`; PostgreSQL publishes only `127.0.0.1:54320`. Caddy is
  consequently a real loopback peer and Rust's closed `loopback` policy accepts
  its single sanitized `X-Forwarded-For` address. A live probe sent two requests
  from one origin, including a forged forwarding header, plus one request from
  a second origin: the durable limiter created four buckets (the expected
  global/source and account/source pair per origin) and recorded six
  consumptions, proving spoof normalization and distinct-client attribution.

## V3-only production lobby and unlimited-turn milestone

Implemented on 2026-07-29:

- Production multiplayer now exposes only API V3 server/account state, an open
  lobby browser, authoritative active/closed games, friends, chat, rewind, and
  owner administration. Legacy file-server credentials, UUID entry, skip-turn
  clocks, total-time clocks, recovered-time controls, and timed
  force-resignation are absent from this UI.
- Match creation reuses the bounded normal setup screen and adds a name, human
  slots, and optional password. Revision zero remains private pregame state:
  projections and ordinary gameplay commands are rejected until the owner
  starts an exactly full, unanimously ready lobby.
- PostgreSQL migration 30 adds server-owned lobby metadata/readiness and game
  display names. Passwords are Argon2 verifiers. Join, readiness, and start are
  authorization-, capacity-, unique-faction-, idempotency-, and
  compare-and-swap-bound. The private Kotlin worker returns the actual
  canonical major-faction pool; each joining account chooses one available
  faction from that pool.
- Public protocol version 4 carries the new lobby/setup contract while the
  private worker protocol remains version 3. Existing verified snapshot bytes
  are unchanged and migration 30 relabels their protocol metadata from 3 to 4.
- V3 human turns are unlimited. Canonical compatibility timer fields use
  unbounded values internally, but public setup and production UI do not expose
  timers, and the worker rejects timed force resignation.
- Android-to-desktop account handoff qualification now creates and starts a
  real two-human lobby, restores the same owner account on desktop without a
  save, commits a resignation, runs the intervening AI in the packaged server
  worker, and hands the turn to the second human.

Failure repair:

1. The first broad Kotlin run exposed stale setup fixtures that still expected
   random owner assignment and timers. Focused reproduction localized the
   mismatch to setup tests and worker parity fixtures; they were updated to the
   closed chosen-owner/unlimited contract, after which focused and complete
   server/core suites passed.
2. The first account-handoff run returned `503 game_unavailable`: revision-zero
   snapshots were still labelled public protocol 3. Migration 30 now performs
   the representation-preserving metadata transition and the next run reached
   lobby join.
3. That run rejected a client-hinted faction which was not present in the
   generated canonical match. The bounded owner was the worker create response:
   it now returns the actual ordered major-faction pool, Rust cross-validates
   the requested owner and capacity, and PostgreSQL stores only that pool.
4. The next exact run reached started play but resignation returned HTTP 500.
   PostgreSQL showed the head remained at revision 1; the
   `game_lobby_readiness` foreign key still referenced the resigning
   `game_members` row. The canonical commit now removes only the obsolete
   started-lobby readiness dependency inside the same transaction before
   membership deletion. Rerunning the same packaged
   `account_handoff` test passed.
5. Warnings-as-errors Clippy then identified a `filter(...).next_back()` in the
   qualification test. It was replaced with the equivalent `rfind`; the exact
   handoff and final Clippy gates were rerun after that last source edit.

The required two-person release match through an actual Domination result is
still open in `missing_multiplayer.md`; this automated preflight is strong
cross-device/server-AI evidence but is not substituted for that human gate.

## Membership-scoped game chat and completed social/lobby policy

Implemented on 2026-07-28:

- API v3 now exposes bounded, paginated per-game chat to current owner, player,
  and spectator memberships. Posting is bound to a caller-supplied nonzero
  message UUID; an exact retry succeeds once, while changed actor/body reuse
  fails with the stable idempotency conflict.
- Chat lives only in `game_chat_messages`. It never calls the worker or changes
  `GameInfo`, snapshots, revisions, the command journal, projections, outbox,
  turn state, AI, RNG, or canonical hashes. Per-game advisory serialization
  makes the 500-message retention cap reliable across API replicas.
- Bodies are trimmed, nonempty, limited to 1,000 UTF-8 bytes, and reject
  unsupported control characters. Pages are capped at 100 and use opaque UUID
  cursors. Posting has a durable 10-per-minute account/game limit with a
  60-second block.
- The shared Kotlin client validates every page, retains one message UUID
  across ambiguous exact retries, and provides the same production chat popup
  on desktop and Android. Removed memberships immediately lose both read and
  write access.
- The lobby policy is now explicit: the authenticated directory plus
  server-created revision-zero membership and invitation inbox are the lobby.
  Owners may enter immediately; invitees accept a durable invitation, receive
  an unclaimed civilization from the Kotlin worker, rediscover membership, and
  open only their projection. Joining extends the current revision, so a late
  invitee does not depend on a client-held setup screen or a second readiness
  authority.

Verification on 2026-07-28:

- All 12 focused Rust HTTP/OpenAPI tests pass after regenerating the checked-in
  contract. The public route inventory includes the combined game-chat
  GET/POST resource without exposing canonical or worker-private fields.
- The focused PostgreSQL test passes against only
  `postgres:19beta2-alpine@sha256:bc62313e826eb44d5f608425b7665962b72820e686da017799e906604bfeb8a5`;
  the live server reported `19beta2`. It proves membership read/write
  authorization, outsider denial, exact retries, changed-meaning conflict,
  byte/control validation, pagination/cursor rejection, sender identity, and
  exact 500-row retention. The disposable container was removed.
- `./gradlew.bat :tests:test --tests
  com.unciv.logic.multiplayer.authoritative.AuthoritativeGameChatCoordinatorTests
  --no-daemon` passes all three focused client tests after compiling production
  UI. The initial compile exposed one GL-thread helper called outside its
  coroutine receiver; the success callback was moved to the existing
  GL-thread boundary and the clean rerun passed.
- `./gradlew.bat :tests:test :server:test :android:assembleDebug --no-parallel
  --no-daemon` passes the complete shared client, game/core, and worker/server
  regression lane and produces the Android debug APK: 1,171 tests discovered,
  1,155 executed, 16 intentionally skipped, zero failures, and zero errors.
  The repository's existing SDK XML-version warning remains non-fatal.
- Two initial disposable-database attempts found harness/fixture errors before
  the passing run: PostgreSQL's temporary bootstrap server briefly reported
  ready before restart, and the fixture named a nonexistent
  `game_members.joined_revision` column. Stable role-bootstrap polling and the
  actual schema fixed both; neither error was deferred.

## Separate account friendship service

Implemented on 2026-07-28:

- API v3 now owns a durable account friendship graph outside canonical
  `GameInfo`, game revisions, the command journal, worker protocol, and
  projections. Authenticated accounts can list friends and incoming/outgoing
  requests, create a caller-ID-bound request, accept an incoming request,
  reject/cancel a pending request, and remove a friendship.
- PostgreSQL serializes unordered account pairs, enforces one pending request
  and one friendship per pair, bounds friends and pending requests, and applies
  separate durable social-write rate limiting at the HTTP boundary.
- The shared Kotlin client validates the bounded graph, preserves one request
  UUID across an ambiguous exact retry, and exposes a focused authoritative
  friends popup on both desktop and Android. Legacy API-v2 friends remain
  reachable only when no authenticated v3 session is installed.
- The work stays in descriptive modules. Rust `social.rs`,
  `social_contracts.rs`, and `postgres/social_graph.rs` contain the behavior;
  the Kotlin coordinator and popup are separate files, while `main.rs`,
  `lib.rs`, and session/bootstrap façades retain only narrow delegation.

Verification on 2026-07-28:

- All 12 focused Rust HTTP/OpenAPI tests pass. `cargo fmt --all -- --check`,
  warnings-as-errors `cargo clippy --all-targets --all-features -- -D warnings`,
  and `git diff --check` pass for the backend milestone.
- Two focused social persistence tests pass serially against only
  `postgres:19beta2-alpine@sha256:bc62313e826eb44d5f608425b7665962b72820e686da017799e906604bfeb8a5`;
  the live server reported `19beta2`. They prove exact retry identity,
  changed-meaning rejection, reverse-request collision rejection,
  recipient-only acceptance, outsider denial, bilateral visibility,
  cancellation, and removal. The disposable container was removed.
- `./gradlew.bat :tests:test --tests
  com.unciv.logic.multiplayer.authoritative.AuthoritativeSocialCoordinatorTests
  --no-daemon` passes all three focused client tests after compiling the
  production UI. The first compile found explicit `Unit` return annotations
  missing from optional transport defaults and an invalid GL-thread helper
  receiver; both were corrected before the clean rerun.
- The combined social checklist item remains open: game-member chat and final
  pregame lobby/readiness behavior are not yet implemented.

## Schema compatibility, capacity alerts, and disk-full recovery

Implemented and destructively qualified on 2026-07-28:

- Published migrations are immutable and forward-only under an explicit
  expand, deploy, backfill, prove, then contract policy. The API continues to
  reject missing, extra, failed, or checksum-mismatched SQLx history. There are
  no automated down migrations: an incompatible binary rollback restores the
  verified pre-migration physical backup/WAL target and matching release;
  otherwise operators ship a forward repair.
- A separate read-only `unciv-monitor` identity runs a hardened five-minute
  capacity timer. It validates coherent thresholds and safe non-symlink roots,
  then emits bounded JSON for data/WAL/backup filesystem use, database bytes,
  pending outbox rows, and snapshot count. Healthy, warning, and critical exits
  are distinct and contain no credentials or canonical/player data.
- Disk exhaustion is fail-closed. The API is removed from readiness until space,
  checkpoint, and reconciliation are healthy; operators never delete canonical
  history or accept a client-uploaded repair. A failed command is retried only
  with the same idempotency key.

Verification on 2026-07-28:

- `pwsh -NoProfile -File
  authoritative-server/tests/run-postgres-disk-full-smoke.ps1` passes against
  only the pinned PostgreSQL 19 Beta 2 digest. A 160 MiB tmpfs was reduced from
  122,120 KiB available to 1,024 KiB; the capacity checker changed from `ok` to
  `critical` with exit code 2.
- A deterministic 12 MiB incompressible valid UTF-8 snapshot failed at
  `game_snapshot_blobs` with PostgreSQL `No space left on device`. Rust returned
  `Storage`; the head remained revision zero and the command journal remained
  empty.
- After deleting only the drill filler and checkpointing, the same command ID
  committed revision one. A changed duplicate returned the original acceptance
  without a second command. Canonical validation and `unciv-v3-reconcile`
  completed with zero findings.
- The first drill wrapper failed before constraining storage because PowerShell
  interpreted an `awk` field expression; shell positional parsing replaced it.
  The next run exposed a non-UTF-8 synthetic fixture at final validation; the
  payload is now deterministic printable UTF-8. Neither error remains deferred,
  and every disposable container/tmpfs was removed.

## PostgreSQL 19 TLS, service isolation, and credential rotation

Implemented and live-qualified on 2026-07-28:

- An exact-digest PostgreSQL 19 Beta 2 production compose service is owned by a
  hardened systemd orchestrator, listens only on Linux loopback host networking,
  reads the bootstrap secret from a file, starts before migration/API/backup,
  and bounds CPU, memory, shared memory, PIDs, and Linux capabilities.
- Checked production configuration enables TLS 1.2 or newer, SCRAM-SHA-256,
  PostgreSQL 19 worker I/O, bounded connections and memory, continuous WAL
  archiving, and TLS-aware health. HBA rejects every non-TLS TCP session and
  admits each service role only to its required database or replication scope.
- The idempotent role bootstrap creates separate runtime, migration, backup,
  restore, and audit identities. All are non-superuser, non-createdb,
  non-createrole, and non-bypass-RLS. Runtime gets DML without DDL; migration
  owns schema evolution; backup gets replication without table reads; restore
  has no production admission; audit is read-only. Public database, schema,
  function, table, and sequence authority is removed or replaced with explicit
  current/default grants.
- Closed password rotation accepts only those five identities. The runbook
  requires independent protected credentials, consumer restart/readiness,
  denial of the old password, acceptance of the replacement over TLS, old
  session termination, and credential-free audit records.

Verification on 2026-07-28:

- `docker compose --file
  authoritative-server/postgresql/compose.production.yaml config --quiet`
  passes, the static systemd/config/role suite passes 8/8, and
  `systemd-analyze verify` accepts the PostgreSQL, migration, API, backup,
  worker, and proxy dependency graph in the pinned Linux qualification image.
- `pwsh -NoProfile -File
  authoritative-server/tests/run-postgres-security-smoke.ps1` passes against
  only
  `postgres:19beta2-alpine@sha256:bc62313e826eb44d5f608425b7665962b72820e686da017799e906604bfeb8a5`.
  The live server negotiated TLS 1.3. Runtime DML succeeded while DDL failed;
  migration DDL succeeded; audit read and zero-finding reconciliation succeeded
  while audit writes failed; backup completed `pg_basebackup` plus
  `pg_verifybackup`; restore and non-TLS production connections failed.
- PostgreSQL reported SCRAM password storage for all five service roles and the
  expected closed attributes, with replication true only for backup. Rotating
  runtime credentials made the old password fail and the replacement succeed.
  The disposable container, data volume, private CA, certificates, keys, and
  backup were removed.
- The first qualification correctly rejected a multi-statement `ALTER SYSTEM`
  query because PostgreSQL forbids it inside a transaction block. The script
  now applies each setting independently; no error remains deferred.

## PostgreSQL 19 physical backup and point-in-time recovery

Implemented and destructively qualified on 2026-07-28:

- PostgreSQL continuously archives WAL through a fail-closed script that
  accepts only segment, base-backup history, and timeline-history filenames;
  refuses symlinks or differing immutable destinations; stages on the archive
  filesystem; and publishes `0600` files atomically.
- A separate `unciv-backup` systemd identity runs daily randomized persistent
  physical backups with a replication-only PostgreSQL login. `pg_basebackup`
  streams required WAL and emits SHA-256 manifests; `pg_verifybackup` must pass
  before the staging directory is atomically published.
- A checked-in destructive drill creates canonical revision 1 plus membership,
  session, audit, journal, snapshot/blob, and outbox evidence; takes the
  verified backup; creates a named WAL restore point between two committed
  marker transactions; restores into a new data volume; and promotes only at
  that target. The client/API cannot participate in recovery or replace
  canonical state.
- The operator runbook requires a separate restore identity, a read-only WAL
  mount, isolated startup, canonical reconciliation, incident-boundary checks,
  and preservation of the exact backup, target, image digest, and reports
  before promotion.

Verification on 2026-07-28:

- `cargo test --manifest-path authoritative-server/Cargo.toml --test
  systemd_api_packaging` passes 6/6 packaging and fail-closed policy tests.
- `pwsh -NoProfile -File
  authoritative-server/tests/run-postgres-pitr-smoke.ps1` passes against only
  `postgres:19beta2-alpine@sha256:bc62313e826eb44d5f608425b7665962b72820e686da017799e906604bfeb8a5`.
  `pg_verifybackup` passed with a SHA-256 manifest, `pg_stat_archiver` reported
  zero failures, recovery promoted at
  `unciv_v3_backup_qualification`, the before-target marker count was one, and
  the after-target marker count was zero.
- The restored Rust invariant test passed. `unciv-v3-reconcile` scanned one
  game, two revisions, and two snapshots with `total_findings: 0`, proving the
  restored head revision, canonical and payload hashes, revision/command
  journal, membership, session, security audit, and transactional outbox
  invariants.
- Early drill runs exposed and fixed the absent replication HBA entry,
  root-owned archive volume, temporary-bootstrap readiness race, PostgreSQL 19
  `PGDATA` restore offset, missing backup-history filename allowance,
  same-transaction restore-point mistake, and scalar PowerShell output parsing.
  Every disposable run cleaned its containers, volumes, and network; none of
  these errors remain deferred.

## Legacy import normalization boundary

The first legacy-import milestone keeps untrusted saves out of Rust rules code.
A typed, private `normalize_legacy_game` worker operation loads the legacy save
through the shared Kotlin engine under an approved content-addressed manifest.
It rejects a mismatched legacy game ID, non-online saves, missing or duplicate
human mappings, duplicate destination accounts, malformed account UUIDs, and an
owner mapping that does not resolve to exactly one civilization. On success it
rekeys the game to a server-selected canonical UUID, rewrites every human
player identity to its v3 account UUID, and returns normalized snapshot bytes,
their hash, and the owner's civilization.

Rust exposes this only through a focused private worker-client module; no HTTP
route or client whole-save path was added. Persistence remains deliberately
unimplemented until the operator-only dry-run/apply command can combine bounded
candidate reads, conflict reporting, account validation, provenance, and the
idempotent genesis transaction atomically.

Verification on 2026-07-27:

- The focused Kotlin normalization test passes and proves successful rekeying,
  owner remapping, and fail-closed legacy-ID validation.
- Packaged-worker parity inventory passes with the new operation explicitly
  classified as fresh-process state parity.
- The focused Rust serialization/client test passes.
- `cargo fmt` was applied. The Windows linker emitted only its normal
  import-library informational warning; no Rust test failed.

## Complete sealed-operation fresh-worker parity

Implemented on 2026-07-27:

- Fresh packaged-JVM state parity now covers all 84 sealed worker operations.
  The final scenario covers `queue_construction_at_tile`,
  `purchase_construction_at_tile`, and `transform_unit` by deriving its legal
  placement, purchase, and transform identities from the authoritative player
  projection before submitting each mutation.
- A minimal extension ruleset supplies the two mechanics absent from the
  shipped base rulesets. It is source-controlled under server test fixtures,
  included as a Gradle test input, hashed into the same immutable manifest as
  operator mods, and validated with the production link, entry, file-size,
  total-size, name, and directory constraints.
- The worker accepts the explicit test mod root only when
  `UNCIV_V3_UNPACKAGED_DEV=1`; packaged production startup fails closed if that
  override is present. Normal game, Android, desktop, and packaged asset trees
  do not contain the synthetic content.
- Test catalog setup is now single and synchronized across the complete server
  suite. The event parity fixture also clears its global tutorial-completion
  prerequisite, eliminating an order-dependent failure found by the clean
  broad gate.

Verification on 2026-07-27:

- Focused parity and coverage tests pass, comparing all seven responses from
  the new stateful scenario across two independent worker JVMs.
- `./gradlew :server:cleanTest :tests:test :server:test
  :android:lintRelease :android:assembleDebug :desktop:dist --no-parallel
  --no-daemon --console=plain` passes 1,144 JVM/server cases: 1,130 executed,
  14 intentional skips, zero failures, and zero errors. Android release lint,
  debug APK assembly, and desktop distribution also pass.
- The first clean broad run exposed incompatible duplicate catalog
  initialization; after unifying it, the server sweep exposed the
  order-dependent tutorial prerequisite. Both were corrected and the exact
  complete gate above passed. The Android SDK XML version 3/4 compatibility
  warning remains non-failing and no compile, test, lint, packaging, or
  documentation error is deferred.
- `git diff --check` passes. No Rust source changed in this milestone; the Rust
  façade and source-size guardrails remain unchanged.

## Public HTTP origin and response hardening

Implemented on 2026-07-27:

- The Rust API now rejects any request carrying an unapproved browser `Origin`
  before its route handler executes. `UNCIV_V3_ALLOWED_ORIGINS` accepts at most
  16 distinct exact HTTPS origins and rejects empty entries, HTTP, user
  information, paths, queries, malformed header values, and oversized origins.
  Native Android/desktop requests without `Origin` remain valid.
- CORS preflight is restricted to the API's closed method set plus
  `Authorization` and `Content-Type`; wildcard/reflected origins and
  credentialed CORS are absent.
- Every normal or error response overrides caching with `no-store` and adds
  `nosniff`, `no-referrer`, and a camera/microphone/geolocation-denying
  permissions policy. Origin rejection uses the same stable detail-redacted
  JSON error boundary as the rest of the API.
- The operator contract is recorded in
  `docs/operations/authoritative-http-security.md`. TLS/HSTS remains explicitly
  open until the trusted reverse-proxy termination boundary is implemented and
  qualified.

Verification on 2026-07-27:

- Four focused router tests prove exact configuration validation, rejection
  before mutation, native and allowed-origin behavior, hardened response
  headers, and bounded preflight behavior.
- `cargo test --lib` passes 163 active tests with 26 PostgreSQL tests
  explicitly ignored in that lane. `cargo test --bin
  unciv-authoritative-server` passes all 20 HTTP/OpenAPI tests.
- `cargo fmt --all -- --check` and warnings-as-errors
  `cargo clippy --all-targets --all-features -- -D warnings` pass.
- The first warnings-as-errors run exposed a test-only status-code import in
  the production module scope; it was moved into the test module and the exact
  complete gate passed. No HTTP boundary, formatting, compile, test, or Clippy
  error is deferred.

## Bounded WebSocket admission and lifecycle

Implemented on 2026-07-27:

- Authenticated notification upgrades now acquire exact per-account and global
  connection permits before switching protocols. Defaults are four sockets per
  account and 1,024 per process; environment overrides are bounded and
  incoherent or malformed policies fail startup.
- The account broadcast queue remains bounded at 64 hints and lag emits only
  `resync_required`. Subscription drop guards release both counters and remove
  unused account channel state on normal closure, transport error, idle
  eviction, write timeout, or abandoned upgrade.
- The runtime sends numbered pings, requires ping/pong activity within a
  bounded idle interval, ignores text/binary traffic for liveness, and puts a
  hard deadline around every notification, resynchronization, and heartbeat
  write. Existing 4 KiB message/frame and 64 KiB maximum write-buffer limits
  remain enforced.
- `docs/operations/authoritative-websocket-runtime.md` records every variable,
  default, bound, failure behavior, and the non-authoritative HTTP
  reconciliation invariant. Multi-instance fanout and fleet-wide admission
  remain explicitly open.

Verification on 2026-07-27:

- Notification-hub tests prove account isolation, duplicate tolerance,
  per-account/global admission, exact permit reuse, and complete channel
  cleanup. Transport-generic lifecycle tests prove silent peers and blocked
  writers terminate within deterministic deadlines.
- `cargo test --lib` passes 164 active tests with 26 PostgreSQL tests
  explicitly ignored in that lane. `cargo test --bin
  unciv-authoritative-server` passes all 24 HTTP/OpenAPI/runtime tests.
- `cargo fmt --all -- --check` and warnings-as-errors
  `cargo clippy --all-targets --all-features -- -D warnings` pass.
- The first transport-generic compile exposed an `Unpin` limitation in a test
  helper and the first broad Clippy pass rejected redundant result matching.
  The helper was replaced with an explicit pending sink, the assertion was
  simplified, and the exact complete gate passed. No lifecycle, compile, test,
  format, or Clippy error is deferred.

## Shared cross-instance notification fan-out

Implemented on 2026-07-27:

- The durable outbox dispatcher now publishes a closed, versioned JSON hint to
  PostgreSQL channel `unciv_v3_revision_hints` instead of directly addressing
  only its own process-local hub. Every Rust replica establishes its listener
  before its local claimant starts, receives the same bounded frame, resolves
  current recipients from authoritative membership, and fans out only to
  matching local accounts.
- Shared frames are capped at 1 KiB and contain only schema/event/protocol
  versions, game ID, committed revision, and canonical state hash. Unknown
  fields, versions, event types, non-lowercase-SHA-256 hashes, malformed JSON,
  and oversized frames fail closed.
- Publication completes before outbox acknowledgement. A crash in between can
  produce only a duplicate hint, while a failed publication returns the row to
  bounded retry. PostgreSQL notifications remain non-authoritative and
  transient: listener gaps, rejected shared frames, or recipient lookup
  failures send `resync_required` to every local subscriber so authenticated
  HTTP projections recover exact state.
- The transport lives in the focused
  `notifications/dispatcher.rs` implementation module; `main.rs`, `lib.rs`,
  API bootstrap, and module façades remain nearly logic-free.

Verification on 2026-07-27:

- Six focused notification tests pass, covering account isolation, duplicate
  tolerance, admission/cleanup, process-wide resynchronization, exact shared
  codec round trips, and closed decoder rejection.
- A live disposable PostgreSQL 19 Beta 2 instance using the sole pinned digest
  opened two independent replica listeners and local hubs before one
  publication. Both listener loops decoded the exact frame, independently
  queried membership, and delivered the same typed hint to their account-local
  socket within the deadline. The first run exposed an integration-test
  constant-visibility error; the qualified reference fixed it and the
  strengthened live test passed. The disposable container was removed
  afterward.
- The complete serialized PostgreSQL lane then passed all 27 integration tests
  on another fresh exact-digest Beta 2 instance. `cargo test --lib` passes 167
  active tests with those 27 database tests ignored in the ordinary lane;
  `cargo test --bin unciv-authoritative-server` passes all 24 server tests.
  `cargo fmt --all -- --check` and warnings-as-errors
  `cargo clippy --all-targets --all-features -- -D warnings` pass. No
  notification, database, compile, test, format, or Clippy error is deferred.

## Generated AsyncAPI notification lifecycle contract

Implemented on 2026-07-27:

- `GET /api/v3/asyncapi.json` now serves a generated AsyncAPI 3.1 contract for
  the authenticated `/api/v3/notifications` WSS channel. The channel binding
  closes the GET handshake and opaque bearer header; its client-perspective
  receive operation permits exactly `revision_committed` and
  `resync_required`.
- Both JSON payload schemas are closed and derived from the same protocol
  constant used at runtime. Revision hints require the exact five public
  fields, lowercase SHA-256 hash, UUID game ID, and nonnegative revision;
  resynchronization has only its type and protocol version. The runtime now
  serializes a dedicated `ResyncRequiredNotification` DTO rather than an
  independent string literal.
- Lifecycle extensions record that hints never authorize or mutate state,
  initial/reconnect HTTP reconciliation, numbered Ping/Pong liveness, bounded
  queue/frame/message/write-buffer behavior, lag recovery, and
  duplicate/lost/delayed/reordered delivery semantics.
- `--write-openapi` generates OpenAPI and AsyncAPI together. The checked-in
  `openapi/notifications-v3.json` must match byte-for-byte, the public OpenAPI
  advertises the discovery endpoint, and release bundles now require
  `contracts/notifications-v3.json`.

Verification on 2026-07-27:

- The runtime parity test serializes both actual outbound Rust DTOs and proves
  their keys/constants match the two closed AsyncAPI schemas, rejects private
  canonical/worker/account terms, and compares the generated document with the
  checked-in artifact.
- Official `@asyncapi/cli` 2.16.0 validation reports the AsyncAPI 3.1 document
  valid with no governance issues using diagnostics format JSON and
  error-severity failure.
- The complete Rust gate passes 167 active library tests with 27 explicit
  PostgreSQL integration tests ignored in that lane, plus all 25
  HTTP/OpenAPI/AsyncAPI/runtime tests. Release-bundle tamper/extra-file
  verification passes with the AsyncAPI artifact required.
  `cargo fmt --all -- --check` and warnings-as-errors
  `cargo clippy --all-targets --all-features -- -D warnings` pass.
- The first combined generator converted typed OpenAPI through generic
  `serde_json::Value`, which reordered the checked contract and correctly
  failed parity. Direct typed OpenAPI serialization restored stable output;
  both generated parity tests and the complete gate then passed. No contract,
  packaging, compile, test, format, or Clippy error is deferred.

## Bounded outbox poison handling, retention, and operator repair

Implemented on 2026-07-27:

- Migration `0021_outbox_operations.sql` adds consistent dead-letter state,
  excludes poison rows from ordinary claims, and introduces minimal immutable
  delivery receipts plus append-only operator audit records. Release
  compatibility now requires the complete ordered migration set through 21.
- Runtime maximum attempts default to 12 and are bounded to 3-100; the lag
  threshold defaults to 60 seconds and is bounded to 10-86,400 seconds.
  Invalid or non-Unicode environment values fail startup. Failed delivery uses
  the existing bounded delay until the configured attempt atomically
  dead-letters the row with a fixed redacted reason.
- Every replica checks health at a bounded 30-second interval. Any dead-letter
  row or oldest pending event beyond the configured threshold emits a redacted
  alert containing only aggregate counts, ages, and maximum attempts.
  `unciv-v3-outbox status` exposes the same JSON and exits two while alerting.
- `unciv-v3-outbox requeue` and `compact` are dry-run-first. Requeue changes
  only exact still-dead rows, resets delivery state, and audits in one
  transaction. Compaction selects at most 10,000 old delivered rows with
  `SKIP LOCKED`, creates compact receipts, deletes only receipted rows, and
  audits atomically.
- Receipts preserve outbox ID, game/revision/topic identity, timestamps, and
  attempt count but no payload, claim token, or error. Reconciliation counts
  active events plus receipts and detects orphan receipts; repair will not
  recreate a compacted event. Canonical revisions, snapshots, commands,
  membership, and projections are never changed by this workflow.
- `docs/operations/authoritative-outbox-operations.md` records runtime bounds,
  alert semantics, exit codes, incident review, exact commands, compaction
  invariants, and backup/restore requirements.

Verification on 2026-07-27:

- A fresh exact-digest PostgreSQL 19 Beta 2 scenario forced three failures,
  proved the third attempt dead-lettered and left the claim index, observed the
  health alert, previewed/applied audited requeue, delivered the reset row,
  previewed/applied receipt compaction, and proved reconciliation stayed at
  zero findings before and after compaction.
- The complete serialized PostgreSQL lane passes all 28 integration tests on a
  separate fresh exact-digest Beta 2 database, including recovery, repair,
  reconciliation, retention, failover, worker-death, and multi-replica paths.
- The complete Rust gate passes 168 active library tests with the 28 explicit
  PostgreSQL tests ignored outside their live lane, all 25 server
  HTTP/OpenAPI/AsyncAPI/runtime tests, and the `unciv-v3-outbox` binary target.
  `cargo fmt --all -- --check`, warnings-as-errors
  `cargo clippy --all-targets --all-features -- -D warnings`, and
  `git diff --check` pass.
- Both disposable PostgreSQL containers were removed. No database, compile,
  test, format, Clippy, diff, or cleanup error remains deferred.

## Rich server-seeded creation parity

Implemented on 2026-07-27:

- The packaged-worker random parity suite now creates a nontrivial canonical
  Gods & Kings game through the real private `GameStarter` boundary using a
  server-owned seed, three major civilizations, three city states, a small
  fractal hex map, abundant resources, shuffled players, strategic balance,
  raging barbarians, ancient ruins, and natural wonders.
- Two independent packaged JVMs must return an exactly identical complete
  worker response, canonical hash, and snapshot. The test inspects the
  resulting canonical state to prove that resources, natural wonders, ruins,
  city states, and two server AI players were actually generated. Changing
  only the server seed must change both the canonical hash and snapshot.
- The prior compact setup construction was extracted into one test helper so
  the combat and event fixtures retain their exact behavior while random setup
  branches can be selected explicitly.
- Kotlin release compatibility now asserts migration 21, matching the checked
  PostgreSQL 19 Beta 2 contract and Rust release-bundle verifier.

Verification on 2026-07-27:

- Focused rich-creation, combat-randomness, event-choice, and release-contract
  tests pass through the packaged worker.
- `./gradlew :tests:test :server:test --no-parallel` passes 1,145 cases:
  1,092 core/client cases with 13 intentional skips and 53 server cases with
  one intentional skip. The server lane was rerun after correcting the stale
  migration-20 assertion and passes cleanly.
- `git diff --check` passes. No compile, test, release-contract, or diff error
  remains deferred.

## All server-generated map types have fresh-process parity

Implemented on 2026-07-27:

- One bounded packaged-worker scenario now creates canonical games for every
  public API-v3 generated map type: Pangaea, Small Continents, Perlin, Fractal,
  Continent and Islands, Archipelago, Two Continents, Three Continents, Inner
  Sea, Lakes, Four Corners, Spiral, and Boreal.
- The complete 13-game sequence runs independently inside two newly started
  packaged Kotlin workers. Every serialized worker response, canonical hash,
  snapshot, projection, event set, and turn metadata must match exactly by
  sequence position.
- Each result is decoded and checked against the requested engine `MapType`,
  server-owned seed, and a nonempty generated map. The explicit API-to-engine
  mapping and enum cardinality check ensure a newly added public generator
  cannot silently escape parity classification.

Verification on 2026-07-27:

- The focused packaged-worker random parity class passes all generator, rich
  setup, combat randomness, and event-choice scenarios.
- `./gradlew :tests:test :server:test --no-parallel` passes 1,146 cases:
  1,092 core/client cases with 13 intentional skips and 54 server cases with
  one intentional skip.
- The expanded random parity file remains 322 lines. `git diff --check`
  passes, and no compile, test, formatting, or diff error remains deferred.

## Multi-round server AI campaign parity

Implemented on 2026-07-27:

- A dedicated packaged-worker scenario creates one four-major-civilization
  canonical game with three server AI players, two city states, and normal
  barbarians. The authenticated human selects a server-derived prerequisite
  path to the furthest available technology, avoiding client-authored research
  queue state.
- The private Kotlin worker executes eight complete
  human-to-three-AI-to-human rounds. Every round must increment the canonical
  turn exactly once and return current-player control to the authenticated
  human; all three AI civilizations must establish cities.
- The complete ten-response sequence—creation, research selection, and eight
  end-turn results—runs in two independent packaged JVMs. Every response,
  snapshot, canonical hash, projection, event set, and turn field must match
  exactly at each sequence position.
- `PackagedWorkerScenarioFixture` now accepts bounded major-civilization and
  barbarian settings while preserving its prior defaults for every existing
  command-family scenario. The campaign remains isolated in its own 89-line
  logical test module.

Verification on 2026-07-27:

- The focused multi-round campaign passes across both fresh workers.
- `./gradlew :tests:test :server:test --no-parallel` passes 1,147 cases:
  1,092 core/client cases with 13 intentional skips and 55 server cases with
  one intentional skip.
- The initial focused compile identified an incorrect test-only `Technology`
  import; it was corrected to the engine model package before the clean focused
  and complete reruns. `git diff --check` passes, and no compile, test, or diff
  error remains deferred.

## Executable packaged-worker parity inventory and first stateful unit batch

Implemented on 2026-07-26:

- Added an executable registry for all 84 classified `WorkerOperation` wire
  variants. The test derives the live sealed subtype names from the serializer
  descriptor and fails if a protocol operation is added, removed, or renamed
  without an explicit fresh-process evidence classification.
- Extended the shared packaged-worker harness to execute a sequence of typed
  requests in one fresh JVM and repeat the whole scenario in a second fresh
  JVM. It compares every complete serialized response, including errors,
  actor, projections, snapshot, and canonical state hash.
- Added a stateful unit fixture that creates a seeded game and then performs an
  exact move, rename, worker-derived legal posture, and disband. Both
  independent packaged JVMs produce byte-identical responses after every
  operation and the final canonical state is asserted.
- Fresh-process evidence now covers 11 of 84 operations. The remaining 73 are
  deliberately classified as in-process-only debt; the exhaustive checklist
  remains unchecked in `missing_multiplayer.md`.

Focused verification on 2026-07-26:

- `./gradlew :server:test --tests
  com.unciv.app.server.authoritative.PackagedWorkerParityCoverageTests --tests
  com.unciv.app.server.authoritative.PackagedWorkerUnitOrderParityTests`
  passes all three focused tests.
- `./gradlew :tests:test :server:test :android:lintRelease
  :android:assembleDebug :desktop:dist --no-parallel --console=plain` passes
  1,119 JVM/server tests with 14 intentional skips and no failures or errors,
  plus Android release lint, the debug APK, and desktop packaging.
- The first coverage implementation inspected the outer sealed serializer
  envelope (`type` and `value`) rather than its subtype descriptor; it was
  corrected before the clean run. The first posture fixture assumed `Sleep`
  for a fortifiable unit; it now selects an actually worker-advertised posture
  from canonical state. No failure was deferred.
- This milestone changes only descriptive test modules and documentation; no
  production façade or gameplay module grew.

## Packaged-worker city and projection parity batch

Implemented on 2026-07-26:

- Extracted deterministic seeded-game request construction and snapshot loading
  into the focused `PackagedWorkerScenarioFixture` test module. Stateful
  packaged-worker tests now share setup without growing a generic helper or
  duplicating the complete setup contract.
- Added a two-fresh-JVM city sequence covering typed city founding, private
  player projection, three ordinary construction selections, one adjacent
  queue reorder, exact queue removal, and one projection-advertised
  queue-context action. The final city queue is checked against canonical
  snapshot state.
- The same canonical state now exercises the distinct public spectator
  projection boundary. Complete serialized worker responses are compared after
  every step, including both projection types and every intermediate canonical
  snapshot/hash.
- Fresh-process evidence now covers 18 of 84 classified worker operations. The
  remaining 68 retain their explicit `InProcessOnly` classification and remain
  unchecked in `missing_multiplayer.md`.

Focused verification on 2026-07-26:

- `./gradlew :server:test --tests
  com.unciv.app.server.authoritative.PackagedWorkerParityCoverageTests --tests
  com.unciv.app.server.authoritative.PackagedWorkerUnitOrderParityTests --tests
  com.unciv.app.server.authoritative.PackagedWorkerCityQueueParityTests
  --no-parallel --console=plain` passes all four focused tests.
- `./gradlew :tests:test :server:test :android:lintRelease
  :android:assembleDebug :desktop:dist --no-parallel --console=plain` passes
  1,120 JVM/server tests with 14 intentional skips and no failures or errors,
  plus Android release lint, the debug APK, and desktop packaging.
- The first queue fixture assumed append order instead of reading the canonical
  worker result and was rejected by the exact-entry guard. It now derives
  reorder/removal coordinates and names from the returned snapshot. A later
  assertion incorrectly required a removed construction to remain absent even
  when the projected context action legally re-added it; the test now asserts
  the intended final top entry and queue size. No failure was deferred.
- Test modules remain shallow: the shared fixture is under 80 lines, the city
  scenario under 170, and the refactored unit scenario under 110. No production
  façade or gameplay module changed.

## Packaged-worker durable unit-order parity batch

Implemented on 2026-07-26:

- Added a deterministic stateful fixture for server-owned multi-turn movement.
  Its canonical setup marks one far reachable destination explored, then two
  independent packaged workers apply the same `MoveUnitToward` and
  `CancelUnitMovementOrder` sequence.
- The same scenario uses separate canonical units for exploration and broad
  automation. Both enable/disable transitions execute through the packaged
  shared engine and every complete intermediate response is byte-identical
  across fresh JVMs.
- The seeded scenario helper now owns snapshot encoding as well as decoding, so
  specialized fixture preparation remains centralized without exposing mutable
  state to production code.
- Fresh-process evidence now covers 22 of 84 classified worker operations. The
  remaining 64 keep their explicit `InProcessOnly` classification.

Focused verification on 2026-07-26:

- `./gradlew :server:test --tests
  com.unciv.app.server.authoritative.PackagedWorkerParityCoverageTests --tests
  com.unciv.app.server.authoritative.PackagedWorkerUnitAutomationParityTests
  --no-parallel --console=plain` passes all three focused tests.
- `./gradlew :tests:test :server:test :android:lintRelease
  :android:assembleDebug :desktop:dist --no-parallel --console=plain` passes
  1,121 JVM/server tests with 14 intentional skips and no failures or errors,
  plus Android release lint, the debug APK, and desktop packaging.
- The fixture derives the far destination from canonical pathfinding and uses
  separate units for movement, exploration, and automation so immediate shared
  engine actions cannot make a later transition artificially illegal.
- The new scenario is a descriptive module under 150 lines; the shared fixture
  remains under 80 lines. No production façade or gameplay module changed.

## Packaged-worker city citizen and economy parity batch

Implemented on 2026-07-26:

- Added one projection-driven two-fresh-JVM city scenario. A deterministic
  fixture supplies a founded city, canonical treasury, and one sellable
  specialist-slot building; every submitted specialist, tile, focus, purchase,
  and sale choice is then selected from worker-owned state or projection.
- Covered specialist count, manual-specialist mode, exact city-tile locking,
  citizen reset, avoid-growth, and citizen focus. The sequence verifies that
  population is reassigned by the shared engine, locks are cleared, and final
  policy state is canonical.
- Covered server-priced single-tile acquisition, bounded ring acquisition, and
  building sale. The client-side fixture supplies no price or refund; the
  worker derives costs, ownership, and treasury changes from canonical state.
- Every complete response, intermediate snapshot/hash, and player projection is
  byte-identical across independent packaged JVMs. Fresh-process evidence now
  covers 31 of 84 classified operations; 53 remain explicitly in-process-only.

Focused verification on 2026-07-26:

- `./gradlew :server:test --tests
  com.unciv.app.server.authoritative.PackagedWorkerParityCoverageTests --tests
  com.unciv.app.server.authoritative.PackagedWorkerCityCitizenParityTests
  --no-parallel --console=plain` passes all three focused tests.
- `./gradlew :tests:test :server:test :android:lintRelease
  :android:assembleDebug :desktop:dist --no-parallel --console=plain` passes
  1,122 JVM/server tests with 14 intentional skips and no failures or errors,
  plus Android release lint, the debug APK, and desktop packaging.
- The scenario derives specialist capacity, worked tile, selectable focus,
  affordable tile, and affordable ring from fresh worker projections. Its only
  direct fixture preparation is deterministic prerequisite state that normal
  gameplay would have produced earlier.
- The new scenario remains under 250 lines and no production façade or gameplay
  module changed.

## Packaged-worker research and policy parity batch

Implemented on 2026-07-26:

- Added a two-fresh-JVM research/policy scenario that first builds a canonical
  research path through the typed worker command, then chooses a legal queue
  edit from the returned private player projection.
- Deterministic fixture prerequisites add one free technology, one free policy,
  and one research-completion alert. The subsequent free technology, policy,
  and opaque completion prompt are selected only from worker projection and
  submitted through their closed typed operations.
- The final player projection proves the technology and policy were committed
  and the acknowledged completion prompt was removed. Every full response and
  intermediate canonical snapshot/hash is byte-identical across independent
  packaged JVMs.
- Fresh-process evidence now covers 35 of 84 classified worker operations; the
  remaining 51 retain explicit `InProcessOnly` classifications.

Focused verification on 2026-07-26:

- `./gradlew :server:test --tests
  com.unciv.app.server.authoritative.PackagedWorkerParityCoverageTests --tests
  com.unciv.app.server.authoritative.PackagedWorkerResearchPolicyParityTests
  --no-parallel --console=plain` passes all three focused tests.
- `./gradlew :tests:test :server:test :android:lintRelease
  :android:assembleDebug :desktop:dist --no-parallel --console=plain` passes
  1,123 JVM/server tests with 14 intentional skips and no failures or errors,
  plus Android release lint, the debug APK, and desktop packaging.
- Queue entry/action, free technology, policy, and completion prompt identity
  are all projection-derived; no client-authored research dependency, policy
  eligibility, or prompt identity enters the worker request.
- The new test is a descriptive module under 160 lines and no production
  façade or gameplay module changed.

## Packaged-worker multiplayer lifecycle parity batch

Implemented on 2026-07-26:

- Added independent two-fresh-JVM scenarios for authenticated self-resignation,
  owner kick, and timer-qualified owner force-resignation. Each scenario creates
  a canonical game and assigns the second authenticated account through the
  packaged worker before exercising lifecycle authority.
- Self-resignation and force-resignation of the current player prove that the
  shared engine transfers the player to AI and performs canonical server-side
  turn rotation. Kick proves the selected non-owner membership is converted to
  AI without trusting a client-authored player identity.
- Force-resignation uses an explicit canonical turn start, target allowance,
  and worker request time. The worker returns the exact civilization it
  force-resigned; the final snapshot proves cleared player identity and
  restored control to the owner.
- Every complete response and canonical snapshot/hash is byte-identical across
  independent packaged JVMs. Fresh-process evidence now covers 38 of 84 classified
  operations; 48 remain explicitly in-process-only.

Focused verification on 2026-07-26:

- `./gradlew :server:test --tests
  com.unciv.app.server.authoritative.PackagedWorkerParityCoverageTests --tests
  com.unciv.app.server.authoritative.PackagedWorkerLifecycleParityTests
  --no-parallel --console=plain` passes all five focused tests.
- `./gradlew :tests:test :server:test :android:lintRelease
  :android:assembleDebug :desktop:dist --no-parallel --console=plain` passes
  1,126 JVM/server tests with 14 intentional skips and no failures or errors,
  plus Android release lint, the debug APK, and desktop packaging.
- The first compile exposed a test import using the wrong `PlayerType` package
  and was corrected. The first kick run exposed a brittle assertion about
  serialized notification presentation; canonical AI transfer, cleared player
  identity, and complete response parity remain the asserted contract. No
  failure was deferred.
- The lifecycle test is a descriptive module under 160 lines. The shared
  scenario fixture gained only an optional authenticated-actor parameter; no
  production façade or gameplay module changed.

## Packaged-worker unit advancement parity batch

Implemented on 2026-07-26:

- Added a projection-driven two-fresh-JVM scenario with an owned city, one
  XP-ready military unit, and two affordable same-type upgrade candidates.
  Fixture preparation unlocks only the canonical prerequisite technology and
  supplies treasury; the submitted promotion and upgrade target come from the
  private worker projection.
- Promotion spends server-derived XP and saves the canonical unit promotion set
  as the city default. A separate typed command disables that exact projected
  base-unit preference, and the final projection proves the preference changed.
- One bounded upgrade command upgrades both stable unit IDs to their common
  projected target. Canonical gold cost, target equivalence, placement, and
  resulting unit type remain worker-derived.
- Every complete response and intermediate snapshot/hash is byte-identical
  across independent packaged JVMs. Fresh-process evidence now covers 41 of 84
  sealed operations; 45 remain explicitly in-process-only.

Focused verification on 2026-07-26:

- `./gradlew :server:test --tests
  com.unciv.app.server.authoritative.PackagedWorkerParityCoverageTests --tests
  com.unciv.app.server.authoritative.PackagedWorkerUnitAdvancementParityTests
  --no-parallel --console=plain` passes all three focused tests.
- Promotion identity, saved base-unit preference, and the common upgrade target
  are all selected from worker projections. The client fixture supplies no XP
  cost or upgrade gold cost.
- The new test is a descriptive module under 150 lines and no production
  façade or gameplay module changed.
- `./gradlew :tests:test :server:test :android:lintRelease
  :android:assembleDebug :desktop:dist --no-parallel --console=plain` passes
  1,127 JVM/server tests with 14 intentional skips and no failures or errors,
  plus Android release lint, the debug APK, and desktop packaging.

## Packaged-worker unit construction parity batch

Implemented on 2026-07-26:

- Added a projection-driven two-fresh-JVM scenario for tile-improvement
  start/cancel, long-distance road connection, and friendly unit swapping.
- Every improvement name, optional queued improvement, road destination, and
  swap destination submitted to the worker comes from the authenticated
  player's private projection. Canonical build legality, pathfinding,
  automation, movement cost, and unit placement remain Kotlin-engine decisions.
- The final projection proves cancellation cleared the improvement queue and
  both stable unit IDs exchanged their exact coordinates. Every one of the ten
  complete responses and intermediate snapshots/hashes is byte-identical
  across independent packaged JVMs.
- Fresh-process evidence now covers 44 of 84 classified operations; 40 remain
  explicitly classified as in-process-only debt.

Focused verification on 2026-07-26:

- `./gradlew :server:test --tests
  com.unciv.app.server.authoritative.PackagedWorkerParityCoverageTests --tests
  com.unciv.app.server.authoritative.PackagedWorkerUnitConstructionParityTests
  --no-parallel --console=plain` passes all three focused tests.
- The initial fixture used hash-backed visible-tile iteration and the parity
  harness correctly rejected different setup coordinates between runs.
  Coordinate sorting removed that fixture nondeterminism before the clean
  rerun; no production behavior was weakened or bypassed.
- `./gradlew :tests:test :server:test :android:lintRelease
  :android:assembleDebug :desktop:dist --no-parallel --console=plain` passes
  1,128 JVM/server tests with 14 intentional skips and no failures or errors,
  plus Android release lint, the debug APK, and desktop packaging.

## Packaged-worker city context parity batch

Implemented on 2026-07-26:

- Added a two-fresh-JVM city scenario covering perpetual construction,
  ordinary building purchase, captured-city disposition, and subsequent city
  governance.
- The selected perpetual option, affordable currency/cost pair, captured-city
  action, and governance action all come from the authenticated player's
  projection. The worker alone derives reserves, purchase legality, canonical
  city ownership, pending conquest alerts, razing legality, and state changes.
- The final canonical snapshot proves the perpetual queue entry remains, the
  purchased building is complete, annexation removed puppet status and its
  pending alert, and the projected governance action took effect.
- All ten complete responses and intermediate snapshots/hashes are
  byte-identical across independent packaged JVMs. Fresh-process evidence now
  covers 48 of 84 classified operations; 36 remain in-process-only debt.
- Tile-targeted construction queue/purchase operations remain classified as
  debt because the pinned Vanilla ruleset exposes no building with a
  create-one-improvement placement contract; they were not marked complete
  using an artificial or non-production ruleset path.

Focused verification on 2026-07-26:

- `./gradlew :server:test --tests
  com.unciv.app.server.authoritative.PackagedWorkerParityCoverageTests --tests
  com.unciv.app.server.authoritative.PackagedWorkerCityContextParityTests
  --no-parallel --console=plain` passes all three focused tests.
- `./gradlew :tests:test :server:test :android:lintRelease
  :android:assembleDebug :desktop:dist --no-parallel --console=plain` passes
  1,129 JVM/server tests with 14 intentional skips and no failures or errors,
  plus Android release lint, the debug APK, and desktop packaging.

## Packaged-worker turn-decision parity batch

Implemented on 2026-07-26:

- Added a Gods & Kings two-fresh-JVM scenario for diplomatic-victory voting,
  free great-person selection, and pantheon belief selection. It creates the
  canonical pending conditions, then submits only identifiers exposed by the
  authenticated player's private projection.
- The final state proves the vote was recorded, the projected land great person
  was placed and consumed its credit, the projected belief was adopted, and
  all three mandatory-turn prompts disappeared.
- The first cross-process run found a real engine defect: the one-time unit-name
  trigger used Kotlin's process-local default random source, so otherwise
  identical great-person commands selected different historical names.
  `UniqueTriggerActivation` now uses its existing state-derived RNG for that
  shuffle. This preserves random-looking single-player behavior while making
  authoritative replay deterministic from canonical state.
- All seven complete responses and intermediate snapshots/hashes are now
  byte-identical across independent packaged JVMs. Fresh-process evidence
  covers 51 of 84 classified operations; 33 remain in-process-only debt.

Focused verification on 2026-07-26:

- `./gradlew :server:test --tests
  com.unciv.app.server.authoritative.PackagedWorkerParityCoverageTests --tests
  com.unciv.app.server.authoritative.PackagedWorkerTurnDecisionParityTests
  --no-parallel --console=plain` passes all three focused tests.
- `./gradlew :tests:test :server:test :android:lintRelease
  :android:assembleDebug :desktop:dist --no-parallel --console=plain` passes
  1,130 JVM/server tests with 14 intentional skips and no failures or errors,
  plus Android release lint, the debug APK, and desktop packaging.

## Packaged-worker tactical parity batch

Implemented on 2026-07-26:

- Added one deterministic two-fresh-JVM war-state scenario covering pillaging,
  paradrops, city bombardment, air sweeps with hidden interceptors, and nuclear
  strikes.
- The submitted unit IDs and every destination/target come from the
  authenticated player's private projection. The worker derives territory
  legality, pillage healing and movement, paradrop range/action consumption,
  bombard damage, interceptor selection, nuclear blast effects, and all combat
  randomness.
- The nuclear command runs last so the scenario observes every preceding
  transition independently. The final snapshot proves the neutral Farm was
  pillaged and healed the unit, the paratrooper reached its projected
  coordinate, the city attack was recorded, the fighter consumed its attack,
  and the nuclear unit was consumed.
- All twelve complete responses and intermediate snapshots/hashes are
  byte-identical across independent packaged JVMs. Fresh-process evidence now
  covers 56 of 84 classified operations; 28 remain in-process-only debt.

Focused verification on 2026-07-26:

- `./gradlew :server:test --tests
  com.unciv.app.server.authoritative.PackagedWorkerParityCoverageTests --tests
  com.unciv.app.server.authoritative.PackagedWorkerTacticalParityTests
  --no-parallel --console=plain` passes all three focused tests.
- The first fixture incorrectly placed the pillage target inside the actor's
  new city border; the private projection correctly reported that pillaging
  was illegal. The clean fixture uses a deterministically selected visible
  neutral tile and introduces no deprecated test API use or compiler warning.
- `./gradlew :tests:test :server:test :android:lintRelease
  :android:assembleDebug :desktop:dist --no-parallel --console=plain` passes
  1,131 JVM/server tests with 14 intentional skips and no failures or errors,
  plus Android release lint, the debug APK, and desktop packaging.

## Packaged-worker bilateral-trade parity batch

Implemented on 2026-07-26:

- Added one deterministic two-account scenario covering all five bilateral
  trade operations: offer, counter, decline, retract, and accept.
- Each account submits only the offer entries and request IDs advertised by its
  authenticated private projection. The worker owns availability,
  affordability, request replacement/removal, transfer, and bilateral trade
  commitment.
- The fixture exercises both actor identities and both current-player
  perspectives. It proves declined and retracted negotiations leave no
  committed trade, while the accepted final offer transfers the exact gold
  amount and creates matching canonical trades for both civilizations.
- All fifteen complete responses and intermediate snapshots/hashes are
  byte-identical across independent packaged JVMs. Fresh-process evidence now
  covers 61 of 84 classified operations; 23 remain in-process-only debt.

Focused verification on 2026-07-26:

- `./gradlew :server:test --tests
  com.unciv.app.server.authoritative.PackagedWorkerParityCoverageTests --tests
  com.unciv.app.server.authoritative.PackagedWorkerTradeParityTests
  --no-parallel --console=plain` passes all three focused tests.
- `./gradlew :tests:test :server:test :android:lintRelease
  :android:assembleDebug :desktop:dist --no-parallel --console=plain` passes
  1,132 JVM/server tests with 14 intentional skips and no failures or errors,
  plus Android release lint, the debug APK, and desktop packaging.

## Packaged-worker major-diplomacy parity batch

Implemented on 2026-07-26:

- Added a deterministic two-account scenario covering all five
  major-civilization diplomacy operations: offer friendship, respond to its
  prompt, make a diplomatic demand, denounce, and declare war.
- The first player selects only a partner and demand advertised by its private
  projection; the second player submits only the exact projected prompt ID.
  The worker derives all relationship eligibility, prompt identity, accepted
  demand consequences, denouncement effects, and war state.
- Separate prepared games keep the friendship, demand, and hostile-action
  preconditions independent. Canonical snapshots prove the accepted friendship
  flag, the accepted demand flag, and the final war state.
- All fourteen complete responses and intermediate snapshots/hashes are
  byte-identical across independent packaged JVMs. Fresh-process evidence now
  covers 66 of 84 classified operations; 18 remain in-process-only debt.

Focused verification on 2026-07-26:

- `./gradlew :server:cleanTest :server:test --tests
  com.unciv.app.server.authoritative.PackagedWorkerMajorDiplomacyParityTests
  --no-parallel --console=plain` passes the new fresh-worker scenario.
- `./gradlew :server:cleanTest :tests:test :server:test :android:lintRelease
  :android:assembleDebug :desktop:dist --no-parallel --no-daemon
  --console=plain` passes 1,133 JVM/server tests with 14 intentional skips and
  no failures or errors, plus Android release lint, the debug APK, and desktop
  packaging.

## Packaged-worker espionage parity batch

Implemented on 2026-07-26:

- Added one deterministic scenario with a city state covering both espionage
  operations: moving a spy between an eligible major-civilization city and its
  hideout, then staging and cancelling a city-state coup.
- The client submits only the spy name and city/coup choices present in its
  private projection. The worker derives explored-city eligibility, hideout
  availability, coup eligibility, action transitions, and countdown values.
- The fixture asserts the canonical moving, coup, and counter-intelligence
  states after each command. All nine complete responses and intermediate
  snapshots/hashes are byte-identical across independent packaged JVMs.
  Fresh-process evidence now covers 68 of 84 classified operations; 16 remain
  in-process-only debt.

Focused verification on 2026-07-26:

- `./gradlew :server:cleanTest :server:test --tests
  com.unciv.app.server.authoritative.PackagedWorkerEspionageParityTests
  --no-parallel --console=plain` passes the new fresh-worker scenario.
- `./gradlew :server:cleanTest :tests:test :server:test :android:lintRelease
  :android:assembleDebug :desktop:dist --no-parallel --no-daemon
  --console=plain` passes 1,134 JVM/server tests with 14 intentional skips and
  no failures or errors, plus Android release lint, the debug APK, and desktop
  packaging.

## Packaged-worker Great Person and unit-gift parity batch

Implemented on 2026-07-27:

- Added a deterministic scenario using shipped Vanilla ruleset assets covering
  a Great Scientist's projected `HurryResearch` action and gifting a projected
  military unit to a city state.
- The private projection selects the exact Great Person action and confirms
  unit-gift eligibility; the worker derives technology progress, Great Person
  consumption, city-state recipient, ownership transfer, and influence.
- Canonical snapshots prove the scientist was consumed with research advanced,
  then the gifted unit was removed from the actor, added to the city state, and
  granted the canonical five influence. All five complete responses and
  intermediate snapshots/hashes are byte-identical across independent packaged
  JVMs. Fresh-process evidence now covers 70 of 84 classified operations; 14 remain
  in-process-only debt.

Focused verification on 2026-07-27:

- `./gradlew :server:cleanTest :server:test --tests
  com.unciv.app.server.authoritative.PackagedWorkerSpecialUnitParityTests
  --no-parallel --console=plain` passes the new fresh-worker scenario.
- `./gradlew :server:cleanTest :tests:test :server:test :android:lintRelease
  :android:assembleDebug :desktop:dist --no-parallel --no-daemon
  --console=plain` passes 1,135 JVM/server tests with 14 intentional skips and
  no failures or errors, plus Android release lint, the debug APK, and desktop
  packaging.

## Packaged-worker city-state diplomacy parity batch

Implemented on 2026-07-27:

- Added one deterministic city-state scenario covering gold gifts, protection
  pledges, negotiated peace, and city-state protection-prompt responses.
- The actor submits only an advertised gold amount, city-state partner, and
  projected prompt ID/response. The worker derives affordability, influence,
  protection eligibility, peace eligibility, prompt consequences, and all
  diplomatic modifiers.
- Prepared war state and the prompt are canonical snapshot inputs; the scenario
  proves the gold/influence transfer, the cleared war state, the condemnation
  modifier, and alert removal after each worker-owned transition.
- All nine complete responses and intermediate snapshots/hashes are
  byte-identical across independent packaged JVMs. Fresh-process evidence now
  covers 74 of 84 classified operations; 10 remain in-process-only debt.

Focused verification on 2026-07-27:

- `./gradlew :server:cleanTest :server:test --tests
  com.unciv.app.server.authoritative.PackagedWorkerCityStateParityTests
  --no-parallel --console=plain` passes the new fresh-worker scenario.
- `./gradlew :server:cleanTest :tests:test :server:test :android:lintRelease
  :android:assembleDebug :desktop:dist --no-parallel --no-daemon
  --console=plain` passes 1,136 JVM/server tests with 14 intentional skips and
  no failures or errors, plus Android release lint, the debug APK, and desktop
  packaging.

## Packaged-worker city-state tribute parity

Implemented on 2026-07-27:

- Added a deterministic Vanilla scenario that creates the city-state's capital
  and a canonical intimidating military presence. The player submits only the
  city-state ID and gold-versus-worker choice advertised by the projection.
- The worker derives tribute willingness and amount, then applies the gold
  transfer, influence penalty, and recent-bullying cooldown. All three complete
  responses and state hashes are byte-identical across independent packaged
  JVMs. Fresh-process evidence now covers 75 of 84 classified operations; 9
  remain in-process-only debt.

Focused verification on 2026-07-27:

- `./gradlew :server:cleanTest :server:test --tests
  com.unciv.app.server.authoritative.PackagedWorkerCityStateTributeParityTests
  --no-parallel --console=plain` passes the new fresh-worker scenario.
- `./gradlew :server:cleanTest :tests:test :server:test :android:lintRelease
  :android:assembleDebug :desktop:dist --no-parallel --no-daemon
  --console=plain` passes 1,137 JVM/server tests with 14 intentional skips and
  no failures or errors, plus Android release lint, the debug APK, and desktop
  packaging.

## Packaged-worker diplomatic-marriage parity

Implemented on 2026-07-27:

- Added a Gods & Kings scenario whose canonical game ID makes server-side
  civilization assignment select Austria; no snapshot identity is rewritten.
- The projection derives marriage availability and cost after canonical
  alliance/cooldown preparation. The worker deducts gold, defeats the city
  state, transfers its cities, and emits the exact diplomatic-marriage alerts.
- All three complete responses and state hashes are byte-identical across
  independent packaged JVMs. Fresh-process evidence now covers 76 of 84
  classified operations; eight remain in-process-only debt.

Focused verification on 2026-07-27:

- `./gradlew :server:cleanTest :server:test --tests
  com.unciv.app.server.authoritative.PackagedWorkerCityStateMarriageParityTests
  --no-parallel --console=plain` passes the new fresh-worker scenario.
- `./gradlew :server:cleanTest :tests:test :server:test :android:lintRelease
  :android:assembleDebug :desktop:dist --no-parallel --no-daemon
  --console=plain` passes 1,138 JVM/server tests with 14 intentional skips and
  no failures or errors, plus Android release lint, the debug APK, and desktop
  packaging.

## Packaged-worker city-state improvement-gift parity

Implemented on 2026-07-27:

- Added a deterministic Vanilla scenario with a canonical city-state-owned
  strategic-resource tile, sufficient influence, researched technology, and
  actor gold. The setup explicitly proves the resource/improvement pairing and
  canonical build eligibility before crossing the worker boundary.
- The projection advertises the exact tile, improvement, and server-derived
  cost. The worker revalidates eligibility, deducts the canonical cost, builds
  the improvement, preserves city-state ownership, and refreshes its resources.
- All three complete responses and state hashes are byte-identical across
  independent packaged JVMs. Fresh-process evidence now covers 77 of 84
  classified operations; seven remain in-process-only debt.

Focused verification on 2026-07-27:

- `./gradlew :server:cleanTest :server:test --tests
  com.unciv.app.server.authoritative.PackagedWorkerParityCoverageTests --tests
  com.unciv.app.server.authoritative.PackagedWorkerCityStateImprovementGiftParityTests
  --no-parallel --console=plain` passes the registry and new fresh-worker
  scenario.
- `./gradlew :server:cleanTest :tests:test :server:test :android:lintRelease
  :android:assembleDebug :desktop:dist --no-parallel --no-daemon
  --console=plain` passes 1,139 JVM/server tests with 14 intentional skips and
  no failures or errors, plus Android release lint, the debug APK, and desktop
  packaging. The existing SDK XML-version mismatch warning remains
  non-failing; no test or build error is deferred.

## Packaged-worker religious-unit parity

Implemented on 2026-07-27:

- Added a deterministic Gods & Kings scenario that founds a capital, selects a
  canonical pantheon, and places a Great Prophet on the capital tile.
- The private projection advertises only the canonically legal
  `FoundReligion` action. The worker invokes the shared religion rules,
  consumes the prophet, enters the founding-religion state, and derives the
  required belief types, available beliefs, and available religion identities.
- All four complete responses and state hashes are byte-identical across
  independent packaged JVMs. Fresh-process evidence now covers 78 of 84
  classified operations; six remain in-process-only debt.

Focused verification on 2026-07-27:

- `./gradlew :server:cleanTest :server:test --tests
  com.unciv.app.server.authoritative.PackagedWorkerParityCoverageTests --tests
  com.unciv.app.server.authoritative.PackagedWorkerReligiousUnitParityTests
  --no-parallel --console=plain` passes the registry and religious-unit
  scenario.
- `./gradlew :server:cleanTest :tests:test :server:test :android:lintRelease
  :android:assembleDebug :desktop:dist --no-parallel --no-daemon
  --console=plain` passes 1,140 JVM/server tests with 14 intentional skips and
  no failures or errors, plus Android release lint, the debug APK, and desktop
  packaging. The existing SDK XML-version mismatch warning remains
  non-failing; no test or build error is deferred.

## Packaged-worker capital-project parity

Implemented on 2026-07-27:

- Added a deterministic Vanilla scenario that creates a capital and places a
  shipped spaceship-part unit on its center tile.
- The private projection advertises the server-derived `The Spaceship` capital
  project only while the unit is in its own capital. The worker revalidates
  that placement, consumes the unit, and increments the canonical spaceship
  part inventory exactly once.
- All three complete responses and state hashes are byte-identical across
  independent packaged JVMs. Fresh-process evidence now covers 79 of 84
  classified operations; five remain in-process-only debt.

Focused verification on 2026-07-27:

- `./gradlew :server:cleanTest :server:test --tests
  com.unciv.app.server.authoritative.PackagedWorkerParityCoverageTests --tests
  com.unciv.app.server.authoritative.PackagedWorkerCapitalProjectParityTests
  --no-parallel --console=plain` passes the registry and capital-project
  scenario.
- `./gradlew :server:cleanTest :tests:test :server:test :android:lintRelease
  :android:assembleDebug :desktop:dist --no-parallel --no-daemon
  --console=plain` passes 1,141 JVM/server tests with 14 intentional skips and
  no failures or errors, plus Android release lint, the debug APK, and desktop
  packaging. The existing SDK XML-version mismatch warning remains
  non-failing; no test or build error is deferred.

## Packaged-worker unit-unique action parity

Implemented on 2026-07-27:

- Added one deterministic Vanilla scenario covering a Great Engineer's
  projected instant-Manufactory action and a Great General's projected
  golden-age trigger.
- Both commands submit only opaque action identities selected from the private
  projection. The worker reconstructs the shared Kotlin callbacks, revalidates
  the unit and tile state, builds the canonical improvement, consumes each
  unit, and enters the server-derived golden age.
- All five complete responses and state hashes are byte-identical across
  independent packaged JVMs. Fresh-process evidence now covers 81 of 84
  classified operations; three remain in-process-only debt.

Focused verification on 2026-07-27:

- `./gradlew :server:cleanTest :server:test --tests
  com.unciv.app.server.authoritative.PackagedWorkerParityCoverageTests --tests
  com.unciv.app.server.authoritative.PackagedWorkerUnitUniqueActionParityTests
  --no-parallel --console=plain` passes the registry and unit-unique scenario.
- The first combined run exposed the harness's 500 ms loopback connect timeout
  as too narrow for a second fresh JVM under load. The harness now allows a
  bounded five seconds to connect while retaining its 120-second response
  timeout; the exact clean focused command then passed.
- `./gradlew :server:cleanTest :tests:test :server:test :android:lintRelease
  :android:assembleDebug :desktop:dist --no-parallel --no-daemon
  --console=plain` passes 1,142 JVM/server tests with 14 intentional skips and
  no failures or errors, plus Android release lint, the debug APK, and desktop
  packaging. The existing SDK XML-version mismatch warning remains
  non-failing; no test or build error is deferred.

## Content-addressed authoritative release bundle

Implemented on 2026-07-26:

- Added a closed compatibility contract that pins public protocol 3, player
  projection 59, spectator projection 1, private worker protocol 2, migration
  head 20, and the sole PostgreSQL 19 Beta 2 image digest. Rust and Kotlin tests
  independently compare their compiled constants to that same checked-in
  contract.
- The core Gradle build embeds the compatibility contract into both the desktop
  client artifact and self-contained worker JAR. The new `unciv-v3-bundle`
  operator tool refuses either artifact unless the embedded contract parses
  exactly and matches the Rust-compiled release contract.
- Bundle creation copies the exact Rust server, worker JAR, client artifact,
  OpenAPI contract, compatibility contract, approved ruleset manifest, and
  contiguous migrations `0001` through `0020` into a same-filesystem staging
  directory. It accepts only bounded regular source files, validates the closed
  ruleset identity, hashes every artifact, derives the bundle ID from the
  canonical closed manifest, self-verifies, and atomically publishes only to a
  previously absent destination.
- Verification rejects missing, changed, extra, linked, special, oversized,
  reordered, noncanonical, or unknown artifacts and recomputes every digest and
  the bundle identity. Deterministic tests corrupt a client artifact and add an
  unexpected file to prove both cases fail closed.
- Packaged production startup is now mandatory by default. Rust verifies the
  whole bundle, its own canonical executable path, the configured bundled
  worker JAR path, compiled compatibility constants, and approved ruleset
  identity before database or public-listener startup. The worker requires the
  same bundle ID and returns it in the authenticated handshake; Rust rejects a
  bundle-ID, engine-build, or required component mismatch before binding.
  Only explicit `UNCIV_V3_UNPACKAGED_DEV=1` test/development runs bypass bundle
  enforcement.
- The systemd worker executes the JAR under
  `/opt/unciv-authoritative/releases/current` and receives the exact bundle ID
  through its protected environment. The release runbook covers build,
  creation, transfer verification, atomic activation, environment wiring, and
  coordinated bundle/ruleset rollback.

Verification on 2026-07-26:

- A real local bundle was built from the compiled Rust server, packaged worker
  JAR, desktop `Unciv.jar`, exact vanilla ruleset manifest, checked-in OpenAPI,
  and all 20 migrations. Creation and an independent verification both passed
  with 26 artifacts and the same recomputed bundle ID; the test-owned directory
  was removed and verified absent.
- Focused Rust tests pass for compatibility constants, atomic bundle creation,
  exact verification, tamper/extra-file rejection, authenticated worker bundle
  identity, and the updated systemd contract. Focused Kotlin tests pass for the
  shared compatibility contract and fresh-process worker protocol behavior.
- An initial fresh-worker test run correctly failed because its subprocess had
  not opted into unpackaged development mode; the fixture was corrected and
  the complete focused worker lane passed. The first real bundle rerun used a
  stale debug bundler binary and returned a redacted I/O failure; rebuilding
  the exact binary made creation and verification pass. The systemd packaging
  test also exposed its old worker path and was updated before rerun. No found
  compile, test, packaging, process, or cleanup error remains deferred.
- Rust entry façades remain logic-free: `unciv-v3-bundle.rs` contains only
  argument delegation and exit handling. Release implementation is split into
  focused 275-line packaging and 325-line manifest/verification modules.

## Packaged-worker combat and event parity

Implemented on 2026-07-26:

- Extracted the authenticated packaged-worker subprocess boundary into
  `PackagedWorkerParityHarness`. It launches a fresh self-contained worker JAR
  on an isolated loopback port, waits through the signed handshake, sends one
  bounded authenticated frame, verifies the signed response, and always
  terminates the process. The prior creation, assignment, research, complete
  AI-turn, forged-actor, and changed-clock fixtures now reuse this harness
  instead of retaining a second process implementation in the already-large
  protocol test.
- Added a canonical melee-combat fixture. A server-created two-civilization
  game is transformed into an exact at-war adjacent-unit snapshot; only the
  stable attacker identity and target coordinate cross the operation boundary.
  Two independent JVMs derive the same attack-from choice, state-based combat
  randomness, damage/outcome, complete snapshot bytes, and SHA-256. Changing
  the canonical turn state changes the resulting snapshot/hash, proving the
  operation is bound to canonical state rather than ambient process randomness.
- Added a real `Civ V - Gods & Kings` event-choice fixture. The canonical
  snapshot contains a pending built-in event alert; the test derives its opaque
  prompt/choice identities from the same projection logic and sends only those
  identities. Two independent JVMs produce identical bytes/hashes and remove
  the event alert, while a forged account is rejected without a snapshot or
  hash.
- The exhaustive parity gap remains open. These fixtures extend evidence to a
  random combat path and a ruleset event path, but do not stand in for every
  setup, command family, combat type, event effect, turn branch, or AI
  configuration.

Focused verification on 2026-07-26:

- `./gradlew :server:test --tests
  com.unciv.app.server.authoritative.EngineWorkerProtocolTests --tests
  com.unciv.app.server.authoritative.PackagedWorkerRandomParityTests
  --no-parallel --console=plain` passes all existing packaged-worker parity
  cases plus both new scenarios.
- The first event fixture correctly produced no projected choice because its
  built-in tutorial event is unavailable before turn two. The canonical fixture
  was corrected to the event's documented availability state and the complete
  focused lane then passed; no test failure was deferred.
- Process/bootstrap code remains outside the scenario tests in a descriptive
  shared helper, and no production file or Rust façade grew for this milestone.

## Worker-bound immutable ruleset identity

Implemented on 2026-07-26:

- The packaged worker now validates its root-owned gameplay asset tree before
  parsing. Built-ins come from `jsons/`; optional operator-staged mods come only
  from `mods/<name>/jsons/` under the worker working directory. Links, special
  files, unsafe names, more than 64 mods, more than 16,384 total entries,
  individual files over 16 MiB, more than 512 MiB total, and any ruleset
  parsing error fail startup.
- `InstalledRulesetCatalog` moved out of the already-large protocol file and
  captures component SHA-256 identities exactly once, immediately after
  `RulesetCache` parses the same files. Handshake and command verification use
  that immutable snapshot rather than re-reading mutable paths for every
  command. This closes the time-of-check/time-of-use mismatch between executing
  parsed rules and attesting current filesystem bytes.
- Kotlin now independently rejects noncanonical hashes, unsafe or duplicate
  component names, more than 64 mods, unavailable engine builds, and content
  mismatches before parsing a canonical snapshot. Rust continues to validate
  the same boundary before sending work.
- The systemd runbook records the read-only asset layout and safe versioned
  staging boundary. Automated authenticated download, archive extraction,
  allowlisting, atomic version activation, rollback, and garbage collection
  remain explicitly unchecked.

## Immutable operator ruleset acquisition

Implemented on 2026-07-26:

- Added the thin `unciv-v3-rulesets` operator CLI and focused acquisition
  modules. Its closed schema binds the exact engine build, built-in ruleset,
  ordered mod names and content hashes, HTTPS archive URLs, exact allowed
  hosts, archive hashes, optional one-component archive roots, and optional
  bearer-token environment names. Unknown fields, unsafe identities, malformed
  hashes, credentials in URLs, fragments, non-HTTPS URLs, and host mismatches
  fail before network access.
- Downloads disable redirects and environment proxies, use Rustls certificate
  validation, require identity encoding and HTTP 200, enforce connect and total
  deadlines, stream into create-new files under a 64 MiB cap, and verify the
  archive SHA-256. Optional bearer tokens are read only from the named
  environment variable into zeroizing memory and are never accepted as command
  arguments or emitted in reports.
- ZIP inspection uses enclosed paths and independently rejects absolute,
  traversing, NUL, backslash, linked, special, case-colliding, and unsupported
  entries. Limits cap entries at 16,384, each file at 16 MiB, total compressed
  input at 64 MiB, and total extracted bytes at 512 MiB. Only the selected
  `jsons/` subtree reaches staging.
- Trusted built-ins and exact extracted mods are staged on the destination
  filesystem, content-hashed using the worker-compatible path framing, and
  passed to the packaged Kotlin worker's offline `--validate-manifest` mode.
  The worker parses the exact files, verifies the immutable catalog, combines
  the selected rulesets, and rejects semantic link errors before Rust atomically
  renames the completed version into `versions/<manifest-hash>`.
- Migration `0020_ruleset_asset_versions.sql` registers installed immutable
  versions. New-game creation now takes the manifest advisory lock and requires
  a registered asset version before contacting the worker. Garbage collection
  takes the same lock, refuses active or game-referenced versions, unregisters
  an unreferenced version, renames it out of `versions/`, and then removes it.
  Acquisition is exactly idempotent; Linux activation and rollback replace a
  relative `active` symlink atomically.
- The production systemd worker now starts inside
  `/opt/unciv-authoritative/rulesets/active`. The acquisition runbook documents
  installation, closed policy authoring, stage-and-review, activation,
  rollback, garbage collection, credential handling, and recovery of exact
  content. Clients have no URL, host, archive, path, hash, or byte-upload field.

Verification on 2026-07-26:

- Direct packaged-worker `--print-catalog` exits successfully and reports the
  exact two built-in rulesets. The provisioned end-to-end acquisition test
  passes against the pinned PostgreSQL 19 Beta 2 image and proves offline
  worker semantic validation, atomic version creation, database registration,
  and exact idempotent reacquisition.
- Deterministic Rust tests pass for the closed HTTPS/identity policy, bounded
  streaming hash verification, rooted ZIP extraction with worker-compatible
  hashes, and rejection of traversal and symbolic-link entries. Kotlin tests
  pass for valid staged assets and manifest/content mismatches.
- `cargo fmt --all -- --check`, warnings-as-errors `cargo clippy --workspace
  --all-targets --all-features -- -D warnings`, and `cargo test --workspace
  --all-features` pass. Rust reports 161 active library tests, 26 provisioned
  database tests, 16 HTTP/OpenAPI tests, two benchmark tests, three systemd
  packaging tests, and the acquisition process test.
- All 26 serialized database tests pass in 8.72 seconds against only
  `postgres:19beta2-alpine@sha256:bc62313e826eb44d5f608425b7665962b72820e686da017799e906604bfeb8a5`.
  Both response-loss/Rust-process cases and both packaged-worker/outbox-death
  cases pass after rebuilding the packaged worker.
- `./gradlew :server:test :tests:test :android:lint :android:assembleDebug
  :desktop:dist` passes. The installed Android SDK was sufficient; no SDK
  component or toolchain error was hidden or deferred.
- Initial compile, formatting, archive-link-fixture, offline JVM-lifecycle, and
  shell-invocation failures found during implementation were corrected and
  every affected focused and broad lane was rerun. No compile, lint, test,
  database, process, Android, or cleanup error remains deferred.
- Rust entry façades remain nearly logic-free. The new binary is four lines;
  acquisition is split into descriptive shallow modules of 23-451 lines, and
  no new god file was introduced.

Focused verification on 2026-07-26:

- `./gradlew :server:test --tests
  com.unciv.app.server.authoritative.EngineWorkerProtocolTests --tests
  com.unciv.app.server.authoritative.InstalledRulesetCatalogTests --tests
  com.unciv.app.server.authoritative.WorkerRulesetAssetsTests --no-parallel
  --console=plain` passes. Tests prove stable path-framed hashes, command
  rejection before snapshot parsing, immutable catalog behavior after a file
  replacement, canonical/bounded manifest identities, bounded staged mod
  acceptance, missing JSON rejection, and link rejection where the host permits
  link creation.
- The first focused compile exposed three tests that still referenced the hash
  helper at its old location after the module extraction. The references were
  corrected and the complete focused lane passed; no compile or test error was
  deferred.
- `./gradlew :android:lintDebug :android:assembleDebug :tests:test :server:test
  :desktop:compileKotlin --no-parallel --console=plain` passes 1,112 JVM/server
  cases: 1,098 executed, 14 intentional skips, zero failures, and zero errors.
  Android lint and debug assembly also pass.
- `cargo fmt --all -- --check`, warnings-as-errors `cargo clippy --workspace
  --all-targets --all-features -- -D warnings`, and `cargo test --workspace
  --all-features` pass. Rust reports 157 active library tests, 16 HTTP/OpenAPI
  tests, two benchmark tests, and three systemd packaging tests.
- All 24 serialized database tests pass in 8.56 seconds against only
  `postgres:19beta2-alpine@sha256:bc62313e826eb44d5f608425b7665962b72820e686da017799e906604bfeb8a5`.
  Both response-loss/Rust-process cases pass in 4.39 seconds, and both rebuilt
  packaged-worker/outbox-death cases pass in a final 4.88-second rerun. The
  disposable container and worker processes were removed and verified absent.

## Phase-specific private-worker deadlines

Implemented on 2026-07-26:

- Replaced the single opaque worker transport timeout with independently
  configurable connect, authenticated-frame write, execution/response read, and
  absolute total deadlines. The API service and bounded recovery CLI use the
  same validated configuration.
- Defaults retain the existing 30-second absolute cap while limiting connection
  establishment to two seconds and request writes to five seconds. Empty,
  malformed, zero, or greater-than-ten-minute values fail startup rather than
  silently disabling a bound.
- Timeout failures are separate redacted error variants. They identify only the
  transport phase and never include actor, request, response, canonical
  snapshot, or private engine diagnostics.
- Each operation still owns exactly one TCP connection. Read or total expiry
  drops that socket, and a subsequent idempotent retry must establish a fresh
  authenticated connection.
- `docs/operations/authoritative-worker-identity.md` now documents all four
  environment variables, defaults, validation bounds, precedence, and retry
  behavior. Circuit breakers, managed worker-process recycling, and OS-enforced
  per-command CPU/memory isolation remain explicitly unchecked.

Verification on 2026-07-26:

- Deterministic Rust tests prove stalled connect and write futures return their
  exact phase errors, stalled worker execution/read returns `ReadTimeout` and
  closes the peer socket, and the absolute total deadline wins over a longer
  read deadline.
- `cargo test --all-targets` passes 148 active Rust library tests and all 16
  HTTP/OpenAPI tests; 24 provisioned database tests and five explicit
  process/failover tests remain ignored by default.
- All 24 serialized PostgreSQL integration tests pass in 7.99 seconds, both
  lost-response/Rust-process tests pass in 4.41 seconds, and both packaged
  worker/outbox process-death tests pass in 3.80 seconds against only
  `postgres:19beta2-alpine@sha256:bc62313e826eb44d5f608425b7665962b72820e686da017799e906604bfeb8a5`.
- `cargo fmt --all -- --check` and warnings-as-errors
  `cargo clippy --all-targets --all-features -- -D warnings` pass.
- `./gradlew :android:lintDebug :android:assembleDebug :tests:test :server:test
  :desktop:compileKotlin --no-parallel --console=plain` passes with 63
  actionable tasks: three executed and 60 up-to-date.
- The first focused Cargo invocation incorrectly supplied two name filters and
  ran no tests; the corrected worker filter passed. The original read-timeout
  assertion still expected the former generic transport error and was updated
  to the new exact error. An initial write-timeout fixture depended on Windows
  loopback buffer saturation and instead reached the read phase; it was
  replaced by a deterministic pending writer. Every corrected focused and
  broad gate then passed, so no test, compile, lint, database, process, or
  Android error remains deferred.
- Rust entry façades remain nearly logic-free (`main.rs` 6 lines and `lib.rs`
  47 lines). The largest Rust source is 796 lines, below the 800-line
  guardrail; deadline policy is isolated in a 142-line descriptive module and
  transport remains 495 lines.

## Private-worker circuit breaker

Implemented on 2026-07-26:

- All clones of one `EngineWorkerClient` now share a bounded circuit breaker.
  The default opens after three consecutive availability/protocol failures for
  five seconds; deployments can configure a threshold from 1-100 and a
  cooldown from 1-600000 milliseconds. Empty, malformed, disabled, or excessive
  values fail API and recovery-CLI startup.
- Connect/write/read/total timeouts, transport failures, invalid service
  identity, oversized frames, and malformed or incompatible responses count
  toward the threshold. A valid response or authenticated rules-engine
  rejection resets the failure count because the worker is responsive.
- An open circuit rejects immediately without opening a socket. After cooldown,
  exactly one caller owns the recovery probe while concurrent callers continue
  to fail fast. A successful or normally rejected probe closes the circuit; a
  failed probe reopens it for the complete cooldown.
- The breaker is availability backpressure, not authority. The PostgreSQL
  transaction, revision compare-and-swap, and command idempotency remain the
  canonical commit boundary. A database integration fixture proves both the
  transport failure and the subsequent open-circuit retry create zero
  revisions, snapshots, commands, or outbox rows before a healthy retry commits
  exactly once.
- The operational worker-identity runbook and threat model now document exact
  configuration and failure classification. The broad checklist remains open
  for managed worker-process recycling and OS-enforced per-command CPU/memory
  isolation.

Verification on 2026-07-26:

- Focused tests prove threshold opening, shared-client fail-fast behavior,
  exclusion of normal engine rejections, one recovery probe, successful
  closing, and failed-probe reopening.
- `cargo test --all-targets` passes 153 active Rust library tests and all 16
  HTTP/OpenAPI tests; 24 provisioned database tests and five explicit
  process/failover tests remain ignored by default.
- All 24 serialized PostgreSQL integration tests pass in 8.01 seconds against
  only
  `postgres:19beta2-alpine@sha256:bc62313e826eb44d5f608425b7665962b72820e686da017799e906604bfeb8a5`.
  Both lost-response/Rust-process tests pass in 4.38 seconds and both packaged
  worker/outbox process-death tests pass in 3.86 seconds.
- `cargo fmt --all -- --check` and warnings-as-errors
  `cargo clippy --all-targets --all-features -- -D warnings` pass.
- `./gradlew :android:lintDebug :android:assembleDebug :tests:test :server:test
  :desktop:compileKotlin --no-parallel --console=plain` passes with 63
  actionable tasks: three executed and 60 up-to-date.
- Clippy initially rejected an unnecessary explicit `drop` of a non-`Drop`
  permit, and the first broad format check found the new database-test import
  needed rustfmt. Both were corrected and every affected gate reran cleanly; no
  compile, test, lint, format, database, process, or Android error remains
  deferred.
- Rust entry façades remain nearly logic-free (`main.rs` 6 lines and `lib.rs`
  59 lines). The largest Rust source remains 796 lines; circuit policy is
  isolated in a 222-line descriptive module, worker transport is 587 lines,
  and the worker façade remains 688 lines.

## Managed private-worker lifecycle and deterministic startup

Implemented on 2026-07-26:

- The private worker now validates independent socket-read and complete-command
  deadlines. Every authenticated command arms a hard watchdog across shared
  Kotlin rules execution, canonical serialization, signing, and response
  writing; expiry terminates the JVM with exit 124 so it cannot continue after
  Rust has abandoned the command.
- A hardened systemd unit runs the packaged worker under dedicated identities,
  a loopback-only network boundary, immutable assets, a protected environment
  file, a bounded JVM heap/metaspace/direct-memory budget, cgroup memory and CPU
  ceilings, no swap, task and descriptor limits, automatic crash restart, and
  six-hour process recycling. The accompanying runbook documents installation,
  secret handling, health checks, and controlled failure drills.
- `LoopbackEngineWorkerServer` moved out of the already-large protocol module
  into a focused 78-line lifecycle module. Configuration/watchdog policy is
  isolated in `EngineWorkerRuntimeLimits.kt`; Rust systemd-contract tests live
  in their own integration target.
- The broad JVM gate exposed a real fresh-process creation mismatch: equal
  starting-location scores retained identity-based `HashMap` iteration order.
  `GameStarter` now uses explicit continent and tile-coordinate tie-breakers.
  Twenty-one fresh JVM creations then produced byte-identical city-state
  snapshots and hashes.
- Correcting the Android toolchain exposed four errors previously hidden by an
  incompatible lint frontend. Declared Kotlin is now 2.3.0, AGP is 8.13.2, and
  Gradle is 8.13. Android notification posting checks the Android 13 runtime
  permission through one guarded helper, and the SDK property path is valid.

Verification on 2026-07-26:

- Focused Kotlin runtime-limit/watchdog and worker-protocol tests pass,
  including watchdog expiry and cancellation. The 21-process deterministic
  creation stress run passed before the normal two-process regression fixture
  was restored.
- `./gradlew :android:lintDebug :android:assembleDebug :tests:test :server:test
  :desktop:compileKotlin --no-parallel --console=plain` passes on JDK 21 and the
  aligned Kotlin 2.3.0/AGP 8.13.2/Gradle 8.13 toolchain in 1 minute 19 seconds.
  A separate non-baselined Android lint rerun passes after fixing all four
  newly exposed errors.
- `cargo fmt --all -- --check`, warnings-as-errors
  `cargo clippy --workspace --all-targets --all-features -- -D warnings`, and
  `cargo test --workspace --all-features` pass: 153 active library tests, all
  16 HTTP/OpenAPI tests, and all three systemd packaging tests pass; 24
  database and five explicit process/failover tests remain ignored only in the
  unprovisioned default lane.
- All 24 PostgreSQL integration tests pass in 8.06 seconds, both
  lost-response/Rust-process tests pass in 4.37 seconds, and both packaged
  worker/outbox death tests pass in 3.86 seconds against only
  `postgres:19beta2-alpine@sha256:bc62313e826eb44d5f608425b7665962b72820e686da017799e906604bfeb8a5`.
- Live Linux systemd/cgroup/OOM qualification remains explicitly unchecked;
  Windows cannot provide that evidence. The combined implementation checklist
  was split so completed lifecycle controls are checked without pretending the
  production-host drill has run.
- Rust entry façades remain nearly logic-free. Every Rust source remains below
  800 lines (largest: `projection.rs`, 796 lines). The Kotlin protocol module
  remains 1,472 lines after extracting server lifecycle and is still a
  proactive modularization target; it is below the absolute 2,000-line ban but
  is not treated as ideal.

## Bounded worker admission and measured connection model

Implemented on 2026-07-26:

- All clones of one Rust worker client now share one execution permit matching
  the Kotlin worker's sequential process model. Rust acquires it before opening
  a socket; the OS listener backlog is no longer an implicit work queue.
- A separately configurable admission bound counts the running operation plus
  queued operations. The default admits 64; the next request fails fast without
  worker contact. Queue waits have a bounded deadline and remain within the
  existing absolute total worker deadline. API and recovery-CLI startup reject
  empty, malformed, disabled, or excessive queue settings.
- Added a release-mode JSON benchmark executable covering authenticated
  fresh-connection handshakes and real shared-engine tiny-game creation. The
  ADR now records why the first 1-vCPU/1-GB target retains one persistent JVM
  and one disposable connection per command rather than adding stream
  multiplexing or multiple memory-heavy JVMs.

Measured on Windows 11 build 26200, an i7-13700KF, 32 GB RAM, and Temurin JDK
21.0.11:

- 500 warmed fresh-connection handshakes: mean 3.82 ms, p50 3.05 ms, p95
  6.39 ms, p99 24.12 ms.
- 50 real two-major tiny-game creations: mean 31.31 ms, p50 20.59 ms, p95
  109.69 ms, p99 129.05 ms.
- Ten cold packaged workers became connectable in 1,093.74 ms on average
  (p50 1,031.75 ms, p95 1,186.15 ms). Ready working set averaged 108.27 MiB;
  observed post-workload peak working set was 243.61 MiB.

These values qualify only the process/connection decision. Linux cgroup
behavior, large saves, AI/end-turn latency, sustained concurrency, and final
low-resource capacity remain open in `missing_multiplayer.md`.

Verification on 2026-07-26:

- Focused worker verification passes 43 queue, deadline, circuit-breaker,
  authentication, transport, and wire-contract tests. It proves cloned clients
  share the admission bound, the 65th default-capacity operation can fail
  before socket creation, execution is serialized, and queued work expires.
- `cargo test --workspace --all-features` passes 157 active library tests, all
  16 HTTP/OpenAPI tests, both benchmark-binary tests, and all three systemd
  packaging tests; 24 provisioned database tests and five explicit
  process/failover tests are ignored only in the default unprovisioned lane.
- All 24 serialized PostgreSQL tests pass in 8.57 seconds against only
  `postgres:19beta2-alpine@sha256:bc62313e826eb44d5f608425b7665962b72820e686da017799e906604bfeb8a5`.
  Both lost-response/Rust-process tests pass in 4.39 seconds and both packaged
  worker/outbox-death tests pass in 4.03 seconds. The exact disposable
  `unciv-v3-worker-queue-test` container was removed and verified absent.
- `cargo fmt --all -- --check` and warnings-as-errors
  `cargo clippy --workspace --all-targets --all-features -- -D warnings` pass.
- `./gradlew :android:lintDebug :android:assembleDebug :tests:test :server:test
  :desktop:compileKotlin --no-parallel --console=plain` passes with 65
  actionable tasks: seven executed and 58 up-to-date.
- The first focused Cargo command supplied two name filters, which Cargo
  rejects. The corrected single `worker::` filter passed all 43 focused tests.
  The initial admission fixture constructed but did not poll its queued future;
  it was corrected to run the waiter concurrently before the focused and broad
  gates passed. No compile, test, lint, format, Clippy, database, process, or
  cleanup error remains deferred.
- `main.rs` remains a six-line bootstrap façade and `lib.rs` remains a narrow
  façade. Every Rust source remains below 800 lines; queue policy and the
  benchmark executable are isolated in descriptive modules.

## Packaged-worker research and all-AI-turn parity

Implemented on 2026-07-26:

- Added a deterministic seven-process fixture against the exact packaged
  `UncivAuthoritativeWorker.jar`. It creates a tiny rectangular Civ V Vanilla
  game with one authenticated human, two major AI civilizations, one
  city-state, no barbarians, and no ruins.
- Two independent fresh JVMs apply the same typed research selection to the
  same canonical snapshot and produce byte-identical snapshots and canonical
  hashes.
- Two more independent fresh JVMs end the human turn from that research
  snapshot. Both execute the complete two-AI rotation, return control to the
  human at turn one, persist the exact server turn-start time, and produce
  byte-identical snapshots and hashes.
- A forged account is rejected without a snapshot or hash. Replaying with a
  server clock one millisecond later succeeds but deliberately produces
  different canonical bytes and hash, proving that authenticated actor identity
  and server time are replay-critical inputs.
- The broad recovery and every-command parity checklist entries remain open.
  This fixture does not yet qualify every setup, command family, random
  combat/event path, modded ruleset, or controlled process-fault combination.

Verification on 2026-07-26:

- The focused fresh-process fixture passes twice, including the strengthened
  forged-actor and changed-clock controls; the final focused run completes in
  11 seconds.
- `cargo test --all-targets` passes 143 active Rust library tests and 16
  HTTP/OpenAPI tests; 24 explicitly provisioned database tests and five
  process/failover tests remain ignored by default.
- All 24 serialized PostgreSQL library tests pass in 8.25 seconds, both lost
  HTTP-response process tests pass in 4.37 seconds, and both packaged-worker
  death tests pass in 3.92 seconds against only
  `postgres:19beta2-alpine@sha256:bc62313e826eb44d5f608425b7665962b72820e686da017799e906604bfeb8a5`.
- `cargo fmt --all -- --check` and warnings-as-errors
  `cargo clippy --all-targets --all-features -- -D warnings` pass.
- `./gradlew :android:lintDebug :android:assembleDebug :tests:test :server:test
  :desktop:compileKotlin --no-parallel --console=plain` passes with 63
  actionable tasks: four executed and 59 up-to-date.
- The Kotlin worker protocol test file is 697 lines, within the 300-800-line
  source guardrail. No compile, test, lint, formatting, database, process-test,
  or Android error remains deferred.

## PostgreSQL 19 Beta 2 promotion under live command load

Implemented and verified on 2026-07-26:

- A self-contained ignored Rust integration harness now creates a disposable
  primary and physical streaming standby from only the pinned
  `postgres:19beta2-alpine@sha256:bc62313e826eb44d5f608425b7665962b72820e686da017799e906604bfeb8a5`
  image. It requires streaming state, synchronous standby selection, and
  `synchronous_commit=remote_apply` before accepting the workload.
- A focused Tokio TCP routing endpoint gives SQLx one stable database address.
  Four independent game journals each commit four revisions, synchronize at a
  barrier, and continue while the harness forcibly kills the primary, promotes
  the standby, and reroutes only new connections. Caller-stable command IDs
  retry storage failures without changing command meaning.
- The test requires evidence that at least one operation encountered the dead
  primary. It then proves that all 64 intended commands committed exactly once:
  each game has head revision 16, a contiguous revision 0-16 chain, 16 command
  rows, 17 immutable snapshots including genesis, 16 outbox events, and no
  reconciliation findings.
- The harness is split by responsibility into a three-line integration façade,
  disposable Docker-cluster lifecycle, stable TCP proxy, and canonical-history
  scenario. Test-owned containers, network, and volumes have unique names and
  are removed on both success and panic.

Verification:

- `cargo test --test postgres_failover -- --ignored --nocapture` passes the
  complete failover scenario in 8.40 seconds. Two earlier full live runs also
  passed in 8.32 and 8.37 seconds after the initial readiness race was fixed.
- `cargo test --all-targets` passes 143 active library tests and all 16
  HTTP/OpenAPI tests; the 24 database tests and five explicitly provisioned
  process/failover tests remain intentionally ignored in this default lane.
- Against a separate exact-digest PostgreSQL 19 Beta 2 instance,
  `cargo test --lib -- --ignored --test-threads=1` passes all 24 database tests
  in 8.21 seconds. `cargo test --test http_response_loss -- --ignored
  --test-threads=1` passes both Rust API-process cases in 4.43 seconds, and
  `cargo test --test packaged_worker_death -- --ignored --test-threads=1`
  passes both packaged JVM/outbox process cases in 3.88 seconds.
- `cargo fmt --all -- --check` and warnings-as-errors
  `cargo clippy --all-targets --all-features -- -D warnings` pass.
  `./gradlew.bat :android:lintDebug :android:assembleDebug :tests:test
  :server:test :desktop:compileKotlin --no-parallel --console=plain` passes 63
  tasks (3 executed, 60 up-to-date).
- The first live harness run found that `pg_isready` can report the temporary
  initialization server before the requested database exists. Readiness now
  requires a successful SQL query against the exact database. That failure was
  corrected and all gates rerun; no error remains deferred.
- `main.rs` remains 6 lines and `lib.rs` remains a thin façade. Every Rust
  source remains below 800 lines; the largest is `projection.rs` at 796 lines.

## Owner-controlled spectator revocation

Implemented and verified on 2026-07-26:

- Owners can revoke a named spectator through the new authenticated
  `POST /api/v3/games/{game_id}/spectator-revocations` operation. It accepts
  only a caller-stable operation UUID and username; the server derives the
  owner and target account and never accepts a role, account UUID, revision, or
  canonical-state mutation from the client.
- Migration `0019_spectator_revocations.sql` extends the existing closed
  `game_admin_operations.operation_kind` constraint rather than weakening it.
  The transaction locks the game, rejects archived games and non-owners,
  deletes only a spectator membership, journals the exact normalized request,
  and emits one `game.membership.changed` outbox hint at the unchanged head
  revision.
- Exact lost-response retries return success after the membership is gone.
  Reusing the operation ID with another actor, username, game meaning, or
  operation kind fails closed. Revoked accounts immediately lose game metadata
  and spectator-projection authorization; self-leave remains a separate
  spectator-only operation.
- The Kotlin API client, authenticated session, and administration coordinator
  carry the stable operation identity. The production owner popup now supports
  both spectator invitation and revocation; revocation remains available for a
  closed game so access can be withdrawn after gameplay stops.
- The PostgreSQL spectator scenario moved from the broad integration file into
  a descriptive focused module before that façade crossed the 800-line
  guardrail.

Verification:

- The focused PostgreSQL test passes against only the exact pinned PostgreSQL
  19 Beta 2 image, then all 24 serialized database integration/fault tests pass
  in 8.21 seconds.
- `cargo test --all-targets` passes 143 active library tests and 16
  HTTP/OpenAPI tests. The regenerated 104-path checked-in OpenAPI contract
  matches generated output. `cargo fmt --all -- --check` and
  warnings-as-errors `cargo clippy --all-targets --all-features -- -D warnings`
  pass.
- Both real Rust API process-fault tests pass in 4.40 seconds, and both
  packaged Kotlin worker/outbox process-fault tests pass in 3.98 seconds.
- Focused Kotlin administration/session tests pass. The broad
  `./gradlew.bat :android:lintDebug :android:assembleDebug :tests:test
  :server:test :desktop:compileKotlin --no-parallel --console=plain` gate passes
  63 tasks in 50 seconds (19 executed, 44 up-to-date).
- Initial checks correctly caught the stale OpenAPI path inventory and the
  closed SQL operation-kind constraint. Both were updated and every affected
  gate rerun. SQLx required a clean package rebuild to embed the newly added
  migration; the rebuilt focused and full database suites pass. Android D8
  emits its existing Kotlin-metadata rewrite warnings but completes lint and
  assembly successfully. No failing check remains deferred.
- Rust façades remain thin (`main.rs` 6 lines, `lib.rs` 49 lines), and every
  Rust source remains below 800 lines; the largest remains `projection.rs` at
  796 lines.

## Projection v56 and authoritative city controls

Implemented on 2026-07-26:

- The city-control audit found that `SellBuilding` accepted any bounded
  building name because the player projection had no legal sale list, and
  `SetCityGovernance` checked city ownership but not the projected action.
  Both client-bus gaps are closed: invented building and governance identities
  now fail before transport.
- Projection v56 adds `sellableBuildings`, derived inside the Kotlin worker
  from canonical built buildings, sellability, free-building ownership,
  puppet state, the per-turn sale limit, god mode, and current-turn authority.
  Refund values remain private and are still calculated only during canonical
  worker execution.
- Rust independently enforces a bounded, sorted, unique, nonblank building
  allowlist and rejects sellable/governance actions outside the actor's turn.
  The v56 shared fixture exercises the field through exact Kotlin/Rust semantic
  round-trip and generated OpenAPI.
- The projection-only world now renders and submits affordable single/ring tile
  purchases, assignable tile states, specialist mode and bounded counts,
  citizen reset/growth/focus controls, sellable buildings, governance actions,
  and pending captured-city dispositions. Every controller method requires the
  exact current projected identity before invoking its typed authenticated
  session operation.
- City controls live in a focused `AuthoritativeCityControlController` and
  `AuthoritativeCityControlPanel`. Session-to-command wiring for both city
  modules moved to `AuthoritativeWorldSessionActions`, shrinking the production
  world screen instead of accumulating bootstrap and routing logic there.

Verification on 2026-07-26:

- Canonical Kotlin tests prove a built sellable building appears only on the
  actor's current turn and disappears after the per-turn sale or for a puppet.
  Command-bus tests prove invented sale/governance identities never reach
  transport. Controller tests cover every new projected input family,
  out-of-range/unadvertised rejection, and ambiguous retry without local state
  mutation.
- `./gradlew :tests:test :server:test :desktop:compileKotlin --no-parallel`
  passes 1024 JVM/server cases: 1011 executed, 13 intentional skips, zero
  failures, and zero errors. Desktop compilation passes in the same run.
- `cargo test --lib` passes 118 active tests with 21 database tests explicitly
  gated. Rust semantic tests reject duplicate/blank and out-of-turn
  sellable-building disclosure. `cargo test --bin
  unciv-authoritative-server` passes all 10 HTTP/OpenAPI tests.
- All 21 serialized PostgreSQL integration/fault tests pass in 7.31 seconds
  against only
  `postgres:19beta2-alpine@sha256:bc62313e826eb44d5f608425b7665962b72820e686da017799e906604bfeb8a5`.
  The disposable `unciv-v3-projection-v56-test` container was removed and
  verified absent.
- `cargo fmt --all`, warnings-as-errors
  `cargo clippy --all-targets --all-features -- -D warnings`, generated OpenAPI
  parity, and `git diff --check` pass.
- The first focused test compile used a suspend assertion helper unavailable in
  that test class. It was replaced with explicit coroutine-safe exception
  capture and the focused and broad suites were rerun cleanly. No compile,
  test, format, Clippy, OpenAPI, database, or cleanup error remains deferred.
- Rust façades remain thin (`main.rs` 6 lines, `lib.rs` 49 lines), and every
  Rust source remains below 800 lines (largest: `worker/protocol.rs`, 796).
  The world screen is 273 lines; the extracted city-control controller, panel,
  and session adapter are 137, 150, and 80 lines.

Projection-only city interaction now covers the inventoried city controls
without canonical client state. Unit action surfaces, diplomacy/votes/trades,
religion, espionage/events, combat, and other remaining world families stay
explicitly tracked.

## Projection-only production queues and purchases

Implemented on 2026-07-26:

- The player world now renders each owned city's projected construction queue,
  stored production, server-estimated turns, queueable options, bounded
  queue-management actions, and allowed purchase choices. Prices and available
  currency are display-only values from the projection.
- A focused `AuthoritativeCityEconomyController` validates the exact projected
  city, queue index/name, adjacent move, option action, currency, purchase
  availability, and optional legal tile before invoking the authenticated
  session. It routes only the existing typed remove/move/manage/purchase and
  purchase-at-tile operations; it cannot submit price, affordability,
  prerequisite, refund, production, or result claims.
- Ambiguous command responses leave the full projection unchanged. Repeating
  the same projected input reaches the session's pending-command retry path;
  accepted and stale outcomes continue through the world controller's complete
  revision/hash-validated projection replacement.
- City economy presentation moved into
  `AuthoritativeCityEconomyPanel.kt`. Construction/economy input logic moved
  out of `AuthoritativeWorldController`, reducing that shared controller rather
  than growing a mixed-concern god file.

Verification on 2026-07-26:

- Focused controller tests prove exact queue removal, adjacent reorder,
  server-advertised `AddToTop`, ordinary purchase, tile-targeted purchase,
  invented currency/name rejection before transport, and ambiguous retry
  without local projection mutation.
- `./gradlew :tests:test :server:test :desktop:compileKotlin --no-parallel`
  passes 1019 JVM/server cases: 1006 executed, 13 intentional skips, zero
  failures, and zero errors. Desktop compilation passes in the same run.
- `git diff --check` passes. The world controller is 214 lines, the focused
  city-economy controller is 180, the world screen is 318, the general decision
  renderer is 117, and the city panel is 152. The combined controller test
  remains below the project guardrail at 594 lines.
- Rust, OpenAPI, projection v55, canonical worker execution, and PostgreSQL
  persistence are unchanged in this client-only slice. Their immediately
  preceding clean evidence remains 117 active Rust library tests, 10
  HTTP/OpenAPI tests, warnings-as-errors Clippy, and 21 PostgreSQL integrations
  on the sole pinned PostgreSQL 19 Beta 2 digest.

Construction queue management and purchases no longer require canonical client
state. City tile purchases, citizen/specialist controls, governance/disposition,
building sales, and the other projected gameplay families remain separate
production UI gaps.

## Projection v55 and authoritative construction selection

Implemented on 2026-07-26:

- `ProjectedConstructionOption` now carries the closed `ordinary` or
  `perpetual` kind derived by the Kotlin worker from the canonical construction
  type. This removes the need for a disposable client to infer which typed
  command to send from a name, nullable cost, or local ruleset.
- Rust independently validates the v55 shape. A perpetual option must have zero
  stored production, no production cost or estimated turns, no placement
  targets, and no purchases. The shared v55 fixture includes ordinary and
  perpetual options and round-trips semantically through Kotlin and Rust.
- The projection-only world renders every queueable choice per owned city.
  Ordinary choices without placement submit `QueueConstruction`; ordinary
  choices with a selected server-advertised coordinate submit
  `QueueConstructionAtTile`; perpetual choices submit
  `SetPerpetualConstruction`. The client controller requires the exact city,
  construction kind, queueability, and placement identity from its current
  projection before invoking transport.
- Accepted/stale/retry/rejected outcomes use the existing projection replacement
  boundary. No client production cost, prerequisite, uniqueness, placement, or
  result claim is accepted, and canonical mutation remains inside the private
  Kotlin worker.

Verification on 2026-07-26:

- Focused controller tests prove ordinary, perpetual, and tile-placed dispatch
  and prove invented construction/placement identities never invoke transport.
  Projection contract and end-turn-readiness tests pass against v55.
- `./gradlew :tests:test :server:test :desktop:compileKotlin --no-parallel`
  passes 1016 JVM/server cases: 1003 executed, 13 intentional skips, zero
  failures, and zero errors. Desktop compilation passes in the same run.
- `cargo test --lib` passes 117 active tests with 21 database tests explicitly
  gated. The new semantic test rejects perpetual options carrying ordinary
  production state. `cargo test --bin unciv-authoritative-server` passes all 10
  HTTP/OpenAPI tests after explicit schema regeneration.
- All 21 serialized PostgreSQL integration/fault tests pass in 11.85 seconds
  against only
  `postgres:19beta2-alpine@sha256:bc62313e826eb44d5f608425b7665962b72820e686da017799e906604bfeb8a5`.
  The disposable `unciv-v3-projection-v55-test` container was removed and
  verified absent.
- `cargo fmt --all`, warnings-as-errors
  `cargo clippy --all-targets --all-features -- -D warnings`, OpenAPI parity,
  and `git diff --check` pass.
- The first focused compile exposed use of Kotlin's `isNotEmpty` extension on a
  LibGDX `SnapshotArray`. The renderer now checks its explicit size and every
  focused and broad gate was rerun cleanly. No compile, test, formatting,
  Clippy, OpenAPI, database, or cleanup error remains deferred.
- Rust façades remain thin (`main.rs` 6 lines, `lib.rs` 49 lines), and every
  Rust source remains below 800 lines (largest: `worker/protocol.rs`, 796).
  Projection-only concerns remain split across the 259-line controller,
  286-line world screen, and 152-line decision renderer.

`PickConstruction` is now resolvable from a fresh projection-only client
without canonical state. Construction queue reordering/removal, purchases,
city tiles/citizens/governance, and other gameplay surfaces remain tracked
separately and are not claimed complete.

## Projection-only research and policy decisions

Implemented on 2026-07-26:

- The API-v3 world now renders the actor's current research, science progress,
  queue, server-advertised queue actions, selectable and appendable research
  targets, free-technology choices, completion prompts, culture progress, and
  adoptable policies directly from `PlayerProjection`.
- Every button submits one existing typed authenticated command through
  `AuthoritativeMultiplayerSession`: set/append research, manage a queue entry,
  choose a free technology, acknowledge completion, or adopt a policy. The
  controller independently requires the exact identity/action to be present in
  its current projection before transport is invoked.
- Accepted and stale results replace the complete projection through the same
  revision/hash validation used by movement and end turn. Ambiguous responses
  leave the projection unchanged, and repeating the same input lets the session
  reuse the pending idempotency key. No research prerequisite, cost, policy
  legality, or outcome is calculated on the client.
- UI construction lives in the focused
  `AuthoritativeWorldDecisions.kt` module rather than expanding the world
  screen into a mixed-concern file. The projection-only source assertion now
  covers this module and continues to forbid canonical `GameInfo`,
  `WorldScreen`, `GameStarter`, and local multiplayer-save dependencies.

Verification on 2026-07-26:

- Focused `AuthoritativeWorldControllerTests` pass. They prove exact projected
  identities for research selection, queue removal, free technology, policy
  adoption, and completion acknowledgement, plus prove invented research and
  policy identities never call transport.
- `./gradlew :tests:test :server:test :desktop:compileKotlin --no-parallel`
  passes 1014 JVM/server cases: 1001 executed, 13 intentional skips, zero
  failures, and zero errors. Desktop compilation passes in the same run.
- The first focused compile exposed that a method with an optional middle
  argument cannot be used as a two-argument Kotlin function reference. The UI
  now supplies an explicit lambda and both the focused and broad gates were
  rerun cleanly. No compile or test error remains deferred.
- `git diff --check` passes. The controller, world screen, decision renderer,
  and controller tests are 206, 271, 109, and 335 lines respectively. Rust,
  OpenAPI, persistence, and canonical engine execution were unchanged in this
  client-only milestone; their previously passing PostgreSQL 19 Beta 2 and
  warnings-as-errors gates remain recorded in the preceding milestone.

Research and policy interaction no longer block projection-only play. City
construction, diplomacy/votes, great-person and religious choices, espionage,
combat, and the remaining projected action families still require production
world surfaces before the full rendering and end-turn prerequisite items can
be checked.

## Account-backed game discovery and projection reopening

Implemented on 2026-07-26:

- `AuthoritativeGameDirectory` reconstructs the authenticated account's API-v3
  game directory entirely from bounded server membership pages. It has no
  multiplayer-file or save dependency, rejects blank/repeated cursors,
  duplicate game IDs, oversized pages, invalid metadata, and configured result
  overflow instead of looping or accepting ambiguous membership state.
- The production multiplayer screen shows API-v3 server games separately from
  legacy saved-game previews. Refreshing either source does not convert,
  fabricate, upload, or save a canonical `GameInfo`.
- Opening an owner/player membership initializes the existing authenticated
  command bus through an HTTP projection refresh. Opening a spectator
  membership calls only the public spectator-projection endpoint.
  Unavailable games and admin-only memberships cannot be opened as players.
- The current screen displays synchronized projection identity, revision, turn,
  and role. Projection-only lobby/world rendering and invitation/player-setup
  UI remain explicit P0 gaps; this milestone does not claim a playable v3 world
  or complete lifecycle migration.

Verification on 2026-07-26:

- Focused
  `./gradlew :tests:test --tests "com.unciv.logic.multiplayer.authoritative.AuthoritativeGameDirectoryTests" --no-parallel`
  passes all 7 tests. They cover multi-page fresh-client reconstruction,
  repeated-cursor and duplicate-game rejection, exact player/spectator endpoint
  routing, projection/membership identity agreement, and fail-closed
  unavailable membership.
- `./gradlew :tests:test :server:test --no-parallel` passes 995 JVM/server tests:
  982 executed, 13 intentional skips, zero failures, and zero errors.
- `./gradlew :desktop:compileKotlin --no-parallel` passes. The Android project
  is conditionally excluded by `settings.gradle.kts` because this checkout has
  neither `sdk.dir` nor `ANDROID_HOME`; no Android compile is claimed.
- `git diff --check` passes. `MultiplayerScreen.kt` is 578 lines and the new
  directory implementation is 162 lines, both within the repository's
  proactive split guidance.
- No compile or test failure is deferred. The failed intermediate compile/test
  attempts exposed and corrected a coroutine receiver ambiguity, test-module
  constructor visibility, JUnit expression return types, and fixture field
  drift before the clean focused and broad reruns. A combined desktop/Android
  compile command also named the absent conditional Android project; project
  discovery confirmed the SDK-gated exclusion and the valid desktop target
  then passed.

## Production invitation and player-join UI

Implemented on 2026-07-26:

- The API-v3 multiplayer screen now exposes a bounded account invitation inbox.
  It lists only target-scoped invitations returned by the authenticated server,
  rejects duplicate invitation/game identities, and never joins from a
  user-entered game ID.
- Accepting an invitation submits the server-provided revision and canonical
  hash through the existing join command. An exact ambiguous retry retains one
  command ID; refreshing a stale invitation to a new revision/hash rotates the
  command identity before the user retries. Successful acceptance refreshes the
  account-backed game directory.
- Owners of active, available games receive an invitation action that accepts
  an account username. Its operation ID remains stable across an exact failed
  network retry and is discarded after confirmed success. The action is
  disabled for players, spectators, admins, closed/archived games, unavailable
  games, and legacy saved-game selections.
- With API v3 installed, the old user-entered game-ID action is explicitly
  labeled `Add legacy saved game`; it remains available solely for the
  preserved legacy multiplayer path.
- Retry bookkeeping is protected by a coroutine mutex because inbox refresh,
  acceptance, and owner invitation requests execute on background workers.

Verification on 2026-07-26:

- Focused
  `./gradlew :tests:test --tests "com.unciv.logic.multiplayer.authoritative.AuthoritativeInvitationCoordinatorTests" --no-parallel`
  passes all 4 tests. They prove exact acceptance and owner-invitation retries
  reuse their IDs, refreshed stale invitation meaning rotates its command ID,
  and duplicate-game inbox data fails closed.
- `./gradlew :tests:test :server:test :desktop:compileKotlin --no-parallel`
  passes. The test reports contain 999 JVM/server cases: 986 executed,
  13 intentional skips, zero failures, and zero errors; the desktop client
  compiles in the same run.
- `git diff --check` passes. `MultiplayerScreen.kt` remains 615 lines; the
  coordinator and popup modules are 135 and 128 lines respectively.
- No source, compile, test, or formatting error is deferred. Android remains
  unclaimed because the SDK-gated Android Gradle project is absent from this
  checkout, as recorded in the preceding discovery milestone.

## Production owner administration UI

Implemented on 2026-07-26:

- Active, available owner memberships now expose a dedicated server-game
  administration popup. Player, spectator, admin, closed, archived,
  unavailable, and legacy selections cannot enable it.
- Kick is kept on the revisioned typed-command boundary. The coordinator opens
  the authenticated player projection when needed, then delegates to the
  command bus so stale refresh and ambiguous-response idempotency retain their
  existing command semantics.
- Ownership transfer, close, and archive retain caller-stable operation UUIDs
  bound to one exact game, operation, and target username. Exact network retries
  reuse the ID; changing a transfer target produces a different identity;
  confirmed success releases the retry state.
- Every destructive action requires confirmation and displays the selected
  server game and revision. A retryable ambiguous kick keeps the popup open;
  stale or rejected authority and API authorization errors refresh membership
  metadata and close the invalid owner surface. Successful transfer, close, or
  archive also refreshes lifecycle/role metadata.
- The UI remains isolated from legacy rename/delete/resign controls and never
  edits, uploads, or saves a canonical `GameInfo`.

Verification on 2026-07-26:

- Focused
  `./gradlew :tests:test --tests "com.unciv.logic.multiplayer.authoritative.AuthoritativeAdministrationCoordinatorTests" --no-parallel`
  passes all 5 tests. They prove exact transfer retries retain their operation
  ID, changed transfer targets rotate it, close/archive identities remain
  distinct, kick trims and uses the revisioned command boundary, and a missing
  opened projection fails closed.
- `./gradlew :tests:test :server:test :desktop:compileKotlin --no-parallel`
  passes. The reports contain 1004 JVM/server cases: 991 executed,
  13 intentional skips, zero failures, and zero errors; desktop compilation
  passes in the same run.
- `git diff --check` passes. `MultiplayerScreen.kt` is 646 lines; the
  administration coordinator and popup are 103 and 147 lines, keeping each
  concern in a focused shallow module.
- No source, compile, test, or formatting failure is deferred. Android remains
  unclaimed because this checkout has no configured SDK and therefore excludes
  its conditional Gradle project.

## Packaged-worker fresh-process parity

Implemented on 2026-07-26:

- The server build now produces a dedicated self-contained
  `UncivAuthoritativeWorker.jar` whose entry point is the private loopback
  worker, separate from the legacy public server entry point.
- The deterministic protocol fixture launches that exact packaged artifact in
  four independent JVMs. Two workers create the same seeded tiny game with two
  major civilizations and two city-states; two more replay the same
  `AssignPlayer` operation from the resulting canonical snapshot.
- Creation and replay must independently match the complete serialized
  snapshot, canonical SHA-256 state hash, and worker-selected civilization.
  This catches process-global initialization, packaged-classpath, collection
  ordering, and city-state placement drift that same-JVM tests cannot detect.
- The harness uses a bounded startup loop, bounded socket timeouts, a five-minute
  JUnit deadline, loopback-only sockets, and terminates every child process.

Verification on 2026-07-26:

- The focused packaged-worker parity test passes after building
  `:server:authoritativeWorkerDist`.
- `./gradlew :tests:test :server:test --no-parallel` passes all 984 JVM/server
  tests: 977 core/game tests and 7 server tests, with 13 intentional skips and
  no failures or errors.
- The first harness run exposed Windows retaining a redirected temporary log
  handle after child termination. The harness was changed to inherit diagnostic
  output without a temporary file. The next run reached exact snapshot/hash
  equality and then exposed a test-only attempt to read an uninitialized
  transient nation field from deserialized canonical state; the assertion now
  classifies civilizations through the loaded pinned ruleset. The exact focused
  test and both broad suites were rerun cleanly, so neither issue is deferred.
- This is representative recovery evidence, not full qualification. Every
  supported setup, command family, random combat/event path, authoritative AI
  turn, controlled process fault, and release digest/compatibility bundle remain
  explicitly unchecked in `missing_multiplayer.md`.

## Production-screen authoritative creation boundary

Implemented on 2026-07-26:

- `NewGameScreen` now selects one closed creation route: local, explicit legacy
  API v2, or authoritative API v3. The route is authoritative only when online
  play is selected and the platform has explicitly installed an API-v3 session,
  preserving offline/hotseat and existing legacy behavior behind a tested
  boundary.
- The authoritative branch runs before scenario loading and `GameStarter`.
  It converts only the bounded generated-map setup, resolves the exact installed
  ruleset manifest through the authenticated session, and invokes retry-safe
  server creation. It never constructs, uploads, or autosaves canonical
  `GameInfo` state on the client.
- One typed retry-state object binds the caller operation UUID to the exact base
  ruleset, mod set, and bounded setup. Lost-response retries and screen
  recreation reuse that UUID; changing any meaning rotates it before another
  request, preventing changed-content idempotency reuse.
- Authoritative setup UI normalizes civilization slots to one authenticated
  owner plus AI slots, removes legacy player UUID fields and explicit
  civilization/spectator selection, disables public spectators and client
  nation pools, and rejects god mode or unrepresented advanced map-generation
  values. The server remains the only source of seed, civilization assignment,
  legality, and revision-zero state.
- An exact creation retry now returns the already-open synchronized command bus
  without a redundant projection fetch.

Verification on 2026-07-26:

- Focused setup/session tests prove local, legacy-v2, and authoritative-v3 route
  selection; fail-closed setup mapping; meaning-stable versus changed-meaning
  operation IDs; exact repeated creation input; and reuse of the opened
  revision-zero command bus.
- `./gradlew :tests:test :server:test --no-parallel` completes 988 JVM/server
  cases: 975 pass and 13 are intentionally skipped, with no failures or errors.
  The 981 core/game cases and 7 server cases include the dedicated
  packaged-worker parity gate.
- The first compile after extracting the authoritative UI helper found two
  GL-thread calls that required the explicit dispatcher. The first retry-state
  test compile found a missing JUnit import, and the first repeated-creation
  assertion exposed the redundant projection refresh. Each issue was fixed and
  the focused tests plus both complete suites were rerun cleanly; no compile,
  test, or cleanup error remains deferred.
- This does not claim the complete production lifecycle. Secure platform token
  stores and default session installation, account/login UI, authoritative game
  discovery/lobby entry, and projection-only world rendering remain explicitly
  unchecked in `missing_multiplayer.md`.

## Bounded journal recovery and immutable publication

Implemented on 2026-07-26:

- Every new committed command now retains the exact private worker operation,
  authenticated replay identity, and control-plane-selected execution time.
  Snapshot bytes are excluded from the operation journal. Legacy rows without
  complete replay evidence are explicitly marked unavailable and fail
  reconciliation rather than being guessed.
- Read-only reconstruction scans prior immutable snapshots newest-first,
  validates their payload and canonical hashes, enforces a caller-bounded
  contiguous tail, and asks the private Kotlin worker to replay each exact
  operation with its original identity and time. Each result must match the
  corresponding immutable revision hash.
- Applying a reconstruction creates a distinct immutable recovery revision,
  recovery audit record, snapshot, and resynchronization outbox event in one
  compare-and-swap transaction. It never rewrites damaged history, requires the
  game to remain quarantined, and rejects a stale second publication.
- `unciv-v3-recover <game-uuid> [--max-tail <count>] [--apply]` is dry-run-only
  unless `--apply` is explicit. Its output contains revision metadata and the
  canonical hash, never the private snapshot.
- Worker transport was extracted into a focused submodule. `main.rs` and
  `lib.rs` remain thin, and the largest Rust source is 796 lines.

Verification on 2026-07-26:

- `cargo fmt --check`, `cargo check --all-targets`, and warnings-as-errors
  `cargo clippy --all-targets --all-features -- -D warnings` pass.
- `cargo test --all-targets --all-features` passes 125 active Rust tests with
  21 explicitly database-gated tests ignored: 115 library tests and 10
  HTTP/OpenAPI tests, with no failures.
- All 21 serialized PostgreSQL integration and controlled replica-fault tests
  pass in 7.08 seconds against only
  `postgres:19beta2-alpine@sha256:bc62313e826eb44d5f608425b7665962b72820e686da017799e906604bfeb8a5`.
  Recovery coverage proves read-only reconstruction from a damaged head,
  immutable publication, audit/outbox creation, quarantine clearing, damaged
  byte preservation, and stale-publication rejection. The disposable database
  container was removed.
- During implementation, the first complete database run exposed an invalid
  default revision kind for revision zero, and an earlier reconciliation
  fixture omitted the newly required operation evidence before reaching its
  intended missing-time assertion. Both defects were corrected and the entire
  database suite rerun cleanly. No compiler, formatting, Clippy, unit,
  integration, migration, container-cleanup, or documentation error remains
  deferred.
- Production recovery is not yet claimed complete: fresh-process parity with
  the packaged real Kotlin worker for city-state placement and every supported
  setup, plus controlled worker/process fault coverage, remains checked as
  missing in `missing_multiplayer.md`.

## Durable server replay context

Migration `0013_deterministic_replay_context.sql` makes the server-selected
execution time part of every new command-journal row and retains the secret
creation seed and execution time with every new revision-zero operation. Rust
also chooses the canonical game UUID before invoking `GameStarter`; Kotlin
injects that UUID before constructing the state-based creation RNG. The private
worker must echo Rust's timestamp exactly, and Rust rejects a rewritten or
missing value.

`GameStarter` now supplies its state-based RNG to civilization selection and
player-order shuffles instead of using ambient Kotlin randomness. Reconciliation
reports `missing_command_time` and `missing_creation_replay_context` for legacy
or damaged history and never guesses those inputs.

This milestone does not claim bounded recovery is complete. A repeated-creation
test exposed additional ambient randomness in map resource and starting-position
generation. That audit, fresh-process byte-identical fixtures, bounded tail
execution, and publication of a recovery revision remain explicitly unchecked
in `missing_multiplayer.md`.

## Repeatable server-created map baseline

The first repeated-creation failure is now fixed for a representative generated
two-major-civilization map. `GameStarter` uses its state-based RNG for nation,
city-state, player-order, and starting-location ordering. Start normalization,
luxury placement, strategic/bonus placement, continent traversal, and region
traversal likewise use explicit RNG streams and stable coordinate/name ordering
rather than ambient Kotlin collection shuffles or hash iteration.

`EngineWorkerProtocolTests.createGameDerivesSetupFromPinnedManifestAndServerSeed`
now executes the same typed creation request twice with the same canonical UUID,
server seed, server time, manifest, and zero-city-state setup. It asserts exact
snapshot bytes and canonical SHA-256 equality. The complete server suite passes
normally and with `--rerun-tasks`; the core test suite also passes.

This is a deterministic baseline, not completion of the recovery requirement.
City-state placement across fresh processes and the broader setup matrix still
need parity fixtures. Bounded journal-tail execution and immutable recovery
revision publication remain unchecked.

## Immutable command replay actor identity

Implemented on 2026-07-26:

- Migration `0012_command_replay_actor.sql` adds an immutable
  `actor_civilization_id` to accepted command journal rows. It safely backfills
  identities that remain available from current memberships and deliberately
  retains `NULL` with an explicit unavailable marker for older history that
  cannot be proven. A database constraint requires every new default-marked
  replayable row to carry a nonempty actor.
- Every new canonical commit resolves the actor civilization inside the same
  transaction after invitation membership is created and before resignation or
  kick membership is removed. The journal therefore remains replay-complete
  after lifecycle changes instead of consulting mutable current membership.
- Read-only reconciliation reports `missing_command_actor` for legacy or
  damaged commands whose replay identity is unavailable. Recovery must fail
  closed on those rows; it may not infer an actor from current state.

Verification on 2026-07-26:

- Focused PostgreSQL tests prove ordinary commits retain the actor, invitation
  acceptance records the newly assigned civilization, resignation preserves
  actor identity after membership deletion, and reconciliation detects a
  missing actor without mutating any row. The database rejects a new
  default-replayable journal row that omits its actor.
- Rust passes 112 active library tests, all 10 HTTP/OpenAPI tests, and the
  reconciliation binary target. `cargo fmt --all -- --check` and
  warnings-as-errors `cargo clippy --all-targets -- -D warnings` pass.
- All 20 serialized PostgreSQL integration tests pass in 6.97 seconds against
  only
  `postgres:19beta2-alpine@sha256:bc62313e826eb44d5f608425b7665962b72820e686da017799e906604bfeb8a5`.
  The disposable container was removed.
- The first focused assertion used `Rome`, while the shared database fixture
  intentionally names its civilization `test-civilization`; the expectation
  was corrected and the complete focused and full database suites passed. No
  source, migration, test, formatting, Clippy, database, or cleanup error
  remains deferred.
- Full bounded snapshot replay and recovery-revision publication remain open.
  This milestone supplies the immutable historical actor identity they require;
  it does not claim recovery is complete.

## Bounded authoritative generated-game setup

API-v3 game creation now requires a closed setup object rather than silently
using worker defaults. The public schema accepts only predefined generated map
types, shapes, sizes and resource densities; a closed barbarian mode; bounded
fixed major/city-state counts, maximum turns and multiplayer timers; bounded
sorted victory identities; ruleset-derived difficulty, speed and era names;
and explicit gameplay/map toggles. Victory identities must also be distinct.
Unknown fields and enums, client seeds,
custom/scenario map payloads, arbitrary player records, raw setup JSON,
snapshots, and `GameInfo` remain outside the contract.

Rust validates structural and resource bounds before worker execution, then
adds the independent OS-backed secret seed. The private Kotlin worker repeats
all numeric/order checks, derives the exact installed combined ruleset from the
pinned manifest, verifies every named choice and available civilization count,
constructs `GameSetupInfo`, assigns only the authenticated owner as human, and
runs shared `GameStarter`. The API-v3 Kotlin client converts supported local
setup state into the typed request; its authenticated session resolves one
exact manifest, creates revision zero, fetches the first projection, and opens
the command bus. Production `NewGameScreen` routing and retry-safe resource
creation remain separate P0 gaps.

Verification on 2026-07-26:

- Focused worker tests prove the server seed and representative bounded setup
  choices become canonical state, while a client-invented difficulty is
  rejected before snapshot creation. The Rust wire test proves the private
  operation contains only typed setup plus `serverSeed`, with no manifest hash
  claim or snapshot.
- Focused session tests prove creation requires authentication, resolves the
  exact installed manifest, submits the setup, requires revision zero, fetches
  the authoritative projection, and opens the command bus.
- `./gradlew :tests:test :server:test --no-parallel` passes 983 JVM/server tests
  with 13 intentional skips and no failures or errors.
- Rust passes 112 active library tests and all 10 HTTP/OpenAPI tests.
  `cargo fmt --all -- --check`, warnings-as-errors
  `cargo clippy --all-targets --all-features -- -D warnings`, and regenerated
  OpenAPI parity pass.
- All 18 serialized PostgreSQL integration and controlled replica-fault tests
  pass in 6.84 seconds against only
  `postgres:19beta2-alpine@sha256:bc62313e826eb44d5f608425b7665962b72820e686da017799e906604bfeb8a5`.
The fresh disposable container was removed and verified absent.
- The expected first HTTP run exposed stale checked-in OpenAPI. The first
  regeneration command omitted the required binary selector in a multi-binary
  crate; the corrected explicit command regenerated the schema and all 10
  HTTP/OpenAPI tests passed. The architecture review also caught `worker.rs` at
  813 lines after adding the wire fixture; that test moved into the focused
  `worker/game_setup.rs` module before final verification. No compile, test,
  format, Clippy, OpenAPI, database, or cleanup error remains deferred.
- Rust entry façades remain nearly logic-free (`main.rs` 6 lines and `lib.rs`
  47 lines). The largest Rust source is 789 lines, below the 800-line
  guardrail.

## Projection-only authoritative world foundation

Implemented on 2026-07-26:

- Projection v54 adds bounded base terrain, sorted terrain features, natural
  wonders, and resource identity/amount to explored tiles. Kotlin derives these
  fields from canonical state and omits a resource unless the actor can reveal
  it. Rust independently rejects inconsistent ordering, bounds, resource
  name/amount pairs, and mutable tile details disclosed through fog.
- `AuthoritativeWorldController` owns only a validated
  `ApiV3GameProjection`. It rejects backward revisions and same-revision hash
  changes, selects only projected actor units, accepts only move destinations
  advertised by the server, and gates end turn on projected authority and
  blockers. Accepted commands and refreshes replace the complete projection;
  retryable responses never mutate it optimistically.
- An available player membership now opens `AuthoritativeWorldScreen` directly
  from the synchronized projection. The screen has no canonical `GameInfo`,
  local-save, `GameStarter`, or legacy `WorldScreen` dependency. It renders a
  bounded terrain/fog/city/unit/status view, submits the first movement and
  end-turn routes through the authenticated command bus, and reconciles through
  manual refresh plus a five-second authoritative poll.
- The full projection-only world remains intentionally open: the production
  screen does not yet expose the other authoritative command families or the
  interaction surfaces needed to resolve every projected end-turn blocker.
  Spectators still use the bounded public-summary surface.

Verification on 2026-07-26:

- `./gradlew :tests:test :server:test :desktop:compileKotlin --no-parallel`
  passes 1011 JVM/server cases: 998 executed, 13 intentional skips, zero
  failures, and zero errors. Focused tests prove resource reveal gating,
  projection-v54 fixture parity, advertised-only movement, projected end-turn
  gating, retry-without-mutation, and fail-closed revision/hash refresh.
- `cargo test --lib` passes 116 active tests with 21 database tests explicitly
  ignored in that lane. `cargo test --bin unciv-authoritative-server` passes all
  10 HTTP/OpenAPI tests, including checked-in schema parity.
- All 21 serialized PostgreSQL tests pass in 7.16 seconds against only
  `postgres:19beta2-alpine@sha256:bc62313e826eb44d5f608425b7665962b72820e686da017799e906604bfeb8a5`.
  The disposable `unciv-v3-projection-v54-test` container was removed and
  verified absent.
- `cargo fmt --all`, warnings-as-errors
  `cargo clippy --all-targets --all-features -- -D warnings`, generated OpenAPI
  parity, and `git diff --check` pass.
- The first broad Cargo command named a nonexistent integration-test target;
  the correct binary target passed all 10 tests. An initial source-size command
  used paths relative to the wrong working directory; the corrected audit
  found no oversized source. These command issues were corrected immediately
  and no compile, test, formatting, Clippy, OpenAPI, database, or cleanup error
  remains deferred.
- `main.rs` remains 6 lines and `lib.rs` is a 49-line facade. Projection test
  helpers were split into focused modules before `projection.rs` crossed the
  guardrail; every Rust source remains below 800 lines (largest:
  `worker/protocol.rs`, 796 lines). The new Kotlin controller and screen are
  132 and 248 lines respectively.

## Retry-idempotent revision-zero creation

Implemented on 2026-07-26:

- Every API-v3 create request now carries a nonzero caller-stable operation
  UUID. The Kotlin transport and authenticated session require callers to
  retain it rather than silently generating a different identity on each
  attempt.
- PostgreSQL durably binds that UUID to the authenticated account and the
  canonical manifest/setup request. A transaction-scoped advisory lock
  serializes concurrent duplicates across Rust replicas. Exact retries return
  the original game without invoking the Kotlin worker again; changed-account
  or changed-request reuse fails closed.
- Worker execution, revision-zero persistence, owner membership, and the
  creation-operation record share one transaction. Worker or validation
  failure leaves no game and no idempotency record, so the same operation can
  safely retry. Victory identifiers are sorted before binding so equivalent
  ordering has one canonical meaning.

Verification on 2026-07-26:

- `./gradlew :tests:test :server:test --no-parallel` passes 983 JVM/server tests
  with 13 intentional skips.
- Rust passes 112 active library tests and all 10 HTTP/OpenAPI tests.
  `cargo fmt --all -- --check` and warnings-as-errors
  `cargo clippy --all-targets -- -D warnings` pass.
- All 20 serialized PostgreSQL integration tests pass in 6.94 seconds against
  only
  `postgres:19beta2-alpine@sha256:bc62313e826eb44d5f608425b7665962b72820e686da017799e906604bfeb8a5`.
  The new tests race duplicate requests through a one-shot worker, prove one
  game and one worker invocation, reject actor/request rebinding, prove failed
  creation leaves no rows, and retry the same operation successfully. The
  disposable container was removed.
- The first compile after extracting the transactional insertion helper exposed
  the additional dereference required for a borrowed SQLx transaction; it was
  corrected before the clean rerun. The request schema change also made the
  checked-in OpenAPI intentionally stale; explicit regeneration restored parity
  and all HTTP tests passed. No compile, test, formatting, Clippy, OpenAPI,
  database, or cleanup error remains deferred.
- Rust entry façades remain nearly logic-free (`main.rs` 6 lines and `lib.rs`
  47 lines). The largest Rust source is 789 lines, below the 800-line
  guardrail.

## Authenticated ruleset-manifest discovery

The production creation path can now discover the server-approved ruleset
manifests without accepting client content, URLs, raw setup JSON, or whole
saves. Authenticated `GET /api/v3/ruleset-manifests` returns a hash-ordered,
cursor-paginated public summary containing only the manifest hash, engine build,
and base/mod names with their content hashes. Page sizes are bounded to 1-100.
The repository revalidates the stored manifest hash, engine build, identities,
mod count, and globally distinct ruleset names before disclosure; malformed
persisted metadata fails closed.

The Kotlin API-v3 transport and session page this resource and resolve the
selected base-ruleset name and exact mod-name set to one manifest hash. Zero
matches, multiple matches, invalid limits, and repeated pagination cursors are
rejected rather than selecting an arbitrary content version. Raw manifest JSON
and installed ruleset bytes remain private to the server/worker boundary.
Bounded public game-setup choices and production `NewGameScreen` routing remain
separate lifecycle gaps.

Verification on 2026-07-26:

- `./gradlew :tests:test :server:test --no-parallel` passes 981 JVM/server tests
  with 13 intentional skips and no failures or errors. Focused session tests
  cover authentication, pagination, exact matching, and ambiguity rejection.
- Rust passes 112 active library tests and all 8 HTTP/OpenAPI tests.
  `cargo fmt --all -- --check`, warnings-as-errors
  `cargo clippy --all-targets --all-features -- -D warnings`, and generated
  OpenAPI parity pass.
- All 18 serialized PostgreSQL integration and controlled replica-fault tests
  pass in 7.04 seconds against only
  `postgres:19beta2-alpine@sha256:bc62313e826eb44d5f608425b7665962b72820e686da017799e906604bfeb8a5`.
  The manifest test proves bounded ordering, cursor rejection, and fail-closed
  behavior when stored SQL and JSON engine identities disagree or two ruleset
  entries reuse one name. The disposable container was removed and verified
  absent.
- Initial HTTP tests correctly exposed the stale checked-in OpenAPI and route
  inventory; both were regenerated/updated. The first database fixture used
  snake-case private JSON instead of the actual camel-case worker contract and
  was corrected before the complete database rerun. A later verification
  command named a nonexistent Cargo integration target; the correct binary
  target then passed all 8 tests. No compile, test, format, Clippy, OpenAPI,
  database, or cleanup error remains deferred.
- Rust entry façades remain nearly logic-free (`main.rs` 6 lines and `lib.rs`
  47 lines). The largest Rust source is 788 lines, below the 800-line
  guardrail.

## Restarted full-stack verification on 2026-08-11

The pinned local qualification stack was restarted after the prior workspace
restart. PostgreSQL ran as
`postgres:19beta2-alpine@sha256:bc62313e826eb44d5f608425b7665962b72820e686da017799e906604bfeb8a5`.
A disposable Lockwell 0.2.1 service ran on loopback with the archive bucket,
and the packaged Kotlin worker ran with command-local Java 21; the machine's
Java 25 configuration was not changed.

- The PostgreSQL migration set reached exactly version 36. The serialized
  PostgreSQL integration lane ran 52 ignored tests: 49 passed initially. The
  three initial failures were the intentionally ordered restore/disk-full
  qualification tests when invoked as one unordered aggregate: restore and
  disk-full consumers ran before their seed fixtures. Re-running each ordered
  seed/consumer pair passed; the destructive disk-full smoke then passed with a
  160 MiB tmpfs, 1 MiB constrained free space, no phantom revision, one
  idempotent retry, and clean reconciliation.
- The first PITR smoke and first disk-full smoke exposed the same bounded test
  harness defect: disposable PostgreSQL clusters did not create the ACL target
  roles before migrations. The smallest repair was to create the four migration
  grant target roles in each disposable fixture before the migrator runs. The
  final PITR smoke passed `pg_verifybackup`, WAL recovery to the named restore
  point, the included/excluded marker assertion, restored invariants, and clean
  reconciliation. The PostgreSQL security smoke passed TLS 1.3 enforcement,
  least-privilege runtime/audit roles, credential rotation, replication backup,
  and non-TLS denial.
- API readiness, auth refresh/logout invalidation, replay pagination/projection,
  and the packaged Android-to-desktop handoff all passed against the live API,
  PostgreSQL, Lockwell, and Kotlin worker. Packaged worker death during game
  creation and outbox acknowledgement process death both passed crash/retry
  recovery tests.
- Android instrumentation ran on the `unciv-api23` emulator: both Android
  Keystore credential-store tests passed, the debug APK launched, and the
  process remained alive. Desktop `:desktop:dist`, Android debug packaging,
  `:tests:test`, and `:server:test` passed. Rust format, check, and
  warnings-as-errors Clippy passed.
- The benchmark driver initially failed because its registration retry only
  recognized the JSON `rate_limited` code while the PowerShell error exposed
  HTTP 429. The bounded repair widened that match to include HTTP 429. A fresh
  detached run is active with 10 random AI factions, Huge Continents, 6
  city-states, Domination-only victory, and a 100,000-turn practical driver
  ceiling. At the latest observation it had reached turn 674, revision 10,790,
  with five civilizations alive and no winner yet; the process remains healthy
  and the crash-safe CSV remains disposable benchmark output, not a release
  artifact.


Failure repair
1. Failed check: `pwsh -NoLogo -NoProfile -NonInteractive -File authoritative-server/tests/run-postgres-pitr-smoke.ps1`; PostgreSQL 19 Beta 2 disposable source cluster; exit 1; migration failed with `role "unciv_runtime" does not exist`.
2. Minimal reproduction: run the same PITR smoke against a fresh disposable cluster before role bootstrap.
3. Causal diagnosis: the migration set contains ACL statements naming production roles, while the PITR fixture created only the default `postgres` role; production bootstrap already creates those roles, so the failure was fixture setup rather than schema logic.
4. Bounded repair owner: `authoritative-server/tests/run-postgres-pitr-smoke.ps1`; create `unciv_runtime`, `unciv_migrate`, `unciv_restore`, and `unciv_audit` before the seed test.
5. Final state: the same current worktree after the repair edit; the final identity is recorded in the handoff below.
6. Final rerun: the same PITR smoke passed with `pg_verifybackup`, WAL recovery, restored fixture invariants, and `total_findings: 0`.

The disk-full smoke exposed the same cause independently and received the same
bounded fixture-local repair in
`authoritative-server/tests/run-postgres-disk-full-smoke.ps1`; its exact final
rerun passed all three disk-full tests and clean reconciliation.

## Maximum-difficulty all-AI API/client qualification (2026-08-12)

The fresh qualification run uses the rebuilt API and worker, Java 21 only for
that worker process, 10 random AI civilizations at `Deity`, Huge map size,
Continents terrain, 6 city-states, zero human slots, Domination-only victory,
and a 100,000-turn driver ceiling. The previous benchmark artifacts and its
dedicated database volume were removed before this run; only the current CSV
and NDJSON remain as disposable live evidence.

At the latest recorded checkpoint the run was healthy at turn 1,048,
revision 16,780, with six civilizations alive. The CSV contains 1,678 completed
AI-round records and the NDJSON contains 1,678 round events plus 167 resource
samples. No terminal victory has been recorded yet; the benchmark process is
intentionally still running.

The API/client surface comparison found 134 OpenAPI operations and 126 direct
Kotlin transport literals. The nine apparent omissions are expected: health,
OpenAPI, and AsyncAPI documents are server-only; notifications use the
WebSocket URL builder; friends use a URL builder; and the three diplomacy
commands share one dynamic helper. The authoritative client session, shared
core controllers, desktop UI, and Android integration all route through the
same projection-only `ApiV3Client`/`ApiV3Transport` boundary; no client-side
canonical save upload or legacy multiplayer route was used by the benchmark.

Storage qualification during the live run shows 36.7 MB of retained
PostgreSQL snapshot payload and 105.0 MB total logical database size, while
16,539 archive records represent about 1.51 GB of verified Lockwell objects.
The background maintenance has completed 386 bounded passes and the budget
gauge is not exceeded. This is the intended bounded-replay tradeoff: the
PostgreSQL hot set remains small while cold history is recoverable from
verified full checkpoints and bounded deltas.

Observed API status counts are 18,512 successful requests, two successful
creation responses, and two transient HTTP 502 responses. Both 502 requests
were the same idempotent `advance-ai-turn` command retried and recovered; no
429, 409, 4xx command, 5xx terminal failure, projection error, or advance
error was recorded. The worker log has zero `MapPathing` diagnostics and zero
error/warning/exception lines in this run. Prometheus currently reports two
worker protocol failures and two worker timeouts, all recovered without a
benchmark-visible command failure; they remain an operational signal to
investigate after the terminal run rather than being silently discarded.

Focused final checks during this qualification pass include Rust formatting and
`cargo check --all-targets --all-features`, and
`JAVA_HOME=<command-local Java 21> ./gradlew :tests:test
--tests com.unciv.logic.map.PathfindingTests --no-daemon`; all passed. A
terminal-victory result, final storage measurement, and final-state hash must
be appended after the detached benchmark exits.

Final-state verification
Revision: `e6c3151ae5cae75adf6bcbfd0b2b2b90054586a9`; worktree has tracked
source/docs changes, deleted historical generated artifacts, and current
benchmark CSV/NDJSON evidence; exact binary diff hash and untracked hashes are
recorded in the handoff response for this checkpoint.
Last material edit: `docs/architecture/authoritative-multiplayer-status.md`,
updated the maximum-difficulty benchmark checkpoint and verification record.
| Lane | Status | Command or gate | Covered invariant | Result | Blocker |
| Kotlin | passed | `JAVA_HOME=<Java 21> ./gradlew :tests:test :server:test --no-daemon` | shared rules/pathfinding and packaged worker behavior | `BUILD SUCCESSFUL` | - |
| Rust | passed | `cargo fmt --all -- --check`; `cargo test --all-targets --all-features`; `cargo clippy --all-targets --all-features -- -D warnings` | archival, retention, API contracts, authority, corruption/idempotency boundaries | 203 library + 32 API tests passed; 53 environment-gated tests ignored; format/check/Clippy passed | - |
| Desktop | passed | `JAVA_HOME=<Java 21> ./gradlew :desktop:dist --no-daemon` | production desktop packaging uses the shared API-v3 client boundary | `BUILD SUCCESSFUL` | - |
| Android | passed | `JAVA_HOME=<Java 21> ./gradlew :android:assembleDebug --no-daemon` | Android packaging includes the shared projection-only client path | `BUILD SUCCESSFUL` | device/emulator behavior remains outside this checkpoint |
| PostgreSQL/Lockwell | passed | live PostgreSQL 19 Beta 2 + Lockwell qualification telemetry | bounded retained hot set, verified full/delta archives, budget guard | 36.7 MB retained payload; 105.0 MB database; 16,539 archives; 1.51 GB object data; budget gauge 0 | terminal benchmark victory pending |
| Benchmark | unavailable | detached `run-10-random-huge-continents-benchmark.ps1 -AiDifficulty Deity` | terminal Domination victory and final late-game behavior | active at the latest observation; prior checkpoint was turn 1,048/revision 16,780 with six alive; no winner record yet | keep monitoring until terminal result |

## Continuation checkpoint: archive quota and worker timeout evidence (2026-08-12)

The detached Deity benchmark remained active after the previous checkpoint and
reached turn 1,596 / revision 25,540 with six civilizations alive. Its current
CSV has 2,554 completed AI-round records. It has recorded no terminal victory, stale conflicts, rate limits, projection
errors, or advance errors. The
benchmark telemetry records seven HTTP 502 worker timeouts; each was retried
with the identical idempotency key and recovered, so the run has seven
successful retries and no unrecovered command failure. The benchmark driver is
still running; its CSV/NDJSON must not be deleted until the terminal boundary is
closed.

The current storage measurement is approximately 143 MiB for the logical
PostgreSQL database, 51.6 MiB of retained snapshot payload, 25,214 verified
archive metadata rows representing 2,293,343,740 object bytes, and 2.4 GiB on
the local Lockwell volume. The old API binary predates the new aggregate
archive-quota metric, so this run exercises the existing per-game PostgreSQL
budget guard but not the newly added quota configuration.

The archive maintenance configuration now supports
`UNCIV_V3_SNAPSHOT_ARCHIVE_BUDGET_BYTES`. When nonzero, the maintenance pass
accounts for verified `object_size` metadata before each archive, refuses any
object that would cross the aggregate budget, pauses when it is reached, and
emits `unciv_v3_snapshot_archive_bytes`,
`unciv_v3_snapshot_archive_quota_exceeded`, and the corresponding counter. The
existing per-game PostgreSQL budget and protected-checkpoint policy remain
unchanged. Worker failures now log only their bounded public error variant and
elapsed time, never private rejection diagnostics.

A Java Flight Recorder profile was captured from the live Kotlin worker for
300 seconds during the benchmark at
`C:\\Users\\KellHect\\AppData\\Local\\Temp\\unciv-v3-deity-live-20260812.jfr`.
It is 9.9 MiB and contains 5,085 execution samples, 40,930 allocation samples,
828 garbage-collection checkpoints, and 114 socket-write events. It provides
runtime evidence for the late-game worker profile, but no JFR event correlates
the seven historical timeouts to a rules operation because the prior API log
records only the bounded worker failure class; the new source logging will make
that correlation available on the next rebuilt run.

Continuation validation after the quota/logging edits:

- `cargo fmt --all -- --check` passed.
- `cargo test --all-targets --all-features` passed: 203 library tests, 32 API
  tests, and 53 environment-gated tests ignored.
- `cargo clippy --all-targets --all-features -- -D warnings` passed.
- `git diff --check` passed.

Final-state verification
Revision: `e6c3151ae5cae75adf6bcbfd0b2b2b90054586a9`; worktree contains the
pre-existing V3 changes, the quota/logging edits, deleted historical generated
artifacts, current benchmark evidence, and untracked `.freebuff/` left
untouched. The final tracked diff hash and material untracked hashes must be
computed after the benchmark terminal checkpoint.
Last material edit: `docs/architecture/authoritative-multiplayer-status.md`,
updated the live benchmark checkpoint and storage measurements.
| Lane | Status | Command or gate | Covered invariant | Result | Blocker |
| Kotlin | not affected | - | No Kotlin source changed in this continuation; JFR was observational only | - | - |
| Rust | passed | `cargo fmt --all -- --check`; `CARGO_TARGET_DIR=target/continuation-check cargo test --all-targets --all-features`; `CARGO_TARGET_DIR=target/continuation-check-clippy cargo clippy --all-targets --all-features -- -D warnings` | aggregate archive quota, retention, failure diagnostics, authority and recovery contracts | 203 library + 32 API tests passed; 53 environment-gated tests ignored; format and Clippy passed | - |
| PostgreSQL/Lockwell | unavailable | live benchmark telemetry and local PostgreSQL measurements | quota enforcement and archive accounting on the rebuilt binary | existing run used the pre-quota binary; rerun requires rebuilt API and a controlled Lockwell qualification fixture | rebuild API and run the deterministic Lockwell quota qualification |
| Benchmark | unavailable | detached Deity benchmark CSV/NDJSON | terminal Domination victory and late-game worker behavior | turn 1,596/revision 25,540; six alive; seven transient 502s recovered; no winner record at this checkpoint | process remains active; append terminal result after it exits |

## Continuation checkpoint: rebuilt quota qualification and clean Deity rerun (2026-08-12)

The previous benchmark artifacts and its disposable PostgreSQL volume were cleaned
before starting a fresh run. The new run uses 10 random AI civilizations, Deity,
Huge Continents, six city-states, Domination only, zero humans, and a 100,000-turn
driver cap with no practical Time-victory limit. It is running against a rebuilt
Rust API and Java 21 worker; the global Java 25 configuration was not changed.

The live run reached turn 435 / revision 6,960 with four major civilizations
alive. The current CSV is
`authoritative-server/tests/results/benchmark-10random-huge-continents-20260812-230606.csv`;
it contains 696 completed records and no winner yet. All benchmark rows have zero
API, projection, advance, stale-revision, rate-limit, and 5xx counters. Two
worker timeouts at 22:44 UTC returned HTTP 502, were retried with the same
idempotency operation, and recovered; no command was lost or duplicated. No
MapPathing diagnostics or worker stderr errors were observed.

A fresh quota qualification used a clean PostgreSQL 19 Beta 2 database and a
Lockwell bucket with the aggregate budget set to 3,088,605,838 bytes, equal to
the pre-cleanup archive metadata total. The rebuilt API became ready, completed
five maintenance passes, reported `archive_quota_exceeded=true`, and left the
archive row count and byte total unchanged (`32601|3088605838`). The fresh Deity
run uses a 500 MiB quota and has reached 524,287,763 verified archive bytes;
archival is bounded and gameplay continues. A follow-up source repair now also
marks the quota exhausted when the next eligible object does not fit in the
remaining bytes, rather than leaving the gauge false a few bytes below the cap.
The repair has focused unit coverage in
`postgres::retention::tests::quota_pauses_when_the_next_object_cannot_fit`.
The active process predates that last source-only repair and will not be
restarted mid-match; the rebuilt binary is ready for the next controlled run.

The JFR profile analysis found the principal worker allocation pressure in
`java.io.InputStream.readNBytes` / `byte[]` (99.17% sampled allocation pressure,
about 8 TB extrapolated thread allocation over the 300-second recording), with
`HashMap.getNode` the hottest sampled method (12.47% execution samples), followed
by JSON parsing, unique lookup, and map visibility/path traversal. GC was frequent
(828 collections) but individual pauses were generally below 25 ms. Socket writes
were 114 events totaling about 292 MiB, with p99 latency about 488 ms; the
profile does not prove a specific rules operation as the cause of the worker
timeouts.

| Lane | Status | Command or gate | Covered invariant | Result | Blocker |
| Kotlin | not affected | - | No Kotlin source changed; JFR was observational | - | active benchmark uses the already-built worker |
| Rust | passed | `CARGO_TARGET_DIR=target/quota-repair-check cargo fmt --all`; focused retention test; `CARGO_TARGET_DIR=target/quota-final-check cargo clippy --all-targets --all-features -- -D warnings`; final API build | quota boundary, retention policy, worker diagnostics, authority and recovery contracts | 3 focused retention tests passed; format, strict Clippy, and API build passed | - |
| PostgreSQL/Lockwell | passed | clean PostgreSQL 19 Beta 2 + Lockwell quota qualification | verified archive accounting pauses without new objects at the hard quota | ready API; 5 passes; unchanged `32601|3088605838`; readiness passed | active run uses a pre-repair binary; rerun after terminal match if live gauge evidence is required |
| Benchmark | unavailable | detached `run-10-random-huge-continents-benchmark.ps1 -AiDifficulty Deity` | terminal Domination victory and late-game behavior | turn 435/revision 6,960; four alive; 696 CSV rows; two recovered 502s; no winner yet | process remains active; append terminal result after it exits |

## Live desktop + Android client playtest of lobby-through-world routing (2026-08-14)

Playtested the V3 production multiplayer path on a real desktop client and a
real Android client against the local full stack (Rust API on :3060, private
Kotlin worker, digest-pinned PostgreSQL 19 Beta 2). Routing was visually
confirmed end to end on both clients: multiplayer browser -> server connection
-> owner login -> lobby browse -> staging room -> ready -> start -> world
screen -> projection sync -> turn handoff.

### Android (API 34 emulator, current-tree APK)

1. Server popup -> `http://10.0.2.2:3060`; `GET contract -> 200`,
   `Connection LoginRequired`.
2. Owner login (`POST /api/v3/auth/login -> 200`), `Connection Authenticated`.
3. Lobby list parsed and rendered only after the contract fix below; before it,
   every lobby fetch failed client-side with `Unexpected JSON token ... unknown
   key` on `simultaneousHumanTurns`.
4. Opened a staging room; owner seated Rome; `PUT ready -> 200`; second human
   joined via API (Arabia); both ready; `POST start -> 200`.
5. World screen rendered: `Server game ... - revision 1 - turn 0`,
   `Civilization: Rome | Current player: Rome`, `Status: Synchronized`.
6. `End turn` -> `POST command -> 200` -> `revision 2`,
   `Current player: Arabia`.

### Desktop (Windows, current-tree `Unciv.jar`, seeded data dir)

1. Server settings -> `http://127.0.0.1:3060/`, login as owner,
   `Connection Authenticated`.
2. `YOUR MATCHES` -> `Play` on an active match -> `GET projection -> 200` ->
   world screen rendered: `Server game 5edb2435-... - revision 3 - turn 0`,
   `Civilization: Rome | Current player: India`, `Status: Synchronized`,
   terrain labels rendered on the map, `End turn` present but blocked for the
   non-current player.

### Bugs found and fixed during the playtest

- **Lobby list contract mismatch (blocked every client):** the server embeds
  the private worker's full setup JSON in `LobbySummary.setup`, including
  `GameParameters.simultaneousHumanTurns`, but the client DTO
  `ApiV3GameSetup` parsed strictly without that field, so every lobby browse
  failed. Added the field to the DTO, the setup default
  (`false`; the V3 editor never exposed simultaneous turns, so the wire
  behavior is unchanged), and the lobby editor's local state. Pinned with a
  round-trip test in `ApiV3GameSetupTests` and a session-test constructor.
- **World-screen crash on first render (never reachable before):** Kotlin
  precedence bug at `AuthoritativeWorldScreen` header: `"..." + "...".toLabel()`
  re-coerced the whole expression to a `String`, hitting the gdx skin check on
  `Table.add(CharSequence)`. Parenthesized the label.
- **World-screen map crash:** bare `skin` inside `Table().apply { ... }`
  resolved to the Table's own null `skin` field instead of
  `BaseScreen.skin`; `TextButton(..., skin)` got null. Qualified the reference.
- **Decisions panel crash:** raw `String` passed to a skinless `Table.add`;
  wrapped in `toLabel()`. A sweep of the V3 UI package found the same
  precedence bug in four more places (decisions render, spy panel, trade
  panel) that would have crashed on later turns; all fixed.
- **Fixed (2026-08-14): Android 6/7 login crash (`NoSuchMethodError` in
  Ktor `NonceKt`).** Ktor's notification-WebSocket handshake calls
  `SecureRandom.getInstanceStrong()` (API 26+) via `NonceKt`, crashing the
  client on Android 6.0 (API 23) right after login. Root-caused to Ktor's
  nonce provider list (`NativePRNGNonBlocking`, `WINDOWS-PRNG`, `DRBG`)
  containing nothing Android provides, so the fatal fallback always ran on
  API 21-25. Fixed with `KtorNonceProviderGuard`: Ktor consults the
  `io.ktor.random.secure.random.provider` system property before its provider
  list, so `AndroidLauncher.onCreate` pins it to `SHA1PRNG` (available on
  every Android release) on API < 26 only; newer platforms keep Ktor's
  defaults. Live-verified on the API 23 emulator: fresh login `POST -> 200`,
  no crash, session and browser fully functional.
- **Fixed (2026-08-14): notification WebSocket never connected on any
  platform.** While verifying the crash fix, probing showed Ktor's
  `webSocketSession { url { ... } }` builder starts from an empty `ws://` and
  is not fed by `defaultRequest { url(...) }`, so the client built
  `ws:///api/v3/notifications` (no authority), threw `ConnectException` on
  every attempt, and silently retried forever - on desktop and Android alike.
  Fixed in `ApiV3Client.notifications()` by carrying the full origin in the
  URL string via `apiV3NotificationWebSocketUrl` (`http` -> `ws`, `https` ->
  `wss`). Live-verified: a JVM probe held an `ESTABLISHED` connection for the
  whole collection window, and the API 23 emulator keeps the notification
  WebSocket `ESTABLISHED` after session restore.

### Verification record

Final-state verification
Revision: playtest working tree at HEAD `20e960d21` (tileview-migration); the
9 playtest-edited files below remain unstaged alongside pre-existing earlier-turn
changes; tracked diff hash for the 9 files `77cc97cbd840167719ffbba97e0b3c43d4662f6f`;
material untracked hashes per `git status --short` at handoff.
Last material code edit: `core/src/com/unciv/ui/screens/multiplayerscreens/AuthoritativeTradePanel.kt`
(precedence-bug sweep); the only edit after the final verification runs is this status record itself.

| Lane | Status | Command or gate | Covered invariant | Result | Blocker |
| Kotlin | passed | `./gradlew :tests:test` | full core suite incl. the new `simultaneousHumanTurns` round-trip and session constructors | 116 classes / 1185 tests, 0 failures | - |
| Kotlin (focused) | passed | `./gradlew :tests:test --tests "com.unciv.logic.multiplayer.authoritative.ApiV3GameSetupTests" --tests "com.unciv.logic.multiplayer.authoritative.AuthoritativeMultiplayerSessionTests"` | DTO round-trip and session wiring of the new field | passed | - |
| Android | passed | `./gradlew :android:assembleDebug` + live API-34 emulator run of the built APK | lobby -> ready -> start -> world -> end-turn handoff routing | APK built; full routing visually confirmed; `POST command -> 200` | - |
| Desktop | passed | `./gradlew :desktop:dist` + live Windows run of the built jar | browser -> YOUR MATCHES -> Play -> projection -> world screen routing | jar built; `GET projection -> 200`; world screen rendered with correct turn state | - |
| Rust | not affected | - | no Rust source changed in this milestone | - | - |
| PostgreSQL | not affected | - | no schema/query change; server-side state unchanged | - | - |

## Android 6/7 login crash and notification-WebSocket connectivity fixes (2026-08-14)

Fixed two client-side V3 networking bugs found in the playtest era:

1. **Android API 21-25 crash after login** - Ktor `NonceKt` falls back to
   `SecureRandom.getInstanceStrong()` (API 26+) when none of its JVM-oriented
   providers exists, throwing `NoSuchMethodError` during the notification
   WebSocket handshake. `KtorNonceProviderGuard.configureFor(Build.VERSION.SDK_INT)`
   runs in `AndroidLauncher.onCreate` and pins the
   `io.ktor.random.secure.random.provider` system property to `SHA1PRNG` on
   API < 26, which Ktor consults before its provider list. API 26+ keeps
   Ktor's defaults.
2. **Notification WebSocket never connected on any platform** - Ktor's
   `webSocketSession { url { ... } }` builder starts from an empty `ws://`
   and `defaultRequest { url(...) }` does not supply the authority, so the
   client built `ws:///api/v3/notifications`, threw `ConnectException` on
   every attempt, and retried forever. `ApiV3Client.notifications()` now uses
   the full URL from `apiV3NotificationWebSocketUrl` (`http` -> `ws`,
   `https` -> `wss`).

Live verification on the API 23 emulator (current-tree APK):
- fresh login `POST /api/v3/auth/login -> 200`, no `FATAL`/`NoSuchMethodError`
  in logcat, app stays on the multiplayer browser;
- session restore keeps the notification WebSocket
  `ESTABLISHED` (`10.0.2.15 -> 10.0.2.2:3060`) across the whole watch window;
- `Connection Authenticated` on screen.
A JVM probe against the local stack held the same WebSocket `ESTABLISHED` for
the full collection window, confirming the fix is platform-independent.

Final-state verification
Revision: working tree at HEAD `20e960d21` (tileview-migration); 4 files
edited for this milestone plus the pre-existing earlier-turn changes.
Last material code edit: `core/src/com/unciv/logic/multiplayer/authoritative/ApiV3Client.kt`
(notification WebSocket URL fix); the only later edit is this record.

| Lane | Status | Command or gate | Covered invariant | Result | Blocker |
| Kotlin (focused) | passed | `./gradlew :tests:test --tests "com.unciv.logic.multiplayer.authoritative.KtorNonceProviderGuardTests" --tests "com.unciv.logic.multiplayer.authoritative.ApiV3ServerIdentityTests" --tests "com.unciv.logic.multiplayer.authoritative.AuthoritativeMultiplayerSessionTests"` | property pin below API 26, no-op at/above, resolvable provider, ws/wss URL derivation | all passed | - |
| Kotlin (full) | passed | `./gradlew :tests:test` | full core suite incl. both new test classes | 118 classes / 0 failures | - |
| Android | passed | `./gradlew :android:assembleDebug` + live API 23 emulator run of the built APK | login without `NoSuchMethodError`; notification WebSocket connects and stays ESTABLISHED | APK built; fresh login 200; no crash; WS ESTABLISHED across watch window; Connection Authenticated | - |
| Desktop | not affected | - | no desktop-specific behavior changed; same core code proven on JVM by the live probe | - | - |
| Rust | not affected | - | no Rust source changed in this milestone | - | - |
| PostgreSQL | not affected | - | no schema/query change | - | - |

## Deterministic guards for lobby-to-world routing and world-screen rendering patterns (2026-08-14)

The 2026-08-14 playtest crashed the world screen three times on patterns the
compiler cannot see. Two source-level guard tests were added to
`AuthoritativeProductionRoutingTests` (the project's established deterministic
routing-test convention):

- `lobbyToWorldRoutingOpensOnlyStartedPlayerProjections` pins both production
  entry points into the world screen - the lobby's post-start hop and the
  match browser's Play button - to the projection-only
  `AuthoritativeGameDirectory.open` player path, forbids `GameInfo`/`GameStarter`
  on the world screen, and pins the start gate: only the owner may start, only
  at exact capacity (`occupiedSlots == humanSlots`) with every member ready and
  civilization-assigned.
- `worldSurfacesAreSkinSafeAndNeverCoerceLabelsBackToStrings` guards the four
  world surfaces (`AuthoritativeWorldScreen`, `AuthoritativeWorldDecisions`,
  `AuthoritativeSpyPanel`, `AuthoritativeTradePanel`) against the two traps
  that crashed during the playtest:
  1. `"a" + "b" + "c".toLabel()` - Kotlin's `String.plus(Any)` re-coerces the
     sum back to `String`, reaching `Table.add(CharSequence)` on a skinless
     table. The guard rejects any `+` whose continuation ends in
     `"literal".toLabel()` and requires the fixed `(...).toLabel()` form.
  2. a bare `skin` inside `Table().apply {}` resolves to the table's own null
     skin field instead of `BaseScreen.skin`; the guard rejects a bare `skin`
     identifier. The panel files additionally reject raw string literals
     passed straight to `add()`.

Mutation-validated against the fixed sources: each guard was exercised with a
temporarily reintroduced bug (raw literal add, `"..." .toLabel()` coercion,
bare `skin`) and each failed the suite exactly as intended, then the files
were restored. Full `:tests:test`: 118 classes, 0 failures.

Final-state verification
Revision: working tree at HEAD `20e960d21` (tileview-migration); the only
material edit for this milestone is the routing-test file plus this record.
Last material edit: `tests/src/com/unciv/logic/multiplayer/authoritative/AuthoritativeProductionRoutingTests.kt`
(+96 lines, two tests).

| Lane | Status | Command or gate | Covered invariant | Result | Blocker |
| Kotlin (focused) | passed | `./gradlew :tests:test --tests "com.unciv.logic.multiplayer.authoritative.AuthoritativeProductionRoutingTests"` | lobby->world and browser->world routing, start gate, world-surface skin/label patterns | 29 tests incl. both new guards, 0 failures; 3 mutation checks fired the guards | - |
| Kotlin (full) | passed | `./gradlew :tests:test` | full core suite with the extended routing test class | 118 classes / 0 failures | - |
| Android | not affected | - | no production code changed; guards are compile-time source pins | - | - |
| Desktop | not affected | - | no production code changed | - | - |
| Rust | not affected | - | no Rust source changed | - | - |

## Single-player-style projection world HUD and staging-room fixes (2026-08-21)

The playtest verdict was that the online match still felt like "a menu of
buttons": `AuthoritativeWorldScreen` extended `PickerScreen` and stacked the
map (capped at 62% height), tile readout, unit table, order rows, city panel,
and a permanent scrollable wall of every server-advertised decision into one
column. The staging room also kept a stale layout after rotation, offered raw
civilization IDs in its faction picker, and hid the AI seats from the roster.

Changes, all inside the existing projection-only boundary:

- `AuthoritativeWorldScreen` now extends `BaseScreen` directly. The real hex
  renderer fills the screen; floating chrome sits above it: a full-width top
  bar (nation portrait, game/revision/turn identity line, treasury, turn
  state, connection status, Decisions toggle, Leave), the game's own
  `UnitTable` docked bottom-left with the advertised action/order rows stacked
  above it like the single-player action row, End turn in the bottom-right
  corner with the real tile readout above it, and one slide-in side panel for
  everything else. The panel shows either the open city's economy/control
  panels or the full decision stack (research, policies, prompts, spies,
  religion, diplomacy, trade, history) with retry/reconnect at its top; it
  auto-opens once per revision while end-turn blockers remain undismissed.
  The screen implements `RecreateOnResize`, sets `GUI.hudHost = this` while
  active so widgets consulting `GUI.isAllowedChangeState` are answered by the
  hosting screen, and clears that host on dispose only if it is still itself.
- `AuthoritativeLobbyScreen` implements `RecreateOnResize` (rotation rebuilds
  the room around the newest lobby state); the faction picker is a new
  file-private `CivilizationSelect` that displays leader names while every
  session call keeps sending the server civilization ID (ambiguous modded
  display names fall back to the ID); and `renderAiSeats()` renders the AI
  roster inside the players card, labelled "Server-run".
- The in-flight HUD-seam groundwork landed with this milestone: `GUI.hudHost`
  (null-safe `isAllowedChangeState` fallback defaulting to false),
  `CityScreen.forceReadOnly`, and the `gameInfoOrNull` espionage fallbacks in
  `CityView`/`ForeignCityView`, covered by the new headless
  `ProjectionCityViewTests` driving every `CityView`/`CityConstructionsView`
  call the city screens make over a materialized projection city, including
  the pinned boundary that construction costs cannot be computed client-side.
- New source-routing pins in `AuthoritativeProductionRoutingTests`:
  `projectionWorldUsesAFloatingSinglePlayerStyleHudInsteadOfAButtonStack`
  (BaseScreen+WorldHudHost+RecreateOnResize, no PickerScreen, fullscreen map,
  the four HUD widgets, exact hudHost set/clear) and
  `stagingRoomSurvivesResizeNamesFactionsByLeaderAndShowsAiSeats`.

Final-state verification
Revision: base HEAD `012666f79` (tileview-migration); tracked diff hash
`ec3dfdd81c97a0f36e15d3b1e235398021dc9008` over GUI.kt, CityScreen.kt,
CityView.kt, ForeignCityView.kt, AuthoritativeWorldScreen.kt,
AuthoritativeLobbyScreen.kt, AuthoritativeProductionRoutingTests.kt, and
missing_multiplayer.md; material untracked file
`tests/src/com/unciv/ui/components/tilegroups/ProjectionCityViewTests.kt`.
This record is the only edit after that hash.
Last material edit: `core/src/com/unciv/ui/screens/multiplayerscreens/AuthoritativeWorldScreen.kt` (rewritten to the floating-HUD surface).

| Lane | Status | Command or gate | Covered invariant | Result | Blocker |
| Kotlin (focused) | passed | `./gradlew :tests:test --tests "com.unciv.logic.multiplayer.authoritative.AuthoritativeProductionRoutingTests" --tests "com.unciv.ui.components.tilegroups.ProjectionCityViewTests" --tests "com.unciv.ui.components.tilegroups.ProjectionWorldMapRenderTests"` | routing pins incl. two new guards; CityView/CityConstructionsView calls over a projection city; hex-render parity | 0 failures | - |
| Kotlin (full) | passed | `./gradlew :tests:test` | complete core suite with the rewritten world screen and lobby | 1222 tests / 0 failures / 17 documented skips | - |
| Desktop | not affected | - | no desktop-only source changed; core compiles under allWarningsAsErrors | - | - |
| Android | not affected | - | no android source changed; UI is shared core code exercised by :tests:test | - | - |
| Rust | not affected | - | no Rust source changed; client-only presentation rework | - | - |
