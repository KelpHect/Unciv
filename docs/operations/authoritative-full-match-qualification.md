# Authoritative V3 full-match qualification

This is the release gate for claiming that API V3 can replace legacy online
multiplayer. It must be run against a disposable deployment built from the
candidate release bundle, the digest-pinned PostgreSQL 19 Beta 2 image, and the
packaged Kotlin worker. Unit, projection, and command-inventory tests do not
replace this gate.

## Automated preflight

Build the packaged worker, bootstrap a disposable database with the production
roles, and run:

```text
UNCIV_V3_DATABASE_URL=postgres://... cargo test \
  --manifest-path authoritative-server/Cargo.toml \
  --test account_handoff -- --ignored --test-threads=1
```

The test uses separate bearer sessions for an Android-labelled owner, a fresh
desktop restoration of the same account, and a second account. It creates a
three-major Domination lobby, has the second player choose an available
canonical faction, makes both humans ready, starts as the owner, proves both
owner sessions receive the same canonical projection, resigns through the
desktop session, and proves the packaged worker processes the intervening
server AI before handing control to the second human.

## Human release run

Use two people, one Android device, and one supported desktop build. Preserve
the server logs, client logs, game ID, release-bundle ID, manifest hash, and
final committed revision.

1. Person A registers on Android and creates a named, optionally
   password-protected tiny Pangaea Domination lobby with two human slots, three
   major civilizations, no city-states, and their chosen faction.
2. Person B signs in independently, finds the lobby browser entry, supplies the
   password when configured, chooses a different available faction, and joins.
   Both players mark themselves ready; only Person A can start. Confirm the
   third faction remains server AI and no gameplay projection was available
   before start.
3. Each player completes at least two turns. Exercise founding, production,
   research, movement, combat, diplomacy, and end-turn from only projected
   choices. Confirm AI revisions occur without either client running AI.
4. Suspend Android while it is not Person A's turn. Confirm the native
   background notification arrives when the turn returns.
5. Close Android, sign into the same account on desktop, discover the game with
   no copied save, and compare the opened revision and projection hash with the
   server. Complete a turn, restart desktop, and retry one intentionally
   interrupted command with the same idempotency key.
6. Resume Android and confirm it reconciles to the desktop-committed revision
   without local canonical mutation.
7. Continue the real match until the Kotlin engine records a Domination
   victory. Confirm both player projections and a spectator projection show the
   same winner, victory type, and victory turn; every gameplay command after
   that revision must be rejected.
8. Run authoritative reconciliation and require zero findings. Record the
   exact commands, duration, final revision/hash, and evidence locations in
   `docs/architecture/authoritative-multiplayer-status.md`.

The checklist may be marked complete only after this human run finishes. An
all-AI simulation after both humans resign is not a substitute: it changes the
scenario and can exceed the bounded synchronous worker deadline.
