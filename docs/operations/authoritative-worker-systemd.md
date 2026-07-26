# Authoritative worker systemd deployment

This unit runs the packaged Kotlin rules worker as a private, disposable
process. The worker remains the only component that executes Unciv rules and
AI. PostgreSQL and the Rust control plane remain the only canonical commit
boundary; killing or recycling this JVM cannot publish a revision.

## Runtime boundary

`authoritative-server/systemd/unciv-authoritative-worker.service`
enforces:

- one unprivileged `unciv-worker` process with no Linux capabilities;
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

Build and stage immutable artifacts:

```text
./gradlew :server:authoritativeWorkerDist
install -d -o root -g root -m 0755 /opt/unciv-authoritative/worker
install -d -o root -g root -m 0755 /opt/unciv-authoritative/assets
install -d -o root -g root -m 0755 /opt/unciv-authoritative/docs
install -o root -g root -m 0444 server/build/libs/UncivAuthoritativeWorker.jar /opt/unciv-authoritative/worker/
install -o root -g root -m 0444 docs/operations/authoritative-worker-systemd.md /opt/unciv-authoritative/docs/
cp -a android/assets/. /opt/unciv-authoritative/assets/
find /opt/unciv-authoritative/assets -type d -exec chmod 0555 {} +
find /opt/unciv-authoritative/assets -type f -exec chmod 0444 {} +
```

Create identities and the deployment secret without placing it in shell
history:

```text
groupadd --system unciv-authoritative
useradd --system --gid unciv-authoritative --home-dir /nonexistent --shell /usr/sbin/nologin unciv-worker
install -d -o root -g unciv-authoritative -m 0750 /etc/unciv-authoritative
install -o root -g unciv-authoritative -m 0640 /dev/null /etc/unciv-authoritative/worker.env
openssl rand -hex 32 | sed 's/^/UNCIV_ENGINE_WORKER_SECRET=/' > /etc/unciv-authoritative/worker.env
printf '%s\n' 'UNCIV_ENGINE_WORKER_PORT=43170' >> /etc/unciv-authoritative/worker.env
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
through its separate protected configuration, and pass its authenticated
capability handshake before accepting traffic.

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
platform. A live `systemd-analyze`, cgroup-pressure/OOM drill, and Linux
end-to-end smoke remain required before the separate production-deployment
checklist can be completed.
