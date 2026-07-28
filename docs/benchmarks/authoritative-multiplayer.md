# Authoritative multiplayer benchmarks

This report contains reproducible measurements, not capacity promises. Each
section names its host, build, dataset, and limitations. Production capacity
is expressed as a measured floor for the documented workload, never as an
unlimited-user claim.

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

The representative and constrained qualifications below now cover large saves,
end-turn/AI percentiles, Linux container memory and CPU, recycle cost,
concurrent command contention, API/PostgreSQL/WebSocket traffic, and storage
growth. They establish a conservative capacity floor for this exact workload;
late-game, mod-specific, geographic, and fleet-scale planning still requires
deployment-specific measurement.

## Representative Large-map creation, projection, and server AI

Measured on 2026-07-28 on the same Windows/JDK host with the release-mode
schema-v2 benchmark and freshly packaged worker. The scenario uses a Large
two-continent map, eight major civilizations, twelve city-states, barbarians,
ruins, natural wonders, espionage, and all four victory types. Rust creates the
game through the typed worker setup, requests the owner's bounded projection,
selects an advertised technology through an authoritative command, and ends the
human turn. The Kotlin worker then executes all AI civilizations before
returning the next canonical snapshot.

Raw machine-readable evidence is retained in
`docs/benchmarks/results/windows-large-ai-2026-07-28.json`.

| Operation | Samples | mean | p50 | p95 | p99 | min | max |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Fresh authenticated handshake | 50 | 0.45 ms | 0.44 ms | 0.57 ms | 0.64 ms | 0.34 ms | 0.64 ms |
| Large-game creation | 3 | 217.50 ms | 233.99 ms | 234.77 ms | 234.77 ms | 183.75 ms | 234.77 ms |
| Initial player projection | 3 | 103.43 ms | 105.32 ms | 187.81 ms | 187.81 ms | 17.15 ms | 187.81 ms |
| First end turn plus seven server AI players | 3 | 289.13 ms | 319.13 ms | 428.88 ms | 428.88 ms | 119.38 ms | 428.88 ms |

Initial canonical snapshots were 229,159-234,548 bytes (mean 231,048);
post-AI snapshots were 270,098-277,162 bytes (mean 273,077). This proves the
representative command and AI path and supplies a dataset for the constrained
Linux lane. It does not predict multi-turn late-game or arbitrary mod costs.

## Hosted constrained Linux production stack

Qualified on 2026-07-28 by immutable tag
`authoritative-v3-0.1.0-beta.2.8`, commit `1fb6d174c`, and GitHub Actions run
`30393852174`. The run used the verified production bundle, the exact pinned
PostgreSQL 19 Beta 2 and Temurin 21 images, disabled swap, and these hard
container limits:

| Service | CPU limit | Memory limit | Observed peak CPU | Observed peak memory |
| --- | ---: | ---: | ---: | ---: |
| Rust API | 0.10 core | 192 MiB | 5.04% | 22.39 MiB |
| PostgreSQL | 0.25 core | 288 MiB | 6.76% | 82.39 MiB |
| Kotlin worker | 0.65 core | 512 MiB | 66.65% | 229.20 MiB |
| **Total envelope** | **1.00 core** | **992 MiB** | — | **333.98 MiB summed peaks** |

The client created 60 independent Large-map games, fetched each projection,
opened WebSocket delivery, and issued eight simultaneous end-turn requests at
the same expected revision. Exactly one command per game committed and ran the
server AI; all 420 losing requests returned the required stale-revision
conflict. The 60 commits produced 120 WebSocket notifications.

| Operation | Samples | mean | p50 | p95 | p99 | min | max |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Large-game creation | 60 | 452.96 ms | 395.87 ms | 894.98 ms | 4,208.47 ms | 150.52 ms | 4,208.47 ms |
| Player projection | 60 | 89.14 ms | 86.52 ms | 185.52 ms | 668.09 ms | 18.04 ms | 668.09 ms |
| Contended end turn plus server AI | 60 | 1,785.84 ms | 1,510.21 ms | 3,301.81 ms | 7,907.23 ms | 883.46 ms | 7,907.23 ms |
| Complete scenario | 60 | 2,509.62 ms | 2,029.31 ms | 4,589.46 ms | 13,397.87 ms | 1,222.39 ms | 13,397.87 ms |

The load completed in 152.586 seconds (about 0.393 complete scenarios/second,
or 23.6/minute). PostgreSQL grew from 9,525,131 to 15,546,251 bytes: 6,021,120
bytes total, about 100 KiB per game including snapshots, journals, audit, and
outbox state. The surrounding production smoke killed the worker, observed
fail-closed readiness, restarted the packaged worker, and restored readiness in
1,122 ms.

### Capacity statement

The defensible single-instance floor is **60 sequential Large-game
create/project/server-AI scenarios, each with eight-way same-revision command
contention, within 153 seconds under 1 CPU and 992 MiB with swap disabled**.
That is a workload qualification, not a simultaneous-player maximum or an SLA.
Operators must re-run it with representative late-game saves, enabled mods,
network latency, retention, and target hardware before choosing production
concurrency or latency objectives. The raw JSON, container samples, signed
archive, digest, SBOM, and attestations are retained by run `30393852174`.
