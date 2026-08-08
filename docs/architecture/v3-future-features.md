# Authoritative multiplayer v3 — future features and optimization notes

This file captures user requirements, investigation findings, and implementation
plans for the next phase of authoritative multiplayer v3 work. It is a living
document: update it as features are implemented or requirements evolve.

Last updated: 2026-08-07

---

## 1. All-AI matches (zero human players)

### Requirement

The system must support matches with zero human players — all civilizations
controlled by AI, no human participation required. The owner creates the match
as a spectator/observer and watches it play out. This is essential for:

- Benchmarking full matches without human interaction
- Spectator-only viewing of AI-vs-AI games
- Automated testing and qualification of match-to-completion behavior

### Current blocker

`human_slots` has a CHECK constraint `BETWEEN 1 AND 16` in migration 0030.
The entire lobby/create/start pipeline assumes at least one human (the owner)
who claims a civilization, marks ready, and calls end_turn.

### Investigation findings (2026-08-07)

**Schema (1 new migration):**
- `0030_v3_match_lobbies.sql` line 25: change `human_slots BETWEEN 1 AND 16` to
  `BETWEEN 0 AND 16`
- `owner_account_id` stays NOT NULL — the creator account is still required for
  authorization, but would be a spectator instead of a player

**Rust API (4 files):**
- `api/games.rs`: allow `human_slots == 0`, allow empty `available_civilizations`
  when `human_slots == 0`, skip `owner_civilization_id` containment check
- `api/lobbies.rs`: allow `human_slots == 0` in reconfigure
- `api/game_setup.rs`: make `owner_civilization_id` optional or allow empty when
  `human_slots == 0`
- `postgres/game_creation.rs`: skip `owner_civilization_id` checks when
  `human_slots == 0`

**Rust Postgres (3 files):**
- `postgres/lobbies.rs` `insert_lobby`: skip readiness row insert when
  `human_slots == 0`
- `postgres.rs` `create_game_in_transaction`: insert owner as `'spectator'` with
  NULL `civilization_id` when `human_slots == 0`
- `postgres/lobby_reconfiguration.rs`: allow `human_slots == 0`, handle
  owner-as-spectator in participants list

**Kotlin Worker (2 files):**
- `WorkerGameSetup.kt`: allow empty `humans` list; skip owner-first check
- `EngineWorkerProtocol.kt`: skip `ownerCivilization` lookup when no human is
  assigned; return spectator/observer response

**Turn advancement (the key challenge):**
- `HeadlessGameEngine.endTurn()` requires an authenticated human actor — cannot
  be called with 0 humans
- `GameInfo.nextTurn()` while loop processes ALL AI players to completion in one
  call when there are no humans (the `player.isAI()` condition is always true,
  so the loop never stops until `victoryData != null`)
- Need a new `AdvanceAiTurn` worker operation that the owner/spectator triggers
- Two approaches:
  1. **Run to completion in one worker call** — simplest but could take minutes
     for large maps; the worker would block until the entire game finishes
  2. **Single-AI-turn advancement** — add a `maxAiTurns` parameter to
     `nextTurn()` (default `Int.MAX_VALUE` for backward compatibility), pass
     `maxAiTurns = 1` for all-AI matches so each `AdvanceAiTurn` call processes
     exactly one AI player's turn and returns
- Approach 2 is preferred: it gives per-turn benchmarking, spectator viewing,
  and bounded worker execution time

### Minimal implementation plan

1. New migration: `human_slots BETWEEN 0 AND 16`
2. API: allow `human_slots == 0`, make `owner_civilization_id` optional
3. Postgres: insert owner as spectator, skip readiness row
4. Worker setup: allow empty participants
5. Game start: owner calls `start_lobby` — passes immediately (0 ready == 0
   required)
6. Turn advancement: new `AdvanceAiTurn` API endpoint + worker operation +
   `HeadlessGameEngine.advanceAiTurn()` method using `maxAiTurns = 1`
7. Victory: when `victoryData != null`, game ends normally; spectator reads
   projection to see winner

---

## 2. Simultaneous human turns (Civ VI style)

### Requirement

Implement simultaneous (parallel) human turns like Civilization VI, where
multiple human players act on the same turn simultaneously instead of
sequentially. This should be a server-side toggle — AI turns remain sequential
within each round. When the toggle is off, the current sequential behavior is
preserved.

### Investigation findings (2026-08-07)

**Current model:** Strictly sequential. One `currentPlayer` (a single civID
string) is active at a time. `nextTurn()` advances through players one at a
time. All 37 command handlers in `HeadlessGameEngine.kt` guard on
`require(game.currentPlayer == actorCivilization.civID)`.

**Projection:** `is_current_turn` is a boolean (not a list).
`current_player_civilization_id` is a single string. All command-enabling
projection fields are gated on `canIssueTurnCommands`.

**Conflict resolution:** The existing CAS optimistic concurrency model naturally
serializes concurrent commands. The first command commits; the second gets a
stale revision, re-projects, and re-issues. "Simultaneous" in this architecture
is really "concurrent submission with sequential execution."

**Scope of changes:** 37+ files across the engine, projection, API, worker, and
validation layers. Key files:
- `GameInfo.kt`: replace single `currentPlayer` with a set of active players or
  add `playersWhoEndedTurn: Set<String>`
- `HeadlessGameEngine.kt`: change `endTurn()` from "advance the whole turn" to
  "mark this civ as done, advance only when all humans are done"; change all 37
  turn guards
- `PlayerProjection.kt`: `isCurrentTurn` becomes true for all active humans
- `projection.rs` / `projection_spectator.rs`: `current_player_civilization_id`
  becomes a list or supplemented with `active_player_civilization_ids`
- `projection_validation.rs`: update all `is_current_turn` validation rules
- `WorkerGameSetup`: add `simultaneous_human_turns: bool` toggle
- `GameParameters.kt`: add `simultaneousHumanTurns` field

**AGENTS.md constraints:**
- Must preserve unlimited human turn time (no skip-turn or timers)
- Must preserve consensual whole-game rewind (checkpoint semantics change from
  "one player ended" to "all players ended and the turn advanced")
- AI still runs in the private Kotlin worker under server-owned execution

**Conflict scenarios:**
- Combat: two players attack the same tile — CAS serializes, second gets stale
  revision
- Unit movement: two players move into the same empty tile — CAS serializes
- Trade/diplomacy: one accepts while other retracts — CAS serializes
- City founding: competing for tiles — CAS serializes

### Implementation approach

The toggle would be a `simultaneous_human_turns: bool` field in
`WorkerGameSetup` / `GameParameters`. When enabled:
1. All human players are "active" at the start of each turn
2. Each human's `end_turn` marks them as done (not advances the global turn)
3. The global turn advances (AI batch runs, next turn starts) only when ALL
   active humans have ended
4. The 37 `require(game.currentPlayer == ...)` guards become
   `require(actor is among active players for this turn)`

This is a large change (37+ files) but the CAS model provides natural conflict
resolution. Recommended as a later phase after all-AI match support.

---

## 3. Match replay viewing with graphs

### Requirement

Implement a public match replay/viewing system where anyone can watch any
completed (or live) match like a YouTube video:

- **Public access** — no invitation needed. Anyone can browse and replay any
  public match. The current "owner-invited spectators only" model is too
  restrictive for replay. Completed public matches should be viewable by all.
- **Video-like playback UI** — a play bar at the bottom like YouTube:
  - Play/pause with auto-advance at configurable speed
  - Scrub/drag to any point in the match
  - Step forward/back through individual actions (revisions), not just turns
  - Go back and replay from any point
- **Step-by-step granularity** — each revision in the database is a single
  command (one unit move, one attack, one construction pick, one end_turn).
  The replay should advance through individual revisions, not just turns. A
  real human match has dozens of revisions per turn; the replay should show
  each action as it happened.
- **No fog of war** — all civilizations' units, cities, territory, and stats
  visible to the replay viewer
- **Live graphs** — gold growth, army size, population, territory, technologies,
  culture, score, etc. over the course of the match, updating as you scrub
  through the replay
- **Full map view** — territory changes, unit positions, city growth visible
  at every step

### Why step-by-step (not turn-by-turn) works

Each revision stores a full compressed `GameInfo` snapshot. In the current
benchmark match, 298 revisions covered 296 turns because Rome only issued
end_turn. But in a real human match, each turn has many revisions (move unit,
attack, queue construction, end turn). The revision chain already provides
step-by-step granularity — the replay just needs to advance through revisions,
showing the full game state at each one. The existing snapshot storage is
sufficient; no new storage format is needed.

### Investigation findings (2026-08-07)

**What already exists:**
- Every revision stores a full compressed `GameInfo` snapshot in
  `game_snapshot_blobs` — the complete game state including all civilizations,
  their `statsHistory`, cities, units, tile map, diplomacy, etc.
- `CivRankingHistory` (a `HashMap<Int, Map<RankingType, Int>>`) is already
  serialized in every snapshot, recording per-turn stats for all 10 ranking
  types: Score, Population, Growth, Production, Gold, Territory, Force,
  Happiness, Technologies, Culture
- Single-player already has `VictoryScreenCharts.kt` (line charts from
  `statsHistory`), `VictoryScreenReplay.kt` (map replay with play/pause/slider),
  and `LineChart.kt` (chart rendering component)
- The rewind checkpoint system demonstrates loading historical snapshots and
  projecting them
- The `game_revisions` table has `revision`, `parent_revision`,
  `revision_kind` (`'genesis'`, `'command'`, `'recovery'`, `'rewind'`,
  `'lobby_reconfiguration'`), and `created_at` — sufficient for a replay
  timeline

**What's missing:**
- **Spectator projection is too minimal**: only has civ ID, name, human/defeated
  flags. Needs a new `ReplayProjection` with all civs' full data (gold, cities,
  units, territory, stats history) and no fog-of-war filtering
- **No replay API endpoints**: need `GET /games/{id}/revisions` (list all
  revisions with turn numbers and timestamps) and
  `GET /games/{id}/revisions/{rev}/replay` (full no-fog-of-war projection at
  that revision)
- **No replay worker operation**: need `ProjectReplayState { snapshot }` that
  builds a no-fog-of-war projection of ALL civilizations
- **No public replay access**: the current spectator projection requires
  `role='spectator'` membership. Replays need a new public access model where
  anyone can view completed (or live) public matches without being added as a
  spectator
- **No replay UI**: need a new client screen with a YouTube-style play bar,
  step controls, map rendering from the replay projection, and live-updating
  graphs. The existing `VictoryScreenReplay.kt` and `VictoryScreenCharts.kt`
  can be adapted but need to work from server projections instead of in-memory
  `GameInfo`
- **Snapshot retention**: migration 0017 allows `payload_retention_status=
  'compacted'` — old snapshot blobs could be deleted. A replay system needs to
  ensure snapshots are retained for completed/public matches

**Access control design:**
- The current model: owner invites spectators by username, spectator must be a
  `game_members` row with `role='spectator'`
- The replay model: public matches have a visibility setting (public/private).
  Anyone can fetch the replay projection for a public match without
  authentication (or with just an account, no membership). Live public matches
  can be spectated in real-time; completed matches can be replayed from
  revision history.
- This requires a new `game_visibility` field on the `games` table (or a
  separate `public_matches` directory), new API routes that don't require
  membership, and updated threat model documentation

**Key files for implementation:**
- `authoritative-server/migrations/00NN_replay_visibility.sql` (new): add
  `game_visibility` column (public/private), add public replay access
- `authoritative-server/src/worker/protocol.rs`: add `ProjectReplayState`
  operation
- `authoritative-server/src/projection_replay.rs` (new): `ReplayProjection`
  struct with all civs' stats, cities, units, territory, statsHistory, full map
- `authoritative-server/src/api/replay.rs` (new): public replay API endpoints
  (list revisions, get replay projection at revision, no membership required for
  public matches)
- `core/src/com/unciv/logic/multiplayer/authoritative/ReplayProjection.kt`
  (new): Kotlin builder for no-fog-of-war projection
- `core/src/com/unciv/ui/screens/multiplayerscreens/ReplayScreen.kt` (new):
  YouTube-style replay screen with play bar, step controls, map, and live graphs
- `docs/security/authoritative-multiplayer-threat-model.md`: update for public
  replay access (no fog of war on completed/public matches)

### Implementation approach

1. **Schema**: Add `game_visibility` column to `games` table (public/private,
   default private). Add index for listing public matches.
2. **Worker operation**: `ProjectReplayState { snapshot }` builds a full
   no-fog-of-war projection of ALL civilizations from any stored snapshot.
   Returns map terrain, all cities, all units, all territory, all civs' gold,
   stats history, diplomacy state, and wonder events.
3. **API endpoints**:
   - `GET /api/v3/public-matches` — list public completed/live matches
   - `GET /api/v3/games/{id}/revisions` — list all revisions with turn numbers,
     timestamps, and revision kind (no membership required for public matches)
   - `GET /api/v3/games/{id}/revisions/{rev}/replay` — full no-fog projection at
     that revision (no membership required for public matches)
4. **Access control**: Public matches allow unauthenticated (or
   account-only-no-membership) access to replay endpoints. Private matches
   still require spectator membership. Live public matches can be spectated
   in real-time via the same projection.
5. **Client UI**: New `ReplayScreen` with:
   - YouTube-style play bar at the bottom showing revision timeline
   - Play/pause button with speed control (0.5x, 1x, 2x, 5x)
   - Step forward/back buttons (one revision at a time)
   - Scrub/drag to any point in the match
   - Full map view showing all civs' units, cities, territory (no fog of war)
   - Stats graphs (gold, army size, population, territory, etc.) that update
     as you scrub through the replay
   - Civ selector to highlight/focus on a specific civilization
6. **Snapshot retention**: Ensure completed public match snapshots are not
   compacted/deleted. Add a `retention_hold` flag or change compaction policy
   for public matches.
7. **Performance**: Loading a snapshot and projecting it takes ~10-100ms
   (based on benchmark data). For smooth playback, consider caching the
   replay projection for the current revision and pre-fetching adjacent
   revisions. Alternatively, consider projecting only the delta between
   adjacent revisions for faster step-through.

---

## 4. Victory detection and end-of-match

### Requirement

Ensure proper end-of-match detection for all victory types, especially
Domination, in all-AI matches. The benchmark match should run to completion
with no turn limit.

### Investigation findings (2026-08-07)

**Victory types (vanilla):**
- **Domination**: capture all capitals (NOT last civ standing). A living civ
  that lost its capital still counts — the attacker must capture it from
  whoever holds it
- **Scientific**: build Apollo Program + all spaceship parts in capital
- **Cultural**: complete 5 policy branches + build Utopia Project
- **Diplomatic**: anyone builds United Nations + win diplomatic vote
- **Time**: highest score after `maxTurns` (default 500)

**Victory detection flow:**
- `GameInfo.checkForVictory()` iterates all civs, calls
  `TurnManager(civ).updateWinningCiv()`
- `TurnManager.endTurn()` calls `updateWinningCiv()` after each AI player's turn
- The `nextTurn()` while loop exits when `victoryData != null`
- Victory is correctly checked during AI turn processing inside `endTurn()`

**Turn limit behavior:**
- `GameParameters.maxTurns` defaults to 500
- If Time victory is enabled, the civ with highest score wins at `maxTurns`
- If Time victory is NOT enabled (as in our Domination-only match), the game
  does NOT end at `maxTurns` — there is no automatic draw or score mechanism
- This is why our 7-civ Huge match hit 500 turns without ending: we set
  `victory_types: ["Domination"]` only, with no Time victory, so no one won by
  score at turn 500, and no one achieved Domination (capturing all 7 capitals on
  a Huge map is very hard for Chieftain-difficulty AI within 500 turns)

**Elimination handling:**
- `Civilization.isDefeated()`: civ with capital history is defeated when it
  loses all cities; civ without capital history is defeated when it has no units
- Defeated AI civs are still in the `civilizations` list and get their turn
  processed as no-ops
- Turn rotation correctly skips through defeated AI civs
- **Defeated HUMAN civs in online multiplayer are NOT auto-processed** —
  `shouldAutoProcessOnlinePlayer()` only auto-processes spectators and defeated
  players in hotseat, not defeated human players in online games. This means a
  defeated human player continues to receive turns and must call `end_turn` to
  advance. This is arguably correct (a defeated human should still be able to
  watch the game), but it means a benchmark script driving a passive human will
  keep working even after the human's civ is eliminated.

**Issues found:**
1. **No-victory stalemate**: If no victory type is enabled that can trigger
   (e.g., Domination-only on a large map where AI can't capture all capitals),
   the game runs forever with no natural stopping point. The Time victory
   would be the ideal fallback, but it is hidden and rejected by the worker
   (see issue 4 below).
2. **Benchmark match fix**: The match script should either:
   - Enable all non-hidden victory types (Domination, Scientific, Cultural,
     Diplomatic) so the AI has multiple paths to win, OR
   - Set `max_turns` to 1500 (the API maximum) and hope a victory triggers
3. **All-AI match turn advancement**: In an all-AI match, `nextTurn()` runs all
   AI to completion in one call. To get per-turn benchmarking and spectator
   viewing, the `AdvanceAiTurn` operation (from the all-AI match feature) is
   needed.
4. **Time victory is hidden and rejected by the worker**: The vanilla `Time`
   victory type has `"hiddenInVictoryScreen": true` in `VictoryTypes.json`. The
   worker validates `ruleset.victories[it]?.hiddenInVictoryScreen == false` and
   rejects hidden victory types. This means Time victory cannot be used in
   match setup. All four non-hidden victory types (Domination, Scientific,
   Cultural, Diplomatic) should be enabled to give the AI multiple paths to
   victory. The `max_turns` API constraint is 100-1500.

### What needs to happen

1. **Fix the benchmark match script**: Enable all non-hidden victory types
   (Domination, Scientific, Cultural, Diplomatic) and set `max_turns` to 1500
2. **Remove the 500-turn limit**: User wants no turn limit on the benchmark —
   set `max_turns` to a very high value or add Time victory as a fallback
3. **Verify Domination victory works**: Run a smaller map (e.g., small or
   standard) where AI can realistically capture all capitals within a
   reasonable turn count
4. **Track alive civs properly**: The current benchmark script counts
   `knownCivilizations` which is from the human's perspective (fog-of-war
   limited). Need to use the spectator projection or a dedicated alive-civs
   field to track actual eliminations

---

## 5. Alive civs tracking and elimination detection

### Requirement

The benchmark match output must properly track how many civilizations are
actually alive (not defeated) at each turn, and detect when eliminations occur.
The current implementation counts `knownCivilizations` from the player
projection, which is fog-of-war limited and doesn't reflect actual eliminations.

### Current issue — diagnosed 2026-08-07

The match script uses `$proj.projection.knownCivilizations.Count` for the
"Alive Civs" column. This is the count of civilizations the human player (Rome)
has **discovered** (met through exploration), not the total number of alive
civilizations. Rome may not have met all 6 AI civs, so this number starts low
and changes as Rome discovers more civs — it is not an elimination indicator at
all. It is a **fog-of-war-limited discovery counter**.

#### Evidence from the 500-turn Huge match

After the match completed, the player projection for Rome at turn 501 revealed:

- **Rome was eliminated**: 0 cities, 0 units, 0 gold, only 50 explored tiles
  (the starting area). Rome's capital was captured at some point during the
  500 turns. The engine kept giving turns to the defeated human player because
  `shouldAutoProcessOnlinePlayer()` does not auto-process defeated *human* players
  in online multiplayer — it only auto-processes AI, spectators, and defeated
  hotseat players. So the match script kept calling `end_turn` for a dead Rome
  and the game never ended.
- **Rome only met 2 of 6 AI civs** (Arabia and Greece) before being eliminated.
  The "Alive Civs" column showed 4 then 3 because Rome discovered Arabia and
  Greece through contact, not because eliminations occurred. Egypt, Persia,
  Aztecs, and Mongolia were never encountered by Rome.
- **The AI was extremely active**: From the trade offer data visible in Rome's
  projection at turn 501:
  - **Arabia**: 127,807 gold, 2,336 gold/turn, ~49 cities, 158 coal, 84 iron,
    20 horses, 9 oil, 3 uranium. Adopted 6 policy branches (Autocracy, Commerce,
    Honor, Liberty, Patronage, Piety).
  - **Greece**: 2,864 gold, 2,199 gold/turn, ~56 cities, 89 coal, 72 iron,
    12 horses. Adopted 6 policy branches (Autocracy, Commerce, Honor, Liberty,
    Rationalism, Tradition).
  - **Arabia and Greece are at war** with each other (both offer WarDeclaration
    as a trade item).
  - **29 world wonders were built** across the match: Egypt built 13 (Temple of
    Artemis turn 57, Great Library turn 67, Chichen Itza turn 94, Hagia Sophia
    turn 108, Stonehenge turn 114, Pyramids turn 117, Himeji Castle turn 125,
    Notre Dame turn 140, Forbidden Palace turn 147, Sistine Chapel turn 159,
    Porcelain Tower turn 167, The Louvre turn 198, Cristo Redentor turn 245),
    Greece built 4 (Colossus turn 109, Oracle turn 122, Eiffel Tower turn 242,
    Sydney Opera House turn 252), Persia built 2 (Mausoleum turn 130, Statue of
    Zeus turn 147), Aztecs built 2 (Angkor Wat turn 123, Great Lighthouse turn
    134), Arabia built 2 (Big Ben turn 172, Kremlin turn 183).
  - **Egypt, Persia, and Aztecs all sent friendship diplomacy prompts** to Rome,
    proving they were alive and active in diplomacy.
- **No Domination victory was achieved** because no single civ captured all 7
  capitals. With 7 civs on a Huge Pangaea map at Chieftain difficulty, the AI
  expands and fights but capturing all 7 capitals within 500 turns is
  extremely unlikely. The match needed Time victory as a fallback.
- **We have zero visibility into 4 of the 7 civs** (Egypt, Persia, Aztecs,
  Mongolia) from Rome's player projection. We only know they existed from wonder
  events and diplomacy prompts. We don't know if they were eliminated or still
  alive at turn 501. Only the spectator projection or direct snapshot inspection
  could tell us.

#### Root causes of the benchmark failure

1. **Fog-of-war**: The player projection only shows civs the player has met.
   Using `knownCivilizations.Count` as "Alive Civs" was fundamentally wrong.
2. **Defeated human players still get turns**: The engine does not auto-process
   defeated human players in online multiplayer, so the match script kept
   calling `end_turn` for a dead Rome. This is arguably correct game behavior
   (a defeated human should still be able to watch), but it means our benchmark
   loop never detected Rome's elimination.
3. **No spectator projection in the benchmark**: The spectator projection has
   `major_civilizations` with `defeated` flags for all civs, which is the
   correct data source for alive-civs tracking. But the benchmark script never
   called it.
4. **No Time victory fallback**: With only Domination enabled and no Time
   victory, the game had no way to end if no one captured all capitals.

### What's needed

1. **Use the spectator projection** for alive-civs tracking. The spectator
   projection has `major_civilizations` with a `defeated` boolean for each civ.
   Counting `!defeated` gives the true alive count. The benchmark script should
   call `GET /api/v3/games/{game_id}/spectator-projection` each turn (or at
   least periodically) to get the real alive-civs count.
2. **Add elimination events to the benchmark output**: when a civ transitions
   from alive to defeated between turns, log it with the turn number and the
   civ name. Track all 7 civs' defeated status from turn 1.
3. **For all-AI matches**: the spectator projection is the only source of
   alive-civs data since there's no human player projection.
4. **Detect when the human player is eliminated**: the benchmark script should
   detect when Rome is defeated and either continue via spectator projection or
   report the elimination as an event.
5. **Include Time victory as a fallback**: ensure the match can end by score at
   `max_turns` even if no Domination victory is achieved.
6. **Direct database inspection**: for replay and debugging, we should be able
   to decompress and inspect any stored snapshot to see the full game state of
   all civs. This is also needed for the replay viewing feature.
7. **Spectator projection access control**: the spectator projection endpoint
   requires `role='spectator'` in `game_members`. The owner (role='owner')
   cannot access it. The benchmark script now creates a second account and
   adds it as a spectator to access the spectator projection. For all-AI
   matches where the owner is a spectator by design, this won't be an issue.
   But for regular matches, consider whether the owner should also be able to
   see the spectator projection (currently they cannot).

### Implementation (completed in match script)

- The benchmark script now creates a second account and adds it as a spectator
  to the game so it can access `GET /api/v3/games/{game_id}/spectator-projection`
- Each turn, the script fetches the spectator projection to track all civs'
  `defeated` status and count alive civs
- Elimination events are logged in real time when a civ transitions from alive
  to defeated
- Time victory is included in `victory_types` so the match ends by score at
  `max_turns` if no Domination occurs
- `max_turns` default raised from 500 to 5000 (effectively no turn limit)
- Victory is checked from both the player projection and the spectator
  projection (spectator works even if the human is eliminated)

---

## 6. Benchmark match results

### 2-player tiny Pangaea match (1 human + 1 AI)
- **Result**: Egypt won by Domination on turn 263
- **Map**: Tiny Pangaea, Quick speed, Domination only
- **Per-turn**: p50 75ms, p95 (not recorded)
- **History**: 264 revisions, 263 commands, 20 rewind checkpoints, 2.4MB
  compressed
- **Post-victory**: commands correctly rejected with 422

### 4-player small map match (1 human + 3 AI)
- **Result**: Played 30 turns (truncated)
- **Map**: Small Pangaea, Quick speed, Domination only
- **Per-turn**: p50 55ms, p95 93ms

### 7-civ Huge map match v1 (1 passive human + 6 AI) — BROKEN BENCHMARK
- **Result**: No victory — hit 500-turn limit
- **Map**: Huge Pangaea, Quick speed, Domination only, no Time victory
- **Per-turn**: min 109ms, p50 431ms, p95 3754ms, p99 4959ms, max 5698ms,
  mean 985ms
- **Early game (1-50)**: mean 141ms
- **Mid game (50-150)**: mean 196ms
- **Late game (150+)**: mean 1330ms
- **Total AI processing**: 493.5s
- **History**: 502 revisions, 20 rewind checkpoints
- **Issue**: No Time victory enabled, so game didn't end at turn 500. AI at
  Chieftain difficulty couldn't capture all 7 capitals on a Huge map within 500
  turns.
- **Benchmark was broken**: "Alive Civs" counted `knownCivilizations` (fog-of-war
  limited discovery counter), not actual alive civs. Rome was eliminated during
  the match but the benchmark never detected it. The AI was extremely active
  (Arabia had 127K gold and 49 cities, Greece had 56 cities, 29 wonders built)
  but the benchmark output showed none of this.

### 7-civ Huge map match v2 (1 passive human + 6 AI) — FIXED BENCHMARK
- **Result**: Egypt won by Scientific victory on turn 296
- **Map**: Huge Pangaea, Quick speed, all non-hidden victory types
  (Domination, Scientific, Cultural, Diplomatic)
- **Per-turn**: min 83ms, p50 242ms, p95 696ms, p99 797ms, max 822ms,
  mean 308ms
- **Early game (1-50)**: mean 104ms
- **Mid game (50-150)**: mean 179ms
- **Late game (150+)**: mean 465ms
- **Total AI processing**: 91.5s
- **Match duration**: 149.3s (2.5 min)
- **History**: 298 revisions, 20 rewind checkpoints
- **Eliminations**: Mongolia eliminated on turn 262
- **Final status**: Egypt (winner), Arabia, Aztecs, Greece, Persia, Rome — ALIVE;
  Mongolia — DEFEATED
- **Spectator projection**: Used a second account as spectator to track all 7
  civs' defeated status via the spectator projection (not fog-of-war limited)
- **Key fix**: All non-hidden victory types enabled, so Egypt achieved Scientific
  victory (spaceship) instead of needing Domination (all capitals). The Time
  victory type is hidden in the vanilla ruleset and rejected by the worker, so
  it cannot be used as a fallback.

### Optimization observations

1. **Late-game slowdown**: turns go from ~140ms (early) to ~1330ms (late) — a
   10x slowdown. Likely causes: more units, more cities, longer AI decision
   trees, larger snapshot serialization/deserialization.
2. **p95 spikes**: p95 is 3754ms (8.7x the p50), suggesting some turns trigger
   expensive operations (combat resolution, city conquest, diplomacy updates).
3. **Snapshot growth**: 2.4MB compressed for 263 turns (tiny map). Huge map
   500 turns would be significantly larger. Snapshot compression and storage
   are not currently a bottleneck but could become one for very long matches.
4. **Worker execution**: all AI turns run synchronously in one `end_turn` call.
   For 6 AI on a Huge map, this means each `end_turn` call processes 6 full AI
   turns plus the human's turn end. This is the primary latency contributor.

### Benchmark improvements needed

- ~~Remove the 500-turn limit~~ — DONE: default is now 1500 (API max)
- ~~Include Time victory or set very high max_turns~~ — DONE: all non-hidden
  victory types enabled (Time is hidden and rejected by worker)
- ~~Track alive civs via spectator projection~~ — DONE: spectator account added
  to each match, alive-civs tracked from `major_civilizations.defeated`
- ~~Log elimination events~~ — DONE: eliminations detected and logged in
  real time
- Record per-turn snapshot size for storage growth analysis
- Add memory/heap tracking for the worker process
- Benchmark on Linux production target (current benchmarks are Windows dev host)

---

## 7. Priority order for implementation

1. **All-AI match support** (Section 1) — enables automated full-match
   benchmarking without human interaction
2. **Alive civs tracking** (Section 5) — needed for meaningful match output
3. **Fix benchmark match script** (Section 4) — DONE: all non-hidden victory
   types enabled, spectator projection for alive-civs tracking, elimination
   logging, max_turns=1500. Verified with Egypt Scientific victory on turn 296.
4. **Match replay viewing** (Section 3) — public, YouTube-style replay with
   step-by-step granularity, no fog of war, and live graphs. This is the
   highest-impact user-facing feature: it makes every public match watchable
   by anyone, like an e-sports replay.
5. **Simultaneous human turns** (Section 2) — largest scope (37+ files),
   recommended as a later phase

---

## 8. Notes on current architecture constraints

- **PostgreSQL 19 Beta 2** is the sole production and test database target
- **Worker is sequential**: one operation at a time, so terrain reads serialize
  against gameplay commands and AI turns
- **CAS optimistic concurrency**: commands are serialized; stale revisions force
  re-projection and re-issue. This is the natural conflict resolution for
  simultaneous turns.
- **AGENTS.md rewind invariant**: checkpoints are at `EndTurn` start-of-turn.
  Simultaneous turns would change checkpoint semantics.
- **Snapshot retention**: migration 0017 allows compaction. Replay system must
  ensure completed-game snapshots are retained.
- **No client RNG or local rules fallback**: all gameplay mutations happen in
  the private Kotlin worker under server-owned execution context.
- **Player projection is fog-of-war limited**: a human player only sees civs
  they have discovered, units in their viewable tiles, and resources on
  explored tiles. This is correct for gameplay but useless for benchmarking
  and debugging. The spectator projection is the correct data source for
  observing the full match state.
- **Spectator projection requires role='spectator'**: the owner (role='owner')
  cannot access the spectator projection. To observe a match as a spectator,
  a separate account must be added with role='spectator'. For all-AI matches
  (Section 1), the owner would be a spectator by design, solving this.
- **Defeated human players still receive turns**: the engine does not
  auto-process defeated human players in online multiplayer. A defeated human
  must still call `end_turn` to advance the game. This is correct for player
  experience but means a benchmark script driving a passive human will keep
  working even after the human's civ is eliminated.
- **Full snapshots are stored in PostgreSQL**: every revision has a full
  compressed `GameInfo` snapshot in `game_snapshot_blobs`. These can be
  decompressed and inspected directly for debugging and replay. The snapshot
  contains ALL civilizations' data (cities, units, stats, diplomacy) with no
  fog-of-war filtering — this is the canonical source of truth for match
  replay. A `ProjectReplayState` worker operation would project this data
  through the existing projection infrastructure without fog-of-war.

---

## 9. Implementation status (2026-08-07)

### All-AI matches — COMPLETE

- Migration 0032: `human_slots BETWEEN 0 AND 16`
- Rust API: `owner_civilization_id` is `Option<String>`, empty humans allowed
- Postgres: owner inserted as `role='spectator'` with NULL civ when no humans
- Kotlin worker: `WorkerGameSetup.materialize()` handles empty humans, null owner civ
- `EngineWorkerProtocol`: handles null owner civ in `CreateGame` and `ReconfigureLobby`
- Verified: 192 Rust tests pass, Kotlin compiles, all 312 authoritative tests pass

### Simultaneous human turns — COMPLETE

- `GameParameters.simultaneousHumanTurns: Boolean = false` (with clone)
- `GameInfo.playersWhoEndedTurn: MutableSet<String>` field
- `HeadlessGameEngine.endTurn()` dual-mode:
  - Sequential: advances turn immediately (existing behavior)
  - Simultaneous: marks player done in `playersWhoEndedTurn`, advances only
    when all active humans have ended
- `requireCanIssueTurnCommands()` helper replaces all 28 turn guards:
  - Sequential: `require(game.currentPlayer == actorCivilization.civID)`
  - Simultaneous: checks human + alive + not spectator + not in `playersWhoEndedTurn`
- `PlayerProjection.canIssueTurnCommands` updated for simultaneous mode
- `WorkerGameSetup.simultaneous_human_turns` field (Rust + Kotlin)
- Fixed infinite recursion in `requireCanIssueTurnCommands` else-branch
- Verified: all 312 authoritative tests pass, full Kotlin test suite passes

### Match replay system — COMPLETE

- Migration 0033: `game_visibility` column (`private`/`public`) + index
- `ProjectReplayState` worker operation (Rust protocol + Kotlin handler)
- `ReplayProjection` structs (Rust + Kotlin): full no-fog-of-war projection with
  all civs' stats, territory, victory info, stats history
- `ReplayProjectionBuilder` (Kotlin): builds from `GameInfo` with all civ data
- `worker/replay.rs`: client method for `project_replay_state`
- `postgres/replay.rs`: `list_revisions`, `replay_projection`, `is_public_game`,
  `list_public_matches` methods
- `api/replay.rs`: 3 endpoints
  - `GET /api/v3/games/{game_id}/revisions` — list all revisions
  - `GET /api/v3/games/{game_id}/revisions/{revision}/replay` — no-fog projection
  - `GET /api/v3/public-matches` — directory of public matches
- Access control: public games accessible without membership; private games
  require membership check
- Kotlin client: `ApiV3Transport` interface methods (with default `error` impls),
  `ApiV3Client` HTTP implementations, `AuthoritativeMultiplayerSession` methods
- Kotlin DTOs: `ApiV3RevisionList`, `ApiV3ReplayGameProjection`,
  `ApiV3PublicMatchSummary`, `ApiV3RevisionSummary`
- `AuthoritativeReplayScreen.kt`: YouTube-style replay screen with:
  - Play/pause toggle with adjustable speed (0.5x, 1x, 2x, 5x)
  - Step forward/backward through revisions
  - Revision slider for scrubbing
  - Civ stats table (gold, cities, units, population, tech, status)
  - Stats history display (score per civ per turn)
  - Victory display
- `AuthoritativePublicMatchesPopup.kt`: popup listing public matches, click to
  open replay screen
- "Watch public matches" button added to `MultiplayerScreen`
- Verified: Rust compiles, clippy clean, 192 tests pass; Kotlin compiles, all
  tests pass

### Remaining work

- Run migrations 0032 and 0033 against the database
- Regenerate `openapi/api-v3.json` contract with new replay endpoints
- Rust projection validation rules for simultaneous turns (`is_current_turn`
  semantics)
- `SpectatorProjection` for simultaneous mode (multiple active players visible)
- `AdvanceAiTurn` worker operation for all-AI matches (currently `nextTurn()`
  runs all AI to completion in one call)
- End-to-end replay test with the completed benchmark match

---

## 10. Snapshot storage architecture and Lockwell cold archival (2026-08-08)

### Decision

PostgreSQL remains the authoritative metadata and transaction store, but it is
no longer required to retain every historical payload. The canonical revision
chain, state hashes, command journal, replay identity, and retention decisions
remain in PostgreSQL. Cold payload bytes can be moved to the Lockwell native
object API only after an upload, checksum validation, conditional-write result,
and byte-for-byte download verification.

The rollout is explicit and fail-closed:

1. Set `UNCIV_V3_SNAPSHOT_ZSTD_LEVEL=9` for new hot snapshots. Existing zstd
   rows decode unchanged; no codec migration is needed.
2. Apply migrations 0034 and 0035, which add verified archive metadata and the
   bounded `zstd_delta` archive codec.
3. Configure `UNCIV_LOCKWELL_ENDPOINT`, `UNCIV_LOCKWELL_BUCKET`,
   `UNCIV_LOCKWELL_ACCESS_KEY_ID`, and `UNCIV_LOCKWELL_SECRET_KEY`. The bucket
   must be provisioned by an operator; the server never creates buckets or
   broadens access-key scope.
4. Run `unciv-v3-reencode <game-uuid>` as a dry run to measure historical
   level-9 savings; use `--apply` only after inspecting the report.
5. Run `unciv-v3-archive <game-uuid> --recent 64 --long-term-interval 100`
   for a dry run, then repeat with `--apply`; add `--delta` only after the
   object-store/replay qualification passes.

`--delta` keeps full checkpoints at revision 0 and every configured long-term
interval, retains the recent window and end-turn/recovery milestones, and stores
older snapshots as independently verified checkpoint-relative deltas when the
bounded delta is smaller than the original full payload. Normal runs therefore
use a full checkpoint as the delta base; the reader also permits a legacy or
operator-selected delta base but caps reconstruction at 64 links and fails
closed on cycles or excessive depth. If Lockwell is unavailable, the object is
missing, the checksum/size/hash differs, or the database transaction fails,
reads return a recovery error and the game is not silently reconstructed from
client state.

### Recovery and client-load guarantees

A human join still downloads only metadata followed by one player-scoped
projection. The server may fetch/decompress one current full checkpoint or one
Lockwell full/delta object plus its retained base, then sends the worker-derived
fog-of-war projection. Notifications remain hints; projection deltas are still
bounded to 64 revisions. Replay requests are also point reads: they fetch the
requested revision and its checkpoint if that revision is archived as a delta,
not the complete history. PostgreSQL is therefore removed from the hot payload
path for cold history without changing the client protocol.

### Safety boundaries

The old PostgreSQL-only compactor remains available for private/local history
and intentionally leaves public matches alone. The Lockwell archival command is
separate and opt-in, so public replay retention is a conscious operator action.
The reconciliation scan treats retained blobs as local evidence, requires a
Lockwell archive for `zstd_delta` rows, and permits intentionally compacted full
rows produced by the legacy compactor. Archive object keys are UUID/revision
paths only; access-key scope and Lockwell tenant isolation remain enforced by
Lockwell.

Production archival is intentionally not automatic in the commit hot path:
run the dry-run `unciv-v3-reencode` and `unciv-v3-archive` CLIs, inspect their
candidate/byte counts, then use `--apply`. This avoids coupling a human
command's availability to object-store latency while keeping every cold read
fail-closed and retry-safe.
