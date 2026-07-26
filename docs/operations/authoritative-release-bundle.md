# Authoritative v3 release bundle

Production never assembles API-v3 components independently. One immutable,
content-addressed release directory contains the exact Rust server, Kotlin
worker JAR, supported client artifact, public OpenAPI contract, compatibility
contract, migrations, and approved ruleset manifest.

## Build

From one clean commit, build every artifact that will ship:

```text
./gradlew :server:authoritativeWorkerDist :desktop:dist
cargo build --release --manifest-path authoritative-server/Cargo.toml \
  --bin unciv-authoritative-server --bin unciv-v3-bundle
```

Copy the active ruleset version's `manifest.json` to a root-owned review
location, then create a new bundle directory:

```text
authoritative-server/target/release/unciv-v3-bundle create \
  /opt/unciv-authoritative/releases/<candidate> \
  authoritative-server/target/release/unciv-authoritative-server \
  server/build/libs/UncivAuthoritativeWorker.jar \
  desktop/build/libs/Unciv.jar \
  /opt/unciv-authoritative/rulesets/active/manifest.json
```

Creation is same-filesystem and fail-closed: it writes a private staging
directory, copies only bounded regular files, requires exactly migrations
`0001` through `0020`, validates the closed ruleset manifest, hashes every
artifact, writes a closed manifest, verifies the completed directory, and only
then renames it to the requested destination. Existing destinations are never
replaced.

The compatibility contract pins:

- public protocol 3;
- player projection 59 and spectator projection 1;
- private worker protocol 2;
- migration head 20;
- the sole PostgreSQL 19 Beta 2 image and digest.

Rust unit tests and Kotlin server tests independently compare their compiled
constants with `authoritative-server/release/compatibility.json`. A version
change on only one side fails CI.

## Verify and activate

Verify again after transfer:

```text
authoritative-server/target/release/unciv-v3-bundle verify \
  /opt/unciv-authoritative/releases/<candidate>
```

The verifier rejects missing, changed, extra, linked, special, oversized,
reordered, noncanonical, or unknown artifacts and recomputes the bundle ID.
Review the JSON report and atomically change the root-owned `current` symlink.

Configure both processes with the reported ID. Configure Rust with the
canonical bundle root and bundled worker path:

```text
UNCIV_V3_RELEASE_BUNDLE_ROOT=/opt/unciv-authoritative/releases/current
UNCIV_V3_RELEASE_BUNDLE_ID=<verified-bundle-id>
UNCIV_ENGINE_WORKER_JAR=/opt/unciv-authoritative/releases/current/worker/UncivAuthoritativeWorker.jar
```

The Kotlin worker requires `UNCIV_V3_RELEASE_BUNDLE_ID` and returns it through
the authenticated handshake. Before opening its listener, Rust verifies every
bundle artifact, its own executable path, the configured worker JAR path, the
compatibility contract, and the approved ruleset manifest. It then rejects a
worker bundle ID or ruleset catalog mismatch before binding the public API.

`UNCIV_V3_UNPACKAGED_DEV=1` is an explicit test/development escape hatch. It is
never valid in production configuration or deployment units.

## Rollback

Keep prior immutable bundle and ruleset versions. Stop the public API and
worker, atomically restore both matching `current` and ruleset `active` links,
restore their matching bundle ID, verify the bundle, and restart the worker
before the API. Do not combine an old server with a new worker or migration
contract. Database rollback still follows the separate schema policy; a bundle
rollback does not reverse applied migrations.
