# Authoritative reconciliation repair

The reconciliation report is evidence, not permission to rewrite canonical
history. Always preserve a verified logical backup and storage snapshot before
repairing a production game. Rehearse the same action against a restored
PostgreSQL 19 Beta 2 instance first.

## Run the workflow

First run the read-only, database-wide audit:

```text
cargo run --manifest-path authoritative-server/Cargo.toml --bin unciv-v3-reconcile
```

Then preview the bounded workflow for one game:

```text
cargo run --manifest-path authoritative-server/Cargo.toml --bin unciv-v3-repair -- <game-uuid>
```

The preview acquires the same game lock used by commits, reports the intended
actions, and rolls its transaction back. It fails closed if reconciliation
truncates its findings. Apply only after comparing the preview with the backup
and incident evidence:

```text
cargo run --manifest-path authoritative-server/Cargo.toml --bin unciv-v3-repair -- <game-uuid> --apply
```

Applied actions are transactionally serialized with game commits and recorded
in append-only `game_repair_events`. Repeating the command is safe. Run the
read-only reconciliation command again after every application.

## Reviewed finding responses

| Finding | Automated action | Required operator response |
| --- | --- | --- |
| `missing_commit_outbox` | Reconstruct the deterministic notification hint from the immutable revision, exact topic, revision, and canonical state hash. | Confirm the revision itself is valid, apply, and verify reconciliation. Notifications remain non-authoritative. |
| `quarantined_game` | None. Existing quarantine is preserved. | Establish why the game was quarantined. Use the separately documented verified recovery workflow or restore a verified backup. |
| `invalid_head`, `missing_snapshot`, `missing_snapshot_payload`, `broken_revision_chain`, `invalid_snapshot_payload` | Quarantine with `reconciliation_required`; never synthesize canonical state. | Preserve evidence. Prefer verified journal recovery when its complete immutable replay evidence exists; otherwise restore a verified backup. |
| `missing_revision_command`, `missing_command_actor`, `missing_command_time`, `missing_command_replay_operation`, `missing_creation_replay_context` | Quarantine; never infer missing replay evidence from current mutable state. | Supply independently verified historical evidence and rehearse a bespoke migration on a restored copy, or restore a verified backup. |
| `duplicate_civilization_membership`, `invalid_owner_count` | Quarantine; never guess player assignment or ownership. | Resolve identity and ownership through authenticated operator evidence and an audited bespoke migration on a restored copy. |
| `orphan_snapshot`, `orphan_command`, `orphan_commit_outbox` | Quarantine; preserve the orphan as incident evidence. | Determine whether the orphan came from external/manual corruption or an application defect. Do not delete it until backup, root-cause review, and a rehearsed audited migration are complete. |

Every finding other than a missing derived commit-outbox hint therefore makes
the game unavailable. The generic tool never deletes rows, edits immutable
revisions or commands, changes memberships, rewrites snapshots, advances a
head, or clears quarantine.

## Recovery and promotion gate

For canonical snapshot damage, follow the bounded recovery workflow documented
in the
[authoritative multiplayer status](../architecture/authoritative-multiplayer-status.md#bounded-journal-recovery-and-immutable-publication).
Recovery may publish a new immutable revision only after bounded replay
reproduces a verified canonical state from complete server-owned evidence. It
is not a way to invent missing actor, time, command, ownership, or ruleset
context.

Before returning a repaired or restored game to service:

1. Require an untruncated reconciliation report with no unexplained findings.
2. Run the complete serialized PostgreSQL integration suite against the
   candidate database.
3. Compare the head revision and canonical hash with the approved recovery
   record.
4. Preserve the repair events, backup identifiers, commands, and operator
   approval in the incident record.
5. Re-enable a quarantined game only through a separately reviewed,
   game-specific recovery or restore procedure. The generic repair command
   intentionally cannot clear quarantine.
