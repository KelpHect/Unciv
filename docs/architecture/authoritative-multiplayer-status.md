# Authoritative multiplayer v3 status

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
