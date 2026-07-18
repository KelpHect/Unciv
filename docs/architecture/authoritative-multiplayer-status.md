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
`_`, and `-` (3-32 characters). Account persistence and public registration
are the next pieces.
