# Authoritative multiplayer work still missing

This is the current executable gap list for authoritative multiplayer v3 as of
2026-07-26 on the authoritative-v3 feature branch. Completed foundations and
command families are retained below with checked marks; the detailed evidence
and historical milestones remain in `docs/multiplayer-command-coverage.md` and
`docs/architecture/authoritative-multiplayer-status.md`.

The target remains: online clients submit typed intent and render permitted
server projections; the private Kotlin worker runs all game rules, turn
progression, deterministic randomness, and AI; Rust is the only committer of
canonical revisions; PostgreSQL 19 Beta 2 is the sole production/test database.

## Implemented and verified

- [x] Establish the private headless Kotlin worker as the sole gameplay-rules,
  turn-processing, deterministic-randomness, and AI execution boundary. Rust
  remains the public control plane and sole canonical revision committer.
- [x] Establish revisioned PostgreSQL persistence with immutable snapshots,
  command journal, idempotency, compare-and-swap commits, membership, audit,
  and durable notification outbox on the exact pinned PostgreSQL 19 Beta 2
  image.
- [x] Implement authenticated account/session lifecycle, durable throttling,
  security auditing, authenticated game discovery, player-scoped projection
  reads, and revision-aware command submission.
- [x] Implement server-created revision-zero games, owner membership, invited
  joins with server-owned civilization assignment, and retry-safe backend
  administration for kick, ownership transfer, close, and archive. Production
  UI migration for these backend capabilities remains listed below.
- [x] Implement player-scoped full-projection reconciliation plus durable
  revision WebSocket notifications, with HTTP projection refresh as the
  authoritative recovery path.
- [x] Implement inventoried unit movement, exact and durable escort movement,
  movement cancellation, friendly swaps, and projection-owned movement
  legality.
- [x] Implement inventoried unit combat: melee/ranged attacks, city bombardment,
  nuclear strikes, air sweeps, canonical confirmation previews, and
  non-oracular hidden-target disclosure.
- [x] Implement inventoried direct unit special actions: disband, found city,
  paradrop, religious actions, great-person actions, gifting, capital-project
  consumption, instant improvements, transformations, and generic triggered
  uniques.
- [x] Implement inventoried unit promotion, upgrade, saved city-promotion
  defaults, renaming, exploration, automation, postures, siege setup,
  improvement/repair orders, road orders, and pillaging. Optional whole-turn
  autoplay remains unresolved below.
- [x] Implement inventoried city production queues and context operations,
  perpetual construction, purchases, tile-targeted construction, citizen and
  specialist management, tile assignment, single- and multi-tile acquisition,
  city economy, governance, and captured-city disposition.
- [x] Implement public, knowledge-gated world-wonder completion/effect events.
- [x] Implement inventoried research selection, prerequisite-safe append,
  queue reorder/removal, free technologies, progress/history/turn estimates,
  and opaque completion acknowledgements.
- [x] Implement ordinary policies, branches, ideology/tenets, pantheons,
  religion founding/enhancement/reform, religious-unit actions, and bounded
  belief choices through shared Kotlin rules.
- [x] Implement inventoried bilateral trade, major-civilization diplomacy,
  city-state interactions, protection prompts, diplomatic marriage, war/peace,
  and diplomatic-victory voting.
- [x] Implement inventoried espionage controls, great-person selection,
  mod-defined event choices, self-resignation, and owner force-resignation.
- [x] Implement owner-invited spectators with a distinct public-only projection
  and no access to player commands or canonical state.
- [x] Implement authoritative end-turn readiness and advancement, including
  worker-owned pending orders, all AI players, canonical blocker validation,
  and server-side player rotation.
- [x] Implement bounded snapshot compression, corruption quarantine, read-only
  reconciliation tooling, multi-replica commit-race proof, and database
  connection-loss retry proof. Broader process/failover coverage remains
  unresolved below.
- [x] Persist the executing civilization as immutable command-journal replay
  identity. New normal, join, resignation, and kick commits retain the actor
  even after membership deletion; reconciliation reports older or
  damaged rows whose actor cannot be reconstructed instead of guessing.
- [x] Persist server-owned replay context for new history: every committed
  command journals the Rust-selected execution time, and every revision-zero
  creation operation retains its OS-backed seed, execution time, and canonical
  game UUID. The worker must echo the control-plane time and cannot replace it.
  Reconciliation reports legacy or damaged rows missing these inputs.
- [x] Persist the exact private worker operation for each new command without
  duplicating snapshot bytes, then reconstruct a quarantined head read-only from
  the newest valid immutable snapshot through a bounded, contiguous journal
  tail. Every replayed hash must match immutable revision history.
- [x] Publish a verified recovered head as a distinct immutable recovery
  revision with its own audit event and resynchronization outbox event. The
  compare-and-swap publication preserves damaged history, rejects stale
  recovery attempts, and is exposed through a dry-run-first operator CLI.
- [x] Make representative generated-map creation byte-repeatable for identical
  canonical UUID, server seed, server time, manifest, and setup. `GameStarter`,
  region generation, start normalization, and luxury/strategic placement now
  use stable ordering and explicit state-based RNG instead of ambient shuffles;
  the worker test compares both the complete snapshot and SHA-256 hash.
- [x] Build a dedicated self-contained `UncivAuthoritativeWorker.jar` and prove
  process-boundary parity through that packaged artifact. Independent fresh
  JVMs produce byte-identical snapshots and hashes for seeded creation with
  city-states and for replayable player assignment from that snapshot.
- [x] Add an explicit production new-game creation boundary. With an installed
  authenticated API-v3 session, `NewGameScreen` now submits bounded setup
  directly to server creation before any local `GameStarter` call, retains one
  meaning-bound operation ID across exact retries and screen recreation, and
  never uploads or autosaves a client-created canonical game. Offline and
  explicit legacy API-v2 creation routes remain separate and tested.
- [x] Add account-backed API-v3 game discovery and projection reopening to the
  production multiplayer screen. The bounded directory pages server membership
  metadata without consulting local saves, rejects duplicate games or repeated
  cursors, routes players through the authenticated command-bus projection and
  spectators through the public-only projection endpoint, and keeps the
  legacy file-backed list visually and logically separate.
- [x] Keep Rust `main.rs` and `lib.rs` as thin façades and keep substantive Rust
  modules below the 800-line guardrail, with formatting and warnings-as-errors
  Clippy gates.

## P0: required before v3 can replace legacy online play

- [x] Remove the opaque revision-zero worker setup blob and client-selectable
  seed path. The control plane now generates an independent OS-backed secret
  map seed that is not derivable from the public game UUID; the private
  protocol carries only that typed seed, and Kotlin constructs default
  `GameSetupInfo` from the pinned manifest before assigning the authenticated
  owner. Production UI routing remains part of the unchecked lifecycle items
  below.
- [x] Add authenticated, bounded ruleset-manifest discovery and exact client
  resolution. The public API pages only content identities and hashes, validates
  persisted manifest integrity, and never exposes raw manifest JSON or ruleset
  bytes. The Kotlin client resolves an exact base-ruleset and mod-name set and
  fails closed on no match, ambiguity, malformed cursors, or repeated pages.
- [x] Add a closed, bounded generated-game setup contract across the public
  API, Rust-to-worker wire, Kotlin client/session, and private worker. It covers
  ruleset-derived difficulty/speed/era/victories; fixed major/city-state counts;
  predefined map generation, size, shape and resources; distinct bounded
  victory identities; gameplay toggles; and
  multiplayer timers. Rust bounds the request, Kotlin independently validates
  every named choice against the pinned ruleset, and only the control plane
  supplies the secret map seed. The authenticated session resolves the exact
  manifest, creates revision zero, fetches its projection, and opens the
  command bus.
- [x] Make game creation retry-safe with a caller-stable creation operation ID
  bound durably to the authenticated account and exact manifest/setup meaning.
  PostgreSQL serializes duplicate attempts with a transaction-scoped advisory
  lock and atomically records the operation with revision zero. A lost create
  response returns the original game without invoking the worker again;
  changed-account or changed-meaning reuse fails closed, and a failed attempt
  rolls back completely so the same operation can be retried.
- [ ] Make v3 the complete production game lifecycle, not an explicitly opened
  opt-in path. New online games must be created by the v3 server and must never
  be created locally and uploaded. The installed-session route now satisfies
  that invariant, but production still needs to install/restore the secure v3
  session by default and retire new legacy-v2 game creation without breaking
  existing legacy games.
- [ ] Migrate the new-game UI to bounded server setup choices, server-owned
  seed/randomness, exact ruleset-manifest resolution, retry-stable creation,
  progress/error handling, and the returned revision-zero projection. The
  production screen now uses that lifecycle whenever a v3 session is installed,
  strips legacy player IDs and explicit civilization/public-spectator choices,
  rejects unsupported client nation pools, god mode, and advanced map values,
  and retains an operation ID only while setup meaning is unchanged. Secure
  default session/account installation and transition from the returned
  revision-zero projection into a projection-only lobby/world remain.
- [ ] Migrate game discovery, join, civilization assignment, and open-game UI
  to account membership and server projections. A fresh device must reconstruct
  every v3 game without a local save. Membership discovery and projection
  reopening now work without local saves; invitation acceptance/player setup
  UI remains, while an available player membership now transitions from the
  synchronized projection into the projection-only world.
- [x] Add the projection-only world foundation. Projection v54 carries bounded
  explored terrain, terrain features, natural wonders, and only resources the
  actor can reveal. Opening an available player membership now constructs a
  world screen from `ApiV3GameProjection` without `GameInfo`, local saves, or
  `WorldScreen`; it renders a bounded map/status view, submits only
  server-advertised movement and eligible end-turn commands, and replaces state
  only from validated manual or periodic authoritative refreshes.
- [x] Expose authoritative research and policy decisions in the projection-only
  world. The client renders only projected research targets, append targets,
  queue operations, free technologies, completion acknowledgements, and
  adoptable policies; every selection uses the existing authenticated typed
  command bus, preserves retry identity, and replaces the full projection only
  after server acceptance or reconciliation.
- [x] Expose authoritative city construction selection in the projection-only
  world. Projection v55 explicitly distinguishes ordinary from perpetual
  construction so the client never infers command type from ruleset names or
  costs. The world submits only server-advertised ordinary, tile-placed, or
  perpetual choices and rejects invented city, construction, and placement
  identities before transport.
- [x] Expose authoritative production queues and purchases in the
  projection-only world. Owned-city projections now drive exact queue removal,
  adjacent reordering, server-advertised multi-queue actions, ordinary
  purchases, and tile-targeted purchases. The focused city controller rejects
  stale names/indices, unavailable currencies, disallowed purchases, and
  unadvertised tiles before transport; ambiguous retries retain the unchanged
  projection and reuse the session's pending command identity.
- [x] Expose authoritative city tiles, citizens, specialists, governance,
  dispositions, and building sales in the projection-only world. Projection
  v56 adds a canonical turn-scoped sellable-building allowlist; the command bus
  now rejects unprojected building and governance identities. The UI submits
  only affordable projected tile/ring purchases, assignable tile states,
  bounded specialist counts, projected focus choices, exact governance and
  captured-city disposition actions, and retry-stable toggles/resets.
- [x] Expose authoritative projected combat in the projection-only world.
  Selecting an owned unit renders only its worker-derived attack, nuclear, and
  air-sweep targets; owned cities render only their projected bombard targets.
  Canonical strength/health previews are display-only, hidden nuclear and
  interceptor effects remain undisclosed until commit, invented coordinates
  never reach transport, and ambiguous retries preserve the current revision.
- [x] Expose authoritative direct unit special actions in the projection-only
  world. The selected-unit panel renders only worker-advertised religious and
  great-person actions, gifting, capital-project consumption, instant
  improvements, transformations, and opaque triggered uniques. Exact action
  identities are rechecked against the latest projection before typed
  submission; canonical costs, effects, and unit consumption stay private.
- [x] Expose exact current-turn unit order state in the projection-only world.
  Selected units can cancel an advertised movement order, flip their projected
  exploration/automation state, and choose one immediate worker-advertised
  promotion or exact friendly swap destination. Out-of-turn, no-op, stale, and
  invented choices fail before transport, while uncertain retries retain the
  unchanged projection.
- [ ] Build projection-only world rendering. The online world screen must not
  require a canonical `GameInfo`, and deleting or modifying every client cache
  must have no gameplay effect. The projection-only foundation and first
  movement/end-turn routes are complete; production still needs the remaining
  projected posture/route/improvement/upgrade and other unit controls,
  diplomacy, belief/religion choices, trade, popup/choice, history/event, and
  remaining end-turn-blocker interaction surfaces before this item can be
  checked.
- [ ] Remove every v3-reachable fallback to legacy whole-save upload, download,
  local turn advancement, local resignation, or direct canonical mutation.
  Preserve those paths only for single-player, hotseat, saves, and explicitly
  legacy/API-v2 games.
- [ ] Finish the source-level mutation audit across all world, city, unit,
  diplomacy, religion, espionage, alert, and multiplayer UI call sites. Every
  player-authored online mutation must either use a closed typed v3 operation or
  be explicitly classified as client-local presentation.
- [ ] Complete all remaining end-turn prerequisites so a legal player cannot be
  permanently blocked by a choice that v3 cannot submit or render.

## P0: partial gameplay and projection families

- [ ] Persistent unit orders: inventory and add any remaining order controls not
  covered by exploration, automation, posture, improvement, road, movement, and
  cancellation commands. Siege setup now uses the closed `setup` posture in
  projection v42 and is worker-owned. Exact current-turn escort movement now
  uses one atomic `MoveUnit` intent with an optional projected companion ID;
  the worker validates both units and moves them together without persisting
  the legacy cache-only `escorting` flag. Multi-turn escort orders are now
  durable canonical `MoveUnitToward` orders: a nullable stable companion ID is
  saved with the route-owning unit, revalidated and reconstructed only inside
  the worker, projected only to the owner, cleared by cancellation/replacement
  through either pair member, and proven across snapshot reload. Pending
  serialized unit orders execute inside the worker immediately before
  authoritative end turn. The projection-only production world now exposes
  exact movement cancellation and exploration/automation state flips, but
  per-unit posture availability, long-route destinations, improvement/road
  choices, upgrades, rename, disband/found/pillage/paradrop, and any
  retained autoplay controls still need explicit projected inputs and UI.
  Whole-turn, military, civilian, and economy autoplay remains fail-closed for
  opened v3 games; if retained, implement it as explicit server-owned AI.
- [x] Migrate capital-project unit consumption (`AddInCapital`) to the
  authoritative worker. Projection v52 advertises only the server-derived
  project name for an owned current-turn unit at its own capital; the client
  submits only the stable unit ID. The Kotlin worker re-derives the capital,
  project unique, exact spaceship-part key, unit consumption, and resulting
  counter change. Lost-response retry reuses the same idempotency key, while
  foreign and out-of-turn projections expose no action metadata.
- [x] Migrate instant water-resource and general/mod-defined improvement
  creation to `CreateInstantImprovement(unitId, actionId)`. Projection v53
  exposes only bounded opaque identities and presentation titles for legal
  owned current-turn actions. The private worker re-derives the exact
  improvement, tile, resource and movement legality, unit consumption, and
  unique side effects through the shared Kotlin callback. Opened-v3 mapping
  failures return without invoking the local mutation path.
- [x] Harden all opaque unit-action button routing. Instant improvements,
  transformations, and generic triggered uniques now share one opened-v3
  fail-closed router: an unavailable cached projection identity suppresses the
  command and can never fall through to the legacy local mutation callback.
  Focused tests preserve the local route for non-v3 and unrelated actions.
- [x] Complete all inventoried authoritative city-production context operations for
  opened-v3 projection (move-to-top/end, add-to-top, add-to-all-cities,
  add-or-move-to-top-in-all-cities, and remove-from-all-cities) through a
  server-derived bounded city set and private worker execution, with no client
  save mutation loop.
- [x] Add the public wonder completion/effect event feed needed by projection-only
  UI. Projection v48 now exposes a durable canonical completion turn, wonder
  identity, and ruleset-derived effect summary while revealing the builder only
  when known and the city/location only after exploration.
- [x] Retain multi-tile buying through bounded authoritative batches.
  Projection v51 advertises only complete server-derived ring proposals with
  canonical tile count, sequential price, and affordability. The client submits
  only city ID and ring; the private Kotlin worker derives the deterministic
  contiguous tile order, validates full affordability before mutation, and
  applies the batch atomically. Retry reuses the same idempotency key, and no
  client-authored tile list, actor, price, legality, or result crosses the wire.
- [x] Research: implement bounded removal and reordering of queued technologies.
  Replace/append/free selection, researched history, queue progress/cost/
  overflow, turn estimates, and opaque public completion prompts plus their
  authoritative acknowledgment were completed in projection v39. Projection
  v49 adds closed move-to-top/up/down/end and remove actions to each exact queue
  entry. The Kotlin worker derives and validates the complete prerequisite-safe
  proposal; the client cannot submit a queue, actor, legality, or result. The
  picker exposes only the actions advertised by the cached projection and
  reconciles after the server commit. Ordinary versus free-tech picker mode is
  also selected from the cached authoritative projection.
- [x] Policies and ideology: ideology branches, tenets, and arbitrary
  mod-defined policy choices use the existing closed `AdoptPolicy` operation
  and shared canonical `PolicyManager` rules. Projection v50 additionally
  exposes each known major civilization's sorted public adopted branch names,
  including ideology, while Rust rejects duplicate, reordered, oversized, or
  malformed branch disclosure. The opened-v3 picker submits only names from
  the actor's projected selectable set and never mutates policies locally.
- [x] Re-audit the `EndTurn` readiness list and prove that every canonical
  blocker has a player-scoped projection and authoritative resolution command.
  Construction, technology/free-tech, policy/ideology, pantheon, religion
  founding, enhancement, reform, diplomatic voting/abstention, and
  great-person selection are covered. `EndTurn` rejects every unresolved
  blocker without changing canonical state, and focused tests clear each only
  through its Kotlin worker command. Rust rejects duplicate, reordered,
  legacy `move_spies`, or blocker projections without a matching choice.
  Transient idle-unit and spy-movement reminders remain client-local.

## P0: membership and game administration

- [x] Wire the production player-setup UI to owner-authorized invitations,
  target-scoped invitation discovery, stale-invitation refresh, and acceptance.
  Durable backend policy, retry IDs, atomic consumption, and multi-revision
  joins are implemented; knowledge of a game ID alone no longer authorizes join.
  The multiplayer screen now exposes an account inbox and owner-only invite
  action, binds retry IDs to exact request meaning, rotates acceptance identity
  after a refreshed revision/hash, refreshes membership after joining, and
  labels the old game-ID add action as legacy-only whenever v3 is installed.
- [x] Wire production administration UI for owner kick, ownership transfer,
  close, and archive, including confirmation, retry with the same operation ID,
  status display, and clear handling when ownership changes remotely. The API,
  persistence, client transport/session, command gating, and metadata are done.
  The production screen now gates confirmed administration to an active,
  available owner membership; revisioned kick opens/reuses the command bus,
  lifecycle operations retain exact-meaning IDs across ambiguous retries, and
  stale/rejected authority refreshes and closes the invalid owner popup.
- [ ] Add a richer game administration/history projection if the product needs
  more than the current lifecycle status and durable audit/outbox records.
- [ ] Decide whether spectator invitations can be revoked by the owner and, if
  supported, implement it as a distinct audited operation.

## P0: recovery and canonical-history guarantees

- [ ] Complete recovery qualification across every supported setup and
  controlled worker/process fault mode. The real packaged Kotlin worker now has
  fresh-process byte/hash parity coverage for seeded city-state placement and a
  replayable player-assignment mutation. The bounded replay and immutable
  recovery-publication path is PostgreSQL integration-tested, but those
  representative fixtures do not yet prove all setups, commands, AI turns, or
  process failures. Pre-migration rows without exact replay context fail closed
  and require operator-supplied recovery evidence.
- [ ] Add revision/snapshot retention and compaction without breaking command
  idempotency, audits, recovery, or projection hashes.
- [x] Add a dry-run-first bounded recovery workflow. `unciv-v3-recover` reports
  only revision metadata and the canonical hash by default; `--apply` publishes
  only a still-current, verified quarantined head as a new immutable revision.
- [ ] Add reviewed repair workflows for the remaining reconciliation findings.
  The bounded read-only reconciliation CLI detects invalid heads/chains,
  missing or orphaned snapshots, commands and commit-outbox events,
  owner/civilization membership damage, missing replay identities, time, or
  exact operations, quarantine state, and invalid compressed/canonical snapshot
  bytes, but it intentionally does not mutate those findings.
- [ ] Complete controlled process fault tests for Rust process death, Kotlin
  worker death, lost HTTP responses, and outbox-dispatch boundaries. Forced
  database-connection termination while blocked at the canonical commit lock is
  covered: it leaves no phantom rows and the same command retries safely.
- [ ] Test actual PostgreSQL/service failover and reconnection under load. The
  independent-replica CAS race is covered: two valid commands at one expected
  revision produce exactly one complete head and one stale conflict.

## P0: projection confidentiality and protocol hardening

- [ ] Write a per-field player and spectator projection policy and keep schemas
  fail-closed when `GameInfo` grows.
- [ ] Expand golden sentinel leak tests across every civilization/relationship,
  fog state, private order, city, resource, notification, diplomacy, religion,
  espionage, and spectator role.
- [ ] Add compact projection deltas with revision/hash validation, while keeping
  full authenticated HTTP projection as the recovery path.
- [ ] Prove canonical snapshots and hidden fields never occur in public HTTP,
  WebSocket frames, normal logs, traces, metrics, audit payloads, or errors.
- [ ] Add property/fuzz tests for command envelopes, unknown fields/enums,
  length-prefixed worker frames, projection serialization, revision transitions,
  idempotency-key reuse with changed content, decompression, and save/ruleset
  deserialization limits.
- [ ] Add request/response size, JSON depth/string/collection, timeout, and
  cancellation limits at every public and worker boundary.

## P1: worker isolation and deterministic execution

- [ ] Replace the current one-operation connection model with a measured bounded
  persistent worker pool or document with benchmarks why the existing model is
  retained.
- [ ] Add authenticated local IPC/service identity, strict connect/read/write and
  command deadlines, worker crash recycling, circuit breakers, and per-command
  CPU/memory limits.
- [ ] Verify the manifest inside the worker and finish immutable mod acquisition:
  allowlisting, archive path/link defenses, byte/entry quotas, redirect and host
  policy, atomic staging, semantic validation, and no client-selected URL fetch.
- [ ] Add deterministic fresh-process parity fixtures for every command family,
  game creation seed, random combat/event path, turn processing, and all AI.
  Packaged-worker coverage currently proves seeded creation with city-states
  and replayable player assignment.
- [ ] Package and pin the exact Kotlin worker build together with compatible
  Rust protocol, client capability, ruleset manifests, and database migrations.
  A self-contained executable worker JAR now exists and is exercised by tests;
  release-level digest pinning and compatibility-bundle enforcement remain.

## P1: authentication, accounts, social features, and clients

- [ ] Build account registration/login/session/account-management UI, including
  password change, logout-all/disable/delete behavior, clear recovery policy,
  and understandable rate-limit errors.
- [ ] Implement secure Android and desktop token stores. The in-memory/testing
  token store is not a production credential store.
- [ ] Add bounded concurrent-session policy, account recovery policy,
  credential-stuffing monitoring, credential rotation, and tests that secrets
  never enter errors, logs, traces, worker frames, or projections.
- [ ] Decide and implement v3 chat, friends, invitations, and lobby behavior as
  separate non-canonical services with membership, size, content, privacy, and
  rate limits. Do not put chat/social data into `GameInfo` revisions.
- [ ] Finish reconnect, offline/stale presentation, retry UX, projection upgrade,
  cache replacement, and explicit legacy/v3 game labeling on supported clients.

## P1: notifications and multi-instance operation

- [ ] Add shared cross-instance notification fan-out; in-process WebSocket
  broadcast is insufficient when more than one Rust replica serves clients.
- [ ] Add connection and subscription limits, heartbeat/idle policy, slow-reader
  handling, bounded queues, reconnect backoff, and sustained duplicate/lost/
  reordered notification tests.
- [ ] Add outbox retention, poison-event handling, lag alerts, and operational
  repair tooling without allowing notifications to become authoritative.
- [ ] Publish a full WebSocket/AsyncAPI lifecycle contract rather than only an
  OpenAPI upgrade and revision-frame description.

## P1: production deployment and PostgreSQL 19 Beta 2 operations

- [ ] Turn the current development compose setup into production packaging with
  separate Rust, private Kotlin worker, and exact digest-pinned PostgreSQL 19
  Beta 2 services, health/readiness gates, resource limits, and upgrade checks.
- [ ] Configure production TLS/HSTS and explicit trusted-proxy handling. Never
  trust forwarding headers from an untrusted peer.
- [ ] Create separate least-privilege PostgreSQL roles for runtime, migrations,
  backups, restores, and audit access; require encrypted database transport and
  credential rotation.
- [ ] Implement automated backups and point-in-time recovery, then run and record
  destructive restore drills that validate head revision, snapshot hash,
  journal, membership, session, audit, and outbox invariants.
- [ ] Document and test PostgreSQL 19 prerelease upgrades/rollback. Production use
  of Beta 2 is an accepted product decision, but it still requires a rehearsed
  migration and restore gate for each later beta/RC/final image.
- [ ] Add schema forward/rollback policy, startup migration compatibility checks,
  database statement/lock timeouts, pool sizing, capacity alerts, and disk-full
  behavior.
- [ ] Add operator runbooks for worker failure, corrupt game quarantine/recovery,
  database failover, outbox backlog, credential compromise, abuse, and
  break-glass access.

## P1: observability, security, and release controls

- [ ] Add redacted structured logs, metrics, traces, dashboards, and alerts for
  authentication abuse, stale conflicts, command latency/failures, worker
  crashes/timeouts, database locks, revision growth, projection size, outbox lag,
  and WebSocket load.
- [ ] Separate operator/admin endpoints from the public network and authentication
  domain; add immutable security-audit export, retention/access policy, and
  incident-response ownership.
- [ ] Add cache-control and content-type hardening, safe error redaction, CORS/
  origin policy, TLS termination tests, dependency/vulnerability scanning, SBOM,
  secret scanning, and release provenance/signing where practical.
- [ ] Add malicious-client integration suites for cross-game/account/civilization
  IDs, stale/reordered commands, changed-payload idempotency reuse, oversized and
  malformed frames, WebSocket exhaustion, and expensive rulesets/commands.
- [ ] Re-check AGPL/MPL boundaries and preserve notices; do not copy code from
  `runciv` or another reference without an explicit compatible licensing record.

## P1: legacy migration and retirement

- [ ] Build a one-way, dry-run-first legacy importer that authenticates ownership,
  bounds and validates the save, pins an approved manifest, records provenance,
  handles two divergent candidates as a conflict, and creates revision zero once.
- [ ] Keep legacy and v3 origins/listeners, credentials, namespaces, storage, and
  sessions isolated. Add automated tests proving legacy endpoints cannot read or
  mutate a v3 game.
- [ ] Add deprecation telemetry and an operator switch that disables legacy writes
  without disabling v3; define the user migration and final retirement plan.

## P2: performance, capacity, and final release evidence

- [ ] Complete the ADR benchmark table with measured JVM startup, idle and peak
  memory, representative large-save load, command latency, end-turn latency,
  worker recycle cost, and deployment complexity.
- [ ] Run sustained low-resource load tests for HTTP, commands, PostgreSQL locks,
  worker concurrency, AI turns, projections, outbox delivery, and WebSockets.
  Publish throughput, latency percentiles, memory, CPU, storage growth, and a
  defensible capacity limit—never “unlimited users.”
- [ ] Add Linux production smoke tests and supported desktop/Android client build
  and reconnect tests. Preserve and rerun single-player, hotseat, save-format,
  legacy multiplayer, and API-v2 regression suites throughout migration.
- [ ] Run a final repository-wide mutation/authority audit and threat-model review.
  The release gate is zero v3 client path capable of replacing/patching canonical
  state and zero known untracked gameplay mutation family.

## Current verification health

- No known compile, test, formatting, clippy, or database integration error is
  being deferred from the current milestone.
- `./gradlew :tests:test :server:test :desktop:compileKotlin --no-parallel`
  passes (1033 JVM/server tests, 13 intentional skips).
- Rust passes 118 active library tests and 10 HTTP/OpenAPI tests; 21 serialized
  PostgreSQL integration tests pass on the exact PostgreSQL 19 Beta 2 digest.
- `cargo fmt --all -- --check`, warnings-as-errors
  `cargo clippy --all-targets --all-features -- -D warnings`, generated OpenAPI
  parity, and `git diff --check` pass.
- `main.rs` is 6 lines, `lib.rs` is a 49-line facade, and the largest Rust source
  is 796 lines. New work must split by concern before crossing the 800-line
  guardrail.

Update this file whenever a gap is completed, split, newly discovered, or
proven not applicable. Never delete an unresolved item merely because it moves
outside the current milestone.
