use std::{
    collections::BTreeSet,
    fs::File,
    io::{Read, Take},
    net::SocketAddr,
    path::{Path, PathBuf},
    process::ExitCode,
};

use serde::Serialize;
use uuid::Uuid;

use crate::{
    MAX_SNAPSHOT_BYTES,
    postgres::{
        LegacyImportApplication, LegacyImportCandidateEvidence, LegacyImportConflictReport,
        LegacyImportProjectionEvidence, LegacyImportProjectionReport, LegacyImportRole,
        PostgresGameRepository, legacy_import_game_id,
    },
    state_hash,
    worker::{
        EngineWorkerClient, LegacyPlayerMapping, NormalizedLegacyGame, WorkerCircuitBreakerConfig,
        WorkerDeadlines, WorkerIdentityKey, WorkerManifest,
    },
};

const MAX_CANDIDATES: usize = 16;
const MAX_MAPPINGS: usize = 128;

#[derive(Debug, Eq, PartialEq)]
struct Arguments {
    operation_id: Uuid,
    owner_account_id: Uuid,
    operator_label: String,
    legacy_origin: String,
    legacy_game_id: String,
    ruleset_manifest_hash: String,
    selected_candidate_index: usize,
    candidates: Vec<PathBuf>,
    mappings: Vec<LegacyPlayerMapping>,
    apply: bool,
}

#[derive(Serialize)]
#[serde(rename_all = "snake_case")]
struct ImportReport {
    mode: &'static str,
    operation_id: Uuid,
    canonical_game_id: Uuid,
    selected_candidate_index: usize,
    conflict_report: LegacyImportConflictReport,
    projection_report: LegacyImportProjectionReport,
    canonical_state_hash: String,
    created: bool,
}

pub async fn run_legacy_import_cli() -> ExitCode {
    match run(std::env::args().skip(1)).await {
        Ok(report) => {
            println!(
                "{}",
                serde_json::to_string_pretty(&report)
                    .expect("legacy import report is serializable")
            );
            ExitCode::SUCCESS
        }
        Err(message) => {
            eprintln!("{message}");
            ExitCode::FAILURE
        }
    }
}

async fn run(arguments: impl Iterator<Item = String>) -> Result<ImportReport, &'static str> {
    let arguments = parse_arguments(arguments)?;
    let database_url = std::env::var("UNCIV_V3_DATABASE_URL")
        .map_err(|_| "UNCIV_V3_DATABASE_URL is required for legacy import")?;
    let worker_address = std::env::var("UNCIV_ENGINE_WORKER_ADDR")
        .unwrap_or_else(|_| "127.0.0.1:39031".to_owned())
        .parse::<SocketAddr>()
        .map_err(|_| "UNCIV_ENGINE_WORKER_ADDR must be a valid socket address")?;
    let worker_identity = std::env::var("UNCIV_ENGINE_WORKER_SECRET")
        .map_err(|_| ())
        .and_then(|value| WorkerIdentityKey::from_hex(&value).map_err(|_| ()))
        .map_err(|_| {
            "UNCIV_ENGINE_WORKER_SECRET must be exactly 32 bytes encoded as 64 hexadecimal characters"
        })?;
    let worker_deadlines = WorkerDeadlines::from_environment()
        .map_err(|_| "authoritative engine worker deadlines must be valid")?;
    let worker_circuit_breaker = WorkerCircuitBreakerConfig::from_environment()
        .map_err(|_| "authoritative engine worker circuit breaker must be valid")?;
    let worker_queue = crate::worker::WorkerQueueConfig::from_environment()
        .map_err(|_| "authoritative engine worker queue must be valid")?;
    let repository = PostgresGameRepository::connect(&database_url)
        .await
        .map_err(|_| "failed to connect to the authoritative database")?;
    let mapped_accounts = arguments
        .mappings
        .iter()
        .map(|mapping| {
            Uuid::parse_str(&mapping.account_id)
                .map_err(|_| "every mapped account ID must be a normalized UUID")
        })
        .collect::<Result<Vec<_>, _>>()?;
    let manifest = repository
        .validate_legacy_import_prerequisites(
            arguments.owner_account_id,
            &arguments.ruleset_manifest_hash,
            &mapped_accounts,
        )
        .await
        .map_err(|_| "legacy import prerequisites failed closed")?;
    let worker = EngineWorkerClient::with_runtime_policy(
        worker_address,
        worker_deadlines,
        worker_circuit_breaker,
        worker_queue,
        worker_identity,
    );
    execute_import(&repository, &worker, &manifest, arguments).await
}

async fn execute_import(
    repository: &PostgresGameRepository,
    worker: &EngineWorkerClient,
    manifest: &WorkerManifest,
    arguments: Arguments,
) -> Result<ImportReport, &'static str> {
    let canonical_game_id = legacy_import_game_id(arguments.operation_id);
    let mut normalized = Vec::with_capacity(arguments.candidates.len());
    let mut evidence = Vec::with_capacity(arguments.candidates.len());
    for (index, path) in arguments.candidates.iter().enumerate() {
        let canonical_path =
            std::fs::canonicalize(path).map_err(|_| "failed to resolve a legacy candidate path")?;
        let snapshot = read_bounded_candidate(&canonical_path)?;
        let source_hash = state_hash(snapshot.as_bytes());
        let normalized_game = worker
            .normalize_legacy_game(
                &arguments.owner_account_id.to_string(),
                manifest,
                &snapshot,
                &arguments.legacy_game_id,
                &canonical_game_id.to_string(),
                &arguments.mappings,
            )
            .await
            .map_err(|_| "legacy candidate normalization failed closed")?;
        evidence.push(LegacyImportCandidateEvidence {
            index: u32::try_from(index).map_err(|_| "too many legacy candidates")?,
            source_label: candidate_label(&canonical_path),
            source_path_hash: state_hash(canonical_path.as_os_str().to_string_lossy().as_bytes()),
            source_hash,
            source_bytes: u64::try_from(snapshot.len())
                .map_err(|_| "legacy candidate size overflow")?,
            normalized_state_hash: normalized_game.canonical_state_hash.clone(),
            metadata: normalized_game.metadata.clone(),
        });
        normalized.push(normalized_game);
    }
    let conflict_report = build_conflict_report(evidence);
    let selected = normalized
        .get(arguments.selected_candidate_index)
        .ok_or("selected legacy candidate does not exist")?
        .clone();
    let projection_report = build_projection_report(worker, manifest, &selected).await?;
    let canonical_state_hash = selected.canonical_state_hash.clone();
    let mut created = false;
    if arguments.apply {
        let outcome = repository
            .apply_legacy_import(LegacyImportApplication {
                operation_id: arguments.operation_id,
                owner_account_id: arguments.owner_account_id,
                operator_label: arguments.operator_label,
                legacy_origin: arguments.legacy_origin,
                legacy_game_id: arguments.legacy_game_id,
                ruleset_manifest_hash: arguments.ruleset_manifest_hash,
                selected_candidate_index: u32::try_from(arguments.selected_candidate_index)
                    .map_err(|_| "selected candidate index overflow")?,
                conflict_report: conflict_report.clone(),
                projection_report: projection_report.clone(),
                normalized_game: selected,
            })
            .await
            .map_err(|_| "atomic legacy import failed closed")?;
        if outcome.game_id != canonical_game_id
            || outcome.canonical_state_hash != canonical_state_hash
        {
            return Err("legacy import persistence returned inconsistent identity");
        }
        created = outcome.created;
    }
    Ok(ImportReport {
        mode: if arguments.apply { "apply" } else { "dry_run" },
        operation_id: arguments.operation_id,
        canonical_game_id,
        selected_candidate_index: arguments.selected_candidate_index,
        conflict_report,
        projection_report,
        canonical_state_hash,
        created,
    })
}

async fn build_projection_report(
    worker: &EngineWorkerClient,
    manifest: &WorkerManifest,
    normalized: &NormalizedLegacyGame,
) -> Result<LegacyImportProjectionReport, &'static str> {
    let legacy_identities = normalized
        .metadata
        .members
        .iter()
        .map(|member| member.legacy_player_id.as_bytes())
        .collect::<Vec<_>>();
    let mut projections = Vec::with_capacity(normalized.metadata.members.len());
    for member in &normalized.metadata.members {
        let account_id = Uuid::parse_str(&member.account_id)
            .map_err(|_| "worker returned an invalid account")?;
        let (role, civilization_id, serialized) = if member.spectator {
            let projection = worker
                .project_spectator_state(&member.account_id, manifest, &normalized.snapshot)
                .await
                .map_err(|_| "legacy spectator projection failed closed")?
                .projection;
            (
                LegacyImportRole::Spectator,
                None,
                serde_json::to_vec(&projection)
                    .map_err(|_| "legacy spectator projection was not serializable")?,
            )
        } else {
            let projection = worker
                .project_state(
                    &member.account_id,
                    manifest,
                    &normalized.snapshot,
                    &member.civilization_id,
                )
                .await
                .map_err(|_| "legacy player projection failed closed")?
                .projection;
            let role = if member.civilization_id == normalized.owner_civilization_id {
                LegacyImportRole::Owner
            } else {
                LegacyImportRole::Player
            };
            (
                role,
                Some(member.civilization_id.clone()),
                serde_json::to_vec(&projection)
                    .map_err(|_| "legacy player projection was not serializable")?,
            )
        };
        let identity_leak_scan_passed = legacy_identities
            .iter()
            .all(|identity| !contains_bytes(&serialized, identity));
        projections.push(LegacyImportProjectionEvidence {
            account_id,
            role,
            civilization_id,
            projection_hash: state_hash(&serialized),
            serialized_bytes: u64::try_from(serialized.len())
                .map_err(|_| "legacy projection size overflow")?,
            identity_leak_scan_passed,
        });
    }
    projections.sort_by_key(|projection| projection.account_id);
    let mut checked_legacy_identity_hashes = normalized
        .metadata
        .members
        .iter()
        .map(|member| state_hash(member.legacy_player_id.as_bytes()))
        .collect::<Vec<_>>();
    checked_legacy_identity_hashes.sort();
    if projections
        .iter()
        .any(|projection| !projection.identity_leak_scan_passed)
    {
        return Err("legacy identity leaked into a player projection");
    }
    Ok(LegacyImportProjectionReport {
        projections,
        checked_legacy_identity_hashes,
    })
}

fn build_conflict_report(
    candidates: Vec<LegacyImportCandidateEvidence>,
) -> LegacyImportConflictReport {
    let turns_differ = distinct_count(candidates.iter().map(|item| item.metadata.turns)) > 1;
    let current_player_differs = distinct_count(
        candidates
            .iter()
            .map(|item| item.metadata.current_player.as_str()),
    ) > 1;
    let normalized_hashes_differ = distinct_count(
        candidates
            .iter()
            .map(|item| item.normalized_state_hash.as_str()),
    ) > 1;
    LegacyImportConflictReport {
        divergent: turns_differ || current_player_differs || normalized_hashes_differ,
        turns_differ,
        current_player_differs,
        normalized_hashes_differ,
        candidates,
    }
}

fn distinct_count<T: Ord>(values: impl Iterator<Item = T>) -> usize {
    values.collect::<BTreeSet<_>>().len()
}

fn read_bounded_candidate(path: &Path) -> Result<String, &'static str> {
    let file = File::open(path).map_err(|_| "failed to open a legacy candidate")?;
    let maximum = u64::try_from(MAX_SNAPSHOT_BYTES).expect("snapshot bound fits u64");
    let mut bytes = Vec::new();
    let mut bounded: Take<File> = file.take(maximum + 1);
    bounded
        .read_to_end(&mut bytes)
        .map_err(|_| "failed to read a legacy candidate")?;
    if bytes.is_empty() || bytes.len() > MAX_SNAPSHOT_BYTES {
        return Err("legacy candidate is empty or exceeds the import byte limit");
    }
    String::from_utf8(bytes).map_err(|_| "legacy candidate is not UTF-8")
}

fn candidate_label(path: &Path) -> String {
    path.file_name()
        .map(|name| name.to_string_lossy().into_owned())
        .filter(|name| !name.is_empty() && name.len() <= 256)
        .unwrap_or_else(|| "legacy-candidate".to_owned())
}

fn contains_bytes(haystack: &[u8], needle: &[u8]) -> bool {
    !needle.is_empty()
        && haystack
            .windows(needle.len())
            .any(|window| window == needle)
}

fn parse_arguments(arguments: impl Iterator<Item = String>) -> Result<Arguments, &'static str> {
    let mut operation_id = None;
    let mut owner_account_id = None;
    let mut operator_label = None;
    let mut legacy_origin = None;
    let mut legacy_game_id = None;
    let mut ruleset_manifest_hash = None;
    let mut selected_candidate_index = None;
    let mut candidates = Vec::new();
    let mut mappings = Vec::new();
    let mut apply = false;
    let mut arguments = arguments;
    while let Some(argument) = arguments.next() {
        match argument.as_str() {
            "--operation-id" => {
                operation_id = Some(parse_uuid(arguments.next(), "invalid operation ID")?)
            }
            "--owner-account-id" => {
                owner_account_id = Some(parse_uuid(arguments.next(), "invalid owner account ID")?)
            }
            "--operator" => operator_label = arguments.next(),
            "--origin" => legacy_origin = arguments.next(),
            "--legacy-game-id" => legacy_game_id = arguments.next(),
            "--manifest" => ruleset_manifest_hash = arguments.next(),
            "--select" => {
                selected_candidate_index = Some(
                    arguments
                        .next()
                        .ok_or("missing selected candidate index")?
                        .parse::<usize>()
                        .map_err(|_| "invalid selected candidate index")?,
                )
            }
            "--candidate" => candidates.push(PathBuf::from(
                arguments.next().ok_or("missing legacy candidate path")?,
            )),
            "--map-player" => {
                let mapping = arguments.next().ok_or("missing player mapping")?;
                let (legacy_player_id, account_id) = mapping
                    .split_once('=')
                    .ok_or("player mapping must be LEGACY_ID=ACCOUNT_UUID")?;
                let account_id = Uuid::parse_str(account_id)
                    .map_err(|_| "player mapping account must be a UUID")?;
                mappings.push(LegacyPlayerMapping {
                    legacy_player_id: legacy_player_id.to_owned(),
                    account_id: account_id.to_string(),
                });
            }
            "--apply" => apply = true,
            _ => return Err(usage()),
        }
    }
    let result = Arguments {
        operation_id: operation_id.ok_or(usage())?,
        owner_account_id: owner_account_id.ok_or(usage())?,
        operator_label: operator_label.ok_or(usage())?,
        legacy_origin: legacy_origin.ok_or(usage())?,
        legacy_game_id: legacy_game_id.ok_or(usage())?,
        ruleset_manifest_hash: ruleset_manifest_hash.ok_or(usage())?,
        selected_candidate_index: selected_candidate_index.ok_or(usage())?,
        candidates,
        mappings,
        apply,
    };
    if result.operation_id.is_nil()
        || result.owner_account_id.is_nil()
        || !bounded_text(&result.operator_label, 128)
        || !bounded_text(&result.legacy_origin, 256)
        || !bounded_text(&result.legacy_game_id, 256)
        || result.ruleset_manifest_hash.len() != 64
        || !result
            .ruleset_manifest_hash
            .bytes()
            .all(|byte| byte.is_ascii_hexdigit())
        || result.candidates.is_empty()
        || result.candidates.len() > MAX_CANDIDATES
        || result.selected_candidate_index >= result.candidates.len()
        || result.mappings.is_empty()
        || result.mappings.len() > MAX_MAPPINGS
        || distinct_count(
            result
                .mappings
                .iter()
                .map(|mapping| mapping.legacy_player_id.as_str()),
        ) != result.mappings.len()
        || distinct_count(
            result
                .mappings
                .iter()
                .map(|mapping| mapping.account_id.as_str()),
        ) != result.mappings.len()
        || result
            .mappings
            .iter()
            .any(|mapping| !bounded_text(&mapping.legacy_player_id, 256))
    {
        return Err("legacy import arguments violate required bounds");
    }
    Ok(result)
}

fn parse_uuid(value: Option<String>, error: &'static str) -> Result<Uuid, &'static str> {
    Uuid::parse_str(&value.ok_or(error)?).map_err(|_| error)
}

fn bounded_text(value: &str, maximum: usize) -> bool {
    !value.trim().is_empty() && value.len() <= maximum && !value.chars().any(char::is_control)
}

fn usage() -> &'static str {
    "usage: unciv-v3-import-legacy --operation-id <uuid> --owner-account-id <uuid> \
--operator <label> --origin <legacy-origin> --legacy-game-id <legacy-id> \
--manifest <sha256> --candidate <path> [--candidate <path> ...] --select <zero-based-index> \
--map-player <legacy-id=account-uuid> [--map-player ...] [--apply]"
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::worker::{LegacyImportMetadata, LegacyImportedMember};

    fn arguments(extra: &[&str]) -> Vec<String> {
        let mut values = vec![
            "--operation-id",
            "00000000-0000-4000-8000-000000000001",
            "--owner-account-id",
            "00000000-0000-4000-8000-000000000002",
            "--operator",
            "migration-admin",
            "--origin",
            "legacy.example",
            "--legacy-game-id",
            "legacy-game",
            "--manifest",
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "--candidate",
            "first.json",
            "--select",
            "0",
            "--map-player",
            "legacy-owner=00000000-0000-4000-8000-000000000002",
        ]
        .into_iter()
        .map(str::to_owned)
        .collect::<Vec<_>>();
        values.extend(extra.iter().map(|value| (*value).to_owned()));
        values
    }

    fn evidence(
        index: u32,
        turns: i32,
        current: &str,
        hash: &str,
    ) -> LegacyImportCandidateEvidence {
        LegacyImportCandidateEvidence {
            index,
            source_label: format!("candidate-{index}.json"),
            source_path_hash: "a".repeat(64),
            source_hash: "b".repeat(64),
            source_bytes: 100,
            normalized_state_hash: hash.repeat(64),
            metadata: LegacyImportMetadata {
                legacy_game_id: "legacy-game".to_owned(),
                canonical_game_id: "00000000-0000-5000-8000-000000000001".to_owned(),
                serialization_version: 4,
                created_with: "test".to_owned(),
                turns,
                current_player: current.to_owned(),
                base_ruleset: "Base".to_owned(),
                mods: vec![],
                members: vec![LegacyImportedMember {
                    legacy_player_id: "legacy-owner".to_owned(),
                    account_id: "00000000-0000-4000-8000-000000000002".to_owned(),
                    civilization_id: "Rome".to_owned(),
                    spectator: false,
                }],
            },
        }
    }

    #[test]
    fn parser_is_dry_run_by_default_and_requires_explicit_selection() {
        let parsed = parse_arguments(arguments(&[]).into_iter()).unwrap();
        assert!(!parsed.apply);
        assert_eq!(parsed.selected_candidate_index, 0);
        assert_eq!(parsed.candidates, [PathBuf::from("first.json")]);
    }

    #[test]
    fn parser_rejects_changed_identity_mapping_and_out_of_range_selection() {
        let mut duplicate =
            arguments(&["--map-player", "other=00000000-0000-4000-8000-000000000002"]);
        assert!(parse_arguments(duplicate.drain(..)).is_err());
        let selected = arguments(&["--select", "1"]);
        assert!(parse_arguments(selected.into_iter()).is_err());
    }

    #[test]
    fn conflict_report_discloses_each_divergence_dimension() {
        let report = build_conflict_report(vec![
            evidence(0, 10, "Rome", "a"),
            evidence(1, 11, "Greece", "b"),
        ]);
        assert!(report.divergent);
        assert!(report.turns_differ);
        assert!(report.current_player_differs);
        assert!(report.normalized_hashes_differ);
    }

    #[test]
    fn byte_scan_finds_embedded_legacy_identity() {
        assert!(contains_bytes(b"{\"player\":\"secret-id\"}", b"secret-id"));
        assert!(!contains_bytes(
            b"{\"player\":\"account-id\"}",
            b"secret-id"
        ));
    }
}
