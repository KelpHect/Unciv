#!/usr/bin/env bash
set -euo pipefail

readonly unit=unciv-authoritative-worker.service
readonly probe=/usr/local/libexec/unciv/worker-probe.py

fail() {
    printf 'qualification failure: %s\n' "$*" >&2
    exit 1
}

wait_for_worker() {
    local attempt
    for attempt in $(seq 1 30); do
        if "$probe" handshake >/tmp/worker-handshake.json 2>/dev/null; then
            return
        fi
        sleep 1
    done
    systemctl status "$unit" --no-pager >&2 || true
    journalctl -u "$unit" --no-pager -n 80 >&2 || true
    fail "worker did not recover an authenticated handshake"
}

wait_for_new_pid() {
    local old_pid=$1
    local attempt new_pid
    for attempt in $(seq 1 30); do
        new_pid=$(systemctl show "$unit" -p MainPID --value)
        if [[ "$new_pid" != 0 && "$new_pid" != "$old_pid" ]]; then
            wait_for_worker
            printf '%s\n' "$new_pid"
            return
        fi
        sleep 1
    done
    fail "worker PID did not change from $old_pid"
}

assert_property() {
    local name=$1
    local expected=$2
    local actual
    actual=$(systemctl show "$unit" -p "$name" --value)
    [[ "$actual" == "$expected" ]] ||
        fail "$name expected '$expected' but was '$actual'"
}

restart_worker() {
    systemctl reset-failed "$unit"
    systemctl restart "$unit"
}

systemd-analyze verify "/etc/systemd/system/$unit"
systemctl start "$unit"
wait_for_worker

assert_property ActiveState active
assert_property MemoryHigh 469762048
assert_property MemoryMax 536870912
assert_property MemorySwapMax 0
assert_property TasksMax 64
assert_property LimitNOFILE 1024
assert_property CPUQuotaPerSecUSec 800ms
assert_property RuntimeDirectoryPreserve no
assert_property NoNewPrivileges yes
assert_property ProtectSystem strict
assert_property PrivateTmp yes

pid=$(systemctl show "$unit" -p MainPID --value)
control_group=$(systemctl show "$unit" -p ControlGroup --value)
readonly cgroup="/sys/fs/cgroup${control_group}"
[[ "$(cat "$cgroup/memory.high")" == 469762048 ]] || fail "memory.high is not enforced"
[[ "$(cat "$cgroup/memory.max")" == 536870912 ]] || fail "memory.max is not enforced"
[[ "$(cat "$cgroup/memory.swap.max")" == 0 ]] || fail "memory.swap.max is not enforced"
[[ "$(cat "$cgroup/pids.max")" == 64 ]] || fail "pids.max is not enforced"
read -r cpu_quota cpu_period < "$cgroup/cpu.max"
[[ "$cpu_quota" != max && "$cpu_quota" -lt "$cpu_period" ]] ||
    fail "cpu.max does not enforce a sub-core quota"
grep -Eq '^Max open files +1024 +1024 ' "/proc/$pid/limits" ||
    fail "RLIMIT_NOFILE is not enforced"

[[ "$(stat -c '%a:%U:%G' /etc/unciv-authoritative/worker.env)" == \
    "640:root:unciv-authoritative" ]] || fail "worker secret permissions drifted"
runuser -u unciv-worker -- test -r /etc/unciv-authoritative/worker.env ||
    fail "worker identity cannot read its secret"
if runuser -u nobody -- test -r /etc/unciv-authoritative/worker.env; then
    fail "unrelated identity can read the worker secret"
fi
if runuser -u unciv-worker -- touch \
    /opt/unciv-authoritative/rulesets/active/qualification-write; then
    fail "worker identity can mutate immutable ruleset assets"
fi

old_pid=$pid
systemctl kill --signal=SIGKILL "$unit"
restarted_pid=$(wait_for_new_pid "$old_pid")

mkdir -p "/run/systemd/system/$unit.d"
printf '%s\n' \
    '[Service]' \
    'RuntimeMaxSec=5s' \
    > "/run/systemd/system/$unit.d/qualification-recycle.conf"
systemctl daemon-reload
restart_worker
wait_for_worker
recycle_pid=$(systemctl show "$unit" -p MainPID --value)
recycled_pid=$(wait_for_new_pid "$recycle_pid")
rm "/run/systemd/system/$unit.d/qualification-recycle.conf"
systemctl daemon-reload
restart_worker
wait_for_worker

cp /etc/unciv-authoritative/worker.env /run/worker.env.production
sed -i \
    's/UNCIV_ENGINE_WORKER_COMMAND_TIMEOUT_MS=30000/UNCIV_ENGINE_WORKER_COMMAND_TIMEOUT_MS=1000/' \
    /etc/unciv-authoritative/worker.env
restart_worker
wait_for_worker
timeout_pid=$(systemctl show "$unit" -p MainPID --value)
tr '\0' '\n' < "/proc/$timeout_pid/environ" |
    grep -qx 'UNCIV_ENGINE_WORKER_COMMAND_TIMEOUT_MS=1000' ||
    fail "worker did not receive the qualification watchdog deadline"
set +e
"$probe" create-huge >/tmp/worker-timeout-probe.json 2>/tmp/worker-timeout-probe.err
probe_status=$?
set -e
[[ "$probe_status" -ne 0 ]] || fail "huge command unexpectedly beat the 1-second watchdog"
timed_out_status=
for attempt in $(seq 1 30); do
    timed_out_status=$(systemctl show "$unit" -p ExecMainStatus --value)
    if [[ "$timed_out_status" == 124 ]]; then
        break
    fi
    sleep 1
done
[[ "$timed_out_status" == 124 ]] || fail "watchdog did not record exit status 124"
timeout_recovery_pid=$(wait_for_new_pid "$timeout_pid")
install -o root -g unciv-authoritative -m 0640 \
    /run/worker.env.production /etc/unciv-authoritative/worker.env
restart_worker
wait_for_worker

mkdir -p "/run/systemd/system/$unit.d"
printf '%s\n' \
    '[Service]' \
    'ExecStart=' \
    'ExecStart=/usr/bin/java -Djava.awt.headless=true -Djava.io.tmpdir=/run/unciv-worker -Xms8m -Xmx8m -XX:MaxMetaspaceSize=16m -XX:MaxDirectMemorySize=8m -XX:+ExitOnOutOfMemoryError -jar /opt/unciv-authoritative/releases/current/worker/UncivAuthoritativeWorker.jar' \
    > "/run/systemd/system/$unit.d/qualification-oom.conf"
systemctl daemon-reload
restart_worker
oom_seen=false
for attempt in $(seq 1 30); do
    if journalctl -u "$unit" --no-pager --since '-45 seconds' |
        grep -Eq 'OutOfMemoryError|Terminating due to java.lang.OutOfMemoryError'; then
        oom_seen=true
        break
    fi
    sleep 1
done
[[ "$oom_seen" == true ]] || fail "constrained JVM did not produce an OOM exit"
rm "/run/systemd/system/$unit.d/qualification-oom.conf"
systemctl daemon-reload
restart_worker
wait_for_worker

printf '%s\n' \
    '{' \
    '  "schema_version": 1,' \
    '  "platform": "ubuntu-24.04-systemd-container",' \
    '  "systemd_verify": "passed",' \
    '  "authenticated_handshake": "passed",' \
    '  "sigkill_restart_recovery": "passed",' \
    '  "runtime_recycle_recovery": "passed",' \
    '  "watchdog_exit_124_recovery": "passed",' \
    '  "jvm_oom_recovery": "passed",' \
    '  "cgroup_cpu_memory_swap_tasks": "passed",' \
    '  "descriptor_limit": "passed",' \
    '  "immutable_assets": "passed",' \
    '  "secret_permissions": "passed",' \
    "  \"restart_pid\": $restarted_pid," \
    "  \"recycle_pid\": $recycled_pid," \
    "  \"timeout_recovery_pid\": $timeout_recovery_pid" \
    '}'
