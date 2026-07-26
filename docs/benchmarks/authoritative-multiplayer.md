# Authoritative multiplayer benchmarks

This report contains reproducible measurements, not capacity promises. Each
section names its host, build, dataset, and limitations. Production capacity
remains unqualified until the documented Linux 1-vCPU/1-GB load run is complete.

## Private-worker process and connection model

Measured on 2026-07-26 using:

- Windows 11 Pro build 26200, x86-64.
- Intel Core i7-13700KF, 16 physical cores and 24 logical processors.
- 32 GB RAM.
- Eclipse Temurin JDK 21.0.11.
- Unciv worker `4.21.1 (Build 1236)`.
- Release-mode Rust benchmark client.
- One warmed packaged `UncivAuthoritativeWorker.jar` on loopback.
- JVM limits `-Xms64m -Xmx384m`.

The benchmark opens a fresh authenticated TCP connection for every operation.
The handshake sample includes TCP establishment, request/response HMAC,
bounded JSON encoding/decoding, Kotlin dispatch, and the capability response,
so it is a conservative upper bound for connection/authentication overhead.
Tiny-game creation runs the real shared `GameStarter` with two major
civilizations on a tiny rectangular Pangaea map, no city-states, barbarians,
ruins, or natural wonders.

Run the packaged worker from `android/assets` with a generated test secret, then:

```text
set UNCIV_ENGINE_WORKER_ADDR=127.0.0.1:43171
set UNCIV_ENGINE_WORKER_SECRET=<64 lowercase hexadecimal characters>
set UNCIV_WORKER_BENCH_HANDSHAKES=500
set UNCIV_WORKER_BENCH_CREATIONS=50
cargo run --release --bin unciv-v3-worker-benchmark
```

Do not reuse the benchmark secret in production or place it in shell history.
The benchmark emits JSON so later Linux runs can retain the raw result.

| Operation | Samples | mean | p50 | p95 | p99 | min | max |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Fresh-connection authenticated handshake | 500 | 3.82 ms | 3.05 ms | 6.39 ms | 24.12 ms | 2.72 ms | 33.76 ms |
| Real tiny-game creation | 50 | 31.31 ms | 20.59 ms | 109.69 ms | 129.05 ms | 13.83 ms | 129.05 ms |

Ten cold packaged-worker starts reached a connectable loopback listener in
1,015.09-1,186.15 ms (mean 1,093.74 ms, p50 1,031.75 ms, p95 1,186.15 ms).
Ready working set averaged 108.27 MiB and peaked at 110.45 MiB across those
starts. After the measured workload, the worker working set was 141.34 MiB and
the observed peak was 243.61 MiB. These are Windows process counters, not Linux
cgroup evidence.

### Decision

Retain one persistent, sequential JVM and one authenticated connection per
command for the initial 1-vCPU/1-GB target:

- The warmed fresh-connection handshake p50 is 3.05 ms. Even against the
  deliberately small creation fixture it is only an upper-bound 14.8% of the
  20.59-ms creation p50; rules-heavy commands and AI turns make the proportion
  smaller.
- Connection lifetime is also the cancellation and crash-isolation boundary.
  Reusing a stream would require request multiplexing, response correlation,
  poisoned-stream recovery, and cancellation semantics without improving
  canonical correctness.
- Multiple 384-MiB-heap JVMs are incompatible with the first 1-GB target before
  PostgreSQL, Rust, the reverse proxy, and the operating system are budgeted.

Rust now serializes execution before opening a socket and admits at most 64
running-plus-waiting operations by default. The 65th operation fails fast;
queued work has its own deadline and remains inside the absolute total worker
deadline. This removes reliance on the operating-system listen backlog and
bounds task/socket pressure. Deployments may configure a smaller queue after
load testing; increasing it does not increase worker throughput.

This decision does not qualify final capacity. Still required: representative
large saves, end-turn/AI percentiles, Linux cgroup memory and CPU, recycle cost
under systemd, concurrent-game/API/PostgreSQL/WebSocket load, storage growth,
and the complete 1-vCPU/1-GB sustained run.
