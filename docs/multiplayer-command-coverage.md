# Authoritative multiplayer command coverage

This is the completion ledger for API-v3 gameplay authority. It is based on
current source call sites, not intended behavior. A row is **Complete** only
when the public command is closed and versioned, the Kotlin worker calls shared
domain logic, Rust derives the actor from session and membership, legality and
authorization tests pass, the player projection contains the data needed to
issue and reconcile the command, and every online UI call site uses the v3
command bus. A server handler without migrated client call sites is **Partial**.

## Current authority boundary

Legacy online play remains client-authored. `WorldScreen.nextTurn()` clones and
advances `GameInfo`, then calls `Multiplayer.updateGame()`; `Multiplayer.kt`
also mutates resignation/turn state locally; and `MultiplayerServer.uploadGame()`
sends the resulting whole save. Therefore no gameplay family is globally
complete yet, even where the v3 vertical slice has a valid server handler.

API v3 currently exposes `JoinGame`, `MoveUnit`, `QueueConstruction`, and
`EndTurn`. The production `Multiplayer` owner can now install one
`AuthoritativeMultiplayerSession`, which negotiates capabilities, restores or
creates an authenticated session, opens per-game command buses from an HTTP
projection, reconciles WebSocket hints through HTTP, and pages through the
authenticated account's server-owned game memberships. For an explicitly
opened v3 game, `WorldScreen.nextTurn()` now submits through that lifecycle
before cloning or advancing local state and never uploads the result. No other
production world-screen action is routed yet. API-v3 game creation, discovery,
and projection reads are implemented outside the gameplay command union.

Status meanings:

- **Partial**: some authoritative protocol/worker/server/test support exists,
  but required commands, projection data, or client migration remains.
- **Missing**: no v3 gameplay command and no authoritative handler exist.
- **Not applicable**: local-only presentation behavior that must remain local.

## Coverage matrix

| Mutation family | Required typed command(s) | Shared handler / current mutator | Server authorization and validation required | Projection and reconciliation effects | Current online call sites / evidence | Status |
|---|---|---|---|---|---|---|
| Server game creation | `CreateGame` setup intent (HTTP resource creation, not a state-upload command) | `GameStarter.startNewGame` through `HeadlessGameEngine.createGame` | Authenticated owner; server-approved engine/ruleset manifest; bounded setup; server RNG/clock | Initial player projection, membership, revision `0` | `NewGameScreen.kt` still creates and uploads legacy games; v3 HTTP route exists | **Partial** |
| Join and civilization assignment | `JoinGame` | `HeadlessGameEngine.assignPlayer` | Authenticated non-member; joinable revision/status; server selects civilization; one account/civilization | Membership/civilization and new revision visible to affected players | v3 route/worker/PostgreSQL tests exist; legacy player setup remains separate | **Partial** |
| Unit movement | `MoveUnit(unitId, destination)` plus path/order variants if gameplay requires them | `UnitMovement.moveToTile` through `HeadlessGameEngine.moveUnit` | Current actor/turn; unit ownership; bounds; reachability; movement and terrain legality | Stable unit IDs, legal map knowledge, resulting positions/movement, visibility changes | v3 handler and deterministic tests exist; world-map/unit UI still mutates local `MapUnit` | **Partial** |
| Unit melee/ranged/city combat | `Attack(attackerId, targetId/coordinate, attackFrom?)`, `AirSweep`, `NuclearStrike`, `CityBombard` | `Battle`, `AirInterception`; direct actions in `worldscreen/bottombar/BattleTable.kt` | Actor/turn; attacker ownership; target visibility; range/path; attack count; war state; deterministic combat RNG | Damage, deaths/captures, movement, XP, war/visibility/public events | `BattleTable.kt` calls battle/air-sweep behavior locally; no v3 variant | **Missing** |
| Unit special actions | Closed variants for settle/found city, great-person actions, paradrop, airlift, disband, gift, transform, spread religion, inquisitor, and mod uniques | `worldscreen/unit/actions/UnitActions*.kt` and shared unit/domain actions | Actor/turn; unit ownership/type; movement/action use; target and resource legality; unique-specific validation | Unit removal/change, new city/religion/tile/civ stats and public events | `UnitActions.kt`, `UnitActionsGreatPerson.kt`, `UnitActionsReligion.kt`, `UnitActionsFromUniques.kt` mutate locally | **Missing** |
| Unit automation and orders | `SetUnitOrder`, `CancelUnitOrder`, or bounded server-side `AutomateUnit` | `UnitAutomation`; direct flags/actions in `UnitActions.kt` and `NextTurnAction.kt` | Actor/turn; ownership; allowlisted order; server executes all resulting moves/actions | Order state and any server-executed movement/actions | `UnitActions.kt` sets `automated`; `AutoPlayMenu.kt` and `NextTurnAction.kt` execute automation locally | **Missing** |
| Promotions and upgrades | `PromoteUnit`, `UpgradeUnit` (explicit target promotion/unit) | promotion logic; `unit.upgrade.performUpgrade` | Ownership; promotion availability; XP; movement/attack restrictions; gold/resources; target compatibility | Promotion set, stats, unit replacement, treasury/resources | `UnitPresenter.kt`, `UnitActionsUpgrade.kt`, `UnitUpgradeMenu.kt` invoke locally | **Missing** |
| Worker improvements and pillage/repair | `SetImprovementOrder`, `CancelImprovement`, `Pillage`, `Repair`, `BuildRoad` | worker/unit actions in `UnitActions.kt`, `UnitActionsPillage.kt`, automation logic | Ownership/turn; tile visibility/ownership; improvement technology; movement; war; resource cost | Unit order/movement and tile improvement/road/resource changes | Unit action files mutate canonical objects locally | **Missing** |
| City production queue | `QueueConstruction` implemented; `RemoveConstruction`, `MoveConstruction`, `SetPerpetualConstruction`, and bounded batch variants remain | `CityConstructions.addToQueue` through `HeadlessGameEngine.queueConstruction`; remove/reorder remain direct | Queue command derives civilization, checks current turn, city ownership, name bounds, canonical constructability and capacity; remaining variants need equivalent checks | Own city projection now includes queue and currently constructable building/unit names; progress, costs, tile-specific choices and public wonder events remain | Shared v3 transport/command bus can queue and reconcile; `CityScreenConstructionMenu.kt`, `CityConstructionsTable.kt`, `CityScreen.kt` still mutate local queues | **Partial** |
| City purchases | `PurchaseConstruction(cityId, constructionId, currency)` | city construction purchase logic via `BuyButtonFactory.kt` | City ownership; price/currency/resources; purchase availability; occupancy; server recomputes cost | Treasury/faith/resources, created unit/building, queue and city stats | `BuyButtonFactory.kt` performs local purchase/next-construction behavior | **Missing** |
| Citizen and specialist assignment | `SetWorkedTile`, `SetSpecialistCount`, `SetCityAutomation`, `LockCitizen` | city population/specialist logic in city-screen tables | City ownership; workable tile; population; specialist slots; resistance/puppet policy | Own worked tiles, specialists, yields, locks and automation flags | `CitizenManagementTable.kt`, `SpecialistAllocationTable.kt`, `CityScreenTileTable.kt` | **Missing** |
| City tile acquisition and tile-specific construction | `BuyCityTile`, `ChooseConstructionTile` | city expansion/construction logic in `CityScreen.kt` | City ownership; adjacency; price; visibility; construction-specific legal tile | Treasury, owned tiles, selected construction tile | `CityScreen.kt` chooses tile and calls `addToQueue` locally | **Missing** |
| Captured-city decisions | `AnnexCity`, `PuppetCity`, `RazeCity`, `StopRazing`, `LiberateCity`, `DestroyCity` | city conquest methods from `AlertPopup.kt` | Captor identity; pending decision; city/holy/capital constraints; liberation target; one-shot idempotency | Ownership, city status/removal, diplomacy, notifications/public events | `AlertPopup.kt` calls `annexCity`, `puppetCity`, and `liberateCity` locally | **Missing** |
| Research selection | `SetResearchPath` implemented for a destination technology; `RemoveQueuedTechnology`, queue reordering, and `ChooseFreeTechnology` remain | `TechManager.getRequiredTechsToDestination` through `HeadlessGameEngine.setResearchPath`; remaining variants still use local picker logic | Membership-derived actor/civilization and current turn; bounded technology in pinned ruleset; server derives the complete prerequisite queue; free-tech grants fail closed pending their dedicated command | Projection v4 includes current technology, ordered queue, legal destination targets, and legal free-tech choices; progress, costs, researched-tech history/events remain | `TechPickerScreen.kt` routes normal selection for an explicitly opened v3 game through the session/command bus and never mutates its local queue; local, hotseat, and legacy games retain their existing behavior. Queue append and free-tech UI remain local-only and unsupported for v3 | **Partial** |
| Policies and ideology | `AdoptPolicy`, `AdoptPolicyBranch`, `ChooseIdeology`, `ChooseTenet` | `Civilization.policies.adopt` from `PolicyPickerScreen.kt` | Actor/turn; culture/free-policy balance; prerequisites; branch/ideology constraints | Own policy state/culture and public ideology/events where legal | `PolicyPickerScreen.kt` directly calls `adopt` | **Missing** |
| Pantheon, religion, enhancement, beliefs | `FoundPantheon`, `FoundReligion`, `EnhanceReligion`, `ChooseBeliefs`, `RenameReligion` as closed variants | religion manager from picker screens and `UnitActionsReligion.kt` | Actor/turn; faith/free beliefs; prophet/unit; globally available beliefs/religion names; holy city | Own religion/beliefs/faith; legally visible city religion and public founding events | `PantheonPickerScreen.kt`, `ReligiousBeliefsPickerScreen.kt`, `UnitActionsReligion.kt` mutate locally | **Missing** |
| Diplomacy and war | `DeclareWar`, `OfferPeace`, `Denounce`, `PromiseResponse`, `DiplomaticResponse` | diplomacy managers from `DiplomacyScreen.kt` and `AlertPopup.kt` | Actor civilization; contact; treaties/cooldowns; target; current diplomatic state; pending prompt token | Legally known diplomatic state, treaties, public war events, private prompts | `DiplomacyScreen.kt` and multiple `AlertPopup.kt` branches call `declareWar` locally | **Missing** |
| Trades and agreements | `ProposeTrade`, `AcceptTrade`, `RejectTrade`, `WithdrawTrade` with stable offer IDs | `TradeLogic.acceptTrade` in `TradePopup.kt`/diplomacy UI | Both members; offer ownership/version/expiry; affordability; war/treaty rules; no client-supplied transfer outcome | Private offers, accepted public/private agreement effects, treasury/resources/cities | `TradePopup.kt` and `CityStateDiplomacyTable.kt` accept locally | **Missing** |
| City-state interactions | `GiftGold`, `PledgeProtection`, `DemandTribute`, `BullyCityState`, `DiplomaticMarriage`, quest-specific actions | city-state diplomacy/influence logic | Actor/turn; contact; treasury/unit power; cooldowns; quest/action-specific legality | Influence, treasury, protection/war status, private quests and public events | `CityStateDiplomacyTable.kt` and `AlertPopup.kt` mutate local diplomacy/city state | **Missing** |
| Espionage | `MoveSpy`, `SetSpyAction`, `RigElection`, `Coup`, `StealTechnology` only where player-selected | espionage manager; `EspionageOverviewScreen.kt` | Spy ownership; destination known; travel/cooldown; city/action legality; authoritative RNG for outcomes | Own spy identities/status, permitted foreign city info, private results | `EspionageOverviewScreen.kt` selects/moves spies against local state | **Missing** |
| Diplomatic/world-congress votes | `CastDiplomaticVote` and future congress proposal/vote variants | victory/diplomacy vote state from `DiplomaticVotePickerScreen.kt` | Eligible voter; active ballot; legal target; exact vote allowance; one ballot/revision | Own submitted ballot; unrevealed other votes absent; results public only when resolved | `DiplomaticVotePickerScreen.kt` writes local vote selection | **Missing** |
| Golden age and other civilization choices | Explicit commands for spending a great person, starting optional golden age, choosing rewards, ruins/options, and mod-defined choice prompts | alert/picker/domain-specific handlers | Pending server-issued choice token; actor; allowlisted option; one-shot resolution | Private pending choices and resulting public/private events | `AlertPopup.kt` and picker screens resolve canonical choices locally | **Missing** |
| End turn | `EndTurn` | `GameInfo.nextTurn(executionContext)` through `HeadlessGameEngine.endTurn` | Current actor from membership; current civilization; server rejects canonical pending construction, technology, policy, espionage, religion, or diplomatic-vote choices; server clock/RNG | New revision, turn metadata, AI/rotation effects, next-player notification, refreshed projection | An explicitly opened v3 game routes `WorldScreen.nextTurn()` through the command bus before any clone/local advance/upload; legacy games retain their negotiated path. Projection-only world rendering and commands for resolving every prerequisite choice remain incomplete | **Partial** |
| Resign and force-resign | `Resign`, privileged/time-gated `ForceResign` | `Multiplayer.resignPlayer`; `MultiplayerScreen.kt` | Self for resign; server inactivity threshold and role/policy for force-resign; target from membership; audit | Membership/player-type/turn changes and public event | `MultiplayerScreen.kt` downloads, mutates, advances, and uploads legacy state | **Missing** |
| Spectator join/visibility | `JoinAsSpectator`, `LeaveGame`; reads use a spectator projection, not a gameplay mutation | No v3 spectator projection policy/handler | Game policy/invite; spectator role; no civilization; explicit admin distinction | Fog/public-only or delayed/omniscient policy must be explicit and leak-tested | Schema permits spectator role, but v3 join assigns a player and projection requires civilization | **Missing** |
| Game administration | `KickMember`, `TransferOwnership`, `CloseGame`, `ArchiveGame` as separate privileged resource operations | No v3 admin handlers | Owner/admin role; cannot impersonate actor; auditable; safe turn/member consequences | Membership/game metadata and public events | Legacy delete/rename UI exists; authoritative admin lifecycle absent | **Missing** |
| Chat and social data | Separate non-game-state API operations, never canonical gameplay commands | Legacy/API-v2 chat, friend, invite and lobby concepts | Authenticated membership/channel rules; rate/size/content limits | Chat/lobby projection only; never canonical snapshot mutation | API-v2/legacy concepts exist; v3 integration absent | **Missing** |
| Camera, selection, overlays, music, local tutorials | None; remain client-local | UI state only | Must not affect canonical state or be uploaded as authority | None | world-screen presentation code | **Not applicable** |

## Cross-cutting requirements for every command

Every implemented row must use the common envelope with protocol version,
game ID, globally unique command ID, expected revision, and diagnostic-only
observed hash. Rust must derive account/civilization/role, enforce request and
entity-ID bounds, return stable authentication/authorization/stale/illegal/
incompatible/rate/internal errors, and commit command, revision, snapshot or
journal reference, actor/turn metadata, and outbox atomically. Duplicate IDs
must be bound to the original actor and exact payload meaning.

Worker handlers must verify the pinned engine and content hashes, load only the
canonical snapshot, call shared Unciv domain logic, use server clock and RNG,
and return structured errors without partial persistence. Client prediction is
optional and disposable; HTTP projection reconciliation is mandatory after
acceptance, rejection, stale conflict, reconnect, or notification.

## Projection gaps blocking command migration

The current projection supports own gold, cities, stable unit IDs/positions,
explored/visible coordinates, known civilizations, and visibility-filtered
foreign units. Projection v4 also exposes the ordered server-derived names of
pending turn actions, so a client can explain why `EndTurn` is blocked without
receiving canonical state, plus the player's current research, canonical queue,
legal destination targets, and legal free-technology choices. It is sufficient for the vertical-slice `MoveUnit`,
but not for most rows above. Missing projection contracts include terrain and legally known
resources/improvements; movement/action availability; city yields, production
progress, purchase costs and tile-specific choices; research progress/cost/history, policies, and religion; diplomacy, trades and
private prompts; espionage; votes; notifications/history; server-issued choice
tokens and the legal payload options needed to resolve projected blockers;
spectator views; and structured public events/deltas.

## Completion gate

This matrix is intentionally red. API v3 must not become the default for new
online games until all applicable gameplay rows are complete or an unsupported
mechanic is explicitly disabled server-side and in the online UI. The final
audit must search for direct canonical mutations reachable while
`isOnlineMultiplayer` and prove that each routes through the authoritative bus;
absence from this document is not evidence of completion.
