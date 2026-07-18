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
  round-trip `protocol/player-projection-v2.fixture.json` and reject an injected
  canonical-game field.

Generation and verification:

```text
cargo run --manifest-path authoritative-server/Cargo.toml -- --write-openapi
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

PostgreSQL 18.4 is the current production baseline. PostgreSQL 19 Beta 2 is a
required forward-compatibility lane so migrations and workloads can exercise
its newer behavior, but it is not a production recommendation while PostgreSQL
classifies version 19 as a prerelease. Move the production baseline to 19 after
general availability and a successful backup/restore and load validation. The
older PostgreSQL 16 entries above remain historical evidence, not the current
deployment target.

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
