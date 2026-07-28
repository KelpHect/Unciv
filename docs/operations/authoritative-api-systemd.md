# Authoritative Rust API and database migration systemd deployment

The public Rust process and schema migration process use separate Linux users,
environment files, PostgreSQL credentials, and executables. The API never
applies migrations. It opens its listener only after the database contains the
exact embedded migration versions and checksums and the private Kotlin worker
passes its authenticated release handshake.

## Install the units

Create locked service accounts without login shells:

```text
groupadd --system unciv-api
groupadd --system unciv-migrate
groupadd --system unciv-backup
useradd --system --gid unciv-api --home /nonexistent --shell /usr/sbin/nologin unciv-api
useradd --system --gid unciv-migrate --home /nonexistent --shell /usr/sbin/nologin unciv-migrate
useradd --system --gid unciv-backup --home /nonexistent --shell /usr/sbin/nologin unciv-backup
install -o root -g root -m 0444 docs/operations/authoritative-api-systemd.md /opt/unciv-authoritative/docs/
install -o root -g root -m 0644 authoritative-server/systemd/unciv-authoritative-api.service /etc/systemd/system/
install -o root -g root -m 0644 authoritative-server/systemd/unciv-authoritative-migrate.service /etc/systemd/system/
chmod 0711 /etc/unciv-authoritative
install -d -o root -g unciv-api -m 0750 /etc/unciv-authoritative/api
install -d -o root -g unciv-migrate -m 0750 /etc/unciv-authoritative/migration
systemd-analyze verify /etc/systemd/system/unciv-authoritative-api.service
systemd-analyze verify /etc/systemd/system/unciv-authoritative-migrate.service
systemd-analyze verify /etc/systemd/system/unciv-authoritative-backup.service
```

`/etc/unciv-authoritative/migration/migration.env` is root-owned mode `0640`, group
`unciv-migrate`, and contains only the migration-role URL:

```text
UNCIV_V3_MIGRATION_DATABASE_URL=postgres://unciv_migrate:...@127.0.0.1:5432/unciv_authoritative?sslmode=require
UNCIV_V3_DB_POOL_MAX=1
UNCIV_V3_DB_POOL_MIN=1
UNCIV_V3_DB_STATEMENT_TIMEOUT_MS=300000
UNCIV_V3_DB_LOCK_TIMEOUT_MS=30000
```

`/etc/unciv-authoritative/api/api.env` is root-owned mode `0640`, group
`unciv-api`, and contains only the runtime-role URL plus the release, worker,
HTTP, and bounded runtime configuration:

```text
UNCIV_V3_DATABASE_URL=postgres://unciv_runtime:...@127.0.0.1:5432/unciv_authoritative?sslmode=require
UNCIV_V3_BIND=127.0.0.1:3000
UNCIV_V3_METRICS_BIND=127.0.0.1:9464
UNCIV_V3_LOG=unciv_authoritative_server=info,warn
UNCIV_V3_TRUSTED_PROXY=loopback
UNCIV_V3_DB_POOL_MAX=10
UNCIV_V3_DB_POOL_MIN=1
UNCIV_V3_DB_ACQUIRE_TIMEOUT_MS=5000
UNCIV_V3_DB_IDLE_TIMEOUT_SECONDS=300
UNCIV_V3_DB_MAX_LIFETIME_SECONDS=1800
UNCIV_V3_DB_STATEMENT_TIMEOUT_MS=15000
UNCIV_V3_DB_LOCK_TIMEOUT_MS=5000
UNCIV_V3_MAX_ACTIVE_SESSIONS=8
```

Never put database credentials for different roles in one file. PostgreSQL must grant the
runtime role only `CONNECT`, schema `USAGE`, required table DML, and sequence
usage; it must not own the database/schema or receive `CREATE`. The migration
role owns the schema objects. Rotate each password independently and restart
only its consumer. The exact grants, TLS-only HBA, backup/restore/audit roles,
and credential-rotation gate are documented in
`authoritative-postgresql-19.md`.

`UNCIV_V3_TRUSTED_PROXY=loopback` is valid only with a loopback
`UNCIV_V3_BIND`; startup rejects any public bind in that mode. The API then
requires one unambiguous `X-Forwarded-For` address on authentication/account
security routes from the loopback peer. Forwarding headers from every
non-loopback peer are ignored. Caddy's protected default derives this header
from its direct remote address, while the qualified configuration removes
competing forwarding headers.

Account recovery and active-session policy are documented in
`authoritative-account-security.md`. Do not raise the session limit to work
around stolen or repeatedly evicted credentials.

## Release activation

Build and activate one verified release bundle as documented in
`authoritative-release-bundle.md`. Before starting the API:

```text
systemctl daemon-reload
systemctl start unciv-authoritative-migrate.service
systemctl restart unciv-authoritative-worker.service
systemctl restart unciv-authoritative-api.service
systemctl restart unciv-authoritative-proxy.service
curl --fail http://127.0.0.1:3000/healthz
curl --fail http://127.0.0.1:3000/readyz
```

`/healthz` is liveness only. `/readyz` returns HTTP 200 only while both
PostgreSQL and the authenticated private worker are reachable; otherwise it
returns HTTP 503 with component status and no private error detail. The reverse
proxy/load balancer must use `/readyz` for admission and must never publish the
worker listener.

See `authoritative-tls-proxy.md` for the public HTTPS/HSTS boundary. Do not
publish port 3000 or start the API with a wildcard bind.

For a local release rehearsal against an already migrated disposable database,
build the worker distribution and debug API, then run:

```text
authoritative-server/tests/run-api-readiness-smoke.ps1 -DatabaseUrl 'postgres://...'
```

The bounded smoke starts both real processes, requires liveness and dependency
readiness, kills the worker, requires a 503/unready response while PostgreSQL
remains ready, and always removes its temporary files and child processes.

If migration fails, preserve its journal output and do not start the new API.
If API startup reports a missing, extra, failed, or checksum-mismatched
migration, stop: do not grant migration privileges to the API role or bypass
the compatibility check. Restore/rehearse according to
`authoritative-postgresql-19.md`.
