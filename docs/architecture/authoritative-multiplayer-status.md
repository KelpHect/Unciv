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
