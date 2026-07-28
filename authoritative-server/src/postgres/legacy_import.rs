use std::collections::BTreeSet;

use serde::{Deserialize, Serialize};

use super::*;
use crate::worker::{LegacyImportMetadata, NormalizedLegacyGame};

const LEGACY_IMPORT_NAMESPACE: Uuid = Uuid::from_u128(0x7d0a_b76e_9b8d_5e71_aa8f_f5bd_4b22_35c1);
const MAX_IMPORT_CANDIDATES: usize = 16;
const MAX_IMPORT_MEMBERS: usize = 128;
const MAX_IMPORT_REPORT_BYTES: usize = 1024 * 1024;

#[derive(Clone, Copy, Debug, Deserialize, Eq, Ord, PartialEq, PartialOrd, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum LegacyImportRole {
    Owner,
    Player,
    Spectator,
}

impl LegacyImportRole {
    fn as_database_role(self) -> &'static str {
        match self {
            Self::Owner => "owner",
            Self::Player => "player",
            Self::Spectator => "spectator",
        }
    }
}

#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "snake_case", deny_unknown_fields)]
pub struct LegacyImportCandidateEvidence {
    pub index: u32,
    pub source_label: String,
    pub source_path_hash: String,
    pub source_hash: String,
    pub source_bytes: u64,
    pub normalized_state_hash: String,
    pub metadata: LegacyImportMetadata,
}

#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "snake_case", deny_unknown_fields)]
pub struct LegacyImportConflictReport {
    pub candidates: Vec<LegacyImportCandidateEvidence>,
    pub divergent: bool,
    pub turns_differ: bool,
    pub current_player_differs: bool,
    pub normalized_hashes_differ: bool,
}

#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "snake_case", deny_unknown_fields)]
pub struct LegacyImportProjectionEvidence {
    pub account_id: Uuid,
    pub role: LegacyImportRole,
    pub civilization_id: Option<String>,
    pub projection_hash: String,
    pub serialized_bytes: u64,
    pub identity_leak_scan_passed: bool,
}

#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "snake_case", deny_unknown_fields)]
pub struct LegacyImportProjectionReport {
    pub projections: Vec<LegacyImportProjectionEvidence>,
    pub checked_legacy_identity_hashes: Vec<String>,
}

#[derive(Clone, Debug)]
pub struct LegacyImportApplication {
    pub operation_id: Uuid,
    pub owner_account_id: Uuid,
    pub operator_label: String,
    pub legacy_origin: String,
    pub legacy_game_id: String,
    pub ruleset_manifest_hash: String,
    pub selected_candidate_index: u32,
    pub conflict_report: LegacyImportConflictReport,
    pub projection_report: LegacyImportProjectionReport,
    pub normalized_game: NormalizedLegacyGame,
}

#[derive(Clone, Debug, Eq, PartialEq, Serialize)]
#[serde(rename_all = "snake_case")]
pub struct LegacyImportOutcome {
    pub game_id: Uuid,
    pub canonical_state_hash: String,
    pub created: bool,
}

struct ValidatedImport {
    request: serde_json::Value,
    selected: LegacyImportCandidateEvidence,
    memberships: Vec<(Uuid, LegacyImportRole, Option<String>)>,
}

pub fn legacy_import_game_id(operation_id: Uuid) -> Uuid {
    Uuid::new_v5(&LEGACY_IMPORT_NAMESPACE, operation_id.as_bytes())
}

impl PostgresGameRepository {
    /// Read-only prerequisite validation used by dry runs. The apply transaction
    /// repeats these checks under locks, so this result is never authoritative.
    pub async fn validate_legacy_import_prerequisites(
        &self,
        owner_account_id: Uuid,
        ruleset_manifest_hash: &str,
        mapped_account_ids: &[Uuid],
    ) -> Result<WorkerManifest, CommitError> {
        validate_account_ids(owner_account_id, mapped_account_ids)?;
        let active_ids: Vec<Uuid> = sqlx::query_scalar(
            "SELECT id FROM accounts WHERE id=ANY($1::uuid[]) AND disabled_at IS NULL AND deleted_at IS NULL",
        )
        .bind(mapped_account_ids)
        .fetch_all(&self.pool)
        .await
        .map_err(CommitError::storage)?;
        if active_ids.len() != mapped_account_ids.len() {
            return Err(CommitError::Unauthorized);
        }
        load_installed_manifest(&self.pool, ruleset_manifest_hash).await
    }

    /// Atomically creates one imported game at revision zero and records the
    /// selected source plus conflict/projection evidence. The operation ID is
    /// idempotent, while `(legacy_origin, legacy_game_id)` may be imported only
    /// once even if a different operation ID is attempted later.
    pub async fn apply_legacy_import(
        &self,
        application: LegacyImportApplication,
    ) -> Result<LegacyImportOutcome, CommitError> {
        let validated = validate_application(&application)?;
        let game_id = legacy_import_game_id(application.operation_id);
        let mut tx = self.pool.begin().await.map_err(CommitError::storage)?;
        sqlx::query("SELECT pg_advisory_xact_lock(hashtextextended($1, 0))")
            .bind(format!(
                "legacy-import-operation:{}",
                application.operation_id
            ))
            .execute(&mut *tx)
            .await
            .map_err(CommitError::storage)?;

        if let Some(row) = sqlx::query(
            "SELECT owner_account_id, request, game_id, canonical_state_hash FROM legacy_game_imports WHERE operation_id=$1",
        )
        .bind(application.operation_id)
        .fetch_optional(&mut *tx)
        .await
        .map_err(CommitError::storage)?
        {
            if row.get::<Uuid, _>("owner_account_id") != application.owner_account_id
                || row.get::<serde_json::Value, _>("request") != validated.request
            {
                return Err(CommitError::InvalidCommand);
            }
            let outcome = LegacyImportOutcome {
                game_id: row.get("game_id"),
                canonical_state_hash: row.get::<String, _>("canonical_state_hash").trim_end().to_owned(),
                created: false,
            };
            tx.commit().await.map_err(CommitError::storage)?;
            return Ok(outcome);
        }

        sqlx::query("SELECT pg_advisory_xact_lock(hashtextextended($1, 0))")
            .bind(format!(
                "legacy-import-source:{}:{}",
                application.legacy_origin, application.legacy_game_id
            ))
            .execute(&mut *tx)
            .await
            .map_err(CommitError::storage)?;
        let already_imported: bool = sqlx::query_scalar(
            "SELECT EXISTS(SELECT 1 FROM legacy_game_imports WHERE legacy_origin=$1 AND legacy_game_id=$2)",
        )
        .bind(&application.legacy_origin)
        .bind(&application.legacy_game_id)
        .fetch_one(&mut *tx)
        .await
        .map_err(CommitError::storage)?;
        if already_imported {
            return Err(CommitError::InvalidCommand);
        }

        sqlx::query("SELECT pg_advisory_xact_lock(hashtextextended('ruleset-asset:' || $1, 0))")
            .bind(&application.ruleset_manifest_hash)
            .execute(&mut *tx)
            .await
            .map_err(CommitError::storage)?;
        let manifest =
            load_installed_manifest_in_transaction(&mut tx, &application.ruleset_manifest_hash)
                .await?;
        if manifest.base_ruleset.name != application.normalized_game.metadata.base_ruleset
            || sorted_mod_names(&manifest) != application.normalized_game.metadata.mods
        {
            return Err(CommitError::WorkerRevisionMismatch);
        }

        let account_ids = validated
            .memberships
            .iter()
            .map(|(account_id, _, _)| *account_id)
            .collect::<Vec<_>>();
        let active_ids: Vec<Uuid> = sqlx::query_scalar(
            "SELECT id FROM accounts WHERE id=ANY($1::uuid[]) AND disabled_at IS NULL AND deleted_at IS NULL FOR KEY SHARE",
        )
        .bind(&account_ids)
        .fetch_all(&mut *tx)
        .await
        .map_err(CommitError::storage)?;
        if active_ids.len() != account_ids.len() {
            return Err(CommitError::Unauthorized);
        }

        self.create_game_in_transaction(
            &mut tx,
            NewGame {
                game_id,
                owner_account_id: application.owner_account_id,
                ruleset_manifest_hash: application.ruleset_manifest_hash.clone(),
                snapshot: application.normalized_game.snapshot.as_bytes().to_vec(),
                owner_civilization_id: application.normalized_game.owner_civilization_id.clone(),
            },
        )
        .await?;
        for (account_id, role, civilization_id) in &validated.memberships {
            if *role == LegacyImportRole::Owner {
                continue;
            }
            sqlx::query(
                "INSERT INTO game_members (game_id, account_id, role, civilization_id) VALUES ($1, $2, $3, $4)",
            )
            .bind(game_id)
            .bind(account_id)
            .bind(role.as_database_role())
            .bind(civilization_id)
            .execute(&mut *tx)
            .await
            .map_err(CommitError::storage)?;
        }

        let candidate_report = serde_json::to_value(&application.conflict_report)
            .map_err(|_| CommitError::InvalidCommand)?;
        let projection_report = serde_json::to_value(&application.projection_report)
            .map_err(|_| CommitError::InvalidCommand)?;
        sqlx::query(
            "INSERT INTO legacy_game_imports (
                operation_id, owner_account_id, operator_label, legacy_origin, legacy_game_id,
                ruleset_manifest_hash, selected_candidate_index, selected_source_label,
                selected_source_path_hash, selected_source_hash, candidate_report,
                projection_report, request, game_id, canonical_state_hash
             ) VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15)",
        )
        .bind(application.operation_id)
        .bind(application.owner_account_id)
        .bind(&application.operator_label)
        .bind(&application.legacy_origin)
        .bind(&application.legacy_game_id)
        .bind(&application.ruleset_manifest_hash)
        .bind(
            i32::try_from(application.selected_candidate_index)
                .map_err(|_| CommitError::InvalidCommand)?,
        )
        .bind(&validated.selected.source_label)
        .bind(&validated.selected.source_path_hash)
        .bind(&validated.selected.source_hash)
        .bind(candidate_report)
        .bind(projection_report)
        .bind(&validated.request)
        .bind(game_id)
        .bind(&application.normalized_game.canonical_state_hash)
        .execute(&mut *tx)
        .await
        .map_err(CommitError::storage)?;
        tx.commit().await.map_err(CommitError::storage)?;
        Ok(LegacyImportOutcome {
            game_id,
            canonical_state_hash: application.normalized_game.canonical_state_hash,
            created: true,
        })
    }
}

fn validate_application(
    application: &LegacyImportApplication,
) -> Result<ValidatedImport, CommitError> {
    if !bounded_text(&application.operator_label, 128)
        || !bounded_text(&application.legacy_origin, 256)
        || !bounded_text(&application.legacy_game_id, 256)
        || !is_sha256(&application.ruleset_manifest_hash)
        || application.normalized_game.snapshot.is_empty()
        || application.normalized_game.snapshot.len() > MAX_SNAPSHOT_BYTES
        || state_hash(application.normalized_game.snapshot.as_bytes())
            != application.normalized_game.canonical_state_hash
        || !is_sha256(&application.normalized_game.canonical_state_hash)
        || application.normalized_game.metadata.legacy_game_id != application.legacy_game_id
        || application.normalized_game.metadata.canonical_game_id
            != legacy_import_game_id(application.operation_id).to_string()
    {
        return Err(CommitError::InvalidCommand);
    }
    validate_conflict_report(&application.conflict_report)?;
    let selected = application
        .conflict_report
        .candidates
        .get(application.selected_candidate_index as usize)
        .filter(|candidate| candidate.index == application.selected_candidate_index)
        .cloned()
        .ok_or(CommitError::InvalidCommand)?;
    if selected.metadata != application.normalized_game.metadata
        || selected.normalized_state_hash != application.normalized_game.canonical_state_hash
    {
        return Err(CommitError::InvalidCommand);
    }

    let memberships = validated_memberships(application)?;
    validate_projection_report(application, &memberships)?;
    let request = json!({
        "legacy_origin": application.legacy_origin,
        "legacy_game_id": application.legacy_game_id,
        "ruleset_manifest_hash": application.ruleset_manifest_hash,
        "selected_source_hash": selected.source_hash,
        "selected_normalized_state_hash": selected.normalized_state_hash,
        "members": application.normalized_game.metadata.members,
    });
    if serde_json::to_vec(&application.conflict_report)
        .map_err(|_| CommitError::InvalidCommand)?
        .len()
        > MAX_IMPORT_REPORT_BYTES
        || serde_json::to_vec(&application.projection_report)
            .map_err(|_| CommitError::InvalidCommand)?
            .len()
            > MAX_IMPORT_REPORT_BYTES
    {
        return Err(CommitError::InvalidCommand);
    }
    Ok(ValidatedImport {
        request,
        selected,
        memberships,
    })
}

fn validate_conflict_report(report: &LegacyImportConflictReport) -> Result<(), CommitError> {
    if report.candidates.is_empty() || report.candidates.len() > MAX_IMPORT_CANDIDATES {
        return Err(CommitError::InvalidCommand);
    }
    for (index, candidate) in report.candidates.iter().enumerate() {
        if candidate.index as usize != index
            || !bounded_text(&candidate.source_label, 256)
            || !is_sha256(&candidate.source_path_hash)
            || !is_sha256(&candidate.source_hash)
            || !is_sha256(&candidate.normalized_state_hash)
            || candidate.source_bytes == 0
            || candidate.source_bytes > MAX_SNAPSHOT_BYTES as u64
        {
            return Err(CommitError::InvalidCommand);
        }
    }
    let turns_differ = report
        .candidates
        .iter()
        .map(|candidate| candidate.metadata.turns)
        .collect::<BTreeSet<_>>()
        .len()
        > 1;
    let current_player_differs = report
        .candidates
        .iter()
        .map(|candidate| &candidate.metadata.current_player)
        .collect::<BTreeSet<_>>()
        .len()
        > 1;
    let normalized_hashes_differ = report
        .candidates
        .iter()
        .map(|candidate| &candidate.normalized_state_hash)
        .collect::<BTreeSet<_>>()
        .len()
        > 1;
    if report.turns_differ != turns_differ
        || report.current_player_differs != current_player_differs
        || report.normalized_hashes_differ != normalized_hashes_differ
        || report.divergent != normalized_hashes_differ
    {
        return Err(CommitError::InvalidCommand);
    }
    Ok(())
}

fn validated_memberships(
    application: &LegacyImportApplication,
) -> Result<Vec<(Uuid, LegacyImportRole, Option<String>)>, CommitError> {
    let metadata = &application.normalized_game.metadata;
    if metadata.members.is_empty() || metadata.members.len() > MAX_IMPORT_MEMBERS {
        return Err(CommitError::InvalidCommand);
    }
    let mut accounts = BTreeSet::new();
    let mut legacy_players = BTreeSet::new();
    let mut civilizations = BTreeSet::new();
    let mut memberships = Vec::with_capacity(metadata.members.len());
    for member in &metadata.members {
        let account_id =
            Uuid::parse_str(&member.account_id).map_err(|_| CommitError::InvalidCommand)?;
        if !accounts.insert(account_id)
            || !legacy_players.insert(&member.legacy_player_id)
            || !civilizations.insert(&member.civilization_id)
            || !bounded_text(&member.legacy_player_id, 256)
            || !bounded_text(&member.civilization_id, 256)
        {
            return Err(CommitError::InvalidCommand);
        }
        let role = if account_id == application.owner_account_id && !member.spectator {
            LegacyImportRole::Owner
        } else if member.spectator {
            LegacyImportRole::Spectator
        } else {
            LegacyImportRole::Player
        };
        let civilization_id = match role {
            LegacyImportRole::Spectator => None,
            LegacyImportRole::Owner | LegacyImportRole::Player => {
                Some(member.civilization_id.clone())
            }
        };
        memberships.push((account_id, role, civilization_id));
    }
    if memberships
        .iter()
        .filter(|(_, role, civilization_id)| {
            *role == LegacyImportRole::Owner
                && civilization_id.as_deref()
                    == Some(&application.normalized_game.owner_civilization_id)
        })
        .count()
        != 1
    {
        return Err(CommitError::Unauthorized);
    }
    validate_account_ids(
        application.owner_account_id,
        &memberships
            .iter()
            .map(|(account_id, _, _)| *account_id)
            .collect::<Vec<_>>(),
    )?;
    Ok(memberships)
}

fn validate_projection_report(
    application: &LegacyImportApplication,
    memberships: &[(Uuid, LegacyImportRole, Option<String>)],
) -> Result<(), CommitError> {
    let expected = memberships.iter().cloned().collect::<BTreeSet<_>>();
    let actual = application
        .projection_report
        .projections
        .iter()
        .map(|projection| {
            (
                projection.account_id,
                projection.role,
                projection.civilization_id.clone(),
            )
        })
        .collect::<BTreeSet<_>>();
    if expected != actual
        || actual.len() != application.projection_report.projections.len()
        || application
            .projection_report
            .projections
            .iter()
            .any(|projection| {
                !is_sha256(&projection.projection_hash)
                    || projection.serialized_bytes == 0
                    || projection.serialized_bytes > MAX_SNAPSHOT_BYTES as u64
                    || !projection.identity_leak_scan_passed
            })
    {
        return Err(CommitError::InvalidCommand);
    }
    let mut expected_identity_hashes = application
        .normalized_game
        .metadata
        .members
        .iter()
        .map(|member| state_hash(member.legacy_player_id.as_bytes()))
        .collect::<Vec<_>>();
    expected_identity_hashes.sort();
    let mut checked_identity_hashes = application
        .projection_report
        .checked_legacy_identity_hashes
        .clone();
    checked_identity_hashes.sort();
    if expected_identity_hashes != checked_identity_hashes {
        return Err(CommitError::InvalidCommand);
    }
    Ok(())
}

fn validate_account_ids(owner_account_id: Uuid, ids: &[Uuid]) -> Result<(), CommitError> {
    let unique = ids.iter().copied().collect::<BTreeSet<_>>();
    if ids.is_empty()
        || ids.len() > MAX_IMPORT_MEMBERS
        || unique.len() != ids.len()
        || !unique.contains(&owner_account_id)
    {
        return Err(CommitError::Unauthorized);
    }
    Ok(())
}

async fn load_installed_manifest(
    pool: &PgPool,
    ruleset_manifest_hash: &str,
) -> Result<WorkerManifest, CommitError> {
    if !is_sha256(ruleset_manifest_hash) {
        return Err(CommitError::InvalidCommand);
    }
    let row = sqlx::query(
        "SELECT m.engine_build, m.manifest FROM ruleset_manifests m
         WHERE m.hash=$1 AND EXISTS (
             SELECT 1 FROM ruleset_asset_versions v WHERE v.manifest_hash=m.hash
         )",
    )
    .bind(ruleset_manifest_hash)
    .fetch_optional(pool)
    .await
    .map_err(CommitError::storage)?
    .ok_or(CommitError::NotFound)?;
    validate_manifest_row(ruleset_manifest_hash, row)
}

async fn load_installed_manifest_in_transaction(
    tx: &mut sqlx::Transaction<'_, sqlx::Postgres>,
    ruleset_manifest_hash: &str,
) -> Result<WorkerManifest, CommitError> {
    if !is_sha256(ruleset_manifest_hash) {
        return Err(CommitError::InvalidCommand);
    }
    let row = sqlx::query(
        "SELECT m.engine_build, m.manifest FROM ruleset_manifests m
         WHERE m.hash=$1 AND EXISTS (
             SELECT 1 FROM ruleset_asset_versions v WHERE v.manifest_hash=m.hash
         ) FOR KEY SHARE",
    )
    .bind(ruleset_manifest_hash)
    .fetch_optional(&mut **tx)
    .await
    .map_err(CommitError::storage)?
    .ok_or(CommitError::NotFound)?;
    validate_manifest_row(ruleset_manifest_hash, row)
}

fn validate_manifest_row(
    ruleset_manifest_hash: &str,
    row: PgRow,
) -> Result<WorkerManifest, CommitError> {
    let engine_build: String = row.get("engine_build");
    let manifest: WorkerManifest = serde_json::from_value(row.get("manifest"))
        .map_err(|_| CommitError::WorkerRevisionMismatch)?;
    if !manifest.is_valid()
        || manifest.engine_build != engine_build
        || state_hash(
            &serde_json::to_vec(&manifest).map_err(|_| CommitError::WorkerRevisionMismatch)?,
        ) != ruleset_manifest_hash
    {
        return Err(CommitError::WorkerRevisionMismatch);
    }
    Ok(manifest)
}

fn sorted_mod_names(manifest: &WorkerManifest) -> Vec<String> {
    let mut names = manifest
        .mods
        .iter()
        .map(|ruleset| ruleset.name.clone())
        .collect::<Vec<_>>();
    names.sort();
    names
}

fn bounded_text(value: &str, maximum: usize) -> bool {
    !value.trim().is_empty() && value.len() <= maximum && !value.chars().any(char::is_control)
}

fn is_sha256(value: &str) -> bool {
    value.len() == 64 && value.bytes().all(|byte| byte.is_ascii_hexdigit())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn import_game_id_is_stable_and_separate_from_operation_id() {
        let operation_id = Uuid::parse_str("00000000-0000-4000-8000-000000000001").unwrap();
        let first = legacy_import_game_id(operation_id);
        assert_eq!(first, legacy_import_game_id(operation_id));
        assert_ne!(first, operation_id);
        assert_eq!(first.get_version_num(), 5);
    }
}
