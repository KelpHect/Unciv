# Authoritative Multiplayer Threat Model

## Overview

This threat model covers the Unciv repository as it relates to multiplayer integrity and the transition from legacy file exchange to authoritative multiplayer API v3. Unciv is a moddable LibGDX game whose shared Kotlin engine is used by Android, desktop, tests, and the legacy Ktor server. The v3 design adds a public Rust control plane, PostgreSQL canonical storage, and a private Kotlin worker that reuses the game engine for rules execution.

The model is intentionally repository-wide. Multiplayer correctness depends not only on HTTP handlers, but also on save and ruleset deserialization, mod acquisition, platform credential storage, database operations, worker isolation, deployment, and the release supply chain.

## Final authority review

Reviewed repository-wide on 2026-07-28. This section is the current closure
assessment; later `Current controls` and `Required hardening` paragraphs retain
the design history that led to it.

| Boundary | Current enforced result |
| --- | --- |
| Public writes | The closed OpenAPI command-route inventory exactly matches the Kotlin API-v3 transport. Requests contain typed intent, command identity, and expected revision; no V3 route accepts `GameInfo`, snapshots, save replacement, arbitrary patches, actor civilizations, worker operations, or client RNG. |
| Production client | Every gameplay `*IfOpen` session mutation is referenced by the projection-only production UI. Source gates prohibit canonical `GameInfo`, `GameStarter`, legacy upload/download, local turn execution, and client autoplay in that UI. Whole-save access elsewhere must explicitly cross the `.legacy` façade. |
| Rules and AI | Only the private Kotlin worker loads canonical snapshots and executes the shared game engine. End turn runs every AI civilization in that worker before Rust may compare-and-swap the resulting revision. Rust performs identity, protocol, consistency, and persistence checks but contains no second rules engine. |
| Mods | New games resolve an operator-approved, content-addressed base-plus-mod manifest. Acquisition is bounded and fail-closed; fresh packaged workers validate and execute mod-defined generation and stateful commands. A client mod name or local content is never authoritative. |
| Persistence | PostgreSQL transactions lock the head and bind actor, command meaning, expected revision, journal, snapshot, hash, membership effects, and outbox. Exact retries return the original result; stale or changed-meaning retries cannot add a revision. Recovery, restore, replica-race, worker/Rust crash, and reconciliation tests preserve one history. |
| Confidentiality | Players and spectators receive explicit bounded projections only. Sentinel, role, malformed-state, response-shape, cache, log, audit, notification, and public-route tests keep canonical snapshots and opponent secrets inside the trusted service boundary. |
| Availability | Worker frames, deadlines, queue, circuit breaker, JVM/cgroup resources, WebSocket leases, HTTP sizes, rate limits, SQL timeouts, storage alarms, outbox retries, readiness, and operator recovery are bounded and fail closed. Notifications remain non-authoritative hints reconciled through HTTP. |
| Release | The Linux bundle is content-addressed and self-verifying, includes exact migrations, binaries, rulesets, license, and SPDX evidence, and is built only from immutable tags. PostgreSQL 19 Beta 2 and runtime images are digest-pinned. Hosted provenance, SBOM, secret-scan, RustSec, production-stack, worker-restart, and constrained-load evidence are release gates. |

The final mutation inventory found no untracked V3 gameplay family and no
client path capable of changing canonical state without a typed server command.
Approved mods are supported end to end. AI is deliberately unavailable as a
client-side V3 automation feature because all AI players execute server-side.

The accepted residual risks are operational rather than missing authority:
PostgreSQL 19 Beta 2 may contain prerelease defects; a compromised trusted
operator, Rust process, worker host, or database remains a server incident; and
capacity is bounded by the measured release profile rather than advertised as
unlimited. A later PostgreSQL 19 beta, RC, or final image must pass the
documented upgrade/restore rehearsal before replacing Beta 2. Future
gameplay/rules/mod/UI changes are governed by `AGENTS.md` and must preserve the
inventories above.

### Security objectives and assets

- Preserve the single canonical game history: game identity, monotonically increasing revision, canonical state hash, ruleset manifest, membership, turn ownership, and accepted command history.
- Prevent a player from acting for another account or civilization, learning hidden opponent state, replaying a command with different meaning, or replacing authoritative state with a client-produced save.
- Protect password verifiers, bearer sessions, account identities, audit data, database credentials, signing material, and operator credentials.
- Keep canonical snapshots and full rules-engine state inside trusted server components. Player clients receive only player-scoped projections and disposable cache data.
- Ensure the worker executes the exact immutable ruleset selected for the game and that accepted commands use the same engine semantics as supported Unciv clients.
- Preserve availability and recoverability of the control plane, worker pool, PostgreSQL revisions and snapshots, notification outbox, backups, and migration tooling.
- Preserve the integrity of downloaded mods, local saves, packaged applications, dependencies, CI workflows, and published artifacts where they can affect multiplayer behavior.

## Threat Model, Trust Boundaries, and Assumptions

### Actors

- **Unauthenticated network attacker:** can register and log in, enumerate public endpoints, send malformed or oversized traffic, open WebSockets, and operate many source addresses.
- **Malicious authenticated player:** controls the client, local storage, requests, command IDs, revisions, timing, and local mods. They may collude with other players or own multiple accounts.
- **Honest player:** expects turn integrity, hidden-information confidentiality, session security, and recovery after reconnects or stale commands.
- **Mod author or archive host:** controls ruleset JSON, media, archive paths, compression ratios, metadata, and availability. A compromised host or repository is equivalent.
- **Operator or administrator:** can access deployment configuration, databases, backups, worker hosts, logs, and migration tools. This is a privileged role whose mistakes or compromise can affect every game.
- **Developer, CI workflow, and dependency publisher:** can influence source, generated artifacts, Gradle or Cargo dependencies, build plugins, release credentials, and distributed binaries.
- **Rust control plane, PostgreSQL, and Kotlin worker:** trusted service identities. A compromise of any one is a server-side security incident, not a client trust assumption.

### Trust boundaries

1. **Player client to public v3 HTTP/WebSocket service.** Everything from the client is untrusted, including identifiers, expected revisions, hashes, command IDs, and WebSocket behavior. TLS termination and forwarding configuration sit on this boundary.
2. **Rust control plane to PostgreSQL.** The database is the durable source of truth for accounts, sessions, membership, manifests, revisions, snapshots, idempotency records, rate-limit buckets, audits, and outbox delivery. SQL credentials and schema migration authority are high-value capabilities.
3. **Rust control plane to private Kotlin worker.** Length-prefixed JSON carries canonical snapshots, actor identity, immutable ruleset manifests, typed operations, results, projections, and hashes. This channel must remain private and mutually attributable even if it is loopback-only today.
4. **Kotlin worker to shared Unciv engine and rulesets.** Existing game logic and deserializers become server security-critical. Ruleset selection, engine version, determinism, resource limits, and exception handling affect authoritative integrity and availability.
5. **Canonical state to player projection.** Full game state may contain fog-of-war, opponent plans, private resources, diplomacy, or other hidden data. Projection is an allowlist transformation; UI hiding is not an access control.
6. **Legacy multiplayer to v3.** The legacy Ktor routes exchange whole files and retain Basic-auth-style credentials and chat behavior. They must not be able to read, write, import into, or impersonate authoritative games without an explicit, validated migration path.
7. **Network and local content to filesystem and parser.** Save strings, maps, mod JSON, media, ZIP entries, URLs, response headers, and filenames cross into decompression, deserialization, and local file operations.
8. **Application to Android/desktop operating system.** Session tokens, saves, logs, clipboard or deep-link inputs, proxy settings, certificate validation, and local file permissions rely partly on platform controls.
9. **Source and dependency ecosystem to release artifacts.** GitHub workflows, Gradle and Cargo resolution, wrappers, signing, container builds, and package publication cross from third-party or contributor-controlled inputs into trusted binaries.

### Assumptions

- Clients and client-side caches are fully attacker-controlled. A client-reported state hash is diagnostic input, never authority.
- PostgreSQL, the Rust process, the worker host, and authorized operators are trusted to access canonical state. Compromise of those components is addressed through isolation, least privilege, auditing, backup, and incident response rather than cryptographic concealment from the operator.
- Production uses correctly configured TLS and trusted forwarding. Source-prefix rate limits are meaningful only when the application receives a validated client address from a trusted proxy chain.
- The worker is not internet-accessible. Loopback binding reduces exposure but is not a substitute for process identity, least privilege, framing limits, and timeouts.
- Rulesets used for authoritative games are immutable, content-addressed, size-bounded, and accepted by server policy. A local client mod with the same display name is not necessarily the same content.
- Mod rulesets are data interpreted by Unciv rather than intentionally executable native code. Nevertheless, parsers, unique-expression evaluation, media handling, and pathological data can expose denial-of-service or implementation vulnerabilities.
- WebSocket notifications are hints. Correctness comes from authenticated HTTP projection reconciliation, so notifications may be delayed, duplicated, reordered, or lost without changing canonical state.
- Single-player and hotseat cheating on a user's own device is outside the multiplayer authority boundary unless that data can be imported as authoritative state.

### Required invariants

- Only the server creates or advances canonical revisions. Each commit compares the expected head, changes the head exactly once, stores the snapshot and canonical hash together, and emits its outbox event in the same transaction.
- A command's actor comes from a valid server session and game membership, never from a client-supplied account or civilization field.
- A command ID is idempotent within its defined scope: retrying the same command yields the original result, while reuse with different game, actor, revision, or payload is rejected.
- Clients cannot upload, replace, or directly download canonical snapshots through v3. Only typed, versioned, size-bounded commands cross the public write boundary.
- Only the private worker executes gameplay rules. The Rust control plane validates protocol, identity, concurrency, persistence, and result consistency; it does not independently invent gameplay outcomes.
- Every player response is produced by an explicit projection schema. Unknown fields fail closed, and tests use sentinel secrets to prove they cannot cross the boundary.
- A game's ruleset manifest binds content hashes, sizes, engine/protocol compatibility, and policy. Worker output is rejected if revision or state hash relationships are inconsistent.
- Bearer tokens are high-entropy, stored server-side only as digests, revocable, rotatable, expiry-checked, and never written to logs or audit details.
- Audit records identify security-relevant outcomes without retaining passwords, bearer tokens, raw request bodies, or unnecessarily precise network identifiers.
- Legacy file multiplayer and v3 have separate namespaces, credentials, storage, routing, and operational policy until a one-way migration validates ownership and content.

## Attack Surface, Mitigations, and Attacker Stories

### Authentication, sessions, and account abuse

**Attacker story:** An attacker sprays passwords, registers accounts until storage or operator attention is exhausted, steals a bearer token from insecure local storage or logs, reuses a session after logout, or bypasses a source-address limit through proxy-header confusion.

Current v3 controls include password hashing, opaque session issuance, digest-only server storage, expiry, rotation and revocation, durable fixed-window login and registration limits, stable `429` responses, prefix-reduced network audit data, and one-way identity hashes. Handlers derive the actor from the bearer session.

Required hardening includes production TLS/HSTS policy, an explicit trusted-proxy configuration, secure Android and desktop token storage, bounded session counts, credential-stuffing monitoring, audit retention and access policy, account recovery policy, database credential rotation, and tests proving credentials never enter errors, traces, projections, or worker frames. Rate limiting must remain effective across replicas and must avoid becoming an account-lockout oracle.

### Typed commands, authorization, concurrency, and replay

**Attacker story:** A player changes a game or unit ID to act across tenants, submits an action for another civilization, races two legal commands at one head revision, replays an accepted command with a changed payload, or uses a stale local view to overwrite newer play.

Current v3 controls bind sessions to memberships, expose typed join, move-unit, and end-turn commands, use expected revisions and transactional compare-and-swap commits, persist command IDs for idempotency, enforce turn ownership, assign civilizations server-side, and execute movement and turn rules in the worker. PostgreSQL is the durable authority.

The closed envelope and invariants now cover every tracked gameplay action.
Command IDs are unique per game, and durable retries compare protocol, game,
command ID, expected revision, and typed command against the original journal
payload before worker execution and again inside the locked commit transaction.
Changed-meaning reuse receives a stable conflict while an exact retry may vary
only its explicitly diagnostic observed-state hash. Bounded IDs/payloads,
normalized authorization failures, controlled multi-replica races, and worker
fault tests prove rejected or failed executions cannot partially mutate
membership, head, snapshot, journal, or outbox state.

### Projection confidentiality

**Attacker story:** A legitimate member requests another player's view, discovers hidden state in a nested or newly added field, correlates hashes or metadata to infer fog-of-war information, or obtains a full snapshot through diagnostics, errors, logs, or an administrative endpoint.

Current v3 controls require membership for projection access, send the full snapshot only to the private worker, return an allowlisted projection DTO, and include sentinel-leak tests. Game metadata does not serve the canonical snapshot.

Required hardening includes a documented per-field projection policy, golden tests for every civilization and spectator role, fail-closed serialization when the engine model grows, response-size and timing review, safe error redaction, operator endpoint separation, cache-control headers, and tests that canonical snapshots and hidden fields never occur in HTTP, WebSocket, audit, or routine log output.

### Worker protocol and rules execution

**Attacker story:** A malicious command or snapshot triggers excessive CPU, memory, recursion, or an engine crash; a local process impersonates the worker; malformed length prefixes desynchronize the stream; a compromised worker returns an invalid snapshot/hash pair; or nondeterministic engine behavior creates divergent outcomes.

Current controls keep the worker protocol off the public router, use typed JSON
operations and bounded response frames, bind actor civilization server-side,
and verify worker-produced canonical hashes before persistence. Protocol v2
also authenticates every request and response with direction-separated
HMAC-SHA256 over a fresh request nonce, length, and exact payload; both
processes fail startup when the shared 256-bit service secret is absent or
malformed. The worker verifies identity before JSON parsing or rule execution,
and Rust verifies the response before parsing or persistence. The worker reuses
Unciv's game engine instead of accepting client outcomes.

Implemented controls include strict request/response frame limits, independently
bounded connect/write/read/total deadlines, authenticated direction-separated
local IPC, schema negotiation, deterministic replay tests, redacted errors, and
a shared fail-fast circuit breaker with a single recovery probe. PostgreSQL
keeps the old canonical head on every worker failure. Remaining hardening
includes live Linux qualification of the checked-in hard command watchdog,
systemd crash recycling, and JVM/cgroup CPU-memory limits, plus manifest
verification inside the worker.

### PostgreSQL, snapshots, outbox, and recovery

**Attacker story:** SQL injection or an overprivileged service identity exposes accounts and games; a migration corrupts history; backup restore rolls a game backward; snapshot/hash rows become inconsistent; two replicas publish conflicting heads; or a notification lease is abused to exhaust the outbox.

Current v3 code uses parameterized SQL, transactional head comparison, immutable revision/snapshot records, canonical hashes, membership constraints, durable session and rate-limit state, and leased outbox dispatch. WebSocket messages contain revision hints rather than authoritative state.

Required hardening includes least-privilege database roles for runtime, migration, backup, and audit access; encrypted transport and backups; point-in-time recovery drills; restore validation against revision/hash invariants; schema downgrade policy; outbox retention and poison-event handling; replica/load tests; database statement and lock timeouts; capacity alerts; and reconciliation that detects missing snapshots, orphan revisions, duplicate memberships, or a head without its committed outbox event.

### Legacy multiplayer and migration

**Attacker story:** A player uploads a fabricated whole-game save through legacy `/files/{fileName}`, exploits filename handling, relies on weak or absent legacy credentials, subscribes to chat channels without game membership validation, or causes legacy data to be treated as a v3 canonical game.

The repository currently keeps legacy Ktor routes separate from `/api/v3`, while v3 capability discovery explicitly forbids whole-state uploads. That separation is a transition control, not proof that legacy play is authoritative.

Required hardening includes separate origins or network listeners, storage roots and credentials; canonical path validation and request-size limits on legacy filenames/uploads; explicit deprecation telemetry; no shared session interpretation; and a one-way migration tool that authenticates game ownership, validates and size-bounds the save, pins a server-approved manifest, records provenance, and creates revision zero exactly once. Operators must be able to disable legacy writes without disabling v3.

### Saves, maps, mods, archives, and remote content

**Attacker story:** An archive entry such as `../target` escapes the mod staging directory; a ZIP bomb or huge JSON exhausts disk or memory; a redirect reaches an unintended host; crafted ruleset/save data crashes or stalls the engine; a mutable remote mod changes after a game starts; or media parsing exploits a platform library.

The client downloads GitHub or direct ZIP content into a staging area and parses saves, maps, and ruleset JSON through shared code. This is a distinct untrusted-content boundary. The observed ZIP extraction builds destinations from entry names, so containment must be enforced by canonical-path checks rather than assumed from staging.

Current v3 acquisition is an offline operator boundary with a closed exact-hash policy. It permits HTTPS only, requires an exact allowlisted host, disables redirects and environment proxies, keeps optional bearer credentials in environment memory, streams into bounded new staging files, verifies archive hashes before parsing, and rejects unsafe paths, links, special files, unsupported compression, collisions, and archive bombs. It extracts only the selected JSON subtree, validates exact component hashes and combined ruleset semantics in the packaged worker, atomically installs immutable versions, and prevents garbage collection from racing new-game creation. Clients and authoritative workers have no arbitrary URL-fetch surface.

Remaining hardening includes live Linux/systemd failure qualification, parser and decompressor fuzzing beyond the deterministic adversarial fixtures, media limits for any future server-side media ingestion, release-level worker/control-plane bundle pinning, and continued dependency and parser review.

### WebSocket notifications and availability

**Attacker story:** A client opens many authenticated sockets, reads slowly, causes unbounded queues, repeatedly reconnects, or treats a forged/stale notification as authority. A process crash loses in-memory fanout after an outbox row is leased.

Current controls authenticate the notification endpoint, scope fanout by
account, persist outbox events, lease delivery, and instruct clients to
reconcile revisions through HTTP. Notifications do not contain full state.
Each Rust process now bounds global and per-account sockets, frame/message and
write-buffer sizes, account hint queues, idle lifetime, and every write.
Admission occurs after authentication and before upgrade; exact drop guards
release permits and remove unused channel state. Ping/pong control traffic is
required to remain live, arbitrary data frames cannot extend the deadline, and
a lagged account receives only `resync_required`. A durable outbox claimant now
publishes a closed 1 KiB PostgreSQL notification to every replica; each replica
re-resolves authoritative membership before local delivery. Publish precedes
outbox acknowledgement, duplicates are harmless, and listener gaps or rejected
shared payloads force HTTP resynchronization.

Fleet-wide PostgreSQL leases now enforce global/per-account admission across
replicas, clients use bounded reconnect jitter, and bounded metrics cover lag,
drops, rejections, and lease-loss disconnects. Deterministic notification tests
exercise duplicate, missing, reordered, lagged, and oversized traffic without
canonical mutation. Sustained low-resource full-stack load qualification
remains tracked separately in the release checklist.

### Client platform and local data

**Attacker story:** Malware or another local user steals a session from plaintext preferences, an exported Android component injects a URI or file, a malicious save/mod name escapes a storage root, logs expose identifiers or tokens, or a stale client displays a move as accepted when the server rejected it.

Required controls include platform keystore/keychain-backed session storage where available, restrictive file permissions and Android component exports, URI and path validation, certificate validation, redacted diagnostics, projection cache invalidation on account/game changes, clear pending/rejected command UI, and treating all local saves and mods as untrusted on import. A compromised player device is not trusted to preserve multiplayer secrets already revealed to that player.

### Operations, observability, and administrative tooling

**Attacker story:** An operator runs an unsafe migration, exposes a debug endpoint, copies production snapshots into insecure test storage, leaks secrets through metrics, or cannot distinguish abuse from a broken worker during an incident.

Required controls include separate administrative authentication and network policy, least-privilege runbooks, secret-manager integration, redacted structured logs, immutable security audit export, metrics without credentials or hidden state, alerting for auth abuse/stale conflicts/worker failures/outbox lag, tested backup and rollback procedures, incident-response ownership, and auditable break-glass access. Health endpoints should disclose only what unauthenticated load balancers require.

### Build, dependency, and release supply chain

**Attacker story:** A dependency or Gradle plugin is compromised, an untrusted pull request gains release credentials, a workflow action changes behavior, generated protocol artifacts drift, or a signing key publishes a malicious Android, desktop, container, or server build.

Required controls include pinned and reviewed workflow actions, least-privilege GitHub tokens, protected release environments, isolated signing credentials, dependency locking and verification, reproducible or provenance-attested artifacts where practical, secret scanning, code review for protocol/schema changes, SBOM and vulnerability monitoring, and release tests that bind client capability declarations, server protocol, worker engine version, and database migrations. Reference implementations may inform behavior but must not introduce license-incompatible code into distributed components.

The public API now treats browser origin as an explicit boundary. Native
clients without an `Origin` header remain supported, while a request carrying
an origin not present in the bounded exact-HTTPS allowlist is rejected before
route execution. CORS never reflects arbitrary origins, never enables
credentials, and permits only the API methods plus bearer/content-type request
headers. All responses are non-cacheable and carry nosniff, no-referrer, and
restricted browser-feature policies. TLS/HSTS and trusted-proxy qualification
remain separate required controls; see
`docs/operations/authoritative-http-security.md`.

### Security validation priorities

The highest-value adversarial tests are: cross-account/cross-civilization command attempts; idempotency-key reuse with changed content; multi-replica revision races; sentinel hidden-state projection tests; worker frame, timeout, crash and resource-exhaustion tests; archive traversal and decompression bombs; legacy/v3 namespace isolation; token/audit/log leakage searches; backup restore and rollback drills; and sustained HTTP/WebSocket/load tests against bounded resources.

## Severity Calibration (Critical, High, Medium, Low)

Severity combines exploitability with confidentiality, integrity, availability, and recovery impact in Unciv's multiplayer context. Gameplay balance defects without a trust-boundary violation are ordinary correctness bugs; the same defect becomes a security issue when a malicious player can use it to violate authoritative state or another player's confidentiality.

### Critical

- Remote code execution in the public service or authoritative worker through commands, saves, mods, archives, or deserialization.
- Unauthenticated arbitrary replacement of canonical state or execution of arbitrary commands across games at scale.
- Exfiltration of production signing keys, database superuser credentials, or secrets sufficient to compromise essentially all accounts and games.
- A systemic endpoint that exposes full canonical snapshots, credentials, or hidden state for most games without membership.

### High

- Authentication or authorization bypass allowing cross-account or cross-civilization actions in authoritative games.
- Reliable revision rollback, split-brain canonical histories, or idempotency failure that lets a player duplicate or change an accepted action.
- SQL injection or path traversal that reads or overwrites account, game, server, or release data with substantial scope.
- Persistent bearer-session theft through server logs, responses, insecure server storage, or a remotely triggerable client flaw.
- Projection leakage of strategically significant hidden state across games or players.

### Medium

- Targeted remote denial of service through expensive engine operations, worker crashes, WebSocket exhaustion, large frames, or pathological rulesets where recovery is available and scope is bounded.
- Limited hidden-information disclosure affecting one game or a narrow field without full canonical-state access.
- Rate-limit or audit bypass that materially enables credential attacks but does not itself bypass authentication.
- A backup, migration, or outbox defect that causes bounded loss, delay, or rollback and is recoverable from verified durable history.
- Local save or mod corruption that requires user interaction but can affect an authoritative migration unless the server rejects it.

### Low

- Disclosure of non-sensitive service metadata with no useful exploit chain.
- An isolated client crash, stale display, or lost notification that is corrected by authenticated HTTP reconciliation and cannot change canonical state.
- Minor resource consumption bounded by existing quotas and affecting only the attacker's session.
- Local-only manipulation requiring control of the player's own device, with no privilege gain, hidden-state exposure, or authoritative multiplayer impact.

Repository: https://github.com/KelpHect/Unciv
Reviewed runtime version: authoritative-v3-0.1.0-beta.2.7
(`df2c6a72bdd8e82775a89e27b9b024f214ecd885`)
