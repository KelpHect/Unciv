# Legacy and authoritative multiplayer isolation

Legacy multiplayer is an independent file service. Authoritative multiplayer
v3 is a Rust control plane backed by PostgreSQL and a private Kotlin worker.
They must not share authority, credentials, storage, or sessions.

## Required deployment boundary

- Publish legacy and v3 on separate origins or listeners. The v3 Caddy and
  systemd configuration must never route legacy `/files/`, `/auth`, or `/chat`
  endpoints.
- Run the services under separate operating-system identities. Never supply
  `UNCIV_V3_DATABASE_URL`, `UNCIV_V3_MIGRATION_DATABASE_URL`,
  `UNCIV_ENGINE_WORKER_ADDR`, or `UNCIV_ENGINE_WORKER_SECRET` to the legacy
  process.
- Give legacy its own file root. It receives no PostgreSQL role, v3 release
  directory, worker socket, or v3 credential file.
- Legacy passwords and sessions are not v3 bearer sessions. A matching user,
  game UUID, or filename in the two systems does not link their identities or
  state.
- The operator-only, one-way legacy importer is the sole supported bridge. It
  reads bounded legacy source files and creates a new validated v3 game; v3
  never writes back to the legacy service.

## Automated proof

Run from the repository root:

```powershell
.\authoritative-server\tests\run-legacy-v3-isolation.ps1
```

The test packages the real legacy server, provisions the exact pinned
PostgreSQL 19 Beta 2 image, and creates a real v3 game. It then starts legacy
with all v3 database and worker variables removed, uploads and reads an
attacker-controlled legacy save under the same UUID, and proves the v3 head,
snapshot hash, games, revisions, and command journal are unchanged. The
disposable legacy file root and PostgreSQL container are removed afterward.

