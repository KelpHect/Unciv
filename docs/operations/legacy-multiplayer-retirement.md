# Legacy multiplayer retirement

API v3 is the authoritative multiplayer service. The legacy server remains a
separate file service only for migration and compatibility. Its default remains
write-enabled until an operator deliberately advances the rollout.

## Telemetry

`GET /legacy-status` returns process-local aggregate counters:

- whether legacy writes are enabled;
- completed whole-save writes;
- rejected whole-save writes;
- completed legacy authentication writes;
- rejected legacy authentication writes.

The response contains no usernames, UUIDs, filenames, network addresses,
credentials, save contents, or v3 state. Scrape and aggregate the counters
outside the process because they reset when the legacy process restarts.

## Write cutoff

Start the legacy server with either of these equivalent settings:

```text
-no-legacy-writes
UncivServerLegacyWrites=false
```

Retirement mode returns `410 Gone` for `PUT /files/{fileName}` and `PUT /auth`.
Existing legacy files remain readable so players and operators can recover
source saves for the one-way v3 importer. Health, read access, and the separate
API-v3 service continue operating. Returning to the default write-enabled mode
is a process restart with the flag/environment override removed; it does not
modify any file or v3 canonical revision.

## Staged rollout

1. Publish API-v3 capability and explicit legacy/v3 labels in supported clients.
2. Scrape `/legacy-status` on every legacy instance for at least one complete
   active-game cycle. Inventory active legacy game owners without logging save
   contents or credentials.
3. Notify owners, run the operator-only importer in dry-run mode, resolve
   divergent candidates explicitly, and migrate selected games as new v3
   revision-zero games.
4. Enable `-no-legacy-writes` on a pilot instance. Confirm rejected counters are
   expected, v3 creation/commands remain healthy, and legacy reads still work.
5. Enable the cutoff fleet-wide. Keep read-only legacy storage through the
   announced recovery window and retain immutable backups under the documented
   retention policy.
6. Remove legacy listeners only after the write-rejection counters have stayed
   at zero for the announced window, every supported client defaults new games
   to v3, remaining owners have an export/import path, and a rollback decision
   has been reviewed.

Never interpret low request volume alone as proof that migration is complete.
The final removal decision requires owner communication, retained source
backups, client capability evidence, and an audited operator change.
