<!--
Thanks for contributing to Unciv!

AGENTS.md is the binding contract. This template is only the place the
final-state verification record lands - it does not restate the rules.
-->

## Summary

<!-- What changed, and why. -->

## Final-state verification

<!--
Required for any change that touches the API v3 change-impact surface.

Fill this in only after your last material edit: if a material file changes
after a check, that check is stale and must be rerun.

Keep every lane row. Mark the lanes outside your change `not affected` with a
specific impact reason - do not delete them, because a missing row is not the
same claim as an assessed one.

Allowed statuses are `passed`, `failed`, `unavailable`, and `not affected`.
See AGENTS.md -> "Final-state verification record" for what each status
requires and which gate each lane owns.
-->

Revision: <HEAD commit; worktree state; diff hash; material untracked hashes>

Last material edit: <path and change>

| Lane | Status | Command or gate | Covered invariant | Result | Blocker |
| --- | --- | --- | --- | --- | --- |
| Kotlin rules, commands, projections, saves, AI, or turn progression | | | | | |
| Rust API, protocol, persistence, recovery, or observability | | | | | |
| Desktop production UI or routing | | | | | |
| Android production UI, routing, credentials, or platform behavior | | | | | |
| PostgreSQL schema, queries, concurrency, backup, recovery, or reconciliation | | | | | |
| Kotlin worker or release packaging | | | | | |
| Mods or rulesets | | | | | |
| Legacy or shared gameplay behavior | | | | | |

<!--
If an affected check failed, add the ordered record from
AGENTS.md -> "Failure-repair record". A retry with no causal diagnosis is a
retry-only pass, not a confirmed repair.

If this change requests a commit, push, PR, CI result, merge, release,
deployment, APK, or desktop artifact, append
AGENTS.md -> "Delivery-closure record".
-->
