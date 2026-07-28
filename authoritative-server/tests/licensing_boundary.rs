const CARGO_MANIFEST: &str = include_str!("../Cargo.toml");
const CARGO_LOCK: &str = include_str!("../Cargo.lock");
const GRADLE_CATALOG: &str = include_str!("../../gradle/libs.versions.toml");
const API_VERSION: &str = include_str!("../../core/src/com/unciv/logic/multiplayer/ApiVersion.kt");
const ADR: &str = include_str!("../../docs/architecture/adr/0001-authoritative-multiplayer-v3.md");
const POLICY: &str = include_str!("../../docs/legal/authoritative-multiplayer-licensing.md");
const ROOT_LICENSE: &str = include_str!("../../LICENSE");

#[test]
fn external_agpl_reference_is_not_a_dependency() {
    for dependency_source in [CARGO_MANIFEST, CARGO_LOCK, GRADLE_CATALOG] {
        let normalized = dependency_source.to_ascii_lowercase();
        assert!(!normalized.contains("runciv"));
        assert!(!normalized.contains("hopfenspace"));
    }
}

#[test]
fn provenance_policy_records_the_preexisting_reference_and_no_copy_boundary() {
    assert!(API_VERSION.contains("https://github.com/hopfenspace/runciv"));
    assert!(ADR.contains("no code from the separate AGPL"));
    for required in [
        "Mozilla Public License 2.0",
        "GNU Affero General Public License v3.0",
        "No `runciv` source",
        "Do not copy, translate, adapt, vendor",
        "legal/LICENSE",
        "SPDX 2.3",
    ] {
        assert!(
            POLICY.contains(required),
            "licensing policy is missing: {required}"
        );
    }
    assert!(ROOT_LICENSE.starts_with("Mozilla Public License Version 2.0"));
}
