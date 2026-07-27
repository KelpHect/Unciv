//! Content-addressed packaging for one deployable authoritative-v3 release.

mod packaging;

use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::{
    fs::File,
    io::{self, Read},
    path::{Path, PathBuf},
};

pub use packaging::run;

const MANIFEST_NAME: &str = "bundle-manifest.json";
const MAX_ARTIFACT_BYTES: u64 = 1024 * 1024 * 1024;
const MAX_ARTIFACTS: usize = 128;

#[derive(Clone, Debug, Deserialize, Serialize, PartialEq, Eq)]
#[serde(deny_unknown_fields)]
pub struct CompatibilityContract {
    pub schema_version: u16,
    pub public_protocol_version: u16,
    pub player_projection_version: u16,
    pub spectator_projection_version: u16,
    pub worker_protocol_version: u16,
    pub latest_migration_version: i64,
    pub postgres_image: String,
}

#[derive(Clone, Debug, Deserialize, Serialize, PartialEq, Eq)]
#[serde(deny_unknown_fields)]
pub struct ReleaseBundleManifest {
    pub bundle_id: String,
    pub compatibility: CompatibilityContract,
    pub artifacts: Vec<ReleaseArtifact>,
}

#[derive(Clone, Debug, Deserialize, Serialize, PartialEq, Eq)]
#[serde(deny_unknown_fields)]
pub struct ReleaseArtifact {
    pub path: String,
    pub size: u64,
    pub sha256: String,
}

pub struct RuntimeReleaseBundle {
    pub bundle_id: String,
    pub ruleset_manifest: crate::worker::WorkerManifest,
}

#[derive(Debug, thiserror::Error)]
pub enum ReleaseBundleError {
    #[error("release bundle policy rejected the input")]
    Policy,
    #[error("release bundle I/O failed")]
    Io(#[source] io::Error),
    #[error("release bundle JSON is invalid")]
    Json(#[source] serde_json::Error),
}

impl From<io::Error> for ReleaseBundleError {
    fn from(value: io::Error) -> Self {
        Self::Io(value)
    }
}

impl From<serde_json::Error> for ReleaseBundleError {
    fn from(value: serde_json::Error) -> Self {
        Self::Json(value)
    }
}

pub fn compatibility_contract() -> Result<CompatibilityContract, ReleaseBundleError> {
    let contract: CompatibilityContract =
        serde_json::from_str(include_str!("../release/compatibility.json"))?;
    if contract.schema_version != 1
        || contract.public_protocol_version != crate::PROTOCOL_VERSION
        || contract.player_projection_version != crate::PROJECTION_VERSION
        || contract.spectator_projection_version != crate::SPECTATOR_PROJECTION_VERSION
        || contract.worker_protocol_version != crate::worker::WORKER_PROTOCOL_VERSION
        || contract.latest_migration_version != 20
        || contract.postgres_image
            != "postgres:19beta2-alpine@sha256:bc62313e826eb44d5f608425b7665962b72820e686da017799e906604bfeb8a5"
    {
        return Err(ReleaseBundleError::Policy);
    }
    Ok(contract)
}

pub fn verify_bundle(root: &Path) -> Result<ReleaseBundleManifest, ReleaseBundleError> {
    let root = root.canonicalize()?;
    if !root.is_dir() {
        return Err(ReleaseBundleError::Policy);
    }
    let manifest_path = root.join(MANIFEST_NAME);
    let metadata = manifest_path.symlink_metadata()?;
    if !metadata.file_type().is_file() || metadata.len() > 256 * 1024 {
        return Err(ReleaseBundleError::Policy);
    }
    let manifest: ReleaseBundleManifest = serde_json::from_reader(File::open(&manifest_path)?)?;
    validate_manifest(&manifest)?;
    let actual = collect_artifacts(&root)?;
    if manifest.artifacts != actual || manifest.compatibility != compatibility_contract()? {
        return Err(ReleaseBundleError::Policy);
    }
    let mut identity = manifest.clone();
    identity.bundle_id.clear();
    let expected = hash_bytes(&serde_json::to_vec(&identity)?);
    if manifest.bundle_id != expected {
        return Err(ReleaseBundleError::Policy);
    }
    Ok(manifest)
}

pub fn verify_runtime_environment() -> Result<RuntimeReleaseBundle, ReleaseBundleError> {
    if std::env::var("UNCIV_V3_UNPACKAGED_DEV").as_deref() == Ok("1") {
        return Ok(RuntimeReleaseBundle {
            bundle_id: "dev-unpackaged".to_owned(),
            ruleset_manifest: crate::worker::WorkerManifest {
                engine_build: String::new(),
                base_ruleset: crate::worker::WorkerRuleset {
                    name: String::new(),
                    sha256: String::new(),
                },
                mods: Vec::new(),
            },
        });
    }
    let root = std::env::var_os("UNCIV_V3_RELEASE_BUNDLE_ROOT")
        .map(PathBuf::from)
        .ok_or(ReleaseBundleError::Policy)?
        .canonicalize()?;
    let manifest = verify_bundle(&root)?;
    let configured_id =
        std::env::var("UNCIV_V3_RELEASE_BUNDLE_ID").map_err(|_| ReleaseBundleError::Policy)?;
    if configured_id != manifest.bundle_id {
        return Err(ReleaseBundleError::Policy);
    }
    let executable = std::env::current_exe()?.canonicalize()?;
    let expected_executable = root.join("bin/unciv-authoritative-server").canonicalize()?;
    let worker_jar = std::env::var_os("UNCIV_ENGINE_WORKER_JAR")
        .map(PathBuf::from)
        .ok_or(ReleaseBundleError::Policy)?
        .canonicalize()?;
    let expected_worker = root
        .join("worker/UncivAuthoritativeWorker.jar")
        .canonicalize()?;
    if executable != expected_executable || worker_jar != expected_worker {
        return Err(ReleaseBundleError::Policy);
    }
    let ruleset_path = root.join("rulesets/manifest.json");
    let ruleset_manifest: crate::worker::WorkerManifest =
        serde_json::from_reader(File::open(ruleset_path)?)?;
    if !ruleset_manifest.is_valid() {
        return Err(ReleaseBundleError::Policy);
    }
    Ok(RuntimeReleaseBundle {
        bundle_id: manifest.bundle_id,
        ruleset_manifest,
    })
}

fn validate_manifest(manifest: &ReleaseBundleManifest) -> Result<(), ReleaseBundleError> {
    if !is_hash(&manifest.bundle_id)
        || manifest.artifacts.is_empty()
        || manifest.artifacts.len() > MAX_ARTIFACTS
    {
        return Err(ReleaseBundleError::Policy);
    }
    let required = [
        "bin/unciv-authoritative-server",
        "client/unciv-client",
        "contracts/api-v3.json",
        "contracts/compatibility.json",
        "contracts/notifications-v3.json",
        "rulesets/manifest.json",
        "worker/UncivAuthoritativeWorker.jar",
    ];
    if required.iter().any(|path| {
        !manifest
            .artifacts
            .iter()
            .any(|artifact| artifact.path == *path)
    }) {
        return Err(ReleaseBundleError::Policy);
    }
    let mut previous = "";
    for artifact in &manifest.artifacts {
        if artifact.path.as_str() <= previous
            || !safe_relative_path(&artifact.path)
            || artifact.size > MAX_ARTIFACT_BYTES
            || !is_hash(&artifact.sha256)
        {
            return Err(ReleaseBundleError::Policy);
        }
        previous = &artifact.path;
    }
    Ok(())
}

fn collect_artifacts(root: &Path) -> Result<Vec<ReleaseArtifact>, ReleaseBundleError> {
    let mut files = Vec::new();
    collect_files(root, root, &mut files)?;
    files.sort();
    if files.is_empty() || files.len() > MAX_ARTIFACTS {
        return Err(ReleaseBundleError::Policy);
    }
    files
        .into_iter()
        .map(|relative| {
            let path = root.join(&relative);
            let metadata = path.symlink_metadata()?;
            if !metadata.file_type().is_file() || metadata.len() > MAX_ARTIFACT_BYTES {
                return Err(ReleaseBundleError::Policy);
            }
            Ok(ReleaseArtifact {
                path: relative,
                size: metadata.len(),
                sha256: hash_file(&path)?,
            })
        })
        .collect()
}

fn collect_files(
    root: &Path,
    directory: &Path,
    files: &mut Vec<String>,
) -> Result<(), ReleaseBundleError> {
    for entry in std::fs::read_dir(directory)? {
        let entry = entry?;
        let metadata = entry.path().symlink_metadata()?;
        if metadata.file_type().is_symlink() {
            return Err(ReleaseBundleError::Policy);
        }
        if metadata.is_dir() {
            collect_files(root, &entry.path(), files)?;
        } else if metadata.is_file() {
            let relative = entry
                .path()
                .strip_prefix(root)
                .map_err(|_| ReleaseBundleError::Policy)?
                .to_string_lossy()
                .replace('\\', "/");
            if relative != MANIFEST_NAME {
                files.push(relative);
            }
        } else {
            return Err(ReleaseBundleError::Policy);
        }
        if files.len() > MAX_ARTIFACTS {
            return Err(ReleaseBundleError::Policy);
        }
    }
    Ok(())
}

fn hash_file(path: &Path) -> Result<String, ReleaseBundleError> {
    let mut file = File::open(path)?;
    let mut digest = Sha256::new();
    let mut buffer = [0_u8; 64 * 1024];
    let mut total = 0_u64;
    loop {
        let read = file.read(&mut buffer)?;
        if read == 0 {
            break;
        }
        total = total
            .checked_add(read as u64)
            .ok_or(ReleaseBundleError::Policy)?;
        if total > MAX_ARTIFACT_BYTES {
            return Err(ReleaseBundleError::Policy);
        }
        digest.update(&buffer[..read]);
    }
    Ok(hex_digest(digest.finalize().as_slice()))
}

fn hash_bytes(bytes: &[u8]) -> String {
    let digest = Sha256::digest(bytes);
    hex_digest(digest.as_slice())
}

fn hex_digest(bytes: &[u8]) -> String {
    let mut output = String::with_capacity(bytes.len() * 2);
    for byte in bytes {
        use std::fmt::Write;
        write!(&mut output, "{byte:02x}").expect("writing to String cannot fail");
    }
    output
}

fn is_hash(value: &str) -> bool {
    value.len() == 64
        && value
            .bytes()
            .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
}

fn safe_relative_path(value: &str) -> bool {
    !value.is_empty()
        && !value.contains('\\')
        && Path::new(value)
            .components()
            .all(|component| matches!(component, std::path::Component::Normal(_)))
}

fn source_path(value: &str) -> Result<PathBuf, ReleaseBundleError> {
    let path = PathBuf::from(value).canonicalize()?;
    let metadata = path.symlink_metadata()?;
    if !metadata.file_type().is_file() || metadata.len() > MAX_ARTIFACT_BYTES {
        return Err(ReleaseBundleError::Policy);
    }
    Ok(path)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn checked_in_compatibility_contract_matches_every_runtime_constant() {
        assert!(compatibility_contract().is_ok());
    }
}
