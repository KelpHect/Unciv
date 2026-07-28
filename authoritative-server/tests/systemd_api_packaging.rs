const API_SERVICE: &str = include_str!("../systemd/unciv-authoritative-api.service");
const MIGRATION_SERVICE: &str = include_str!("../systemd/unciv-authoritative-migrate.service");

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
