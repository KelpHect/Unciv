# Authoritative multiplayer licensing boundary

Reviewed 2026-07-28. This is an engineering provenance record, not legal
advice.

## Repository license

Unciv and the authoritative multiplayer v3 additions in this repository remain
under the repository's Mozilla Public License 2.0 (`LICENSE`). Every production
release bundle must contain the exact repository license at `legal/LICENSE`;
the bundle manifest hashes it and verification rejects removal or alteration.
Dependency notices and licenses remain represented in the mandatory SPDX 2.3
binary SBOM.

## `runciv` boundary

`hopfenspace/runciv` currently identifies itself and its `LICENSE` as
GNU Affero General Public License v3.0. It was consulted only as a public
API-v2/Rust architectural reference. No `runciv` source, migration, static
asset, dependency, vendored subtree, generated artifact, or binary is included
in this repository or its release bundle.

The only production-source reference is a 2023 API-v2 documentation link in
`ApiVersion.kt`, predating authoritative multiplayer v3. The v3 ADR separately
records the no-copy rule. Current Cargo and Gradle dependency declarations and
locks contain no `runciv` or `hopfenspace` dependency.

Authoritative v3 was implemented from Unciv's existing Kotlin game engine, the
repository's own API-v2 concepts, original protocol/persistence design, and
public standards. Similar generic concepts such as PostgreSQL, HTTP, UUID
idempotency, migrations, and systemd are not evidence that source was copied.

## Future contribution rule

- Do not copy, translate, adapt, vendor, or mechanically derive code, SQL,
  schemas, tests, text, or assets from `runciv` into this MPL repository.
- A conceptual comparison must cite the external project and produce an
  independently written implementation based on this repository's
  requirements.
- Any proposed source reuse requires a written provenance record, copyright
  holder permission or a reviewed compatible licensing basis, preserved
  notices, and maintainer/legal approval before the code enters a branch.
- Review new Git/Cargo/Gradle dependencies and generated bundle inputs for
  AGPL-family material. A dependency or artifact with unclear provenance fails
  the release gate.

## Audit evidence

The 2026-07-28 audit checked the repository source (excluding generated
build/target trees), Git history references, Cargo manifests/lock, Gradle
catalog/build declarations, release-bundle inputs, and the upstream repository
license. It found only the pre-existing API-v2 documentation link, the v3 ADR
warning, the executable checklist item, and this record.
