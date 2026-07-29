use std::{
    net::SocketAddr,
    process::ExitCode,
    time::{Duration, Instant},
};

use serde_json::json;
use unciv_authoritative_server::worker::{
    BarbarianMode, EngineWorkerClient, GeneratedMapShape, GeneratedMapSize, GeneratedMapType,
    MapResourceDensity, SetResearchPathIntent, WorkerCircuitBreakerConfig, WorkerDeadlines,
    WorkerGameSetup, WorkerIdentityKey, WorkerManifest, WorkerQueueConfig,
};
use uuid::Uuid;

const DEFAULT_HANDSHAKE_ITERATIONS: usize = 200;
const DEFAULT_CREATE_ITERATIONS: usize = 20;
const DEFAULT_REPRESENTATIVE_ITERATIONS: usize = 3;

#[tokio::main]
async fn main() -> ExitCode {
    let _ = tracing_subscriber::fmt()
        .with_writer(std::io::stderr)
        .without_time()
        .try_init();
    match run().await {
        Ok(report) => {
            println!(
                "{}",
                serde_json::to_string_pretty(&report).expect("benchmark report is serializable")
            );
            ExitCode::SUCCESS
        }
        Err(message) => {
            eprintln!("{message}");
            ExitCode::FAILURE
        }
    }
}

async fn run() -> Result<serde_json::Value, &'static str> {
    let address = environment_address()?;
    let identity = environment_identity()?;
    let handshake_iterations = environment_iterations(
        "UNCIV_WORKER_BENCH_HANDSHAKES",
        DEFAULT_HANDSHAKE_ITERATIONS,
    )?;
    let create_iterations =
        environment_iterations("UNCIV_WORKER_BENCH_CREATIONS", DEFAULT_CREATE_ITERATIONS)?;
    let representative_iterations = environment_iterations(
        "UNCIV_WORKER_BENCH_REPRESENTATIVE_TURNS",
        DEFAULT_REPRESENTATIVE_ITERATIONS,
    )?;
    let client = EngineWorkerClient::with_runtime_policy(
        address,
        WorkerDeadlines::from_environment().map_err(|_| "worker deadlines are invalid")?,
        WorkerCircuitBreakerConfig::from_environment()
            .map_err(|_| "worker circuit breaker is invalid")?,
        WorkerQueueConfig::from_environment().map_err(|_| "worker queue is invalid")?,
        identity,
    );

    for _ in 0..10 {
        client
            .handshake()
            .await
            .map_err(|_| "worker handshake warmup failed")?;
    }
    let handshake_samples = measure_handshakes(&client, handshake_iterations).await?;
    let capabilities = client
        .handshake()
        .await
        .map_err(|_| "worker capability discovery failed")?;
    let base_ruleset = capabilities
        .installed_rulesets
        .iter()
        .find(|ruleset| ruleset.name == "Civ V - Vanilla")
        .cloned()
        .ok_or("Civ V - Vanilla is not installed in the worker")?;
    let manifest = WorkerManifest {
        engine_build: capabilities.engine_build.clone(),
        base_ruleset,
        mods: Vec::new(),
    };
    let setup = tiny_setup();
    let creation_samples = measure_creations(&client, &manifest, &setup, create_iterations).await?;
    let representative =
        measure_representative_turns(&client, &manifest, representative_iterations).await?;

    Ok(json!({
        "schema_version": 2,
        "address": address.to_string(),
        "architecture": std::env::consts::ARCH,
        "operating_system": std::env::consts::OS,
        "available_parallelism": std::thread::available_parallelism()
            .map(usize::from)
            .unwrap_or(1),
        "worker": {
            "engine_build": capabilities.engine_build,
            "connection_model": "one authenticated command per fresh loopback TCP connection",
            "execution_parallelism": 1,
            "queue_capacity": WorkerQueueConfig::from_environment()
                .map_err(|_| "worker queue is invalid")?
                .capacity,
        },
        "handshake": summarize(&handshake_samples),
        "tiny_game_creation": summarize(&creation_samples),
        "representative_large_game": {
            "setup": {
                "map_size": "large",
                "major_civilizations": 8,
                "city_states": 12,
                "barbarians": "normal",
                "espionage_enabled": true,
            },
            "creation": summarize(&representative.creation_samples),
            "initial_projection": summarize(&representative.projection_samples),
            "first_end_turn_with_server_ai": summarize(&representative.end_turn_samples),
            "initial_snapshot_bytes": summarize_counts(&representative.initial_snapshot_bytes),
            "post_ai_snapshot_bytes": summarize_counts(&representative.post_ai_snapshot_bytes),
        },
    }))
}

struct RepresentativeSamples {
    creation_samples: Vec<Duration>,
    projection_samples: Vec<Duration>,
    end_turn_samples: Vec<Duration>,
    initial_snapshot_bytes: Vec<usize>,
    post_ai_snapshot_bytes: Vec<usize>,
}

async fn measure_representative_turns(
    client: &EngineWorkerClient,
    manifest: &WorkerManifest,
    iterations: usize,
) -> Result<RepresentativeSamples, &'static str> {
    let mut report = RepresentativeSamples {
        creation_samples: Vec::with_capacity(iterations),
        projection_samples: Vec::with_capacity(iterations),
        end_turn_samples: Vec::with_capacity(iterations),
        initial_snapshot_bytes: Vec::with_capacity(iterations),
        post_ai_snapshot_bytes: Vec::with_capacity(iterations),
    };
    for index in 0..iterations {
        let game_id = deterministic_game_id(10_000 + index);
        let started = Instant::now();
        let created = client
            .create_game(
                "benchmark-owner",
                manifest,
                &game_id,
                975_310_864 + index as i64,
                &representative_setup(),
            )
            .await
            .map_err(|_| "representative game creation failed")?;
        report.creation_samples.push(started.elapsed());
        report
            .initial_snapshot_bytes
            .push(created.proposal.snapshot.len());
        let mut snapshot = String::from_utf8(created.proposal.snapshot)
            .map_err(|_| "worker returned a non-UTF-8 snapshot")?;
        let civilization_id = created.owner_civilization_id;

        let started = Instant::now();
        let projection = client
            .project_state("benchmark-owner", manifest, &snapshot, &civilization_id)
            .await
            .map_err(|error| {
                eprintln!("representative projection diagnostic: {error}");
                "representative projection failed"
            })?
            .projection;
        report.projection_samples.push(started.elapsed());

        if let Some(technology_name) = projection.research.selectable_targets.first() {
            let proposal = client
                .set_research_path(
                    "benchmark-owner",
                    manifest,
                    0,
                    &snapshot,
                    SetResearchPathIntent {
                        actor_civilization_id: &civilization_id,
                        technology_name,
                        append: false,
                    },
                )
                .await
                .map_err(|_| "representative technology selection failed")?;
            snapshot = String::from_utf8(proposal.snapshot)
                .map_err(|_| "worker returned a non-UTF-8 snapshot")?;
        }

        let started = Instant::now();
        let proposal = client
            .end_turn("benchmark-owner", manifest, 1, &snapshot, &civilization_id)
            .await
            .map_err(|_| "representative end-turn/AI execution failed")?;
        report.end_turn_samples.push(started.elapsed());
        report.post_ai_snapshot_bytes.push(proposal.snapshot.len());
    }
    Ok(report)
}

async fn measure_handshakes(
    client: &EngineWorkerClient,
    iterations: usize,
) -> Result<Vec<Duration>, &'static str> {
    let mut samples = Vec::with_capacity(iterations);
    for _ in 0..iterations {
        let started = Instant::now();
        client
            .handshake()
            .await
            .map_err(|_| "measured worker handshake failed")?;
        samples.push(started.elapsed());
    }
    Ok(samples)
}

async fn measure_creations(
    client: &EngineWorkerClient,
    manifest: &WorkerManifest,
    setup: &WorkerGameSetup,
    iterations: usize,
) -> Result<Vec<Duration>, &'static str> {
    let mut samples = Vec::with_capacity(iterations);
    for index in 0..iterations {
        let game_id = deterministic_game_id(index);
        let started = Instant::now();
        client
            .create_game("benchmark-owner", manifest, &game_id, 246_813_579, setup)
            .await
            .map_err(|_| "measured tiny game creation failed")?;
        samples.push(started.elapsed());
    }
    Ok(samples)
}

fn deterministic_game_id(index: usize) -> String {
    let mut bytes = [0_u8; 16];
    bytes[..8].copy_from_slice(&(index as u64).to_be_bytes());
    bytes[8..].copy_from_slice(&0xBEEFu64.to_be_bytes());
    Uuid::from_bytes(bytes).to_string()
}

fn tiny_setup() -> WorkerGameSetup {
    WorkerGameSetup {
        difficulty: "Prince".to_owned(),
        speed: "Standard".to_owned(),
        starting_era: "Ancient era".to_owned(),
        victory_types: vec!["Domination".to_owned()],
        major_civilizations: 2,
        city_states: 0,
        max_turns: 500,
        map_type: GeneratedMapType::Pangaea,
        map_shape: GeneratedMapShape::Rectangular,
        map_size: GeneratedMapSize::Tiny,
        map_resources: MapResourceDensity::Default,
        barbarians: BarbarianMode::Disabled,
        one_city_challenge: false,
        nuclear_weapons_enabled: true,
        espionage_enabled: true,
        no_start_bias: false,
        shuffle_player_order: false,
        no_city_razing: false,
        world_wrap: false,
        strategic_balance: false,
        legendary_start: false,
        no_ruins: true,
        no_natural_wonders: true,
        owner_civilization_id: "Rome".to_owned(),
        ..WorkerGameSetup::default()
    }
}

fn representative_setup() -> WorkerGameSetup {
    WorkerGameSetup {
        difficulty: "Prince".to_owned(),
        speed: "Standard".to_owned(),
        starting_era: "Ancient era".to_owned(),
        victory_types: vec![
            "Domination".to_owned(),
            "Scientific".to_owned(),
            "Cultural".to_owned(),
            "Diplomatic".to_owned(),
        ],
        major_civilizations: 8,
        city_states: 12,
        max_turns: 500,
        map_type: GeneratedMapType::TwoContinents,
        map_shape: GeneratedMapShape::Rectangular,
        map_size: GeneratedMapSize::Large,
        map_resources: MapResourceDensity::Default,
        barbarians: BarbarianMode::Normal,
        one_city_challenge: false,
        nuclear_weapons_enabled: true,
        espionage_enabled: true,
        no_start_bias: false,
        shuffle_player_order: true,
        no_city_razing: false,
        world_wrap: false,
        strategic_balance: false,
        legendary_start: false,
        no_ruins: false,
        no_natural_wonders: false,
        owner_civilization_id: "Rome".to_owned(),
        ..WorkerGameSetup::default()
    }
}

fn summarize(samples: &[Duration]) -> serde_json::Value {
    let mut micros = samples.iter().map(Duration::as_micros).collect::<Vec<_>>();
    micros.sort_unstable();
    let total: u128 = micros.iter().sum();
    json!({
        "iterations": micros.len(),
        "mean_ms": round_millis(total as f64 / micros.len() as f64),
        "p50_ms": round_millis(percentile(&micros, 50) as f64),
        "p95_ms": round_millis(percentile(&micros, 95) as f64),
        "p99_ms": round_millis(percentile(&micros, 99) as f64),
        "min_ms": round_millis(micros[0] as f64),
        "max_ms": round_millis(micros[micros.len() - 1] as f64),
    })
}

fn summarize_counts(samples: &[usize]) -> serde_json::Value {
    let mut sorted = samples.to_vec();
    sorted.sort_unstable();
    let total: usize = sorted.iter().sum();
    json!({
        "samples": sorted.len(),
        "mean": total / sorted.len(),
        "min": sorted[0],
        "max": sorted[sorted.len() - 1],
    })
}

fn percentile(sorted: &[u128], percentile: usize) -> u128 {
    let index = ((sorted.len() - 1) * percentile).div_ceil(100);
    sorted[index]
}

fn round_millis(micros: f64) -> f64 {
    (micros / 10.0).round() / 100.0
}

fn environment_address() -> Result<SocketAddr, &'static str> {
    std::env::var("UNCIV_ENGINE_WORKER_ADDR")
        .unwrap_or_else(|_| "127.0.0.1:43170".to_owned())
        .parse()
        .map_err(|_| "UNCIV_ENGINE_WORKER_ADDR must be a socket address")
}

fn environment_identity() -> Result<WorkerIdentityKey, &'static str> {
    let secret = std::env::var("UNCIV_ENGINE_WORKER_SECRET")
        .map_err(|_| "UNCIV_ENGINE_WORKER_SECRET is required")?;
    WorkerIdentityKey::from_hex(&secret).map_err(|_| "UNCIV_ENGINE_WORKER_SECRET is invalid")
}

fn environment_iterations(name: &str, default: usize) -> Result<usize, &'static str> {
    let iterations = std::env::var(name)
        .ok()
        .map(|value| value.parse::<usize>())
        .transpose()
        .map_err(|_| "benchmark iteration count must be an integer")?
        .unwrap_or(default);
    if !(1..=10_000).contains(&iterations) {
        return Err("benchmark iteration count must be between 1 and 10000");
    }
    Ok(iterations)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn percentile_summary_is_stable_and_millisecond_scaled() {
        let report = summarize(&[
            Duration::from_micros(100),
            Duration::from_micros(200),
            Duration::from_micros(300),
            Duration::from_micros(400),
        ]);
        assert_eq!(report["iterations"], 4);
        assert_eq!(report["p50_ms"], 0.3);
        assert_eq!(report["p95_ms"], 0.4);
        assert_eq!(report["max_ms"], 0.4);
    }

    #[test]
    fn benchmark_game_ids_are_repeatable_and_distinct() {
        assert_eq!(deterministic_game_id(7), deterministic_game_id(7));
        assert_ne!(deterministic_game_id(7), deterministic_game_id(8));
    }
}
