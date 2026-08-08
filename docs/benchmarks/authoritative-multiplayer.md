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

## Lobby terrain projection preview

Measured on 2026-08-06 on the same Windows/JDK host with the schema-v3
benchmark and freshly packaged worker `4.21.4 (Build 1239)`. Each iteration
creates a fresh tiny Pangaea game, then projects the lobby terrain from the
resulting snapshot. The worker serializes this read against gameplay commands
and AI turns; the client fetches it once per committed `lobby_revision` rather
than per reconciliation poll.

Run with:

```text
set UNCIV_ENGINE_WORKER_ADDR=127.0.0.1:43170
set UNCIV_ENGINE_WORKER_SECRET=<64 lowercase hexadecimal characters>
set UNCIV_WORKER_BENCH_TERRAIN_PREVIEWS=10
cargo run --release --bin unciv-v3-worker-benchmark
```

| Operation | Samples | mean | p50 | p95 | p99 | min | max |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Lobby terrain projection (tiny map) | 10 | 10.73 ms | 8.28 ms | 24.28 ms | 24.28 ms | 6.80 ms | 24.28 ms |

The projection loads the committed snapshot, parses it through the shared
Kotlin engine, and extracts only base terrain plus unlabeled start coordinates.
No generation happens for a preview. The p50 of 8.28 ms is well under the
1.5-second reconciliation poll interval, so a terrain preview never blocks
lobby reconciliation. Late-game or large-map previews will be slower; the
client fetches the preview only once per committed `lobby_revision`, which
bounds the load.

## Full AI match benchmark with per-turn timing and history

Measured on 2026-08-06 on the same Windows/JDK host with worker build
`4.21.4 (Build 1239)`. Two complete matches were played from lobby creation
through Domination victory, with every turn benchmarked and the full revision
history verified for playback.

### 2-player match (1 human + 1 AI, tiny Pangaea, Quick, Domination only)

The human (Rome) ended turn immediately each round; the server AI (Egypt,
Chieftain) executed its full turn through the private Kotlin worker. The match
reached a Domination victory for Egypt on turn 263.

| Metric | Value |
| --- | --- |
| Total turns to victory | 263 |
| Total revisions committed | 264 (0–263) |
| Total commands | 263 (one EndTurn per turn) |
| Retained rewind checkpoints | 20 (most recent accepted EndTurn results) |
| Match wall-clock duration | 40.5 s |

Per-turn EndTurn+AI latency (ms):

| Operation | Samples | mean | p50 | p95 | p99 | min | max |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| EndTurn + 1 AI turn | 262 | 76.2 | 75 | 106 | 158 | 45 | 158 |

Snapshot growth (zstd-compressed, stored as immutable revisions):

| Metric | Value |
| --- | --- |
| Revision 0 snapshot | 3,499 bytes compressed / 26,619 uncompressed |
| Revision 263 snapshot | 16,048 bytes compressed / 105,741 uncompressed |
| Average snapshot | 9,572 bytes compressed / 63,344 uncompressed |
| Total history storage | 2,467 KB compressed (264 snapshots) |

Post-victory behavior was verified: the projection correctly reports
`winningCivilizationId`, `victoryType`, and `victoryTurn`; `isCurrentTurn` is
false; `pendingTurnActions` is empty; and a post-victory `end_turn` command is
rejected with HTTP 422 `invalid_command`.

### 4-player match (1 human + 3 AI, small Pangaea, Quick, Domination only)

The human (Rome) ended turn immediately; three server AI civilizations (Egypt,
Greece, Persia, all Chieftain) each executed their full turn through the
private Kotlin worker before control returned. 30 turns were benchmarked.

Per-turn EndTurn+3AI latency (ms):

| Operation | Samples | mean | p50 | p95 | p99 | min | max |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| EndTurn + 3 AI turns | 30 | 59.4 | 55 | 93 | 94 | 45 | 94 |

Snapshot growth over 31 revisions: 6,003–8,985 bytes compressed (avg 7,672),
total 232 KB. The match was not played to completion; the 30-turn sample
demonstrates that three AI civilizations add negligible per-turn overhead
beyond the 2-player baseline on a small map.

### History and playback verification

Both matches produced a complete, immutable revision chain in PostgreSQL:

- Every `EndTurn` command produced exactly one new revision, one snapshot, one
  command journal entry, and one outbox notification event.
- The rewind checkpoint list exposes the most recent accepted `EndTurn`
  start-of-turn snapshots, enabling consensual whole-game rewind to any
  retained checkpoint.
- The full revision chain (0 to final) is preserved as immutable snapshots with
  content-addressed hashes, enabling complete game playback from any point.
- Snapshot compression (zstd) keeps total history storage small: a complete
  263-turn match occupies 2.4 MB compressed across 264 revisions.

## 10-random-AI Huge Continents benchmark with six city-states

Measured on 2026-08-08 on the same Windows/JDK host (Intel Core i7-13700KF,
32 GB, Temurin JDK 21.0.11) with worker build `4.21.4 (Build 1239)` and a
release-mode authoritative server in unpackaged dev mode. PostgreSQL 19 Beta 2
ran in Docker (2.4 GB container, dedicated `unciv_authoritative_bench`
database migrated to schema 33). The match was driven entirely through the
public API: one authenticated spectator account created the zero-human lobby,
started it, then issued one `advance-ai-turn` command per AI civilization per
round until victory.

Config: 10 major civilizations, all AI with random nations (blank
`civilization_id` seats), 6 city-states, Huge Continents map
(`small_continents`), Quick speed, Chieftain, Ancient era, all non-hidden
victory types (Domination, Scientific, Cultural, Diplomatic), barbarians
disabled, no ruins or natural wonders, max turns 1500.

Outcome: **Rome won a Scientific victory on turn 279**. Aztecs were the only
elimination (turn 216); the other nine majors were alive at the end. The full
match ran 4,474 `advance-ai-turn` commands, one per AI move, each producing
exactly one committed revision (4,475 revisions total, 0-4474).

Per-round and per-AI latency (n=448 full 10-AI rounds):

| Metric | samples | min | p50 | p90 | p99 | max | mean |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Round (10 AI moves), ms | 448 | 481 | 1,153 | 2,139 | 2,700 | 2,935 | 1,286 |
| One AI move (advance-ai-turn), ms | 448 | 48 | 115 | 214 | 275 | 2,025 | 133 |

The slowest AI in a round averaged 176 ms against 102 ms for the fastest. The
match wall-clock was about 11.5 minutes of gameplay from the first accepted
AI move to victory. One transient 502 `worker_rejected` occurred at turn 220
and the next retry committed normally (1 failure in 4,474 commands).

Storage for the complete 279-turn match:

| Metric | Value |
| --- | --- |
| Revisions | 4,475 |
| Commands | 4,474 (all `advance_ai_turn`) |
| Compressed history | 391.8 MB |
| Uncompressed history | 3.4 GB |
| Average snapshot | 91.8 KB compressed / 763 KB uncompressed |

A huge-map all-AI match therefore produces roughly 1.4 MB of compressed
revision history per turn — two orders of magnitude more than the tiny-map
baseline above — which is the expected cost of full-map immutable snapshots
and is directly relevant to retention policy sizing. Raw per-round evidence is
retained in `authoritative-server/tests/results/benchmark-10random-huge-continents-20260808-151953.csv`
(game `92db598b-eadf-46c9-92f9-9d2abc600808`).

This run also exercised the all-AI `advance-ai-turn` commit path end-to-end
for the first time and found a real defect: the generic commit path rejected
the spectator owner of a zero-human lobby (`CommitError::Unauthorized`) even
after the advance-ai-turn authorization gate passed. The fix permits the
spectator role only for `AdvanceAiTurn` commands and records the journal
identity as the engine's spectator civilization; all 4,474 commands above
committed through the fixed path. The lib suite (196 tests) and
`cargo clippy --all-targets --all-features -- -D warnings` pass after the fix.

## Snapshot storage reduction (measured 2026-08-08)

Real snapshots from the 279-turn match above were decompressed and re-compressed
under several schemes to quantify how much storage can be saved without breaking
compatibility. Encode timing is the zstd CLI on the 1.17 MB late-game snapshot;
the server encodes every revision on commit, so hot-path CPU matters.

| Scheme | Late-game snapshot | Encode time | Projected for the 392 MB match |
| --- | ---: | ---: | ---: |
| zstd level 3 (historical default) | 143.7 KB | 81 ms | 392 MB |
| zstd level 9 | 108.7 KB | 81 ms | ~296 MB (-24%) |
| zstd level 15 | 102.5 KB | 139 ms | ~280 MB (-29%) |
| zstd level 19 | 92.6 KB | 425 ms | ~253 MB (-36%) |
| level 9 + shared dictionary | 85.1 KB (21-snapshot avg) | ~level-9 cost | ~235 MB (-40%) |
| level 19 + shared dictionary | 71.2 KB (21-snapshot avg) | ~level-19 cost | ~197 MB (-50%) |
| delta chain vs previous revision | 444 B | sub-ms | ~15 MB (-96%) |

Key facts:

- Consecutive snapshots are content-identical except for one small localized
  change (e.g. rev3999 -> rev4000 differs by ~123 inserted bytes near the start,
  shifting everything after it), so a `zstd --patch-from` delta against the
  previous revision compresses to 444 B (round-trip verified). A 1,000-revision
  checkpoint gap patches to 48 KB, still smaller than a level-3 full snapshot.
- A shared zstd dictionary (128 KB, trained on 21 snapshots spread across the
  match) adds ~15-20% on top of the level bump while keeping every snapshot
  independently decodable (random access preserved).
- A delta chain gives the biggest win but breaks random-access replay: the
  replay endpoint currently fetches one snapshot per revision directly, so
  deltas would require decoding from the nearest checkpoint (schema change:
  add a delta-base revision per blob).

Implemented safely so far: the snapshot codec now reads
`UNCIV_V3_SNAPSHOT_ZSTD_LEVEL` (default 9, clamp 1..=22). Decoding is
level-agnostic (the level is embedded in each zstd frame), so old and new rows
interoperate and no migration is required. `snapshot_codec` tests and clippy
`-D warnings` pass.

## Snapshot format benchmark and Lockwell archival qualification (2026-08-08)

The reproducible binary `unciv-v3-snapshot-benchmark` was run against 101
consecutive snapshots from the existing match fixture (35,860,040 bytes
uncompressed). Every selected delta and dictionary result was round-trip
verified.

| Strategy | Total bytes | Encode time | Notes |
| --- | ---: | ---: | --- |
| zstd level 3 | 3,194,567 | 81 ms | historical baseline |
| zstd level 9 | 2,395,711 | 601 ms | 25.0% below level 3; hot-path default |
| zstd level 15 | 2,297,908 | 3,824 ms | 28.1% below level 3; too expensive per commit |
| zstd level 19 | 1,990,705 | 16,214 ms | 37.7% below level 3; cold rewrite only |
| zstd level 9 + 128 KiB dictionary | 1,809,352 | 610 ms | 1,678,280 payload + 131,072 dictionary; 43.4% below level 3 |
| zstd level 19 + 128 KiB dictionary | 1,373,685 | 10,540 ms | 1,242,613 payload + 131,072 dictionary; 57.0% below level 3; cold rewrite only |
| previous-revision delta | 437,730 | 1,154 ms | 100/100 deltas; no random-access checkpoint |
| checkpoint interval 10 | 892,855 | 1,150 ms | bounded random access |
| checkpoint interval 64 | 1,539,658 | 1,330 ms | lower checkpoint overhead, longer reconstruction path |
| checkpoint interval 100 | 2,072,882 | 1,433 ms | weaker size result on this fixture |

The previous-revision chain is not used directly because replay would require
an unbounded dependency chain. The implemented archive format uses a bounded
checkpoint base, stores the delta payload independently in Lockwell, records
base revision/hash in PostgreSQL, and verifies the reconstructed target hash
before exposing it to the worker or client. Historical delta bases are accepted
only through a 64-link reconstruction bound; normal retention policies keep
long-term checkpoint revisions full.

Lockwell was qualified against the local native API at `http://127.0.0.1:9000`
using its required bearer token, `If-None-Match: *`, `Idempotency-Key`, and
base64 `X-Lockwell-Checksum-SHA256` header. The opt-in PostgreSQL integration
fixture archived 9 cold revisions from a 12-revision game, removed only those
PostgreSQL blobs, retained checkpoints/recent revisions, selected and verified
checkpoint-relative deltas, passed canonical-head validation, and produced no
reconciliation findings. The fixture is ignored by default and requires
explicit PostgreSQL and Lockwell credentials.

Production archival is intentionally not automatic in the commit hot path:
run the dry-run `unciv-v3-archive` CLI, inspect candidate/byte counts, then use
`--apply`. This avoids coupling a human command's availability to object-store
latency while keeping every cold read fail-closed and retry-safe.
