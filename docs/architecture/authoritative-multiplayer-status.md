# Authoritative multiplayer v3 status

## Milestone 0 — baseline and design

Completed on 2026-07-18:

- Verified that the existing protocol is client-authored full-save storage.
- Recorded the current call/data flow in
  `docs/architecture/current-multiplayer-flow.md`.
- Selected the Rust control-plane/Kotlin headless-worker architecture in ADR
  0001; no alternate rules engine will be introduced.
- Added repository guardrails in `AGENTS.md`.
- Added the versioned API-v3 command envelope and closed initial `EndTurn` /
  `MoveUnit` command union, with contract tests. No endpoint consumes it yet.

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
turn ownership is not yet enforced: the current authorization gate is game
membership role only. Player assignment, per-civilization authorization, and
additional typed gameplay commands remain pending.
