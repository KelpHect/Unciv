const API_SERVICE: &str = include_str!("../systemd/unciv-authoritative-api.service");
const MIGRATION_SERVICE: &str = include_str!("../systemd/unciv-authoritative-migrate.service");
const PROXY_SERVICE: &str = include_str!("../systemd/unciv-authoritative-proxy.service");
const BACKUP_SERVICE: &str = include_str!("../systemd/unciv-authoritative-backup.service");
const BACKUP_TIMER: &str = include_str!("../systemd/unciv-authoritative-backup.timer");
const BASE_BACKUP: &str = include_str!("../postgresql/base-backup.sh");
const ARCHIVE_WAL: &str = include_str!("../postgresql/archive-wal.sh");
const PITR_CONFIG: &str = include_str!("../postgresql/authoritative-pitr.conf");
const PITR_SMOKE: &str = include_str!("run-postgres-pitr-smoke.ps1");
const CADDYFILE: &str = include_str!("../caddy/Caddyfile");
const TLS_SMOKE: &str = include_str!("run-tls-proxy-smoke.ps1");

#[test]
fn api_unit_is_bounded_private_and_depends_on_the_worker() {
    for required in [
        "User=unciv-api",
        "Group=unciv-api",
        "EnvironmentFile=/etc/unciv-authoritative/api/api.env",
        "Requires=unciv-authoritative-worker.service",
        "ExecStart=/opt/unciv-authoritative/releases/current/bin/unciv-authoritative-server",
        "CPUQuota=60%",
        "MemoryMax=192M",
        "MemorySwapMax=0",
        "NoNewPrivileges=yes",
        "ProtectSystem=strict",
        "IPAddressDeny=any",
        "IPAddressAllow=localhost",
    ] {
        assert!(
            API_SERVICE.contains(required),
            "missing API policy: {required}"
        );
    }
    assert!(!API_SERVICE.contains("UNCIV_V3_MIGRATION_DATABASE_URL"));
}

#[test]
fn migration_unit_has_a_separate_identity_and_one_shot_executable() {
    for required in [
        "Type=oneshot",
        "User=unciv-migrate",
        "Group=unciv-migrate",
        "EnvironmentFile=/etc/unciv-authoritative/migration/migration.env",
        "ExecStart=/opt/unciv-authoritative/releases/current/bin/unciv-v3-migrate",
        "MemoryMax=192M",
        "MemorySwapMax=0",
        "NoNewPrivileges=yes",
        "ProtectSystem=strict",
        "IPAddressDeny=any",
        "IPAddressAllow=localhost",
    ] {
        assert!(
            MIGRATION_SERVICE.contains(required),
            "missing migration policy: {required}"
        );
    }
    assert!(!MIGRATION_SERVICE.contains("UNCIV_V3_DATABASE_URL="));
}

#[test]
fn public_proxy_unit_is_bounded_and_owns_only_tls_ports() {
    for required in [
        "User=caddy",
        "EnvironmentFile=/etc/unciv-authoritative/proxy/proxy.env",
        "ExecStartPre=/usr/bin/caddy validate",
        "ExecStart=/usr/bin/caddy run",
        "StateDirectory=caddy",
        "MemoryMax=96M",
        "MemorySwapMax=0",
        "AmbientCapabilities=CAP_NET_BIND_SERVICE",
        "CapabilityBoundingSet=CAP_NET_BIND_SERVICE",
        "RestrictAddressFamilies=AF_UNIX AF_INET AF_INET6",
        "NoNewPrivileges=yes",
        "ProtectSystem=strict",
    ] {
        assert!(
            PROXY_SERVICE.contains(required),
            "missing proxy policy: {required}"
        );
    }
    assert!(!PROXY_SERVICE.contains("UNCIV_ENGINE_WORKER"));
    assert!(!PROXY_SERVICE.contains("UNCIV_V3_DATABASE"));
}

#[test]
fn caddy_boundary_enforces_hsts_readiness_and_one_forwarded_identity() {
    for required in [
        "Strict-Transport-Security \"max-age=31536000\"",
        "health_uri /readyz",
        "health_status 200",
        "header_up X-Forwarded-Proto \"https\"",
        "header_up -Forwarded",
        "header_up -X-Real-IP",
    ] {
        assert!(
            CADDYFILE.contains(required),
            "missing Caddy policy: {required}"
        );
    }
    assert!(!CADDYFILE.contains("tls_insecure_skip_verify"));
    assert!(!CADDYFILE.contains("trusted_proxies private_ranges"));
    assert!(!CADDYFILE.contains("header_up X-Forwarded-For"));
    assert!(
        TLS_SMOKE.contains(
            "caddy@sha256:5f5c8640aae01df9654968d946d8f1a56c497f1dd5c5cda4cf95ab7c14d58648"
        )
    );
}

#[test]
fn backup_unit_is_scheduled_bounded_and_separately_credentialed() {
    for required in [
        "Type=oneshot",
        "User=unciv-backup",
        "Group=unciv-backup",
        "EnvironmentFile=/etc/unciv-authoritative/backup/backup.env",
        "ExecStart=/bin/sh /opt/unciv-authoritative/releases/current/postgresql/base-backup.sh",
        "ReadWritePaths=/var/backups/unciv-authoritative/base",
        "MemoryMax=192M",
        "MemorySwapMax=0",
        "NoNewPrivileges=yes",
        "ProtectSystem=strict",
        "IPAddressDeny=any",
        "IPAddressAllow=localhost",
    ] {
        assert!(
            BACKUP_SERVICE.contains(required),
            "missing backup policy: {required}"
        );
    }
    for required in [
        "OnCalendar=*-*-* 02:17:00 UTC",
        "RandomizedDelaySec=30m",
        "Persistent=yes",
        "Unit=unciv-authoritative-backup.service",
    ] {
        assert!(
            BACKUP_TIMER.contains(required),
            "missing backup schedule: {required}"
        );
    }
}

#[test]
fn physical_backup_and_wal_archive_are_verified_and_fail_closed() {
    for required in [
        "pg_basebackup",
        "--wal-method=stream",
        "--manifest-checksums=SHA256",
        "--no-password",
        "pg_verifybackup --exit-on-error",
    ] {
        assert!(
            BASE_BACKUP.contains(required),
            "missing physical backup invariant: {required}"
        );
    }
    for required in [
        "cmp -s",
        "refusing to replace a differing or unsafe archived WAL file",
        "mktemp",
        "chmod 0600",
    ] {
        assert!(
            ARCHIVE_WAL.contains(required),
            "missing WAL archive invariant: {required}"
        );
    }
    for required in [
        "wal_level = replica",
        "archive_mode = on",
        "archive_timeout = '60s'",
        "archive_command =",
    ] {
        assert!(
            PITR_CONFIG.contains(required),
            "missing PITR configuration: {required}"
        );
    }
    for required in [
        "postgres:19beta2-alpine@sha256:",
        "pg_create_restore_point('unciv_v3_backup_qualification')",
        "qualification-recovery.conf",
        "pg_is_in_recovery()",
        "restored_backup_fixture_preserves_every_required_invariant",
        "--bin', 'unciv-v3-reconcile",
        "included_marker = 1",
        "excluded_marker = 0",
    ] {
        assert!(
            PITR_SMOKE.contains(required),
            "missing restore-drill invariant: {required}"
        );
    }
}
