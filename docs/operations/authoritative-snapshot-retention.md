# Authoritative snapshot retention

API-v3 retains the complete revision, command, audit, outbox, canonical-hash,
and snapshot-metadata history. Only old compressed payload blobs are eligible
for compaction.

Migration `0017_snapshot_payload_retention.sql` separates immutable snapshot
metadata from `game_snapshot_blobs`. New snapshots write both records in the
same canonical commit transaction. Blob rows are size-bound and reference the
exact snapshot revision, compressed size, and payload hash.

## Protected payloads

Compaction always retains:

- revision zero;
- the current canonical head;
- every recovery revision;
- every accepted `end_turn` revision;
- the configured number of most recent revisions; and
- every configured long-term interval checkpoint.

The default policy keeps 64 recent revisions and every 100th revision. At
least two recent revisions are required so recovery always has a predecessor
to the current head. A compacted revision remains in the immutable history and
continues to support audit and duplicate-command idempotency. A projection
delta whose old base payload was compacted fails closed and the client uses the
existing authenticated full-projection recovery path.

## Operator workflow

Set `UNCIV_V3_DATABASE_URL` to the PostgreSQL 19 Beta 2 database. Preview one
game without changing it:

```text
cargo run --manifest-path authoritative-server/Cargo.toml --bin unciv-v3-compact -- \
  <game-uuid>
```

The JSON report includes the head revision, retained and eligible payload
counts, and reclaimable bytes. Review it before applying:

```text
cargo run --manifest-path authoritative-server/Cargo.toml --bin unciv-v3-compact -- \
  <game-uuid> --recent 64 --long-term-interval 100 --apply
```

The apply transaction locks the game row, marks only selected snapshot
metadata as compacted, removes the corresponding blob rows, and commits both
changes atomically. It serializes with gameplay commits and recovery
publication.

After compaction, run:

```text
cargo run --manifest-path authoritative-server/Cargo.toml --bin unciv-v3-reconcile
```

Intentional compacted payloads are not reconciliation findings. A metadata row
still marked `retained` without its exact blob is reported as damage.

Backups and restore policy must include both `game_snapshots` and
`game_snapshot_blobs`. Compaction is irreversible from the live database;
restore an old blob only from a verified backup whose size and payload hash
match the retained immutable metadata.
