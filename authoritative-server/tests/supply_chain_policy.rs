const WORKFLOW: &str = include_str!("../../.github/workflows/authoritativeV3SupplyChain.yml");
const RUNBOOK: &str = include_str!("../../docs/operations/authoritative-supply-chain-security.md");
const GITLEAKS_IGNORE: &str = include_str!("../../.gitleaksignore");

#[test]
fn every_supply_chain_action_is_pinned_to_an_immutable_commit() {
    let mut action_count = 0;
    for line in WORKFLOW.lines() {
        let Some(reference) = line.trim().strip_prefix("uses: ") else {
            continue;
        };
        action_count += 1;
        let reference = reference.split_whitespace().next().unwrap();
        let (_, revision) = reference
            .rsplit_once('@')
            .expect("every action reference has a revision");
        assert_eq!(
            revision.len(),
            40,
            "action is not pinned to a full commit: {reference}"
        );
        assert!(
            revision.bytes().all(|byte| byte.is_ascii_hexdigit()),
            "action pin is not hexadecimal: {reference}"
        );
    }
    assert_eq!(action_count, 15);
}

#[test]
fn pull_requests_cannot_receive_release_or_repository_write_authority() {
    assert!(WORKFLOW.contains("permissions:\n  contents: read"));
    assert!(WORKFLOW.contains("github.event_name == 'schedule' ||"));
    assert!(WORKFLOW.contains("github.ref == 'refs/heads/master' ||"));
    assert!(WORKFLOW.contains("github.ref == 'refs/heads/main'"));
    assert!(WORKFLOW.contains("if: startsWith(github.ref, 'refs/tags/authoritative-v3-')"));
    assert!(!WORKFLOW.contains("pull_request_target"));
    assert!(!WORKFLOW.contains("secrets."));
    assert!(!WORKFLOW.contains("persist-credentials: true"));
}

#[test]
fn workflow_covers_vulnerabilities_secrets_sbom_and_signed_tag_evidence() {
    for required in [
        "fail-on-severity: moderate",
        "fetch-depth: 0",
        "GITLEAKS_ENABLE_COMMENTS: 'false'",
        "working-directory: authoritative-server",
        "format: spdx-json",
        "syft-version: v1.49.0",
        "dependency-submission@",
        "id-token: write",
        "attestations: write",
        "subject-path: authoritative-v3-source.tar.gz",
        "sbom-path: authoritative-v3-release.spdx.json",
    ] {
        assert!(
            WORKFLOW.contains(required),
            "missing supply-chain control: {required}"
        );
    }
}

#[test]
fn runbook_defines_failure_response_and_binary_release_boundary() {
    for required in [
        "Moderate-or-higher",
        "rotate it before rerunning",
        "must not be waived",
        "authoritative-v3-*",
        "does not attest production binaries",
        "content-addressed release bundle",
        "two-person review",
    ] {
        assert!(
            RUNBOOK.contains(required),
            "missing supply-chain operating rule: {required}"
        );
    }
}

#[test]
fn secret_scan_exceptions_are_fingerprint_specific_and_reviewed() {
    let exceptions = GITLEAKS_IGNORE
        .lines()
        .filter(|line| !line.is_empty() && !line.starts_with('#'))
        .collect::<Vec<_>>();
    assert_eq!(exceptions.len(), 5);
    for exception in exceptions {
        let mut fields = exception.split(':');
        let commit = fields.next().unwrap();
        assert_eq!(commit.len(), 40);
        assert!(commit.bytes().all(|byte| byte.is_ascii_hexdigit()));
        assert!(fields.next().is_some_and(|path| !path.is_empty()));
        assert!(fields.next().is_some_and(|rule| !rule.is_empty()));
        assert!(
            fields
                .next()
                .is_some_and(|line| line.parse::<u64>().is_ok())
        );
        assert!(fields.next().is_none());
    }
    assert!(!GITLEAKS_IGNORE.contains("regex"));
    assert!(!GITLEAKS_IGNORE.contains("stopwords"));
}
