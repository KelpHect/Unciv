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
`authoritative-v3-*` additionally create an exact `git archive`, regenerate its
SBOM, and use GitHub OIDC plus the repository attestation service to sign
provenance and bind the SBOM to that archive. No long-lived signing key enters
the workflow, and pull requests cannot reach the attestation permissions.

This source attestation does not attest production binaries. A production
release still requires the content-addressed release bundle, its complete
artifact hashes, compatibility contract, migration set, worker/client pairing,
and the release qualification in `authoritative-release-bundle.md`. Until the
production packaging lane publishes and attests that complete bundle, source
attestation is supporting evidence rather than permission to deploy.

For a release, require two-person review of the exact tag, workflow action pin
changes, dependency findings, SPDX output, GitHub attestation verification,
bundle ID, and all production qualification receipts. Preserve the SBOM,
attestation, workflow URL, commit, tag, and bundle manifest with the release
record. Any mismatch, unavailable attestation, unreviewed pin change, or
unresolved vulnerability stops the release.
