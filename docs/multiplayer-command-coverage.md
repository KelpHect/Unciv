# Multiplayer command coverage

API v3 is an incomplete vertical slice. A row is complete only when protocol,
engine handler, authorization, validation, client command-bus migration, and
player projection effects are all complete.

| Mutation family | Command / handler | Authorization and tests | Client / projection | Status |
| --- | --- | --- | --- | --- |
| Create game | `CreateGame` / Kotlin `GameStarter` | Session-derived owner; canonical owner/civilization persisted; cross-process smoke | No v3 client; metadata-only response | Server slice complete; client/projection pending |
| End turn and AI | `EndTurn` / `HeadlessGameEngine.endTurn` | Membership-derived civilization; canonical `playerId` and current-turn checks; unit and HTTP rejection tests | No v3 client; metadata-only reconciliation | Server slice complete; client/projection pending |
| Join and player assignment | TBD closed command | Must atomically bind account, membership, and canonical `playerId` | No client or projection | Not started |
| Unit movement, combat, actions | `MoveUnit` schema placeholder; no handler | No canonical authorization or validation tests | No client or projection | Protocol placeholder only |
| City production, purchases, founding, conquest | TBD closed union | None | None | Not started |
| Research, policies, religion | TBD closed union | None | None | Not started |
| Diplomacy, trades, war, votes | TBD closed union | None | None | Not started |
| Espionage, automation, promotions, upgrades | TBD closed union | None | None | Not started |
| Improvements and map actions | TBD closed union | None | None | Not started |
| Resign, force-resign, spectator changes | TBD closed union | None | None | Not started |

Legacy `Multiplayer.updateGame()` and API-v2 full-payload upload remain outside
v3. They must not mutate a v3 game when API v3 is introduced.
