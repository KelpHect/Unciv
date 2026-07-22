# Authoritative multiplayer v3 status

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

Research queue removal/reordering and projection-only picker entry remain in
`missing_multiplayer.md`; the public research-completion event gap is closed.
