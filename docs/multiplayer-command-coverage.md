# Multiplayer command coverage

API v3 has not been implemented yet. Every row is intentionally **not started**
until it has a typed command, server-side handler, authorization rule,
validation test, client command-bus call site, and projection effect.

| Mutation family | Command | Status |
| --- | --- | --- |
| Create game | `CreateGame` | Not started |
| End turn and AI | `EndTurn` | Not started |
| Unit movement, combat, actions | TBD closed union | Not started |
| City production, purchases, founding, conquest | TBD closed union | Not started |
| Research, policies, religion | TBD closed union | Not started |
| Diplomacy, trades, war, votes | TBD closed union | Not started |
| Espionage, automation, promotions, upgrades | TBD closed union | Not started |
| Improvements and map actions | TBD closed union | Not started |
| Resign, force-resign, spectator changes | TBD closed union | Not started |

Legacy `Multiplayer.updateGame()` and API-v2 full-payload upload remain outside
v3. They must not mutate a v3 game when API v3 is introduced.
