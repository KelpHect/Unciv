# Multiplayer command coverage

API v3 is an incomplete vertical slice. A row is complete only when protocol,
engine handler, authorization, validation, client command-bus migration, and
player projection effects are all complete.

| Mutation family | Command / handler | Authorization and tests | Client / projection | Status |
| --- | --- | --- | --- | --- |
| Create game | `CreateGame` / Kotlin `GameStarter` | Session-derived owner; canonical owner/civilization persisted; cross-process smoke | No v3 client; metadata-only response | Server slice complete; client/projection pending |
| End turn and AI | `EndTurn` / `HeadlessGameEngine.endTurn` | Membership-derived civilization; canonical `playerId` and current-turn checks; unit and HTTP rejection tests | Initial HTTP projection can reconcile revision/turn/own state; no v3 client | Server slice complete; client/full projection pending |
| Join and player assignment | `JoinGame` / `HeadlessGameEngine.assignPlayer` | Session-derived account; deterministic unclaimed civilization; membership, canonical `playerId`, revision, journal, and outbox commit atomically; unit and cross-process HTTP tests | No v3 client; metadata-only response | Server slice complete; client/projection pending |
| Unit movement | `MoveUnit` / `HeadlessGameEngine.moveUnit` | Session/membership-derived civilization; current-turn, durable unit ID ownership, map bounds, reachability, and enterability checks; deterministic engine and cross-process HTTP tests | Initial HTTP projection exposes only permitted unit IDs/coordinates and reconciles after movement; no v3 client | Server slice complete; client/full projection pending |
| Unit combat and actions | TBD closed union | None | None | Not started |
| City production, purchases, founding, conquest | TBD closed union | None | None | Not started |
| Research, policies, religion | TBD closed union | None | None | Not started |
| Diplomacy, trades, war, votes | TBD closed union | None | None | Not started |
| Espionage, automation, promotions, upgrades | TBD closed union | None | None | Not started |
| Improvements and map actions | TBD closed union | None | None | Not started |
| Resign, force-resign, spectator changes | TBD closed union | None | None | Not started |

Legacy `Multiplayer.updateGame()` and API-v2 full-payload upload remain outside
v3. They must not mutate a v3 game when API v3 is introduced.
