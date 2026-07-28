# Security audit export and custody

Security audit records are an operator evidence stream, never a player API.
The public Rust listener exposes no repair, recovery, reconciliation, outbox,
database, or audit-export route. Player-facing game administration remains
ordinary owner-authorized gameplay and cannot invoke operator procedures.

Run the export only from a named local operator session with the independently
rotated `unciv_audit` credential:

```text
UNCIV_V3_AUDIT_DATABASE_URL=postgresql://unciv_audit:...@127.0.0.1:5432/unciv_authoritative?sslmode=require
unciv-v3-export-security-audit --output security-audit-2026-07-28.ndjson
```

The destination must not already exist. The tool fixes a database high-water
mark, reads ascending ID pages of at most 1,000 rows, and emits one NDJSON
record per redacted event. Each record hashes the preceding hash and its
canonical event JSON. A final manifest records the high-water mark, count,
first/last IDs, and final chain hash. A failed export leaves a partial file that
must be quarantined, never mistaken for a completed manifest.

## Retention and access

- Keep the database records for at least 400 days unless a longer legal or
  incident hold applies. `unciv_runtime` may insert and select but cannot update, delete, or truncate
  them; only the offline migrator owns schema
  changes.
- Move completed exports immediately to write-once or object-lock storage in a
  separate security account. Enable retention lock for at least 400 days and
  preserve the manifest hash in the incident or scheduled-export record.
- Grant export access only to the security incident commander and named
  responders. Review access quarterly and remove expired assignments.
- Never place passwords, bearer tokens, full source IPs, request bodies,
  canonical game state, or private projections in the export.
- Export daily and after every security incident. Alert when no complete
  manifest is archived for 26 hours, the ID range regresses or gaps
  unexpectedly, or chain verification fails.

The on-call security incident commander owns collection and containment. The
service owner owns daily export health. A separate reviewer validates custody,
retention lock, chain continuity, and incident closure. Follow
`authoritative-incident-response.md` for escalation and break-glass controls.
