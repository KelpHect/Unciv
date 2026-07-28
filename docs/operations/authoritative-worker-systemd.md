# Authoritative worker systemd deployment

This unit runs the packaged Kotlin rules worker as a private, disposable
process. The worker remains the only component that executes Unciv rules and
AI. PostgreSQL and the Rust control plane remain the only canonical commit
boundary; killing or recycling this JVM cannot publish a revision.

## Runtime boundary

`authoritative-server/systemd/unciv-authoritative-worker.service`
enforces:

- one unprivileged `unciv-worker` process with no Linux capabilities;
- a private mode-0700 executable runtime directory for LibGDX native-library
  extraction, so `/tmp` can remain non-executable;
- loopback-only IPv4/IPv6 access and no public listener;
- a 384 MiB Java heap, 96 MiB metaspace, and 32 MiB direct-memory cap;
- a 512 MiB cgroup hard limit, 448 MiB pressure threshold, and no swap;
- 80% of one CPU, at most 64 tasks, and 1,024 file descriptors;
- restart after every crash, OOM exit, or normal exit;
- a fresh JVM at least every six hours through `RuntimeMaxSec`;
- a five-restart-per-minute start limit to prevent an invalid configuration
  from spinning forever.

`LoopbackEngineWorkerServer` accepts and completes one connection synchronously
before accepting another. The process limits therefore constrain the sole
active command. A five-second socket-read timeout drops a local peer that never
finishes an authenticated request. Once a request is authenticated, a
30-second watchdog covers rules execution, response serialization, signing, and
write. Expiry calls `Runtime.halt(124)` so an uninterruptible engine thread
cannot keep the worker wedged; systemd then starts a clean JVM. Configure these
with `UNCIV_ENGINE_WORKER_SOCKET_TIMEOUT_MS` (100-600000) and
`UNCIV_ENGINE_WORKER_COMMAND_TIMEOUT_MS` (1000-600000). Rust separately bounds
connect, request write, execution/read, and total time and opens its circuit
breaker during repeated failures.

## Install

Build and stage the bootstrap assets:

```text
install -d -o root -g root -m 0755 /opt/unciv-authoritative/bootstrap-assets
install -d -o root -g root -m 0755 /opt/unciv-authoritative/rulesets
install -d -o root -g root -m 0755 /opt/unciv-authoritative/docs
install -o root -g root -m 0444 docs/operations/authoritative-worker-systemd.md /opt/unciv-authoritative/docs/
cp -a android/assets/. /opt/unciv-authoritative/bootstrap-assets/
find /opt/unciv-authoritative/bootstrap-assets -type d -exec chmod 0555 {} +
find /opt/unciv-authoritative/bootstrap-assets -type f -exec chmod 0444 {} +
```

Create and activate the first immutable version using
`docs/operations/authoritative-ruleset-acquisition.md`. The unit deliberately
refuses to start until `/opt/unciv-authoritative/rulesets/active` is an atomic
link to a validated version.

Create and install the matching release bundle using
`docs/operations/authoritative-release-bundle.md`. The worker JAR is executed
only from `/opt/unciv-authoritative/releases/current`, and its environment must
contain the exact bundle ID reported by the verified manifest.

Create identities and the deployment secret without placing it in shell
history:

```text
groupadd --system unciv-authoritative
useradd --system --gid unciv-authoritative --home-dir /nonexistent --shell /usr/sbin/nologin unciv-worker
install -d -o root -g unciv-authoritative -m 0750 /etc/unciv-authoritative
install -o root -g unciv-authoritative -m 0640 /dev/null /etc/unciv-authoritative/worker.env
openssl rand -hex 32 | sed 's/^/UNCIV_ENGINE_WORKER_SECRET=/' > /etc/unciv-authoritative/worker.env
printf '%s\n' 'UNCIV_ENGINE_WORKER_PORT=43170' >> /etc/unciv-authoritative/worker.env
printf '%s\n' 'UNCIV_V3_RELEASE_BUNDLE_ID=<verified-bundle-id>' >> /etc/unciv-authoritative/worker.env
chown root:unciv-authoritative /etc/unciv-authoritative/worker.env
chmod 0640 /etc/unciv-authoritative/worker.env
```

Install, validate, and start the unit:

```text
install -o root -g root -m 0644 authoritative-server/systemd/unciv-authoritative-worker.service /etc/systemd/system/
systemd-analyze verify /etc/systemd/system/unciv-authoritative-worker.service
systemctl daemon-reload
systemctl enable --now unciv-authoritative-worker.service
systemctl show unciv-authoritative-worker.service -p ActiveState -p MainPID -p MemoryCurrent -p MemoryMax -p CPUQuotaPerSecUSec -p NRestarts
```

The Rust service must use `127.0.0.1:43170`, possess the same 256-bit secret
through its separate protected configuration, load the same release bundle,
and pass its authenticated capability handshake. The worker returns its bundle
ID in that signed handshake; Rust rejects a mismatch before accepting traffic.

## Ruleset asset integrity

The worker loads built-in rulesets from `jsons/` and optional operator-staged
mods from `mods/<name>/jsons/` beneath its active version. It never accepts
ruleset bytes, paths, archives, or download URLs from a client. Before parsing,
startup walks those gameplay trees without following links and fails closed on
symbolic links, special filesystem entries, more than 64 mods, more than 16,384
entries, an individual file over 16 MiB, or more than 512 MiB in total.
Any ruleset parsing error also aborts startup instead of silently omitting the
affected mod.

Immediately after `RulesetCache` parses the files, the worker captures a single
content catalog. The authenticated handshake and every command use that
immutable snapshot. Replacing files underneath a running JVM cannot change the
identity it advertises or make already-parsed rules appear to have a different
hash. Every command rejects a mismatched engine build, ruleset name, lowercase
SHA-256, duplicate component, or component count before loading its canonical
snapshot.

The unit's `ProtectSystem=strict` setting makes the root-owned active version
read-only to `unciv-worker`. The operator-only acquisition tool performs
bounded authenticated download, extraction, packaged-worker semantic
validation, PostgreSQL registration, and atomic version activation. Never edit
a version directory after publication.

LibGDX extracts its platform-native library before the worker starts listening.
The JVM therefore uses systemd's private `/run/unciv-worker` runtime directory
instead of `/tmp`. Do not remove `RuntimeDirectory`, change its ownership, or
point `java.io.tmpdir` at a filesystem mounted `noexec`; doing so makes the
headless Linux worker fail closed before its authenticated handshake.

## Recycle and failure drill

Run this on the documented Linux target during deployment rehearsal:

```text
old_pid=$(systemctl show unciv-authoritative-worker.service -p MainPID --value)
systemctl kill --signal=SIGKILL unciv-authoritative-worker.service
sleep 3
new_pid=$(systemctl show unciv-authoritative-worker.service -p MainPID --value)
test "$old_pid" != "$new_pid"
systemctl is-active --quiet unciv-authoritative-worker.service
```

Submit an idempotent command after the authenticated handshake recovers and
verify canonical reconciliation reports zero findings. A command interrupted
by the kill may fail or time out, but it must not create a phantom revision.
Retry only with the same command ID and expected revision.

The checked-in Rust packaging test verifies the unit contract on every
platform. The repeatable live rehearsal is
`authoritative-server/systemd/qualification/run-linux-worker-qualification.ps1`.
It boots the pinned Ubuntu 24.04 image with systemd as PID 1, keeps `/tmp`
mounted `noexec`, and runs the actual packaged JAR through authenticated
handshake, restart, recycle, watchdog-exit-124, JVM-OOM, cgroup, descriptor,
asset, and secret-permission checks. This qualifies the private worker unit;
the separate Rust API, PostgreSQL, proxy/TLS, backup, and full deployment smoke
gates remain tracked independently.
