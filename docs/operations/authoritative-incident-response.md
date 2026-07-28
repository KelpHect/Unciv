# Authoritative multiplayer incident response

This runbook covers production incidents that can affect API-v3 authority,
availability, or confidentiality. Canonical PostgreSQL history is evidence:
never edit `games.head_revision`, immutable snapshots, revisions, commands, or
membership rows by hand. Never accept a client save as repair input.

Every incident gets a UTC incident ID, incident commander, operator, reviewer,
start/end times, affected games/accounts, release bundle ID, PostgreSQL image
digest, commands run, backup/restore identifiers, and the final reconciliation
report. Store secrets outside the incident record.

## Common first response

1. Declare the incident and stop unrelated deployment or migration work.
2. Preserve logs and current state before restarting or changing anything:

   ```text
   date --utc
   systemctl --failed
   systemctl show unciv-authoritative-api.service unciv-authoritative-worker.service unciv-authoritative-postgres.service -p ActiveState -p SubState -p MainPID -p NRestarts
   journalctl --utc --since '<incident-start-utc>' -u unciv-authoritative-api.service -u unciv-authoritative-worker.service -u unciv-authoritative-postgres.service -u unciv-authoritative-proxy.service
   unciv-v3-outbox status
   unciv-v3-reconcile
   ```

3. Capture only redacted reports. Do not copy environment files, database URLs,
   bearer tokens, password hashes, canonical snapshots, projections, worker
   frames, or private outbox payloads into tickets or chat.
4. If integrity is uncertain, stop the public API and leave PostgreSQL and the
   worker available only to the local operator:

   ```text
   systemctl stop unciv-authoritative-api.service
   ```

5. Take a verified backup or storage snapshot before any repair. Rehearse every
   mutating action against an isolated restored PostgreSQL 19 Beta 2 instance.
6. Use the narrow procedure below. A service restart is not proof of recovery.
7. Before reopening traffic, require:
   - the expected release bundle and worker handshake;
   - API readiness;
   - an untruncated reconciliation report with zero unexplained findings;
   - an idempotent command retry using the same command ID when a response was
     lost;
   - reviewer approval recorded in the incident.

## Private worker failure or timeout

Trigger on repeated worker timeouts, circuit-breaker-open responses, handshake
or release-bundle mismatch, JVM OOM, restart loops, or a failed worker unit.

Contain:

```text
systemctl stop unciv-authoritative-api.service
systemctl show unciv-authoritative-worker.service -p ActiveState -p MainPID -p NRestarts -p MemoryCurrent -p MemoryMax
journalctl --utc --since '<incident-start-utc>' -u unciv-authoritative-worker.service
```

Do not bypass the authenticated handshake, widen the worker listener, increase
timeouts without a bounded rehearsal, or run a different worker build against
pinned games. Verify the active release symlink, immutable bundle manifest,
ruleset catalog, secret-file ownership/mode, memory ceiling, and loopback
listener using
[authoritative-worker-systemd.md](authoritative-worker-systemd.md).

Recover by reinstalling the exact verified bundle when artifact or identity
checks fail, or by restarting the unit when the artifact is sound:

```text
systemctl restart unciv-authoritative-worker.service
systemctl is-active --quiet unciv-authoritative-worker.service
systemctl start unciv-authoritative-api.service
```

Retry an interrupted client operation only with the same command ID and
expected revision. The PostgreSQL head must remain unchanged if execution
failed before commit. Run `unciv-v3-reconcile` and confirm the retry commits
zero or one revision, never two.

## Corrupt game quarantine and recovery

Trigger on `game_unavailable`, snapshot hash/decompression failure, a recovery
or reconciliation finding, or a game already marked quarantined.

Immediately stop commands for the affected game; stop the whole API when scope
is unknown. Preserve the game ID, head revision/hash, finding codes, worker
bundle ID, installed ruleset manifest, backup IDs, and relevant redacted logs.

Run the read-only audit and dry-run repair:

```text
unciv-v3-reconcile
unciv-v3-repair <game-uuid>
```

Only a missing derived commit-outbox hint has a generic repair. After reviewer
approval and an isolated rehearsal:

```text
unciv-v3-repair <game-uuid> --apply
unciv-v3-reconcile
```

For canonical snapshot damage, preview bounded journal recovery:

```text
unciv-v3-recover <game-uuid> --max-tail <reviewed-bound>
```

Apply only when complete server-owned replay evidence reproduces the approved
canonical hash on an isolated restored copy:

```text
unciv-v3-recover <game-uuid> --max-tail <reviewed-bound> --apply
unciv-v3-reconcile
```

If replay evidence is incomplete, restore a verified backup/PITR candidate.
Never infer actor, time, RNG, ownership, ruleset, or command data. The generic
repair command intentionally cannot clear quarantine. Follow
[authoritative-reconciliation-repair.md](authoritative-reconciliation-repair.md)
for the finding matrix and promotion gate.

## PostgreSQL primary failure and failover

Trigger on failed database readiness, connection failures across API replicas,
or confirmed primary loss. Stop API and migration units before promotion:

```text
systemctl stop unciv-authoritative-api.service unciv-authoritative-migrate.service
```

Fence the old primary at the host, network, and storage layers. Do not promote
until it is impossible for the old primary to accept writes. Record the last
known timeline/LSN and confirm the candidate is still in recovery:

```text
psql '<candidate-admin-url>' -X -v ON_ERROR_STOP=1 -Atc "select pg_is_in_recovery(), pg_last_wal_replay_lsn(), pg_last_xact_replay_timestamp()"
```

Promote exactly one reviewed candidate, wait for recovery to end, and repoint
the runtime/migration/backup/audit credentials without copying a superuser
credential into application configuration:

```text
pg_ctl -D '<candidate-data-directory>' promote --wait
psql '<candidate-admin-url>' -X -v ON_ERROR_STOP=1 -Atc "select pg_is_in_recovery()"
```

Run `unciv-v3-reconcile`, the serialized PostgreSQL integration suite, API
readiness, and one idempotency/concurrency probe before restarting public
traffic. Keep the former primary fenced until rebuilt from the promoted
timeline. The controlled promotion test is
`cargo test --manifest-path authoritative-server/Cargo.toml --test postgres_failover -- --ignored --nocapture`.

If no safe promotion candidate exists, use the point-in-time recovery gate in
[authoritative-postgresql-19.md](authoritative-postgresql-19.md). Never expose
two writable primaries.

## Outbox backlog or poison event

Outbox notifications are hints; HTTP projections remain authoritative. Trigger
on a nonzero `unciv-v3-outbox status`, dead-letter count, or oldest-pending age
at the configured alert threshold.

Inspect dispatcher/listener health, database capacity, exact outbox ID,
canonical revision, membership, attempt count, and the fixed redacted failure
category. Do not print its private payload into the incident record.

Preview before mutation:

```text
unciv-v3-outbox status
unciv-v3-outbox requeue <outbox-id>
```

After fixing the cause and obtaining review:

```text
unciv-v3-outbox requeue <outbox-id> --apply
unciv-v3-outbox status
unciv-v3-reconcile
```

Never change an outbox topic/payload or fabricate canonical history. Never
requeue repeatedly without finding the poison cause. Follow
[authoritative-outbox-operations.md](authoritative-outbox-operations.md).

## Database credential compromise

Identify the affected role (`unciv_runtime`, `unciv_migrate`, `unciv_backup`,
`unciv_restore`, or `unciv_audit`) without placing the old or new credential in
logs. Stop its consumers, terminate that role's sessions, and temporarily
revoke `LOGIN` when active misuse is suspected. Keep public traffic stopped
when runtime or migration credentials are involved.

Generate the replacement in the approved secret manager. From a protected
local administrator session, run `rotate-role-password.sql` with psql variables
that do not enter shell history. Update only the affected protected credential
file, restore the role's intended attributes from `bootstrap-roles.sql`, and
restart only its consumer.

Prove:

- a new connection with the old credential is denied;
- the new credential can perform only its documented role;
- runtime cannot perform DDL, audit cannot write, backup is replication-only,
  and restore cannot connect to production;
- security audit and PostgreSQL connection logs contain no secret;
- API readiness and reconciliation pass.

If the PostgreSQL administrator/bootstrap secret is compromised, fence
database access, rotate it from the local host, review every role and active
session, and preserve connection logs. Do not put a superuser URL in any
service environment.

## Authentication abuse or service denial

Trigger on registration/login `429` growth, credential-stuffing patterns,
unusual account-security operations, command floods, expensive rulesets, or
WebSocket admission pressure.

Preserve prefix-reduced source and one-way identity hashes from security audit
records. Never log raw bearer tokens, passwords, full IP addresses beyond the
approved operational policy, or private game payloads. Confirm trusted-proxy
validation before treating forwarded addresses as evidence.

Contain at the TLS proxy/firewall first with an expiring, reviewed rule. Keep
PostgreSQL durable rate limiting enabled; do not clear buckets globally or
raise bounded worker/queue/request limits during an attack. For a compromised
player account, use the authenticated account disable/password workflow when
the owner retains access. An operator account-recovery override does not yet
exist and must not be simulated with ad hoc account-row edits.

Verify unrelated native clients still work, abusive requests receive stable
redacted errors, readiness remains truthful, worker/database limits hold, and
canonical reconciliation stays clean. Record every temporary block and remove
it at its expiry after review.

## Break-glass access

There is no public API-v3 operator endpoint and no standing application
superuser. Break-glass access is local-console access to a named production
host using an individual privileged identity plus a second reviewer.

Required controls:

1. Open a UTC incident and state the exact narrow operation.
2. Obtain two-person approval; the requester cannot be the sole reviewer.
3. Use a time-bounded host privilege and the least capable database role.
4. Keep the public API stopped for any integrity-affecting operation.
5. Never disable TLS/HBA, expose PostgreSQL/worker publicly, share credentials,
   load a client save, or hand-edit canonical tables.
6. Capture commands and redacted outputs in the immutable incident record.
7. Revoke temporary access, rotate any exposed credential, reconcile, verify
   readiness, and obtain closure approval.

`unciv_restore` is for isolated restored databases and is denied production
database access. Schema changes use `unciv_migrate` through the reviewed
migrator. Read-only investigation uses `unciv_audit`. Any operation outside
the checked-in repair/recovery/rotation procedures requires a bespoke,
reviewed migration rehearsed on a restored copy; urgency is not permission to
weaken authority.

## Closure checklist

- Root cause and affected interval are known, or uncertainty is recorded.
- Canonical reconciliation is untruncated and has zero unexplained findings.
- Backup/PITR viability and outbox health are confirmed.
- API, worker, database, proxy, and capacity checks are healthy.
- Lost-response retries used the same command ID.
- Temporary network rules, credentials, roles, and host privileges are removed
  or have a named owner and expiry.
- No secret or hidden player state entered logs, tickets, or chat.
- A reviewer signs the recovery evidence and follow-up actions have owners.
