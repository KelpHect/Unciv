const API_SERVICE: &str = include_str!("../systemd/unciv-authoritative-api.service");
const MIGRATION_SERVICE: &str = include_str!("../systemd/unciv-authoritative-migrate.service");
const PROXY_SERVICE: &str = include_str!("../systemd/unciv-authoritative-proxy.service");
const BACKUP_SERVICE: &str = include_str!("../systemd/unciv-authoritative-backup.service");
const BACKUP_TIMER: &str = include_str!("../systemd/unciv-authoritative-backup.timer");
const BASE_BACKUP: &str = include_str!("../postgresql/base-backup.sh");
const ARCHIVE_WAL: &str = include_str!("../postgresql/archive-wal.sh");
const PITR_CONFIG: &str = include_str!("../postgresql/authoritative-pitr.conf");
const PITR_SMOKE: &str = include_str!("run-postgres-pitr-smoke.ps1");
const POSTGRES_SERVICE: &str = include_str!("../systemd/unciv-authoritative-postgres.service");
const POSTGRES_COMPOSE: &str = include_str!("../postgresql/compose.production.yaml");
const POSTGRES_CONFIG: &str = include_str!("../postgresql/production-postgresql.conf");
const POSTGRES_TLS: &str = include_str!("../postgresql/production-tls.conf");
const POSTGRES_HBA: &str = include_str!("../postgresql/production-pg_hba.conf");
const POSTGRES_ROLES: &str = include_str!("../postgresql/bootstrap-roles.sql");
const POSTGRES_ROTATION: &str = include_str!("../postgresql/rotate-role-password.sql");
const POSTGRES_SECURITY_SMOKE: &str = include_str!("run-postgres-security-smoke.ps1");
const CAPACITY_SERVICE: &str = include_str!("../systemd/unciv-authoritative-capacity.service");
const CAPACITY_TIMER: &str = include_str!("../systemd/unciv-authoritative-capacity.timer");
const CAPACITY_CHECK: &str = include_str!("../postgresql/check-capacity.sh");
const DISK_FULL_SMOKE: &str = include_str!("run-postgres-disk-full-smoke.ps1");
const CADDYFILE: &str = include_str!("../caddy/Caddyfile");
const TLS_SMOKE: &str = include_str!("run-tls-proxy-smoke.ps1");
const LEGACY_SERVER_SOURCE: &str =
    include_str!("../../server/src/com/unciv/app/server/UncivServer.kt");
const LEGACY_ISOLATION_TEST: &str = include_str!("legacy_v3_isolation.rs");
const LEGACY_ISOLATION_RUNNER: &str = include_str!("run-legacy-v3-isolation.ps1");

#[test]
fn api_unit_is_bounded_private_and_depends_on_the_worker() {
    for required in [
        "User=unciv-api",
        "Group=unciv-api",
        "EnvironmentFile=/etc/unciv-authoritative/api/api.env",
        "Requires=unciv-authoritative-postgres.service unciv-authoritative-worker.service",
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
fn legacy_file_service_has_no_v3_runtime_or_storage_path() {
    for production_surface in [API_SERVICE, MIGRATION_SERVICE, PROXY_SERVICE, CADDYFILE] {
        for forbidden in ["UncivServer.jar", "/files/"] {
            assert!(
                !production_surface.contains(forbidden),
                "v3 production surface exposes legacy endpoint {forbidden}"
            );
        }
    }
    for forbidden in ["UNCIV_V3_", "UNCIV_ENGINE_WORKER_", "PostgreSQL", "/api/v3"] {
        assert!(
            !LEGACY_SERVER_SOURCE.contains(forbidden),
            "legacy server gained v3 authority path {forbidden}"
        );
    }
    for removed_secret in [
        ".env_remove(\"UNCIV_V3_DATABASE_URL\")",
        ".env_remove(\"UNCIV_V3_MIGRATION_DATABASE_URL\")",
        ".env_remove(\"UNCIV_ENGINE_WORKER_ADDR\")",
        ".env_remove(\"UNCIV_ENGINE_WORKER_SECRET\")",
    ] {
        assert!(LEGACY_ISOLATION_TEST.contains(removed_secret));
    }
    assert!(LEGACY_ISOLATION_RUNNER.contains(
        "postgres:19beta2-alpine@sha256:bc62313e826eb44d5f608425b7665962b72820e686da017799e906604bfeb8a5"
    ));
}

#[test]
fn migration_unit_has_a_separate_identity_and_one_shot_executable() {
    for required in [
        "Type=oneshot",
        "User=unciv-migrate",
        "Group=unciv-migrate",
        "EnvironmentFile=/etc/unciv-authoritative/migration/migration.env",
        "Requires=unciv-authoritative-postgres.service",
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
        "Requires=unciv-authoritative-postgres.service",
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

#[test]
fn postgres_service_is_digest_pinned_private_bounded_and_tls_only() {
    for required in [
        "Requires=docker.service",
        "Before=unciv-authoritative-migrate.service unciv-authoritative-api.service",
        "docker compose --file compose.yaml up --detach --wait postgres",
        "NoNewPrivileges=yes",
        "ProtectSystem=strict",
        "ReadWritePaths=/run/docker.sock",
    ] {
        assert!(
            POSTGRES_SERVICE.contains(required),
            "missing PostgreSQL unit policy: {required}"
        );
    }
    for required in [
        "postgres:19beta2-alpine@sha256:bc62313e826eb44d5f608425b7665962b72820e686da017799e906604bfeb8a5",
        "network_mode: host",
        "POSTGRES_PASSWORD_FILE:",
        "cpus: 0.40",
        "mem_limit: 384m",
        "pids_limit: 128",
        "no-new-privileges:true",
        "cap_drop:",
    ] {
        assert!(
            POSTGRES_COMPOSE.contains(required),
            "missing PostgreSQL container policy: {required}"
        );
    }
    for required in [
        "data_directory = '/var/lib/postgresql/data'",
        "max_connections = 64",
        "shared_buffers = '192MB'",
        "io_method = worker",
        "include = '/etc/unciv-authoritative/postgres/tls.conf'",
        "include = '/etc/unciv-authoritative/postgres/pitr.conf'",
    ] {
        assert!(
            POSTGRES_CONFIG.contains(required),
            "missing PostgreSQL runtime policy: {required}"
        );
    }
    for required in [
        "listen_addresses = '127.0.0.1,::1'",
        "ssl = on",
        "ssl_min_protocol_version = 'TLSv1.2'",
        "password_encryption = 'scram-sha-256'",
    ] {
        assert!(
            POSTGRES_TLS.contains(required),
            "missing PostgreSQL TLS policy: {required}"
        );
    }
    assert!(POSTGRES_HBA.contains("hostnossl all"));
    for role in [
        "unciv_runtime",
        "unciv_migrate",
        "unciv_backup",
        "unciv_audit",
    ] {
        assert!(
            POSTGRES_HBA.contains(role),
            "missing TLS-only HBA role: {role}"
        );
    }
}

#[test]
fn postgres_roles_and_rotation_are_closed_and_least_privilege() {
    for required in [
        "ALTER ROLE unciv_runtime",
        "ALTER ROLE unciv_migrate",
        "ALTER ROLE unciv_backup",
        "ALTER ROLE unciv_restore",
        "ALTER ROLE unciv_audit",
        "NOBYPASSRLS",
        "REVOKE ALL ON DATABASE unciv_authoritative FROM PUBLIC",
        "GRANT CONNECT ON DATABASE unciv_authoritative TO",
        "GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO unciv_runtime",
        "GRANT SELECT ON TABLES TO unciv_audit",
        "default_transaction_read_only = on",
        "REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC",
    ] {
        assert!(
            POSTGRES_ROLES.contains(required),
            "missing role invariant: {required}"
        );
    }
    for role in [
        "'unciv_runtime'",
        "'unciv_migrate'",
        "'unciv_backup'",
        "'unciv_restore'",
        "'unciv_audit'",
    ] {
        assert!(
            POSTGRES_ROTATION.contains(role),
            "rotation allowlist omits {role}"
        );
    }
    assert!(!POSTGRES_ROTATION.contains("ALTER ROLE :"));
    for required in [
        "PGSSLMODE=require",
        "PGSSLMODE=$SslMode",
        "pg_stat_ssl",
        "CREATE TABLE runtime_must_not_create",
        "pg_basebackup",
        "pg_verifybackup",
        "unciv_restore",
        "runtime_rotation_old_credential",
        "runtime_rotation_new_credential",
        "--bin', 'unciv-v3-reconcile",
    ] {
        assert!(
            POSTGRES_SECURITY_SMOKE.contains(required),
            "missing live PostgreSQL security assertion: {required}"
        );
    }
}

#[test]
fn capacity_monitor_is_bounded_read_only_and_fault_qualified() {
    for required in [
        "User=unciv-monitor",
        "EnvironmentFile=/etc/unciv-authoritative/monitor/capacity.env",
        "Requires=unciv-authoritative-postgres.service",
        "MemoryMax=64M",
        "NoNewPrivileges=yes",
        "ProtectSystem=strict",
        "IPAddressDeny=any",
        "IPAddressAllow=localhost",
    ] {
        assert!(
            CAPACITY_SERVICE.contains(required),
            "missing capacity service policy: {required}"
        );
    }
    for required in [
        "OnUnitActiveSec=5m",
        "RandomizedDelaySec=30s",
        "Persistent=yes",
        "Unit=unciv-authoritative-capacity.service",
    ] {
        assert!(
            CAPACITY_TIMER.contains(required),
            "missing capacity schedule: {required}"
        );
    }
    for required in [
        "UNCIV_V3_AUDIT_DATABASE_URL",
        "UNCIV_V3_CAPACITY_WARN_PERCENT",
        "UNCIV_V3_CAPACITY_CRITICAL_PERCENT",
        "pg_database_size(current_database())",
        "\"status\":\"%s\"",
        "exit \"$status\"",
    ] {
        assert!(
            CAPACITY_CHECK.contains(required),
            "missing capacity check invariant: {required}"
        );
    }
    for required in [
        "'--tmpfs', '/var/lib/postgresql:rw,size=160m'",
        "constrained_capacity_status = 'critical'",
        "disk_full_commit_leaves_no_phantom_revision",
        "recovered_space_allows_one_idempotent_retry",
        "--bin unciv-v3-reconcile",
    ] {
        assert!(
            DISK_FULL_SMOKE.contains(required),
            "missing disk-full qualification: {required}"
        );
    }
}
