# Authoritative multiplayer work still missing

This is the current executable gap list for authoritative multiplayer v3 as of
2026-07-22 on the authoritative-v3 feature branch. It records only incomplete work;
completed command families and historical milestones remain in
`docs/multiplayer-command-coverage.md` and
`docs/architecture/authoritative-multiplayer-status.md`.

The target remains: online clients submit typed intent and render permitted
server projections; the private Kotlin worker runs all game rules, turn
progression, deterministic randomness, and AI; Rust is the only committer of
canonical revisions; PostgreSQL 19 Beta 2 is the sole production/test database.

## P0: required before v3 can replace legacy online play

- [ ] Make v3 the complete production game lifecycle, not an explicitly opened
  opt-in path. New online games must be created by the v3 server and must never
  be created locally and uploaded.
- [ ] Migrate the new-game UI to bounded server setup choices, server-owned
  seed/randomness, ruleset-manifest selection, progress/error handling, and the
  returned revision-zero projection.
- [ ] Migrate game discovery, join, civilization assignment, and open-game UI
  to account membership and server projections. A fresh device must reconstruct
  every v3 game without a local save.
- [ ] Build projection-only world rendering. The online world screen must not
  require a canonical `GameInfo`, and deleting or modifying every client cache
  must have no gameplay effect.
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
  authoritative end turn. Whole-turn, military,
  civilian, and economy autoplay controls are fail-closed for opened v3 games;
  if retained as a product feature, implement them as explicit server-owned AI
  operations rather than client automation.
- [ ] City production: add safe add-to-top, all-cities, and bounded batch
  operations where the production UI exposes them; project progress, costs,
  legal tile targets, and public wonder effects needed for projection-only UI.
- [ ] Purchases: project server-derived costs and legal targets for ordinary and
  tile-targeted purchases.
- [ ] City tiles: project ownership/working-city assignment, purchase costs, and
  legal targets; restore any intentionally disabled multi-tile interaction with
  bounded typed commands if it remains a supported feature.
- [ ] Research: implement bounded removal and reordering of queued technologies.
  Replace/append/free selection, researched history, queue progress/cost/
  overflow, turn estimates, and opaque public completion prompts plus their
  authoritative acknowledgment were completed in projection v39. The worker
  derives prerequisites and completion identity without accepting a
  client-authored queue or outcome. The current picker exposes no removal or
  drag-reordering control; any future operation needs server semantics that
  preserve prerequisite validity. Ordinary versus free-tech picker mode is now
  selected from the cached authoritative projection.
- [ ] Policies and ideology: implement ideology selection, tenets, and any
  supported mod-defined multi-choice policy flow; project public ideology/event
  data required by the UI.
- [ ] Re-audit the `EndTurn` readiness list after the above work and prove that
  every blocking action has a player-scoped projection and authoritative
  resolution command. The transient move-spies reminder has been removed from
  canonical readiness; projected construction, research, policy, religion,
  diplomatic-vote, and great-person blockers remain enforced. Continue this
  audit as the projection-only UI replaces local prompt/result flows.

## P0: membership and game administration

- [ ] Wire the production player-setup UI to owner-authorized invitations,
  target-scoped invitation discovery, stale-invitation refresh, and acceptance.
  Durable backend policy, retry IDs, atomic consumption, and multi-revision
  joins are implemented; knowledge of a game ID alone no longer authorizes join.
- [ ] Wire production administration UI for owner kick, ownership transfer,
  close, and archive, including confirmation, retry with the same operation ID,
  status display, and clear handling when ownership changes remotely. The API,
  persistence, client transport/session, command gating, and metadata are done.
- [ ] Add a richer game administration/history projection if the product needs
  more than the current lifecycle status and durable audit/outbox records.
- [ ] Decide whether spectator invitations can be revoked by the owner and, if
  supported, implement it as a distinct audited operation.

## P0: recovery and canonical-history guarantees

- [ ] Implement bounded recovery from the newest valid prior immutable snapshot
  plus journal replay. Current corrupt-head handling quarantines the game but
  does not reconstruct it.
- [ ] Add revision/snapshot retention and compaction without breaking command
  idempotency, audits, recovery, or projection hashes.
- [ ] Add reviewed, dry-run-first repair workflows for reconciliation findings.
  The bounded read-only CLI now detects invalid heads/chains, missing or orphaned
  snapshots, commands and commit-outbox events, owner/civilization membership
  damage, quarantine state, and invalid compressed/canonical snapshot bytes.
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
- [ ] Package and pin the exact Kotlin worker build together with compatible
  Rust protocol, client capability, ruleset manifests, and database migrations.

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
- `./gradlew :tests:test :server:test --no-daemon` passes (950 JVM/server tests,
  13 intentional skips).
- Rust passes 90 active library tests and 7 HTTP/OpenAPI tests; 17 serialized
  PostgreSQL integration tests pass on the exact PostgreSQL 19 Beta 2 digest.
- `cargo fmt --check`, warnings-as-errors `cargo clippy --all-targets -- -D
  warnings`, and `git diff --check` pass.
- `main.rs` is 6 lines, `lib.rs` is a 35-line facade, and the largest Rust source
  is 783 lines. New work must split by concern before crossing the 800-line
  guardrail.

Update this file whenever a gap is completed, split, newly discovered, or
proven not applicable. Never delete an unresolved item merely because it moves
outside the current milestone.
