# Authoritative multiplayer work still missing

This is the current executable gap list for authoritative multiplayer v3 as of
2026-07-29 on `master`. Completed foundations and
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
- [x] Implement server-created revision-zero lobbies, owner membership,
  password/slot/readiness/start authority, player-chosen unclaimed factions
  from the worker-validated canonical pool, and retry-safe backend
  administration for kick, server-derived force-resignation, ownership
  transfer, close, and archive, with production lifecycle controls.
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
  and no access to player commands or canonical state. Owners can also revoke
  spectator access through a distinct retry-safe audited operation; spectators
  retain a separate self-leave operation.
- [x] Implement authoritative end-turn readiness and advancement, including
  worker-owned pending orders, all AI players, canonical blocker validation,
  and server-side player rotation.
- [x] Implement bounded snapshot compression, corruption quarantine, read-only
  reconciliation tooling, multi-replica commit-race proof, and database
  connection-loss retry proof. Controlled Rust/JVM/outbox process death and
  actual PostgreSQL promotion with Rust-pool reconnection are also qualified;
  exhaustive setup/command recovery fixtures remain unresolved below.
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
  Post-upstream fresh-process qualification also removed JVM object identities
  from `GameContext` RNG seeds and added coordinate tie-breaks for identity-set
  region starts and lake traversal.
- [x] Build a dedicated self-contained `UncivAuthoritativeWorker.jar` and prove
  process-boundary parity through that packaged artifact. Independent fresh
  JVMs produce byte-identical snapshots and hashes for seeded creation with
  city-states, replayable player assignment, typed research selection, and a
  complete human-to-two-AI-to-human turn from that snapshot. Forged actor
  identity is rejected and changed server time produces a distinct result.
- [x] Add an explicit production new-game creation boundary. From the V3-only
  multiplayer screen, `NewGameScreen` now submits bounded setup
  directly to server creation before any local `GameStarter` call, retains one
  meaning-bound operation ID across exact retries and screen recreation, and
  never uploads or autosaves a client-created canonical game. Offline
  single-player and compatibility-only legacy API-v2 code remain isolated and
  tested, but legacy multiplayer is absent from production multiplayer UI.
- [x] Add account-backed API-v3 lobby browsing, game discovery, and projection
  reopening to the production multiplayer screen. The bounded directories page
  server lobby/membership metadata without consulting local saves, reject
  duplicate games or repeated cursors, and route started players through the
  authenticated command-bus projection and spectators through the public-only
  projection endpoint.
- [x] Keep Rust `main.rs` and `lib.rs` as thin façades and keep substantive Rust
  modules below the 800-line guardrail, with formatting and warnings-as-errors
  Clippy gates.
- [x] Remove API-v3 interception from canonical `WorldScreen` unit, combat,
  movement, action, turn-status, and alert paths. Production combat, direct
  unit actions, and persistent unit orders now exist only in focused
  projection-world panels/controllers; legacy-only movement/combat adapters
  and their obsolete routing tests are deleted.
- [x] Split API-v1/v2 whole-save behavior behind the explicit
  `onlineMultiplayer.legacy` boundary. The thin multiplayer façade owns only
  API-v3 session lifecycle plus the named legacy service, and a repository-wide
  routing test rejects any unclassified direct access.
- [x] Add fail-closed production API-v3 server detection and session
  restoration. Remote origins require HTTPS, credentials are scoped by
  normalized server origin, Windows uses live-tested user-bound DPAPI storage,
  and unavailable/failed detection cannot fall through to local game creation.

## P0: required before v3 can replace legacy online play

- [x] Add V3-aware background turn notifications on Android. A unique,
  self-chaining WorkManager job now restores only the Android-Keystore-protected
  V3 token, pages authenticated memberships, fetches player projections, and
  derives `isCurrentTurn` without local saves or hidden canonical state.
  Notifications are deduplicated by committed revision. API 23 instrumentation
  proves encrypted-token persistence and one durable poller after repeated
  restoration; focused JVM tests prove player-only projection polling.
- [x] Add desktop V3 turn notification behavior, or explicitly document and
  test an in-app-only product decision on every supported desktop OS. The
  supported product behavior is in-app OS attention: a false-to-true V3 turn
  transition after authenticated HTTP reconciliation invokes the existing
  cross-platform GLFW attention request (with the Windows JNA fallback).
  A closed desktop client does not install a tray daemon. Focused session tests
  prove exactly one attention request and no duplicate for repeated hints.
- [ ] Qualify account handoff and complete-match playability end to end. Run two
  independently authenticated human clients, including an Android-to-desktop
  handoff for the same account, against the packaged Rust/PostgreSQL/Kotlin
  stack; create and join a game with server AI factions, alternate devices,
  reconnect after suspension/restart, and play deterministically through an
  actual Domination terminal state. Existing command inventory, projection,
  AI, mod-parity, and load tests are necessary but do not by themselves prove a
  full human match or the cross-device production UI flow. The packaged-stack
  `account_handoff` preflight now proves lobby creation, independent faction
  choice, both-human readiness, owner start, identical Android-to-desktop
  projection restoration for one account, and server-AI advancement on the
  exact PostgreSQL 19 Beta 2 target. Projection v60 also
  publishes the canonical winner/type/turn, disables terminal controls, and
  rejects post-victory worker mutations. The remaining unchecked evidence is
  the two-person Android/desktop release run through an actual Domination
  result described in
  `docs/operations/authoritative-full-match-qualification.md`; an attempted
  all-AI resignation shortcut was rejected because it exceeded the bounded
  synchronous worker model and is not equivalent to a human match.
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
  victory identities; and gameplay toggles. V3 online matches are intentionally
  unlimited: neither the public setup nor production UI accepts skip-turn,
  total-time, or recovered-time limits, and the worker rejects timed force
  resignation. Rust bounds the request, Kotlin independently validates
  every named choice against the pinned ruleset, and only the control plane
  supplies the secret map seed. The authenticated session resolves the exact
  manifest and creates revision zero as a pregame lobby; projections and the
  command bus remain unavailable until the complete ready lobby is started.
- [x] Make game creation retry-safe with a caller-stable creation operation ID
  bound durably to the authenticated account and exact manifest/setup meaning.
  PostgreSQL serializes duplicate attempts with a transaction-scoped advisory
  lock and atomically records the operation with revision zero. A lost create
  response returns the original game without invoking the worker again;
  changed-account or changed-meaning reuse fails closed, and a failed attempt
  rolls back completely so the same operation can be retried.
- [x] Make v3 the only production path for creating new online games. Production
  detects/restores the configured v3 server and OS-protected session by default;
  authenticated creation returns to the authoritative lobby browser before
  local `GameStarter`, and failed or unavailable v3 setup fails closed. The
  production multiplayer screen no longer exposes API-v1/v2 discovery,
  credentials, player IDs, file-server probes, legacy creation controls, local
  online `GameInfo` construction, or whole-save upload.
- [x] Migrate the new-game UI to bounded server setup choices, server-owned
  seed/randomness, exact ruleset-manifest resolution, retry-stable creation,
  progress/error handling, and the returned revision-zero lobby. The production
  multiplayer create action reuses the complete single-player setup screen,
  adds a match name, human-slot count and optional password, strips legacy
  player IDs and public-spectator setup choices,
  rejects unsupported client nation pools, god mode, and advanced map values,
  and retains an operation ID only while setup meaning is unchanged. The
  owner chooses their faction during setup; the private worker cross-validates
  the resulting canonical faction pool before the screen returns to the lobby.
- [x] Migrate game discovery, join, civilization assignment, and open-game UI
  to the server-owned lobby directory, account membership, and server
  projections. Open lobbies list their name, password requirement, and occupied
  human slots. Each joining account supplies the optional password and chooses
  one unclaimed faction from the worker-validated canonical pool; PostgreSQL
  atomically enforces password, capacity, unique faction, stale revision, and
  idempotency rules. Each human controls only their readiness, only the owner
  can start, and exact capacity plus unanimous readiness is required. A fresh
  device reconstructs every lobby and active game without a local save.
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
- [x] Expose exact projected votes, selections, events, and diplomacy prompts.
  Diplomatic-victory voting includes explicit abstention and advertised
  candidates; great-person selection uses only projected units; mod-event
  choices use opaque prompt/choice IDs; ordinary diplomacy accepts/declines
  only friendship/demand prompts; protected-city-state prompts expose only
  their exact response enum. Wrong prompt kinds now fail in the command bus.
  The legacy great-person and diplomatic-vote pickers contain no API-v3
  projection or command path.
- [x] Expose authoritative spy assignment and coup controls in the
  projection-only world. Every owned spy displays only worker-projected city
  destinations, hideout availability, and coup stage/cancel capabilities.
  Invented destinations, unavailable state changes, and all out-of-turn spy
  commands fail before transport; `move_spies` now has production UI. The
  legacy canonical-`GameInfo` espionage overview contains no API-v3 session or
  command path.
- [x] Expose authoritative religion and belief selection in the projection-only
  world. The UI fills each projected belief-type slot with a distinct
  worker-advertised belief and, while founding, requires one projected icon and
  a bounded printable display name. Slot/type mismatches, invented beliefs or
  icons, invalid identity text, and identity outside founding fail before
  transport; canonical uniqueness and effects remain worker-owned. The legacy
  pantheon/religion picker hierarchy no longer accepts a world screen, builds
  authoritative payloads, or submits API-v3 commands.
- [x] Expose authoritative major-civilization and city-state diplomacy in the
  projection-only world. The UI renders only turn-scoped projected war,
  denouncement, friendship, demand, gold/protection/tribute/improvement gift,
  peace, and diplomatic-marriage capabilities. Exact counterparts, amounts,
  demands, coordinates, improvements, and state changes are rechecked before
  typed submission; out-of-turn projections retain counterparts but no actions.
  Legacy canonical-`GameInfo` diplomacy screens contain no API-v3 session or
  command path.
- [x] Expose authoritative bilateral trade in the projection-only world. The UI
  composes offers and counteroffers from bounded integer quantities of exact
  projected `(name,type,duration)` offers, displays incoming terms, and routes
  accept, decline, retract, and counter decisions by opaque request identity.
  Empty, excessive, duplicate, forged-duration, stale, and out-of-turn trades
  fail before transport; the worker independently revalidates canonical stock.
  Legacy trade tables and popups contain no API-v3 session or command path.
- [x] Build projection-only world rendering. The online world screen does not
  require a canonical `GameInfo`, and deleting or modifying every client cache
  has no gameplay effect. Its map, player/current-turn status, treasury, known
  civilizations, adopted/researched history, public wonder events, and every
  command-bearing top-level projection family now render from the immutable
  server projection. All inputs route through typed authenticated controllers;
  periodic/manual refresh replaces the full validated projection.
- [x] Remove API-v3 interception from every canonical city screen and its
  construction context menu. Production v3 city construction, economy,
  governance, tile, specialist, and citizen controls now exist only in the
  projection-world city panels; local city mutation remains isolated to
  offline, saved, hotseat, and legacy/API-v2 screens.
- [x] Remove every v3-reachable fallback to legacy whole-save upload, download,
  local turn advancement, local resignation, or direct canonical mutation.
  Preserve those paths only for single-player, hotseat, saves, and explicitly
  legacy/API-v2 games. Production creation, directory selection, world entry,
  end turn, self-resignation, force-resignation, and administration now have
  explicit projection/session routes with source-level regression tests. The
  legacy `WorldScreen` prompt/end-turn interceptor has also been removed, so
  local clone/advance/upload behavior is now explicitly legacy-only. The
  historical API-v3 research, policy, and diplomatic-vote branches have also
  been removed from their canonical-`GameInfo` picker screens; production v3
  uses only projection-world controllers for those decisions. Historical
  unit, combat, movement, action, turn-status, alert, great-person, religion,
  espionage, diplomacy, trade, and city picker/overview/screen interceptors
  have also been removed. Every remaining API-v1/v2 whole-save call site now
  opts into the explicit `onlineMultiplayer.legacy` service, while the API-v3
  projection world cannot reference canonical or legacy state operations.
- [x] Finish the source-level mutation audit across all world, city, unit,
  diplomacy, religion, espionage, alert, and multiplayer UI call sites. Every
  player-authored online mutation must either use a closed typed v3 operation or
  be explicitly classified as client-local presentation. Source-level routing
  coverage inventories the legacy canonical screens, forbids API-v3
  interception there, forbids canonical/legacy operations in the projection
  world, and requires all whole-save access to opt into `.legacy`.
- [x] Complete all end-turn prerequisites so a legal player cannot be
  permanently blocked by a choice that v3 cannot submit or render. Every
  `PendingEndTurnAction` family now has a typed projection-only production
  input path; the controller still refuses end turn until the refreshed server
  projection reports no pending actions.

## P0: partial gameplay and projection families

- [x] Persistent unit orders: inventory and add any remaining order controls not
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
  authoritative end turn. Projection v57 and the projection-only production
  world now expose exact posture choices, movement cancellation,
  exploration/automation state flips, disband/pillage/found capability,
  paradrop destinations, affordable upgrade targets, and rename input.
  Projection v58 adds exact long-route destinations, tile
  improvement/repair/cancellation choices, and road destinations/cancellation
  to that production UI. Whole-turn, military, civilian, and economy autoplay
  is deliberately not retained for API v3 because the legacy implementation
  executes AI from `WorldScreen` using client settings and UI globals.
  Source-level regression coverage forbids autoplay/`TurnManager`/
  `NextTurnAutomation` from the projection-only world and forbids an autoplay
  command in the public schema or Rust command union. Local, hotseat, and
  legacy games retain the convenience feature; authoritative AI civilizations
  and persistent automated unit orders execute only inside the private worker.
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
  projection-only world exposes only actions advertised by the immutable
  server projection and reconciles after the server commit. The legacy
  canonical-`GameInfo` tech picker contains no API-v3 session or command path.
- [x] Policies and ideology: ideology branches, tenets, and arbitrary
  mod-defined policy choices use the existing closed `AdoptPolicy` operation
  and shared canonical `PolicyManager` rules. Projection v50 additionally
  exposes each known major civilization's sorted public adopted branch names,
  including ideology, while Rust rejects duplicate, reordered, oversized, or
  malformed branch disclosure. The projection-only world submits only names
  from the actor's projected selectable set and never mutates policies
  locally; the legacy policy picker contains no API-v3 session or command path.
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
  The production screen gates kick, server-derived force-resignation,
  ownership transfer, and close to an active available owner membership, then
  exposes archive only for a closed available owner membership. Revisioned
  commands open/reuse the command bus, lifecycle operations retain
  exact-meaning IDs across ambiguous retries, and stale/rejected authority
  refreshes without falling through to legacy save mutation.
- [x] Decide whether a richer game administration/history projection is needed.
  It is not required for the playable v3 product: the production administration
  surface already exposes current lifecycle/ownership/membership state and all
  authorized mutations, while immutable security audit and outbox records are
  deliberately operator-only. Exposing historical operator records to clients
  would add privacy surface without enabling gameplay, synchronization, or
  recovery, so it is excluded unless a future product requirement defines a
  bounded player-visible history.
- [x] Allow an owner to revoke spectator access through a distinct audited
  operation. The caller-stable operation ID is durably bound to the owner,
  game, normalized target username, and revocation kind; exact retries succeed
  after membership removal, changed actor/meaning reuse fails closed, and the
  membership change emits a non-authoritative outbox resynchronization hint.
  Active owners can invite or revoke from the production administration popup;
  closed-game owners can revoke remaining access, while self-leave remains a
  separate spectator-only endpoint.

## P0: recovery and canonical-history guarantees

- [x] Complete recovery qualification across every supported setup dimension and
  controlled worker/process fault mode. The real packaged Kotlin worker now has
  fresh-process byte/hash parity coverage for seeded city-state placement and a
  replayable player-assignment mutation, typed research selection, a complete
  human-to-two-AI-to-human turn, and rich server-seeded creation using a
  fractal hex map, shuffled players, city states, abundant resources, strategic
  balance, raging barbarians, ruins, and natural wonders. The rich fixture is
  byte/hash identical across fresh packaged JVMs while a changed server seed
  diverges. Every supported named setup value is now materialized and compared
  across independent JVMs: ruleset difficulties, speeds, starting eras, visible
  victory types, map shapes/sizes, resource densities, barbarian
  modes, boolean toggles, and timer extrema. This is exhaustive by setup
  dimension rather than the impractical Cartesian product of equivalent values.
  The bounded replay and immutable recovery-publication path is PostgreSQL
  integration-tested.
  All 13 supported generated map types now have exact full-response/hash/
  snapshot parity across two fresh packaged JVMs, with decoded type and
  server-seed checks. Randomized combat and event branches have state-sensitive
  fresh-process parity coverage. AI qualification includes eight complete
  ordinary rounds and 24 late-era rounds through three server AI civilizations,
  two city states, and raging barbarians, with exact parity at every response.
  All 84 sealed worker operations are covered by the executable parity registry.
  Controlled worker death, response loss, Rust death, outbox death, connection
  loss, and PostgreSQL promotion/reconnection fixtures prove bounded retry and
  recovery behavior. Pre-migration rows without exact replay context fail
  closed and require operator-supplied recovery evidence.
- [x] Add revision/snapshot retention and compaction without breaking command
  idempotency, audits, recovery, or projection hashes.
- [x] Add a dry-run-first bounded recovery workflow. `unciv-v3-recover` reports
  only revision metadata and the canonical hash by default; `--apply` publishes
  only a still-current, verified quarantined head as a new immutable revision.
- [x] Add reviewed repair workflows for the remaining reconciliation findings.
  `unciv-v3-repair` is dry-run by default and game-lock serialized. It can
  reconstruct only deterministic derived commit-outbox hints from immutable
  revisions; every canonical-history, snapshot, replay-evidence, membership,
  ownership, or orphan finding is quarantined without deleting or guessing
  data. Applied actions are append-only audited and idempotent, and the operator
  runbook maps every closed reconciliation finding to recovery, restore, or
  reviewed bespoke migration.
- [x] Prove authenticated worker-boundary death and response-uncertain retry
  safety against PostgreSQL. A worker connection that disappears after a
  complete authenticated `EndTurn` request creates no revision, snapshot,
  command, or outbox row. The identical command then commits once through a
  healthy worker, and a later retry returns that durable result without
  contacting the now-unreachable worker.
- [x] Prove lost HTTP response recovery through the real Rust API process. The
  client discards its TCP connection after PostgreSQL exposes the committed
  journal row without reading the response, reconnects, and retries the exact
  command ID. The API returns the original acceptance with one worker execution
  and exactly one revision, snapshot, command, and outbox event.
- [x] Prove forced Rust API process death after worker execution but before
  commit is atomic. The test holds the canonical PostgreSQL row lock, observes
  the real API blocked there after authenticated worker execution, terminates
  the process, and verifies zero commit artifacts. A restarted API safely
  re-executes the same command ID and commits exactly one revision.
- [x] Prove forced packaged Kotlin worker termination is retry-safe. A private
  proxy forwards a complete authenticated `create_game` frame to the real
  packaged JVM, then the JVM is forcibly terminated before any response reaches
  Rust. No game or creation-operation artifact survives; restarting both sides
  and retrying the exact operation ID creates revision zero once, and a further
  duplicate returns that same game.
- [x] Complete controlled whole-process fault tests at the outbox
  acknowledgement boundary. A temporary test-only PostgreSQL trigger holds the
  real dispatcher after its durable claim; the API and its exact sleeping
  backend are terminated, leaving attempt one leased but undelivered. After
  lease expiry, a restarted API reclaims attempt two, clears the claim,
  acknowledges delivery, and leaves canonical history unchanged.
- [x] Test actual PostgreSQL/service failover and reconnection under load. A
  disposable exact-digest PostgreSQL 19 Beta 2 primary and physical streaming
  standby run with synchronous remote-apply durability. The primary is killed
  while four game journals are advancing through one stable endpoint; the
  standby is promoted and existing Rust SQLx pools encounter the dead-primary
  window, discard failed connections, reconnect, and finish all 64
  caller-stable commands. Each game retains one contiguous 0-16 revision
  chain, 16 unique commands/snapshots/outbox events, the expected canonical
  head, and zero reconciliation findings. The independent-service replica CAS
  race separately proves that two valid commands at one expected revision
  produce exactly one complete head and one stale conflict.

## P0: projection confidentiality and protocol hardening

- [x] Write a per-field player and spectator projection policy and keep schemas
  fail-closed when `GameInfo` grows. Every serialized leaf now has a
  machine-checked audience, classification, and rationale; descriptor/schema
  growth fails until reviewed. Projection v59 also structurally redacts exact
  remaining movement from visible foreign units.
- [x] Expand golden sentinel leak tests across every civilization/relationship,
  fog state, private order, city, resource, notification, diplomacy, religion,
  espionage, and spectator role. Real canonical games now carry unique secret
  sentinels through own, known, unknown, city-state, and barbarian state; all
  diplomatic statuses; never-explored, stale, and visible fog; and player plus
  public spectator serialization.
- [x] Add compact projection deltas with revision/hash validation, while keeping
  full authenticated HTTP projection as the recovery path. Authenticated
  clients request a bounded deterministic replacement delta from their exact
  revision, canonical-state hash, and player-projection hash. The server
  reprojects the immutable base snapshot for that player, refuses stale,
  oversized, non-beneficial, or unavailable deltas, and binds the result to the
  current canonical revision and both target hashes. The client applies only
  sorted, unique, existing JSON-pointer paths through the closed projection
  schema and verifies the resulting projection hash; any unavailable,
  malformed, stale, gapped, duplicate, or hash-mismatched delta falls back to
  the existing authenticated full HTTP projection. WebSockets remain hints,
  and `resync_required` always takes the full recovery path.
- [x] Prove canonical snapshots and hidden fields never occur in public HTTP,
  WebSocket frames, normal logs, traces, metrics, audit payloads, or errors.
  The generated public OpenAPI fails if canonical/worker-private properties
  appear; real-game player/spectator sentinel matrices cover permitted response
  values; revision WebSockets have one exact five-field DTO; worker rejection
  reasons are redacted from HTTP plus `Display`/`Debug`; runtime log
  interpolation is scanned for private values; adding an observability
  dependency requires an explicit disclosure-policy update; and PostgreSQL
  constrains security-audit labels to closed enums while forcing the JSON
  details payload to remain empty. Credentials and identities remain absent or
  one-way hashed.
- [x] Add property/fuzz tests for command envelopes, unknown fields/enums,
  length-prefixed worker frames, projection serialization, revision transitions,
  idempotency-key reuse with changed content, decompression, and save/ruleset
  deserialization limits.
- [x] Add request/response size, JSON depth/string/collection, timeout, and
  cancellation limits at every public and worker boundary.

## P1: worker isolation and deterministic execution

- [x] Add a player-consensus rewind to a completed-turn checkpoint. This is a
  product feature, not operator recovery: an active human player must propose
  one retained completed-turn snapshot, every then-active human player must
  approve that exact target against the same head revision, and the server must
  publish a new append-only canonical revision whose snapshot is the approved
  checkpoint. It must never delete or rewrite later commands/revisions, allow
  an owner-only override, restore an arbitrary per-action state, or accept a
  client save. The API, PostgreSQL consensus/audit records, client session/UI,
  WebSocket resynchronization, worker snapshot validation, stale/idempotent
  retry, refusal/expiry, cross-device, hidden-projection, and crash/retry tests
  must ship together. Until then, players cannot rewind a live V3 game; use the
  existing automatically persisted canonical history only for operator
  recovery, not informal match undo. Implemented as a whole-game
  **start-of-turn** restore: genesis and accepted `EndTurn` results are the only
  selectable checkpoints, so the copied snapshot includes every human, AI,
  map, turn, and RNG outcome at the start of that turn. Migration `0029`
  freezes the active human electorate and immutable votes; unanimous approval
  validates the retained snapshot through the private Kotlin worker and
  publishes a new append-only `rewind` revision. Rejection, changed vote retry,
  stale head/membership, worker failure/retry, cross-account current status,
  outbox resynchronization, projection-only production UI, retention, and exact
  copied-snapshot/head lineage are covered by deterministic Rust/Kotlin gates.

- [x] Measure and bound the private-worker process model. The initial low-memory
  deployment retains one persistent sequential JVM and one authenticated
  connection per command: 500 warmed fresh-connection handshakes measured
  3.05-ms p50/6.39-ms p95, while 50 real tiny-game creations measured
  20.59-ms p50/109.69-ms p95 on the documented Windows host. Rust now owns a
  shared one-operation execution permit plus a configurable bounded admission
  queue; overflow fails before opening a socket, queue waits expire, and queue
  time remains inside the absolute total deadline. Linux/large-save/AI/load
  qualification remains separately unchecked below.
- [x] Add mutually authenticated local IPC/service identity. Worker protocol v2
  HMAC-authenticates every request and response with direction separation, a
  fresh OS-random request nonce, exact frame length, and payload. The worker
  authenticates before JSON/rules execution, Rust authenticates before parsing
  or persistence, missing/malformed secrets fail startup, and captured or
  reflected frames fail closed.
- [x] Add separately configurable connect/read/write deadlines, worker crash
  recycling, circuit breakers, and per-command CPU/memory limits.
  Connect, request-write, response-read/execution, and total deadlines are now
  independently configured, validated at startup, and tested with phase-specific
  redacted failures; each operation already discards its one-shot socket.
  A shared configurable circuit breaker now fails fast after consecutive
  transport/protocol failures, permits one recovery probe after cooldown, and
  excludes normal authenticated rules-engine rejections. The packaged worker
  now applies a bounded socket read plus a hard per-command watchdog that
  terminates an unresponsive JVM. A least-privilege systemd unit restarts
  crashes/timeouts, periodically recycles the JVM, and applies JVM heap,
  metaspace, direct-memory, cgroup memory/no-swap, CPU, task, and descriptor
  limits to the sequential one-command process.
- [x] Qualify the packaged worker unit on the documented Linux production
  target. Exercise systemd restart/recycling, timeout exit 124, JVM OOM exit,
  cgroup CPU/memory/no-swap enforcement, task/descriptor ceilings, immutable
  assets, secret-file permissions, and recovery after each forced failure. A
  pinned Ubuntu 24.04 systemd rehearsal now runs the real packaged JAR with an
  authenticated protocol-v2 probe. It proves SIGKILL and scheduled recycling,
  the hard command watchdog's exit 124, JVM OOM exit, and authenticated recovery
  after every fault; inspects the live cgroup-v2 CPU, memory, no-swap, and task
  controls plus `RLIMIT_NOFILE`; and proves unrelated identities cannot read the
  service secret or mutate root-owned assets. The rehearsal exposed and fixed
  LibGDX native extraction failing on `noexec` `/tmp`: the unit now uses a
  private mode-0700 systemd runtime directory while `/tmp` remains non-executable.
- [x] Verify every pinned manifest inside the worker before parsing a snapshot.
  The worker now captures one immutable hash catalog immediately after parsing
  its root-owned rulesets, rejects engine/name/hash/component mismatches, and
  executes only against that captured identity. Startup rejects links,
  unsupported filesystem entries, more than 64 staged mods, more than 16,384
  ruleset entries, files over 16 MiB, or more than 512 MiB total.
- [x] Implement the immutable operator-only mod-acquisition pipeline with a
  closed exact-hash allowlist, HTTPS-only exact-host downloads, disabled
  redirects and environment proxies, optional environment-only bearer
  authentication, streaming limits, archive traversal/link/bomb defenses,
  same-filesystem atomic version staging, packaged-worker semantic validation,
  PostgreSQL registration, Linux atomic activation and rollback, and
  reference-safe garbage collection. New-game creation now requires a
  registered asset version and serializes against garbage collection. Clients
  have no URL-fetch, archive, path, hash, or content-upload surface. Live
  systemd/Linux failure qualification remains separately unchecked above.
- [x] Add deterministic fresh-process parity fixtures for every command family,
  game creation seed, random combat/event path, turn processing, and all AI.
  An executable registry now classifies all 84 sealed worker operations and
  fails when the protocol changes without an explicit parity classification.
  Fresh packaged-JVM evidence covers all 84 classified operations: handshake,
  game creation, player assignment, self/force resignation, owner kick,
  research selection, end turn with all AI, unit movement,
  durable movement-toward and cancellation, exploration/automation transitions,
  promotion and saved city defaults, batch upgrades, renaming, posture, disband,
  tile-improvement start/cancel, road connection orders, friendly unit swaps,
  city founding, player/spectator projection,
  ordinary and tile-targeted construction queueing, adjacent queue movement,
  exact queue removal, queue-context management, perpetual construction,
  ordinary and tile-targeted purchases,
  captured-city disposition and governance, specialist assignment/mode,
  tile locking, citizen
  reset, avoid-growth/focus policy, single/batch tile purchases, building sale,
  research-queue management, free-technology selection, policy adoption,
  research-completion acknowledgement, diplomatic voting, free great-person
  selection, pantheon belief selection, pillaging, paradrops, melee combat,
  city bombardment, air sweeps, nuclear strikes, the complete bilateral-trade
  lifecycle (offer, counter, decline, retract, and accept), major-civilization
  friendship/demand prompts and responses, denouncements, war declarations,
  spy movement and coups, religious-unit founding, Great Person research
  actions, instant Great Person improvements, unit transformations,
  generic unit-unique triggers,
  spaceship-part capital contributions, city-state unit gifts,
  gold gifts, protection pledges, tribute demands, city-state improvement
  gifts, negotiated peace, diplomatic marriage,
  protection-prompt responses,
  and event choice.
  Stateful unit and city scenarios compare every full response across two
  independent JVMs. Changed canonical combat state, forged actor, and
  changed-clock controls prove that state, authenticated identity, and server
  time remain replay-critical. The last three protocol gaps use a minimal,
  immutable test-only extension ruleset loaded through the same bounded asset
  validator and manifest hashing as packaged mods. The override is accepted
  only by explicitly unpackaged development workers and cannot alter a
  production packaged worker. All 13 server-generated map types now have exact
  fresh-process parity with decoded canonical type/seed checks. Every supported
  setup dimension/value is exercised, including timer extrema and both states
  of each toggle. Random combat and event branches have state-sensitive parity.
  Eight ordinary and 24 late-era human-to-three-AI-to-human rounds also match
  exactly, including AI city founding with city states and barbarians enabled.
- [x] Package and pin the exact Kotlin worker build together with the Rust
  server, supported client artifact, OpenAPI/client capability contract,
  approved ruleset manifest, complete ordered database migration set, and sole
  PostgreSQL 19 Beta 2 digest. The atomic bundle builder/verifier rejects
  missing, changed, extra, linked, special, oversized, or incompatible
  artifacts. Production Rust verifies its own and the worker's bundled paths,
  all hashes, compatibility constants, ruleset catalog, and the worker-reported
  bundle ID before accepting traffic; only explicit test/development mode can
  run unpackaged.

## P1: authentication, accounts, social features, and clients

- [x] Build account registration/login/session/account-management UI, including
  password change, logout-all/disable/delete behavior, clear recovery policy,
  and understandable rate-limit errors. The production multiplayer screen now
  exposes typed login, registration, recovery, password rotation, recovery-code
  replacement, current-device logout, all-device logout, disable, and delete
  flows. Password/recovery fields are cleared after every attempt; recovery
  codes are shown once with explicit storage/expiry/batch-invalidation guidance;
  stable API failures are mapped to understandable redacted messages. Source
  routing proves these screens cannot reach `GameInfo`, the worker, or whole-save
  upload paths. Focused lifecycle/UI-routing tests, the full JVM suite, Android
  debug APK build, Rust gates, and the 34-test PostgreSQL 19 Beta 2 lane pass.
- [x] Implement secure Android and desktop token stores. Windows uses
  current-user DPAPI with server-scoped atomic ciphertext files. macOS stores
  the token directly in the current user's Keychain through Security.framework.
  Linux uses the freedesktop Secret Service through `secret-tool`, passing the
  token only over stdin. Android API 23+ uses a non-exportable Keystore AES-GCM
  key; API 21-22 uses a Keystore RSA pair to encrypt a random AES-GCM key.
  Hosted runtime tests now prove save/reload/clear behavior on Windows, macOS
  15, Ubuntu 24.04, Android API 21, and Android API 23, and verify that
  plaintext tokens are not written to client persistence.
- [x] Add bounded concurrent-session policy, account recovery policy,
  credential-stuffing monitoring, credential rotation, and tests that secrets
  never enter errors, logs, traces, worker frames, or projections. Login now
  enforces a cross-replica LRU session bound; password-confirmed recovery-code
  batches are digest-only, expiring, and one-time; successful recovery replaces
  the password and every old session atomically. Durable rate limits and
  redacted audits cover registration, login, account changes, and recovery.
  Password/session/recovery fields are forbidden from worker and projection
  schemas and sensitive log interpolation. The exact PostgreSQL 19 Beta 2
  focused and 33-test ordinary persistence lanes pass.
- [x] Decide and implement v3 chat, friends, invitations, and lobby behavior as
  separate non-canonical services with membership, size, content, privacy, and
  rate limits. Do not put chat/social data into `GameInfo` revisions.
  Durable friendships, retry-safe friend requests, owner invitations, and
  membership-scoped per-game chat use separate PostgreSQL tables and
  authenticated bounded API-v3 routes. Chat is capped by UTF-8 bytes, page
  size, per-game retention, and durable sender/game rate limits. Desktop and
  Android share the same friends, invitation, and chat clients/popups. The v3
  lobby is now a first-class non-canonical PostgreSQL service: browser-visible
  match name, optional Argon2 password verifier, human capacity, worker-verified
  faction pool, per-account chosen faction, readiness revision, and owner start
  gate. Gameplay projections remain closed before start. Lobby metadata never
  enters `GameInfo`; the canonical join assignment is still a typed,
  revision-bound private-worker mutation.
- [x] Finish reconnect, offline/stale presentation, retry UX, projection upgrade,
  cache replacement, and explicit legacy/v3 game labeling on supported clients.
  Desktop and Android share one projection-only client state machine. Failed
  HTTP reconciliation retains the last projection as explicitly read-only,
  disables projected inputs, and offers a reconnect action that replaces the
  disposable cache from authenticated HTTP. An ambiguous mutation cannot be
  discarded by a generic refresh: the UI exposes one dedicated retry that
  reuses the exact pending command ID and payload. Projection versions are
  negotiated before authentication/game opening; unsupported versions fail
  closed and require a compatible client, while compatible reconnects replace
  rather than migrate trusted local state. API-v3 server games and legacy saved
  games are labeled separately. Focused 272-test authoritative coverage, the
  complete JVM suite, and Android debug assembly pass.

## P1: notifications and multi-instance operation

- [x] Add shared cross-instance notification fan-out. One durable outbox
  claimant publishes a bounded, versioned PostgreSQL notification only after
  the shared listener is active. Every Rust replica receives it, resolves
  current recipients from authoritative membership, and fans out only the
  non-authoritative revision hint to its local sockets. Publish-before-ack
  ordering preserves crash retry, duplicate delivery remains safe, and
  listener gaps, malformed shared frames, or recipient-query failures force
  local HTTP resynchronization rather than guessed replay.
- [x] Add connection and subscription limits, heartbeat/idle policy, slow-reader
  handling, bounded queues, reconnect backoff, and sustained duplicate/lost/
  reordered notification tests. Each Rust process has exact local permits, and
  PostgreSQL migration 25 adds atomically admitted, short-lived, replica-bound
  leases so global/per-account caps apply across the fleet and crashed replicas
  cannot leak capacity. Sockets renew fail-closed and release immediately on
  normal exit. Existing 4 KiB frame/message, 64 KiB write-buffer, ping/pong idle,
  hard-write-deadline, and bounded 64-hint queue policies remain. The API-v3
  client now uses capped exponential equal jitter and still treats every hint,
  lag, loss, duplicate, and reordering as a reason to reconcile through HTTP.
  A 10,000-event adversarial burst test proves queue bounds and preservation of
  the mandatory `resync_required` marker; PostgreSQL 19 Beta 2 tests race two
  independent repository pools and prove exactly one fleet-admission winner,
  replica-bound renewal/release, and expired crash-lease reclamation.
- [x] Add outbox retention, poison-event handling, lag alerts, and operational
  repair tooling without allowing notifications to become authoritative.
  Delivery retries are startup-validated and bounded before atomic
  dead-lettering; dead rows leave the claim index and trigger redacted runtime
  and CLI health alerts. `unciv-v3-outbox` provides JSON status plus
  dry-run-first audited requeue and bounded delivered-row compaction. Compaction
  atomically replaces full rows with immutable minimal receipts, and
  reconciliation/repair count receipts without recreating hints or touching
  canonical state.
- [x] Publish a full WebSocket/AsyncAPI lifecycle contract. The generated
  AsyncAPI 3.1 document is served at `/api/v3/asyncapi.json`, checked in as
  `authoritative-server/openapi/notifications-v3.json`, and included as a
  required release-bundle artifact. It closes the authenticated WSS handshake,
  exact revision/resynchronization schemas, receive operation, heartbeat,
  bounded queues/frames/buffers, lag, reconnect, duplicate/loss/reordering, and
  HTTP-only authority semantics. Runtime-shape/parity tests and the official
  AsyncAPI CLI validator reject drift or invalid documents.

## P1: production deployment and PostgreSQL 19 Beta 2 operations

- [x] Turn the current development compose setup into production packaging with
  separate Rust, private Kotlin worker, and exact digest-pinned PostgreSQL 19
  Beta 2 services, health/readiness gates, resource limits, and upgrade checks.
  The private worker now has a hardened least-privilege systemd unit, immutable
  artifact layout, secret-file permissions, automatic crash/periodic recycling,
  and enforceable JVM/cgroup limits. The release bundle now includes a dedicated
  migration executable and a mandatory validated SPDX 2.3 binary SBOM covered
  by its content-derived ID; the Rust API and migrator have separate hardened
  systemd units, credentials, users, resource ceilings, and active dependency
  readiness. PostgreSQL 19 Beta 2 now has an exact-digest production compose
  service owned by systemd, loopback-only TLS/SCRAM admission, dependency
  ordering, health gating, bounded CPU/memory/PIDs/shared memory/capabilities,
  and continuous WAL/base-backup integration. The public Caddy TLS proxy has
  its own hardened unit, active readiness gate, HSTS, and fail-closed client-IP
  boundary. Exact release tags now build a normalized self-verifying Linux
  bundle, bind its embedded SPDX through GitHub OIDC attestations, and retain
  the archive, external digest, manifest, and SBOM. The first real hosted tag
  `authoritative-v3-0.1.0-beta.2` built and attested the complete bundle at
  commit `f584c22d0`; its retained archive digest and both provenance/SPDX
  predicates independently verify. The later immutable
  `authoritative-v3-0.1.0-beta.2.4` tag additionally passes the full production
  bundle on Linux with the five least-privilege database roles, migrations,
  Rust API, private Kotlin worker, registration, worker-loss fail-closed
  readiness, and worker recovery. Production packaging is complete. The
  2026-07-29 Oracle ARM64 deployment additionally proved the packaged bundle
  with the digest-pinned PostgreSQL 19 Beta 2 image on a real aarch64 VPS,
  including migration 30, atomic vanilla-ruleset acquisition, worker/API
  readiness, external account registration/login/lobby listing/account cleanup,
  and an existing Caddy service remaining healthy. That deployment exposed and
  fixed three Compose-only defects: duplicate unparameterized role SQL
  execution, the migrator environment-variable name, and the missing activated
  ruleset mount/working directory. A focused contract test now prevents those
  boundaries from drifting. `unciv.rusticstack.com` now terminates an
  automatically renewed Let's Encrypt certificate in Caddy, applies HSTS and
  the hardened proxy headers, actively probes API readiness, redirects the
  former raw-IP HTTP endpoint to HTTPS, and passes the complete external
  disposable-account smoke through TLS without disrupting the existing site.
  later-prerelease upgrade/rollback rehearsal is the separate unchecked item
  below; no PostgreSQL 19 release newer than Beta 2 exists yet.
- [x] Configure production TLS/HSTS and explicit trusted-proxy handling. Caddy
  2.11.4 is exact digest-qualified for automatic HTTPS, HTTP redirects,
  one-year HSTS, header hardening, and `/readyz` admission. Rust accepts exactly
  one client address only from an explicitly configured loopback proxy,
  requires a loopback listener in that mode, rejects ambiguous proxy input, and
  ignores forwarding claims from every untrusted peer.
- [ ] Preserve per-origin client addresses in the single-VPS Docker topology
  without weakening the fail-closed trusted-proxy boundary. TLS and canonical
  authority are complete, but the host Caddy connection reaches the
  containerized API through Docker's bridge rather than as a loopback peer, so
  the deployed API intentionally uses `UNCIV_V3_TRUSTED_PROXY=disabled` and
  treats public clients as one proxy source for IP-based rate limits. Move the
  API to the hardened host-loopback systemd topology or add an equivalently
  isolated authenticated proxy transport, then prove spoof rejection and
  distinct-client rate-limit attribution before checking this item.
- [x] Create separate least-privilege PostgreSQL roles for runtime, migrations,
  backups, restores, and audit access; require encrypted database transport and
  credential rotation. Runtime and migration now have separate executables,
  Linux users/groups, protected credential directories, and documented SQL
  privilege boundaries; a live runtime-role smoke proves schema `CREATE` is
  denied while API readiness works. The five-role bootstrap now closes role
  attributes, public access, default privileges, and production HBA scope.
  A pinned PostgreSQL 19 Beta 2 live smoke proves TLS 1.3, non-TLS denial,
  runtime DML/DDL separation, migration DDL, audit read-only access,
  replication-only verified backup, restore production denial, SCRAM storage,
  clean audit-only reconciliation, and old-denied/new-accepted runtime
  credential rotation.
- [x] Implement automated backups and point-in-time recovery, then run and record
  destructive restore drills that validate head revision, snapshot hash,
  journal, membership, session, audit, and outbox invariants. PostgreSQL 19 Beta
  2 now continuously archives immutable WAL files and schedules streamed
  physical base backups with SHA-256 manifests and `pg_verifybackup` under a
  separate `unciv-backup` identity. The 2026-07-28 isolated named-point recovery
  promoted successfully, proved the before-target transaction present and the
  after-target transaction absent, validated every listed canonical invariant,
  and reconciled one game/two revisions/two snapshots with zero findings.
- [ ] Document and test PostgreSQL 19 prerelease upgrades/rollback. Production use
  of Beta 2 is an accepted product decision, but it still requires a rehearsed
  migration and restore gate for each later beta/RC/final image. Externally
  blocked: PostgreSQL 19 Beta 2 is still the newest published PostgreSQL 19
  image, so no successor image exists to rehearse. This is not a current
  Beta-2 deployment or playability gap; execute it before adopting the first
  later beta, RC, or final release.
- [x] Add schema forward/rollback policy, startup migration compatibility checks,
  database statement/lock timeouts, pool sizing, capacity alerts, and disk-full
  behavior. The API now refuses missing, extra, failed, or checksum-mismatched
  migrations without changing the schema; `unciv-v3-migrate` owns schema
  writes. Pool size/acquisition/idle/lifetime and PostgreSQL statement/lock
  timeouts are bounded and environment-configurable. The documented
  expand/backfill/contract policy has no unsafe down migrations: incompatible
  rollback restores a verified pre-migration PostgreSQL backup and matching
  release, while production startup continues to reject missing, extra, failed,
  or checksum-drifted migrations. A read-only five-minute capacity timer reports
  data/WAL/backup filesystem use, database bytes, pending outbox, and snapshots
  with warning/critical exits. The pinned 160 MiB PostgreSQL 19 Beta 2 disk-full
  drill reached 1 MiB free, reported critical, rejected a 12 MiB canonical
  commit with no phantom command/head, then committed the same command once
  after space recovery and reconciled with zero findings.
- [x] Add operator runbooks for worker failure, corrupt game quarantine/recovery,
  database failover, outbox backlog, credential compromise, abuse, and
  break-glass access. The unified incident entry point preserves redacted
  evidence, stops public writes when authority is uncertain, routes every
  mutation through dry-run-first checked-in repair/recovery/outbox procedures,
  fences the old PostgreSQL primary before one promotion, and requires
  reconciliation/readiness before reopening traffic. Break-glass is local,
  named, time-bounded, two-person reviewed, least-privilege, and audited; it
  cannot expose an operator endpoint, accept client saves, or hand-edit
  canonical history. Five build-time policy tests keep all seven incident
  classes, commands, links, redaction rules, and closure gates present.

## P1: observability, security, and release controls

- [x] Add redacted structured logs, metrics, traces, dashboards, and alerts for
  authentication abuse, stale conflicts, command latency/failures, worker
  crashes/timeouts, database locks, revision growth, projection size, outbox lag,
  and WebSocket load. The API now emits bounded JSON request spans and stable
  error events, exports low-cardinality Prometheus metrics only on a separately
  configured loopback listener, and centralizes worker/database/outbox/socket
  failure signals without private identifiers or canonical state. A packaged
  Grafana dashboard and 13 Prometheus alert rules cover every listed class,
  link to the operator runbook, and ship inside the verified release bundle.
  Runtime scrape, redaction/cardinality, immutable-workflow, current Prometheus
  parser, complete Rust, and pinned PostgreSQL 19 Beta 2 qualifications pass.
- [x] Separate operator/admin endpoints from the public network and authentication
  domain; add immutable security-audit export, retention/access policy, and
  incident-response ownership. API-v3 exposes no operator routes or standing
  application superuser; player-facing owner administration remains
  membership-authorized gameplay. Local `unciv-v3-export-security-audit` uses
  the separately authenticated, loopback/TLS, read-only `unciv_audit` role,
  fixes a high-water mark, reads bounded ID pages, and creates a new durable
  hash-chained NDJSON artifact with a final manifest. Migration 24 and role
  bootstrap restrict runtime audit access to append/select. The custody policy
  requires 400-day locked storage, least-privilege quarterly-reviewed access,
  daily/incident exports, gap/chain alerts, and named service-owner, incident-
  commander, and independent-review responsibilities. Static router/policy
  tests and the live pinned PostgreSQL role/export drill enforce the boundary.
- [x] Add cache-control and content-type hardening, safe error redaction, CORS/
  origin policy, TLS termination tests, dependency/vulnerability scanning, SBOM,
  secret scanning, and release provenance/signing where practical. The Rust
  API now rejects unapproved browser origins before handlers, accepts at most
  16 exact HTTPS origins from a fail-closed environment setting, keeps native
  no-Origin clients working, bounds preflight methods/headers, and applies
  no-store, nosniff, no-referrer, and restrictive permissions headers to every
  response. Existing stable API errors remain detail-redacted. TLS/HSTS
  termination tests now pass. A least-privilege API-v3 workflow now pins every
  action by full commit, reviews moderate-or-higher dependency changes, scans
  complete Git history for secrets, audits RustSec advisories daily, submits
  the resolved Gradle graph, emits SPDX source SBOMs, and uses GitHub OIDC
  attestations for exact `authoritative-v3-*` production bundle archives.
  Static policy tests prevent movable action tags or PR write/signing
  authority. Production
  bundles now require a bounded SPDX 2.3 binary SBOM, hash it into their closed
  manifest and bundle ID, and reject missing, changed, unrelated, malformed, or
  dangling evidence. Exact release tags now build and reverify every production
  input under pinned toolchains, create a deterministic self-verifying Linux
  archive, and bind its embedded SPDX to that archive through GitHub OIDC.
  Static policy and a full local Linux build/archive/extract/self-verify drill
  pass. The hosted `authoritative-v3-0.1.0-beta.2` tag run then built and
  reverified the production bundle, passed complete-history secret scanning and
  RustSec audit, issued both OIDC predicates, and retained the evidence.
  Independent download verification matched archive SHA-256
  `7a97b727b14c7f6dde3d24b577756b76c594e941f5040c018265c622923cb97f`;
  `gh attestation verify` bound both SLSA provenance and SPDX 2.3 to commit
  `f584c22d0` and the exact tag.
- [x] Add malicious-client integration suites for cross-game/account/civilization
  IDs, stale/reordered commands, changed-payload idempotency reuse, oversized and
  malformed frames, WebSocket exhaustion, and expensive rulesets/commands. A
  pinned PostgreSQL 19 Beta 2 attack matrix proves cross-game/cross-account,
  reordered stale, and changed-meaning command-ID reuse cannot move either
  canonical head or add journal/outbox rows. Command envelopes reject injected
  actor civilizations, and request identity is compared before the worker and
  again inside the locked transaction. Deterministic body/JSON and authenticated
  worker-frame limits, WebSocket admission/idle/slow-writer tests, bounded
  worker queue/deadline tests, and closed ruleset download/archive limits cover
  the remaining exhaustion and expensive-input boundaries.
- [x] Re-check AGPL/MPL boundaries and preserve notices; do not copy code from
  `runciv` or another reference without an explicit compatible licensing
  record. The current upstream `hopfenspace/runciv` license is AGPL-3.0;
  repository/history/dependency audits found no imported source or dependency.
  The pre-existing API-v2 documentation link remains reference-only, a checked
  provenance policy forbids copying/adaptation without reviewed permission,
  and every content-addressed production bundle now requires and hashes the
  repository MPL-2.0 `legal/LICENSE` alongside its SPDX dependency evidence.

## P1: legacy migration and retirement

- [x] Build a one-way, dry-run-first legacy importer that authenticates ownership,
  bounds and validates the save, pins an approved manifest, records provenance,
  handles two divergent candidates as a conflict, and creates revision zero once.
  The operator-only CLI defaults to dry run, bounds and normalizes every
  explicitly listed candidate in the private Kotlin engine, reports turn,
  current-player, and canonical-hash divergence without guessing, requires an
  explicit selected index and exhaustive legacy-ID-to-account mapping, and
  generates leak-scanned player/spectator projections. Apply revalidates active
  accounts and the installed content-addressed manifest under locks, then
  atomically creates one deterministic revision-zero game, memberships,
  immutable snapshot history, outbox state, and append-only source/conflict/
  projection provenance. Exact operation retries return the original result;
  changed meaning and a second import of the same legacy origin/game fail
  closed. Source files are read-only and unchanged.
- [x] Keep legacy and v3 origins/listeners, credentials, namespaces, storage,
  and sessions isolated. The operations contract forbids routing legacy
  endpoints from the v3 proxy or giving legacy any v3 database/worker secret.
  A live same-UUID attack against the packaged legacy server and a real
  PostgreSQL-backed v3 game proves legacy can read/write only its isolated file
  while the v3 head, snapshot hash, game/revision counts, and command journal
  remain unchanged. A static packaging guard rejects boundary drift.
- [x] Add deprecation telemetry and an operator switch that disables legacy
  writes without disabling v3. The content-free `/legacy-status` counters
  distinguish accepted/rejected file and authentication writes. Explicit
  `-no-legacy-writes`/`UncivServerLegacyWrites=false` retirement mode returns
  `410 Gone` for both legacy write paths while preserving reads and the
  separately deployed v3 service. Unit tests and a packaged live process prove
  the cutoff and counters; the operator runbook defines observation, owner
  notification, dry-run/import, pilot, fleet cutoff, recovery window, rollback,
  and final listener-removal gates.

## P2: performance, capacity, and final release evidence

- [x] Complete the ADR benchmark table with measured JVM startup, idle and peak
  memory, representative large-save load, command latency, end-turn latency,
  worker recycle cost, and deployment complexity. Windows process and
  representative Large-map measurements are joined by immutable Linux tag
  `authoritative-v3-0.1.0-beta.2.8`: under the 1-CPU/992-MiB envelope, summed
  service peak memory was 333.98 MiB, AI-turn p50/p95 was
  1,510.21/3,301.81 ms, and packaged-worker readiness recovered in 1,122 ms.
- [x] Run sustained low-resource load tests for HTTP, commands, PostgreSQL locks,
  worker concurrency, AI turns, projections, outbox delivery, and WebSockets.
  Publish throughput, latency percentiles, memory, CPU, storage growth, and a
  defensible capacity limit—never “unlimited users.” Hosted run `30393852174`
  completed 60 Large-game create/project/server-AI scenarios with eight-way
  contention in 152.586 seconds: 60 commits, 420 expected stale conflicts, 120
  WebSocket notifications, and 6,021,120 bytes of PostgreSQL growth. The
  recorded capacity floor is this exact workload under 1 CPU, 992 MiB, and no
  swap; it is not a simultaneous-user maximum or SLA.
- [x] Add Linux production smoke tests and supported desktop/Android client build
  and reconnect tests. Preserve and rerun single-player, hotseat, save-format,
  legacy multiplayer, and API-v2 regression suites throughout migration. The
  tagged Linux lane boots the verified bundle with production roles and the
  exact PostgreSQL 19 Beta 2 digest, proves health/readiness and registration,
  then proves worker death returns 503 and a packaged-worker restart restores
  readiness. Supported desktop/Android builds and authoritative offline-cache,
  retry, reconnect, and compatibility tests pass; broad JVM regressions retain
  the legacy and non-network game modes.
- [x] Run a final repository-wide mutation/authority audit and threat-model review.
  The release gate is zero v3 client path capable of replacing/patching canonical
  state and zero known untracked gameplay mutation family. The final source and
  executable audit inventories all 80 production session command methods,
  requires exact OpenAPI/Kotlin-client gameplay route parity, and fails if any
  typed gameplay method lacks a projection-only production UI path. It also
  proves V3 UI has no `GameInfo`, `GameStarter`, legacy whole-save, local
  `nextTurn`, or client-autoplay route; whole-save access must opt into
  `.legacy`. Fresh packaged-worker mod parity executes mod-defined generation
  and stateful actions twice, and broad server tests execute server-owned AI
  turns. The current closure assessment is recorded in
  `docs/security/authoritative-multiplayer-threat-model.md`.

## Current verification health

- No known compile, test, formatting, clippy, or database integration error is
  being deferred from the current milestone.
- Clean commit `a42aa6263` produced the installable debug-signed Android APK,
  standalone desktop JAR, and self-contained Windows desktop image. Android
  emulator installation/launch, both desktop smoke launches, the focused
  authoritative client suite, and the packaged PostgreSQL/Kotlin/Rust
  three-major account-handoff/server-AI preflight pass. This is candidate
  readiness evidence; the actual two-person Domination run remains unchecked.
- The public Kotlin server now resolves Ktor 3.5.0, Logback 1.5.34, and the
  complete Netty runtime family through an enforced 4.2.16.Final BOM. Android
  Gradle build tooling resolves the reviewed security floors Bouncy Castle
  1.84, Commons Compress 1.26.0, Commons Lang 3.18.0, JDOM 2.0.6.1,
  jose4j 0.9.6, Netty 4.1.136.Final, and Protobuf Java/Kotlin 3.25.5 instead
  of the vulnerable transitive versions reported for the repository. The
  floors cover AGP-created detached test-tool configurations as well as the
  main buildscript classpath without downgrading Netty 4.2 or future versions.
- The current focused milestone passes `:tests:test` and all 60 `:server:test`
  cases serially (with only documented intentional skips), plus
  `:android:compileDebugKotlin`. Earlier full Android assemble/lint and desktop
  distribution qualifications remain recorded in the status document.
- Rust passes 190 active library tests and 29 HTTP/OpenAPI/AsyncAPI/runtime
  tests; all 39 ordinary serialized PostgreSQL integration tests pass on the
  exact PostgreSQL 19 Beta
  2 digest. Controlled response-loss/Rust-death and packaged-worker/outbox-death
  lanes also pass.
- `cargo fmt --all -- --check`, warnings-as-errors
  `cargo clippy --all-targets --all-features -- -D warnings`, generated
  OpenAPI/AsyncAPI parity, official AsyncAPI validation, and `git diff --check`
  pass.
- `main.rs` is 5 substantive lines, `lib.rs` is a 60-line facade, and the
  largest Rust source is 770 lines. New work must split by concern before
  crossing the 800-line
  guardrail.

Update this file whenever a gap is completed, split, newly discovered, or
proven not applicable. Never delete an unresolved item merely because it moves
outside the current milestone.
