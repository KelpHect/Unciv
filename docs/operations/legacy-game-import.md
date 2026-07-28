# Legacy game import

`unciv-v3-import-legacy` is the only supported whole-save ingestion path for
API v3. It is an operator-only, one-way migration command. It never updates an
existing authoritative game, never changes a source file, and defaults to a
read-only dry run.

## Prerequisites

- Run database migrations before the import.
- Install and register the exact content-addressed ruleset manifest and bundle.
- Create or migrate every destination API-v3 account first.
- Run the private packaged Kotlin worker on its loopback-only address.
- Set `UNCIV_V3_DATABASE_URL`, `UNCIV_ENGINE_WORKER_ADDR`, and the 64-hex-byte
  `UNCIV_ENGINE_WORKER_SECRET` through the operator environment.
- Choose a new, stable operation UUID. Retain it across an ambiguous retry.

## Dry run

Pass every candidate for the same legacy game, even if one is believed to be
newer. Candidate indices follow the command-line order and selection is always
explicit:

```powershell
cargo run --release --bin unciv-v3-import-legacy -- `
  --operation-id 11111111-1111-4111-8111-111111111111 `
  --owner-account-id 22222222-2222-4222-8222-222222222222 `
  --operator migration-admin `
  --origin legacy.example `
  --legacy-game-id legacy-game-id `
  --manifest aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa `
  --candidate C:\imports\legacy-game-device-a.json `
  --candidate C:\imports\legacy-game-device-b.json `
  --select 1 `
  --map-player legacy-owner=22222222-2222-4222-8222-222222222222 `
  --map-player legacy-player=33333333-3333-4333-8333-333333333333
```

The command bounds each source before decoding, hashes the canonical source
path and bytes without printing the path, and normalizes every candidate in the
shared Kotlin engine under the pinned manifest. Its JSON report identifies
turn, current-player, and canonical-hash divergence. It does not guess which
candidate is authoritative.

Every human and spectator legacy player ID must have exactly one destination
account UUID. The authenticated owner account must resolve to exactly one
playable civilization. The dry run also generates every initial player and
spectator projection and rejects any projection containing a legacy identity.

Review the complete report and confirm the explicit `selected_candidate_index`.
If candidates diverge, retain the report with the migration approval record.

## Apply

Repeat the exact command with the same operation UUID, candidate order,
selection, mappings, and `--apply`. The apply transaction revalidates active
accounts and the installed manifest under locks, then atomically creates:

- one deterministic new API-v3 game at revision zero;
- owner, player, and spectator memberships derived from worker metadata;
- the immutable snapshot, revision, and outbox records;
- append-only provenance containing source hashes, the divergence report, and
  projection-leak evidence.

An exact retry returns the original result without invoking a second canonical
creation. Reusing the operation UUID with changed meaning fails closed. A
different operation UUID cannot import the same `(origin, legacy game ID)`
again.

The source files remain unchanged. Archive them according to the operator's
legacy-retention policy only after users have verified the new game; this
command does not delete or move them.
