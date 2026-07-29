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
  --bin unciv-authoritative-server --bin unciv-v3-migrate \
  --bin unciv-v3-export-security-audit --bin unciv-v3-bundle \
  --bin unciv-v3-rulesets
```

Copy the active ruleset version's `manifest.json` to a root-owned review
location. Stage the exact built inputs in an otherwise empty review directory
and generate their SPDX 2.3 SBOM with the release-pinned Syft 1.49.0:

```text
syft scan dir:/opt/unciv-authoritative/review/<candidate> \
  --source-name unciv-authoritative-v3-release-bundle \
  --output spdx-json=/opt/unciv-authoritative/review/<candidate>.spdx.json
authoritative-server/target/release/unciv-v3-bundle verify-sbom \
  /opt/unciv-authoritative/review/<candidate>.spdx.json
```

Review the input tree and SBOM, then create a new bundle directory:

```text
authoritative-server/target/release/unciv-v3-bundle create \
  /opt/unciv-authoritative/releases/<candidate> \
  authoritative-server/target/release/unciv-authoritative-server \
  authoritative-server/target/release/unciv-v3-migrate \
  authoritative-server/target/release/unciv-v3-export-security-audit \
  authoritative-server/target/release/unciv-v3-bundle \
  authoritative-server/target/release/unciv-v3-rulesets \
  server/build/libs/UncivAuthoritativeWorker.jar \
  desktop/build/libs/Unciv.jar \
  /opt/unciv-authoritative/rulesets/active/manifest.json \
  /opt/unciv-authoritative/review/<candidate>.spdx.json
```

Creation is same-filesystem and fail-closed: it writes a private staging
directory, copies only bounded regular files, requires exactly migrations
`0001` through `0030`, validates the closed ruleset manifest, copies the
repository MPL-2.0 license to `legal/LICENSE`, hashes every
artifact, requires a bounded SPDX 2.3 SBOM named for this release and with
at least one inventoried package, rejects duplicate package IDs and any
dangling described-package reference, writes a closed manifest, verifies
the completed directory, and only then renames it to the requested
destination. The SBOM is copied to `evidence/sbom.spdx.json`, covered by the
bundle ID, and cannot be removed or replaced without verification failing.
The bundle carries its own `bin/unciv-v3-bundle` verifier. On Unix, every
bundled Rust executable is created with exact read/execute mode `0555`; mode
drift also fails verification. Existing destinations are never replaced.

The bundled `unciv-v3-migrate` executable is the only normal schema writer.
Run it with the separately protected migration role before activating the API.
The API executable uses its runtime role only and fails closed if the applied
version set or any migration checksum differs from the bundle.

The compatibility contract pins:

- public protocol 4;
- player projection 60 and spectator projection 2;
- private worker protocol 3;
- migration head 30;
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

## Hosted qualification

Tag `authoritative-v3-0.1.0-beta.2.4` at commit `79808f736` passed hosted run
`30388093014`. The workflow built every input on Linux, created and verified
the closed bundle, then ran `authoritative-server/tests/run-linux-production-smoke.sh`.
That smoke used the exact PostgreSQL 19 Beta 2 digest and the five production
roles, migrated with the migration identity, booted the bundled worker and API,
created an account, proved worker loss changes readiness to HTTP 503, and proved
the bundled worker can restart and restore readiness. Only after the smoke
passed did GitHub issue the provenance and SPDX attestations.

The retained archive SHA-256 is
`c16f6d40bffe0c3424fa2f8b15b64287bb19aeef4413196ac472bedd89c38962`.
Operators must verify both the SLSA provenance and
`https://spdx.dev/Document/v2.3` predicate before accepting it.

## Rollback

Keep prior immutable bundle and ruleset versions. Stop the public API and
worker, atomically restore both matching `current` and ruleset `active` links,
restore their matching bundle ID, verify the bundle, and restart the worker
before the API. Do not combine an old server with a new worker or migration
contract. Database rollback still follows the separate schema policy; a bundle
rollback does not reverse applied migrations.
