const WORKFLOW: &str = include_str!("../../.github/workflows/authoritativeV3CredentialStores.yml");

#[test]
fn credential_store_workflow_uses_least_privilege_and_pinned_actions() {
    assert!(WORKFLOW.contains("permissions:\n  contents: read"));
    for line in WORKFLOW.lines().map(str::trim) {
        let Some(reference) = line.strip_prefix("uses: ") else {
            continue;
        };
        let revision = reference
            .split_once('@')
            .unwrap_or_else(|| panic!("action is not pinned: {reference}"))
            .1
            .split_whitespace()
            .next()
            .expect("action revision");
        assert_eq!(
            revision.len(),
            40,
            "action is not pinned to a full commit: {reference}"
        );
        assert!(
            revision.bytes().all(|byte| byte.is_ascii_hexdigit()),
            "action revision is not hexadecimal: {reference}"
        );
    }
}

#[test]
fn credential_store_workflow_executes_every_supported_secure_store() {
    for required in [
        "MacOsApiV3SessionTokenStoreTests",
        "LinuxApiV3SessionTokenStoreTests",
        "api-level:\n          - 21\n          - 23",
        ":android:connectedDebugAndroidTest",
        "gnome-keyring-daemon",
        "$ANDROID_SDK_ROOT/emulator/emulator",
        "timeout 180 \"$ADB\" wait-for-device",
    ] {
        assert!(
            WORKFLOW.contains(required),
            "credential-store workflow is missing {required}"
        );
    }
}
