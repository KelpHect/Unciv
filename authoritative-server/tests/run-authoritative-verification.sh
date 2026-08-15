#!/usr/bin/env bash
# Deterministic authoritative V3 verification entry point.
#
# This script is intentionally non-destructive by default: it runs source,
# unit, and packaging checks only. PostgreSQL integration and destructive
# storage qualifications require explicit flags and an already configured,
# disposable environment. JAVA_HOME is changed only for child Gradle
# processes; the caller's shell and system Java configuration are untouched.
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SERVER_DIR="$ROOT_DIR/authoritative-server"
GRADLEW="$ROOT_DIR/gradlew"

usage() {
    cat <<'EOF'
Usage: authoritative-server/tests/run-authoritative-verification.sh [lanes]

Lanes (default: --rust --kotlin):
  --rust       Rust format, library tests, all-target check, and strict Clippy
  --kotlin    Shared gameplay tests and packaged-worker/server tests
  --desktop    Desktop distribution gate
  --android   Android debug/release APK and AAB gates
  --postgres  Serialized ignored PostgreSQL carrier; requires
              UNCIV_V3_DATABASE_URL and a disposable PostgreSQL 19 Beta 2 DB
  --all       Rust, Kotlin, desktop, and Android (never destructive DB lanes)

Java 21 resolution for Kotlin lanes:
  1. JAVA_HOME_21 or JAVA21_HOME
  2. JAVA_HOME when its java reports major version 21
  3. java on PATH when it reports major version 21

The script does not start services, modify global Java settings, mutate a
production database, or run PITR/disk-full qualifications implicitly.
EOF
}

run() {
    printf '\n>>> %s\n' "$*"
    "$@"
}

java_major() {
    local java_bin="$1" version
    version="$($java_bin -version 2>&1 | sed -n 's/.*version "\([0-9][0-9]*\).*/\1/p' | head -1)"
    printf '%s' "${version:-0}"
}

resolve_java21() {
    local candidate java_bin
    for candidate in "${JAVA_HOME_21:-}" "${JAVA21_HOME:-}" "${JAVA_HOME:-}"; do
        [[ -n "$candidate" ]] || continue
        java_bin="$candidate/bin/java"
        [[ -f "$java_bin" ]] || java_bin="$candidate/bin/java.exe"
        [[ -f "$java_bin" ]] || continue
        if [[ "$(java_major "$java_bin")" == "21" ]]; then
            printf '%s' "$candidate"
            return 0
        fi
    done
    if command -v java >/dev/null 2>&1 && [[ "$(java_major "$(command -v java)")" == "21" ]]; then
        dirname "$(dirname "$(command -v java)")"
        return 0
    fi
    cat >&2 <<'EOF'
No Java 21 toolchain was found. Set JAVA_HOME_21 or JAVA21_HOME to the Java 21
installation for this invocation. The verification script will not change a
global JAVA_HOME or silently run Gradle with another major version.
EOF
    return 1
}

run_gradle() {
    local java_home
    java_home="$(resolve_java21)"
    (cd "$ROOT_DIR" && JAVA_HOME="$java_home" PATH="$java_home/bin:$PATH" run "$GRADLEW" --no-daemon --no-parallel --console=plain "$@")
}

run_rust() {
    (cd "$SERVER_DIR" && run cargo fmt --all -- --check)
    (cd "$SERVER_DIR" && run cargo test --all-targets --all-features)
    (cd "$SERVER_DIR" && run cargo check --all-targets --all-features)
    (cd "$SERVER_DIR" && run cargo clippy --all-targets --all-features -- -D warnings)
}

run_kotlin() {
    run_gradle :tests:test :server:test
}

run_desktop() {
    run_gradle :desktop:dist
}

run_android() {
    run_gradle :android:assembleDebug :android:bundleDebug :android:assembleRelease :android:bundleRelease
}

run_postgres() {
    : "${UNCIV_V3_DATABASE_URL:?Set UNCIV_V3_DATABASE_URL to a disposable PostgreSQL 19 Beta 2 database}"
    (cd "$SERVER_DIR" && run cargo test --all-features -- --ignored --test-threads=1 \
        --skip restored_backup_fixture_preserves_every_required_invariant \
        --skip disk_full::disk_full_commit_leaves_no_phantom_revision \
        --skip disk_full::recovered_space_allows_one_idempotent_retry \
        --skip retirement_switch_rejects_writes_but_preserves_legacy_reads \
        --skip same_uuid_legacy_upload_cannot_read_or_mutate_v3_canonical_state \
        --skip archive::lockwell_archival_verifies_objects_and_removes_only_cold_blobs \
        --skip scenario::postgres_promotion_reconnects_under_load_without_splitting_canonical_history)
}

lanes=()
if (($# == 0)); then
    lanes=(rust kotlin)
else
    while (($# > 0)); do
        case "$1" in
            --rust|--kotlin|--desktop|--android|--postgres) lanes+=("${1#--}") ;;
            --all) lanes+=(rust kotlin desktop android) ;;
            --help|-h) usage; exit 0 ;;
            *) printf 'Unknown option: %s\n\n' "$1" >&2; usage >&2; exit 2 ;;
        esac
        shift
    done
fi

for lane in "${lanes[@]}"; do
    case "$lane" in
        rust) run_rust ;;
        kotlin) run_kotlin ;;
        desktop) run_desktop ;;
        android) run_android ;;
        postgres) run_postgres ;;
        *) printf 'Internal error: unknown lane %s\n' "$lane" >&2; exit 2 ;;
    esac
done

printf '\nAuthoritative V3 verification lanes passed: %s\n' "${lanes[*]}"
