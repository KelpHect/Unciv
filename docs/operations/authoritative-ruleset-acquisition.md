# Authoritative ruleset acquisition

`unciv-v3-rulesets` is the only supported path from a remote mod archive to an
API-v3 worker. It is an offline operator tool, not an HTTP endpoint. Clients
cannot supply a URL, archive, path, hash, host, or ruleset byte.

## Build and install

Build the exact Rust control-plane commit and packaged worker together:

```text
cargo build --release --manifest-path authoritative-server/Cargo.toml \
  --bin unciv-v3-rulesets
./gradlew :server:authoritativeWorkerDist
install -o root -g root -m 0555 \
  authoritative-server/target/release/unciv-v3-rulesets \
  /usr/local/sbin/unciv-v3-rulesets
```

The tool requires PostgreSQL migrations through
`0020_ruleset_asset_versions.sql`. Run it as a dedicated deployment identity
while the public service is not accepting new game creation.

## Closed allowlist policy

First inspect the exact packaged worker and trusted built-in assets:

```text
cd /opt/unciv-authoritative/bootstrap-assets
java -Djava.awt.headless=true -Xmx384m \
  -jar /opt/unciv-authoritative/worker/UncivAuthoritativeWorker.jar \
  --print-catalog
```

Create a root-owned mode-`0400` policy. Every field is closed and unknown fields
are rejected:

```json
{
  "schema_version": 1,
  "engine_build": "4.21.1 (Build 1236)",
  "base_ruleset": {
    "name": "Civ V - Vanilla",
    "sha256": "<catalog lowercase SHA-256>"
  },
  "mods": [
    {
      "ruleset": {
        "name": "Approved Mod",
        "sha256": "<exact extracted jsons tree SHA-256>"
      },
      "url": "https://mods.example.org/releases/approved-mod.zip",
      "allowed_host": "mods.example.org",
      "archive_sha256": "<exact archive lowercase SHA-256>",
      "archive_root": "approved-mod-release",
      "bearer_token_env": "UNCIV_MOD_DOWNLOAD_TOKEN"
    }
  ]
}
```

`archive_root` is optional and is one plain directory name. If absent, the ZIP
must contain `jsons/` at its root. `bearer_token_env` is optional for a public
artifact; when present, the named environment variable is required and sent as
a bearer token. The token is never accepted on the command line, persisted, or
printed.

The downloader permits HTTPS only, disables redirects and environment proxies,
requires the URL host to equal `allowed_host`, uses bounded connection/total
timeouts, requests identity encoding, streams to a new staging file, caps the
archive at 64 MiB, and verifies its SHA-256 before parsing.

ZIP inspection rejects absolute/traversing/NUL/backslash paths, symbolic links,
special files, case-folded duplicate paths, unsupported compression, more than
16,384 entries, files over 16 MiB, over 64 MiB compressed, or over 512 MiB
uncompressed. Only the selected `jsons/` subtree is staged.

## Acquire and activate

The default is stage-and-register without activation:

```text
export UNCIV_V3_DATABASE_URL='postgres://...'
export UNCIV_MOD_DOWNLOAD_TOKEN='...'
/usr/local/sbin/unciv-v3-rulesets acquire \
  /etc/unciv-authoritative/ruleset-policy.json \
  /opt/unciv-authoritative/bootstrap-assets \
  /opt/unciv-authoritative/worker/UncivAuthoritativeWorker.jar \
  /opt/unciv-authoritative/rulesets
```

The tool copies trusted built-ins into a same-filesystem temporary directory,
downloads and extracts each exact mod, verifies component hashes, and launches
the packaged worker with `--validate-manifest`. The worker parses the staged
rules, verifies the engine/content catalog, builds the combined ruleset, and
rejects semantic errors. Only then does Rust rename the complete directory to
`versions/<manifest-hash>` and idempotently register it in PostgreSQL.

After reviewing the JSON report, repeat with `--activate`. On Linux this creates
a temporary relative symlink and atomically renames it to `active`:

```text
/usr/local/sbin/unciv-v3-rulesets acquire \
  /etc/unciv-authoritative/ruleset-policy.json \
  /opt/unciv-authoritative/bootstrap-assets \
  /opt/unciv-authoritative/worker/UncivAuthoritativeWorker.jar \
  /opt/unciv-authoritative/rulesets --activate
systemctl restart unciv-authoritative-worker.service
```

New game creation requires a registered asset version and shares a PostgreSQL
advisory lock with garbage collection. It cannot race removal into a game whose
rules are unavailable.

## Rollback and garbage collection

Rollback accepts only an existing lowercase 64-character version ID and
atomically changes the `active` link:

```text
/usr/local/sbin/unciv-v3-rulesets rollback \
  /opt/unciv-authoritative/rulesets <version-id>
systemctl restart unciv-authoritative-worker.service
```

Garbage collection never removes the active version or a version whose manifest
is referenced by a game. It unregisters an unreferenced version under the same
database lock used by creation, renames it out of `versions/`, then deletes it:

```text
export UNCIV_V3_DATABASE_URL='postgres://...'
/usr/local/sbin/unciv-v3-rulesets gc \
  /opt/unciv-authoritative/rulesets
```

Keep database backups and the original allowlist policies. A removed version
can be reacquired only when the exact archive and all expected hashes still
match; the tool never substitutes newer content.
