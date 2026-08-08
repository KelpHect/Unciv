use std::fs;
use std::path::{Path, PathBuf};
use std::time::Instant;

use serde::Serialize;
use unciv_authoritative_server::{snapshot_delta::SnapshotDelta, state_hash};

#[derive(Serialize)]
struct BenchmarkReport {
    input_directory: String,
    snapshots: usize,
    total_uncompressed_bytes: usize,
    full_zstd: Vec<CodecResult>,
    shared_dictionary: Vec<DictionaryResult>,
    delta_against_previous: StrategyResult,
    checkpoint_strategies: Vec<CheckpointResult>,
}

#[derive(Serialize)]
struct CodecResult {
    level: i32,
    total_compressed_bytes: usize,
    average_compressed_bytes: usize,
    total_encode_millis: u128,
}

#[derive(Serialize)]
struct DictionaryResult {
    level: i32,
    dictionary_bytes: usize,
    total_compressed_bytes: usize,
    total_storage_bytes: usize,
    average_compressed_bytes: usize,
    total_encode_millis: u128,
    verified_round_trips: usize,
}

#[derive(Serialize)]
struct StrategyResult {
    total_bytes: usize,
    average_bytes: usize,
    delta_count: usize,
    fallback_full_count: usize,
    total_encode_millis: u128,
    verified_round_trips: usize,
}

#[derive(Serialize)]
struct CheckpointResult {
    interval: usize,
    checkpoint_count: usize,
    total_bytes: usize,
    checkpoint_bytes: usize,
    delta_bytes: usize,
    total_encode_millis: u128,
    verified_round_trips: usize,
}

fn main() {
    let (directory, fast) = parse_arguments();
    let samples = load_samples(&directory);
    if samples.len() < 2 {
        eprintln!(
            "need at least two .json snapshots in {}",
            directory.display()
        );
        std::process::exit(2);
    }

    let full_levels: &[i32] = if fast { &[3, 9] } else { &[3, 9, 15, 19] };
    let full_zstd = full_levels
        .iter()
        .copied()
        .map(|level| benchmark_full(&samples, level))
        .collect();
    let shared_dictionary = benchmark_dictionary(&samples, fast);
    let delta_against_previous = benchmark_previous_deltas(&samples);
    let checkpoint_strategies = [10, 64, 100]
        .into_iter()
        .map(|interval| benchmark_checkpoints(&samples, interval))
        .collect();
    let total_uncompressed_bytes = samples.iter().map(Vec::len).sum();
    let report = BenchmarkReport {
        input_directory: directory.display().to_string(),
        snapshots: samples.len(),
        total_uncompressed_bytes,
        full_zstd,
        shared_dictionary,
        delta_against_previous,
        checkpoint_strategies,
    };
    println!("{}", serde_json::to_string_pretty(&report).unwrap());
}

fn parse_arguments() -> (PathBuf, bool) {
    let mut args = std::env::args().skip(1);
    let mut directory = None;
    let mut fast = false;
    while let Some(arg) = args.next() {
        match arg.as_str() {
            "--dir" => directory = Some(PathBuf::from(args.next().unwrap_or_default())),
            "--fast" => fast = true,
            _ => {
                eprintln!(
                    "usage: unciv-v3-snapshot-benchmark --dir <snapshot-json-directory> [--fast]"
                );
                std::process::exit(2);
            }
        }
    }
    (directory.unwrap_or_default(), fast)
}

fn load_samples(directory: &Path) -> Vec<Vec<u8>> {
    let mut paths: Vec<_> = fs::read_dir(directory)
        .unwrap_or_else(|error| panic!("cannot read {}: {error}", directory.display()))
        .filter_map(Result::ok)
        .map(|entry| entry.path())
        .filter(|path| path.extension().and_then(|value| value.to_str()) == Some("json"))
        .collect();
    paths.sort();
    paths
        .into_iter()
        .map(|path| {
            fs::read(&path)
                .unwrap_or_else(|error| panic!("cannot read {}: {error}", path.display()))
        })
        .collect()
}

fn benchmark_full(samples: &[Vec<u8>], level: i32) -> CodecResult {
    let started = Instant::now();
    let sizes: Vec<_> = samples
        .iter()
        .map(|sample| {
            zstd::stream::encode_all(sample.as_slice(), level)
                .unwrap()
                .len()
        })
        .collect();
    let total_compressed_bytes = sizes.iter().sum();
    CodecResult {
        level,
        total_compressed_bytes,
        average_compressed_bytes: total_compressed_bytes / sizes.len(),
        total_encode_millis: started.elapsed().as_millis(),
    }
}

fn benchmark_dictionary(samples: &[Vec<u8>], fast: bool) -> Vec<DictionaryResult> {
    let Ok(dictionary) = zstd::dict::from_samples(samples, 128 * 1024) else {
        return Vec::new();
    };
    let levels: &[i32] = if fast { &[9] } else { &[9, 19] };
    levels
        .iter()
        .copied()
        .map(|level| {
            let started = Instant::now();
            let mut total_compressed_bytes = 0;
            let mut verified_round_trips = 0;
            for sample in samples {
                let mut compressor =
                    zstd::bulk::Compressor::with_dictionary(level, &dictionary).unwrap();
                let payload = compressor.compress(sample).unwrap();
                total_compressed_bytes += payload.len();
                let mut decompressor =
                    zstd::bulk::Decompressor::with_dictionary(&dictionary).unwrap();
                let decoded = decompressor.decompress(&payload, sample.len()).unwrap();
                assert_eq!(decoded, *sample);
                verified_round_trips += 1;
            }
            DictionaryResult {
                level,
                dictionary_bytes: dictionary.len(),
                total_compressed_bytes,
                total_storage_bytes: dictionary.len() + total_compressed_bytes,
                average_compressed_bytes: total_compressed_bytes / samples.len(),
                total_encode_millis: started.elapsed().as_millis(),
                verified_round_trips,
            }
        })
        .collect()
}

fn benchmark_previous_deltas(samples: &[Vec<u8>]) -> StrategyResult {
    let started = Instant::now();
    let mut total_bytes = 0;
    let mut delta_count = 0;
    let mut fallback_full_count = 0;
    let mut verified_round_trips = 0;
    for (revision, pair) in samples.windows(2).enumerate() {
        let full = zstd::stream::encode_all(pair[1].as_slice(), 9).unwrap();
        let delta = SnapshotDelta::encode(revision as u64, &pair[0], &pair[1], 9).unwrap();
        if delta.payload.len() < full.len() {
            total_bytes += delta.payload.len();
            delta_count += 1;
            assert_eq!(delta.decode(&pair[0]).unwrap(), pair[1]);
            verified_round_trips += 1;
        } else {
            total_bytes += full.len();
            fallback_full_count += 1;
        }
    }
    StrategyResult {
        total_bytes,
        average_bytes: total_bytes / samples.len().saturating_sub(1).max(1),
        delta_count,
        fallback_full_count,
        total_encode_millis: started.elapsed().as_millis(),
        verified_round_trips,
    }
}

fn benchmark_checkpoints(samples: &[Vec<u8>], interval: usize) -> CheckpointResult {
    let started = Instant::now();
    let mut checkpoint_bytes = 0;
    let mut delta_bytes = 0;
    let mut checkpoint_count = 0;
    let mut verified_round_trips = 0;
    for (revision, target) in samples.iter().enumerate() {
        if revision % interval == 0 {
            checkpoint_bytes += zstd::stream::encode_all(target.as_slice(), 9)
                .unwrap()
                .len();
            checkpoint_count += 1;
            continue;
        }
        let base_revision = revision - (revision % interval);
        let base = &samples[base_revision];
        let full = zstd::stream::encode_all(target.as_slice(), 9).unwrap();
        let delta = SnapshotDelta::encode(base_revision as u64, base, target, 9).unwrap();
        if delta.payload.len() < full.len() {
            delta_bytes += delta.payload.len();
            assert_eq!(delta.decode(base).unwrap(), *target);
            verified_round_trips += 1;
        } else {
            delta_bytes += full.len();
        }
    }
    CheckpointResult {
        interval,
        checkpoint_count,
        total_bytes: checkpoint_bytes + delta_bytes,
        checkpoint_bytes,
        delta_bytes,
        total_encode_millis: started.elapsed().as_millis(),
        verified_round_trips,
    }
}

#[allow(dead_code)]
fn _hash_samples(samples: &[Vec<u8>]) -> Vec<String> {
    samples.iter().map(|sample| state_hash(sample)).collect()
}
