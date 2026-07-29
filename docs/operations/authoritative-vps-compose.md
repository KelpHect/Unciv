# Authoritative v3 single-VPS Compose

`authoritative-server/compose.vps.yaml` runs the digest-pinned PostgreSQL 19
Beta 2 target, one migration job, the Kotlin rules worker, and the Rust API.
The API and worker share one container network namespace, so the authenticated
worker protocol remains reachable only on loopback. PostgreSQL has no published
port. The Compose services mount one attested Linux release bundle read-only;
build and verify it using `authoritative-release-bundle.md` first.

The one-shot ruleset service acquires and atomically activates the reviewed
ruleset policy before the worker starts. Set `UNCIV_V3_SOURCE_ASSETS` to the
reviewed vanilla asset tree, `UNCIV_V3_RULESET_POLICY` to the closed acquisition
policy, and `UNCIV_V3_RULESETS_ROOT` to the persistent root-owned ruleset store.
The worker mounts that store read-only and starts from `rulesets/active`.

Copy `.env.vps.example` to an operator-owned `.env.vps`, replace every value,
and keep that file outside version control. Then:

```sh
docker compose --env-file .env.vps -f compose.vps.yaml config
docker compose --env-file .env.vps -f compose.vps.yaml up -d
docker compose --env-file .env.vps -f compose.vps.yaml ps
curl --fail http://127.0.0.1:3000/readyz
```

For a raw-IP pilot, set `UNCIV_V3_PUBLIC_BIND=0.0.0.0`, open only the chosen
API port in the VPS firewall, and enter `http://IP:PORT` in Unciv's multiplayer
server setting. This mode exposes account tokens and traffic to the network and
is strictly for short-lived testing.

For a real match, leave the API bound to `127.0.0.1`, put the existing hardened
reverse-proxy/TLS service in front, and enter `https://unciv.example.com`.
Cloudflare DNS can point that subdomain at the VPS; TLS still terminates at the
VPS reverse proxy. Never expose port 43170 or PostgreSQL.

After saving the server address, use the production multiplayer screen to
register or sign in. It lists only authoritative V3 lobbies and account games:
there are no legacy IDs or file-server credentials. Creating a match opens the
normal bounded game-setup screen plus match name, human slots, and optional
password. Every joining human chooses an available faction and marks only
themselves ready; the owner starts at exact capacity after everyone is ready.
Human turn time is unlimited.

Back up the named PostgreSQL volume using the documented logical plus WAL/PITR
procedure. An image update is not accepted until the migration job succeeds,
`/readyz` is healthy, and the packaged full-match preflight passes against the
same bundle ID.
