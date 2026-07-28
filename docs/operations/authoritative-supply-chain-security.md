# Authoritative v3 supply-chain security

The `Authoritative v3 supply-chain security` workflow is an independent,
least-privilege gate. Every third-party action is pinned to one reviewed
40-character commit; version comments are informational and cannot move the
executed code.

Pull requests receive read-only repository access. They run dependency review,
the complete-history secret scan, RustSec audit, and source SBOM generation.
Moderate-or-higher dependency additions fail review. A discovered secret is an
incident: revoke or rotate it before rerunning, investigate its complete Git
history exposure, and never suppress the finding merely to make CI green.
RustSec failures must not be waived without a documented applicability review,
expiry, owner, and compensating control.

`.gitleaksignore` is a closed exception registry, not a pattern allowlist. Each
entry identifies one exact commit, path, rule, and line fingerprint. The five
reviewed entries are a documented example placeholder, two historical public
Wikimedia query parameters, one removed historical PAT disclosure that must be
treated as permanently compromised and prohibited, and an intentionally public test
certificate fixture. Never use a leaked credential to test whether it remains
active; only its original owner can confirm revocation. New findings fail until
the credential is rotated and the exact
historical evidence is reviewed; broad path, regex, rule, or entropy
suppressions are forbidden.

Non-pull-request runs submit the resolved Gradle graph to GitHub's dependency
service. This job alone receives `contents: write`; scan jobs remain read-only.
The daily schedule catches newly published advisories even when dependencies do
not change.

## SBOM and attestation

Every run produces an SPDX JSON source SBOM. Tags matching
`authoritative-v3-*` build every production input from the tagged commit using
Java 21 and Rust 1.97.0 with the locked dependency graph. The packaged worker
derives the exact vanilla engine/ruleset content identity and validates that
manifest. Checksum-pinned Syft 1.49.0 inventories the reviewed Rust binaries,
worker JAR, desktop JAR, and ruleset manifest. The release CLI validates that
SPDX document, creates the content-addressed release bundle, re-verifies it,
and archives it with normalized order, timestamp, and ownership.

The tag job uses two GitHub OIDC attestations: one signs build provenance for
`authoritative-v3-linux-x86_64.tar.gz`, and one binds its embedded
`evidence/sbom.spdx.json` under the SPDX 2.3 predicate. No long-lived signing
key enters the workflow, and pull requests cannot reach the attestation
permissions. The retained evidence contains the archive, external SHA-256,
bundle manifest, and exact embedded SBOM.

Download all four retained files without renaming them. Verify the external
digest, GitHub provenance, SPDX predicate, extracted bundle ID, and internal
artifact hashes before deployment:

```text
sha256sum --check authoritative-v3-linux-x86_64.tar.gz.sha256
gh attestation verify authoritative-v3-linux-x86_64.tar.gz \
  --repo KelpHect/Unciv
gh attestation verify authoritative-v3-linux-x86_64.tar.gz \
  --repo KelpHect/Unciv \
  --predicate-type https://spdx.dev/Document/v2.3
tar --extract --gzip --file authoritative-v3-linux-x86_64.tar.gz
bundle/bin/unciv-v3-bundle verify bundle
cmp bundle/bundle-manifest.json \
  release-output/bundle/bundle-manifest.json
cmp bundle/evidence/sbom.spdx.json \
  release-output/bundle/evidence/sbom.spdx.json
```

Any tag whose complete build or attestation job did not pass is not a release.
Source-only evidence, a locally assembled bundle, or an archive whose external
or internal digest differs is not permission to deploy.

The first hosted qualification was
`authoritative-v3-0.1.0-beta.2` at commit
`f584c22d0aa9d1d60e9156f866390cce9e497335`, GitHub Actions run
`30383681835`. The retained archive SHA-256 is
`7a97b727b14c7f6dde3d24b577756b76c594e941f5040c018265c622923cb97f`.
An independent download matched the retained checksum and separately verified
the SLSA provenance and SPDX 2.3 predicates against `KelpHect/Unciv`. This
receipt qualifies that exact immutable tag only; later tags must repeat every
check.

For a release, require two-person review of the exact tag, workflow action pin
changes, dependency findings, SPDX output, GitHub attestation verification,
bundle ID, and all production qualification receipts. Preserve the SBOM,
attestation, workflow URL, commit, tag, and bundle manifest with the release
record. Any mismatch, unavailable attestation, unreviewed pin change, or
unresolved vulnerability stops the release.
