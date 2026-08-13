const INCIDENT_RUNBOOK: &str =
    include_str!("../../docs/operations/authoritative-incident-response.md");
const REPAIR_RUNBOOK: &str =
    include_str!("../../docs/operations/authoritative-reconciliation-repair.md");
const OUTBOX_RUNBOOK: &str =
    include_str!("../../docs/operations/authoritative-outbox-operations.md");
const POSTGRES_RUNBOOK: &str = include_str!("../../docs/operations/authoritative-postgresql-19.md");
const WORKER_RUNBOOK: &str = include_str!("../../docs/operations/authoritative-worker-systemd.md");
const AUDIT_EXPORT_RUNBOOK: &str =
    include_str!("../../docs/operations/authoritative-security-audit-export.md");
const API_BOOTSTRAP: &str = include_str!("../src/api/bootstrap.rs");

#[test]
fn incident_runbook_covers_every_required_incident_class() {
    for heading in [
        "## Private worker failure or timeout",
        "## Corrupt game quarantine and recovery",
        "## PostgreSQL primary failure and failover",
        "## Outbox backlog or poison event",
        "## Database credential compromise",
        "## Authentication abuse or service denial",
        "## Break-glass access",
    ] {
        assert!(
            INCIDENT_RUNBOOK.contains(heading),
            "missing incident procedure: {heading}"
        );
    }
}

#[test]
fn incident_actions_are_dry_run_first_and_preserve_authority() {
    for required in [
        "unciv-v3-reconcile",
        "unciv-v3-repair <game-uuid>",
        "unciv-v3-repair <game-uuid> --apply",
        "unciv-v3-recover <game-uuid> --max-tail <reviewed-bound>",
        "unciv-v3-recover <game-uuid> --max-tail <reviewed-bound> --apply",
        "unciv-v3-outbox requeue <outbox-id>",
        "unciv-v3-outbox requeue <outbox-id> --apply",
        "same command ID",
        "two writable primaries",
        "Never accept a client save as repair input",
        "cannot clear quarantine",
    ] {
        assert!(
            INCIDENT_RUNBOOK.contains(required),
            "missing authority-preserving response: {required}"
        );
    }
}

#[test]
fn incident_runbook_links_to_the_detailed_verified_procedures() {
    for link in [
        "authoritative-worker-systemd.md",
        "authoritative-reconciliation-repair.md",
        "authoritative-postgresql-19.md",
        "authoritative-outbox-operations.md",
    ] {
        assert!(
            INCIDENT_RUNBOOK.contains(link),
            "missing detailed runbook link: {link}"
        );
    }
    for detailed in [
        REPAIR_RUNBOOK,
        OUTBOX_RUNBOOK,
        POSTGRES_RUNBOOK,
        WORKER_RUNBOOK,
    ] {
        assert!(!detailed.trim().is_empty());
    }
}

#[test]
fn break_glass_is_local_reviewed_audited_and_time_bounded() {
    for required in [
        "There is no public API-v3 operator endpoint",
        "two-person approval",
        "time-bounded host privilege",
        "least capable database role",
        "Keep the public API stopped",
        "unciv_restore",
        "unciv_migrate",
        "unciv_audit",
        "Revoke temporary access",
    ] {
        assert!(
            INCIDENT_RUNBOOK.contains(required),
            "missing break-glass boundary: {required}"
        );
    }
}

#[test]
fn incident_records_exclude_credentials_and_private_game_state() {
    for prohibited_evidence in [
        "database URLs",
        "bearer tokens",
        "password hashes",
        "canonical snapshots",
        "private outbox payloads",
    ] {
        assert!(
            INCIDENT_RUNBOOK.contains(prohibited_evidence),
            "missing redaction rule: {prohibited_evidence}"
        );
    }
}

#[test]
fn operator_tools_are_absent_from_the_public_router() {
    for forbidden_route in [
        "unciv-v3-repair",
        "unciv-v3-recover",
        "unciv-v3-reconcile",
        "unciv-v3-outbox",
        "unciv-v3-export-security-audit",
        "unciv-v3-storage",
        "/api/v3/operator",
    ] {
        assert!(
            !API_BOOTSTRAP.contains(forbidden_route),
            "operator capability leaked into public router: {forbidden_route}"
        );
    }
}

#[test]
fn audit_export_policy_is_local_append_only_bounded_and_owned() {
    for required in [
        "UNCIV_V3_AUDIT_DATABASE_URL",
        "must not already exist",
        "at most 1,000 rows",
        "final chain hash",
        "at least 400 days",
        "write-once or object-lock storage",
        "cannot update, delete, or truncate",
        "security incident commander",
        "separate reviewer",
    ] {
        assert!(
            AUDIT_EXPORT_RUNBOOK.contains(required),
            "missing audit export control: {required}"
        );
    }
}
