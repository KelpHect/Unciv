use std::{
    fs::{File, OpenOptions},
    io::Write,
    path::{Path, PathBuf},
    process::{Command, ExitCode, Stdio},
    time::{Duration, Instant},
};

use crate::postgres::PostgresGameRepository;

use super::{
    AcquisitionError, AcquisitionPolicy,
    archive::{extract_ruleset_json, hash_directory},
    download::download,
};

const MAX_POLICY_BYTES: u64 = 256 * 1024;
const WORKER_VALIDATION_TIMEOUT: Duration = Duration::from_secs(60);

pub async fn run_ruleset_acquisition_cli() -> ExitCode {
    match run(std::env::args().skip(1).collect()).await {
        Ok(report) => {
            println!(
                "{}",
                serde_json::to_string_pretty(&report).expect("acquisition report is serializable")
            );
            ExitCode::SUCCESS
        }
        Err(error) => {
            eprintln!("{error}");
            ExitCode::FAILURE
        }
    }
}

async fn run(arguments: Vec<String>) -> Result<serde_json::Value, AcquisitionError> {
    match arguments.as_slice() {
        [command, policy, base_assets, worker_jar, bundle_root]
        | [command, policy, base_assets, worker_jar, bundle_root, _]
            if command == "acquire" =>
        {
            let activate = arguments.get(5).is_some_and(|value| value == "--activate");
            if arguments.len() == 6 && !activate {
                return Err(AcquisitionError::InvalidPolicy);
            }
            acquire(
                Path::new(policy),
                Path::new(base_assets),
                Path::new(worker_jar),
                Path::new(bundle_root),
                activate,
            )
            .await
        }
        [command, bundle_root, version_id] if command == "rollback" => {
            let _lock = acquisition_lock(Path::new(bundle_root))?;
            activate_version(Path::new(bundle_root), version_id)?;
            Ok(serde_json::json!({
                "operation": "rollback",
                "active_version": version_id,
            }))
        }
        [command, bundle_root] if command == "gc" => garbage_collect(Path::new(bundle_root)).await,
        _ => Err(AcquisitionError::InvalidPolicy),
    }
}

async fn acquire(
    policy_path: &Path,
    base_assets: &Path,
    worker_jar: &Path,
    bundle_root: &Path,
    activate: bool,
) -> Result<serde_json::Value, AcquisitionError> {
    let policy = read_policy(policy_path)?;
    policy.validate()?;
    reject_link_or_non_file(worker_jar)?;
    let database_url =
        std::env::var("UNCIV_V3_DATABASE_URL").map_err(|_| AcquisitionError::Registration)?;
    let repository = PostgresGameRepository::connect(&database_url)
        .await
        .map_err(|_| AcquisitionError::Registration)?;
    repository
        .migrate()
        .await
        .map_err(|_| AcquisitionError::Registration)?;

    std::fs::create_dir_all(bundle_root).map_err(AcquisitionError::io)?;
    let lock = acquisition_lock(bundle_root)?;
    let versions = bundle_root.join("versions");
    std::fs::create_dir_all(&versions).map_err(AcquisitionError::io)?;
    let manifest_hash = policy.manifest_hash()?;
    let version_id = manifest_hash.clone();
    let final_path = versions.join(&version_id);
    if !final_path.exists() {
        let staging_path = versions.join(format!(".stage-{}", uuid::Uuid::new_v4()));
        let mut staging = StagingDirectory::create(staging_path)?;
        stage_version(&policy, base_assets, worker_jar, staging.path())?;
        sync_directory(staging.path())?;
        std::fs::rename(staging.path(), &final_path).map_err(AcquisitionError::io)?;
        staging.commit();
        sync_directory(&versions)?;
    } else {
        validate_existing_version(&final_path, &policy)?;
    }
    repository
        .register_ruleset_asset_version(&version_id, &manifest_hash, &policy.manifest())
        .await
        .map_err(|_| AcquisitionError::Registration)?;
    if activate {
        activate_version(bundle_root, &version_id)?;
    }
    drop(lock);
    Ok(serde_json::json!({
        "operation": "acquire",
        "version_id": version_id,
        "manifest_hash": manifest_hash,
        "activated": activate,
        "mods": policy.mods.len(),
    }))
}

fn stage_version(
    policy: &AcquisitionPolicy,
    base_assets: &Path,
    worker_jar: &Path,
    staging: &Path,
) -> Result<(), AcquisitionError> {
    reject_link_or_non_directory(base_assets)?;
    copy_tree(&base_assets.join("jsons"), &staging.join("jsons"))?;
    let base_hash = hash_directory(&staging.join("jsons").join(&policy.base_ruleset.name))?;
    if base_hash != policy.base_ruleset.sha256 {
        return Err(AcquisitionError::Staging);
    }
    let downloads = staging.join(".downloads");
    std::fs::create_dir(&downloads).map_err(AcquisitionError::io)?;
    for (index, item) in policy.mods.iter().enumerate() {
        let archive = downloads.join(format!("{index}.zip"));
        download(item, &archive)?;
        let ruleset_path = staging.join("mods").join(&item.ruleset.name).join("jsons");
        let actual_hash =
            extract_ruleset_json(&archive, item.archive_root.as_deref(), &ruleset_path)?;
        if actual_hash != item.ruleset.sha256 {
            return Err(AcquisitionError::ArchiveRejected);
        }
    }
    std::fs::remove_dir_all(downloads).map_err(AcquisitionError::io)?;
    let manifest_path = staging.join("manifest.json");
    write_json_file(&manifest_path, &policy.manifest())?;
    validate_with_worker(worker_jar, staging, &manifest_path)
}

fn validate_with_worker(
    worker_jar: &Path,
    staging: &Path,
    manifest_path: &Path,
) -> Result<(), AcquisitionError> {
    let java = std::env::var_os("UNCIV_JAVA_BIN").unwrap_or_else(|| "java".into());
    let manifest_name = manifest_path
        .file_name()
        .ok_or(AcquisitionError::WorkerValidation)?;
    let mut child = Command::new(java)
        .arg("-Djava.awt.headless=true")
        .arg("-Xms64m")
        .arg("-Xmx384m")
        .arg("-jar")
        .arg(worker_jar)
        .arg("--validate-manifest")
        .arg(manifest_name)
        .current_dir(staging)
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .spawn()
        .map_err(|_| AcquisitionError::WorkerValidation)?;
    let started = Instant::now();
    loop {
        if let Some(status) = child
            .try_wait()
            .map_err(|_| AcquisitionError::WorkerValidation)?
        {
            return if status.success() {
                Ok(())
            } else {
                Err(AcquisitionError::WorkerValidation)
            };
        }
        if started.elapsed() >= WORKER_VALIDATION_TIMEOUT {
            let _ = child.kill();
            let _ = child.wait();
            return Err(AcquisitionError::WorkerValidation);
        }
        std::thread::sleep(Duration::from_millis(25));
    }
}

fn validate_existing_version(
    path: &Path,
    policy: &AcquisitionPolicy,
) -> Result<(), AcquisitionError> {
    reject_link_or_non_directory(path)?;
    let bytes = read_bounded(&path.join("manifest.json"), MAX_POLICY_BYTES)?;
    let stored: crate::worker::WorkerManifest =
        serde_json::from_slice(&bytes).map_err(|_| AcquisitionError::Staging)?;
    if stored != policy.manifest() {
        return Err(AcquisitionError::Staging);
    }
    Ok(())
}

fn read_policy(path: &Path) -> Result<AcquisitionPolicy, AcquisitionError> {
    reject_link_or_non_file(path)?;
    serde_json::from_slice(&read_bounded(path, MAX_POLICY_BYTES)?)
        .map_err(|_| AcquisitionError::InvalidPolicy)
}

fn read_bounded(path: &Path, limit: u64) -> Result<Vec<u8>, AcquisitionError> {
    let metadata = std::fs::symlink_metadata(path).map_err(AcquisitionError::io)?;
    if !metadata.file_type().is_file() || metadata.len() == 0 || metadata.len() > limit {
        return Err(AcquisitionError::Staging);
    }
    std::fs::read(path).map_err(AcquisitionError::io)
}

fn write_json_file(path: &Path, value: &impl serde::Serialize) -> Result<(), AcquisitionError> {
    let bytes = serde_json::to_vec_pretty(value).map_err(|_| AcquisitionError::Staging)?;
    let mut output = OpenOptions::new()
        .write(true)
        .create_new(true)
        .open(path)
        .map_err(AcquisitionError::io)?;
    output.write_all(&bytes).map_err(AcquisitionError::io)?;
    output.sync_all().map_err(AcquisitionError::io)
}

fn copy_tree(source: &Path, destination: &Path) -> Result<(), AcquisitionError> {
    let mut budget = CopyBudget::default();
    copy_tree_bounded(source, destination, &mut budget)
}

fn copy_tree_bounded(
    source: &Path,
    destination: &Path,
    budget: &mut CopyBudget,
) -> Result<(), AcquisitionError> {
    reject_link_or_non_directory(source)?;
    std::fs::create_dir(destination).map_err(AcquisitionError::io)?;
    for entry in std::fs::read_dir(source).map_err(AcquisitionError::io)? {
        let entry = entry.map_err(AcquisitionError::io)?;
        budget.entries = budget
            .entries
            .checked_add(1)
            .ok_or(AcquisitionError::Staging)?;
        if budget.entries > 16_384 {
            return Err(AcquisitionError::Staging);
        }
        let source_path = entry.path();
        let destination_path = destination.join(entry.file_name());
        let metadata = std::fs::symlink_metadata(&source_path).map_err(AcquisitionError::io)?;
        if metadata.file_type().is_symlink() {
            return Err(AcquisitionError::Staging);
        }
        if metadata.is_dir() {
            copy_tree_bounded(&source_path, &destination_path, budget)?;
        } else if metadata.is_file() && metadata.len() <= 16 * 1024 * 1024 {
            budget.bytes = budget
                .bytes
                .checked_add(metadata.len())
                .ok_or(AcquisitionError::Staging)?;
            if budget.bytes > 512 * 1024 * 1024 {
                return Err(AcquisitionError::Staging);
            }
            let mut input = File::open(&source_path).map_err(AcquisitionError::io)?;
            let mut output = OpenOptions::new()
                .write(true)
                .create_new(true)
                .open(&destination_path)
                .map_err(AcquisitionError::io)?;
            std::io::copy(&mut input, &mut output).map_err(AcquisitionError::io)?;
            output.sync_all().map_err(AcquisitionError::io)?;
        } else {
            return Err(AcquisitionError::Staging);
        }
    }
    Ok(())
}

#[derive(Default)]
struct CopyBudget {
    entries: usize,
    bytes: u64,
}

fn acquisition_lock(bundle_root: &Path) -> Result<File, AcquisitionError> {
    let lock = OpenOptions::new()
        .read(true)
        .write(true)
        .create(true)
        .truncate(false)
        .open(bundle_root.join(".acquisition.lock"))
        .map_err(AcquisitionError::io)?;
    lock.try_lock().map_err(|_| AcquisitionError::Staging)?;
    Ok(lock)
}

fn activate_version(bundle_root: &Path, version_id: &str) -> Result<(), AcquisitionError> {
    if !valid_version_id(version_id) {
        return Err(AcquisitionError::Activation);
    }
    let version = bundle_root.join("versions").join(version_id);
    reject_link_or_non_directory(&version)?;
    #[cfg(unix)]
    {
        let temporary = bundle_root.join(format!(".active-{}", uuid::Uuid::new_v4()));
        std::os::unix::fs::symlink(Path::new("versions").join(version_id), &temporary)
            .map_err(|_| AcquisitionError::Activation)?;
        std::fs::rename(&temporary, bundle_root.join("active"))
            .map_err(|_| AcquisitionError::Activation)?;
        sync_directory(bundle_root)?;
        Ok(())
    }
    #[cfg(not(unix))]
    {
        let _ = bundle_root;
        Err(AcquisitionError::Activation)
    }
}

async fn garbage_collect(bundle_root: &Path) -> Result<serde_json::Value, AcquisitionError> {
    let database_url =
        std::env::var("UNCIV_V3_DATABASE_URL").map_err(|_| AcquisitionError::Registration)?;
    let repository = PostgresGameRepository::connect(&database_url)
        .await
        .map_err(|_| AcquisitionError::Registration)?;
    repository
        .migrate()
        .await
        .map_err(|_| AcquisitionError::Registration)?;
    let _lock = acquisition_lock(bundle_root)?;
    let active = active_version(bundle_root)?;
    let referenced = repository
        .referenced_ruleset_asset_versions()
        .await
        .map_err(|_| AcquisitionError::Registration)?;
    let mut removed = Vec::new();
    for entry in std::fs::read_dir(bundle_root.join("versions")).map_err(AcquisitionError::io)? {
        let entry = entry.map_err(AcquisitionError::io)?;
        let version_id = entry.file_name().to_string_lossy().into_owned();
        if !valid_version_id(&version_id)
            || active.as_deref() == Some(&version_id)
            || referenced.contains(&version_id)
        {
            continue;
        }
        if repository
            .unregister_unreferenced_ruleset_asset_version(&version_id)
            .await
            .map_err(|_| AcquisitionError::Registration)?
        {
            let trash = bundle_root.join(format!(".trash-{}", uuid::Uuid::new_v4()));
            std::fs::rename(entry.path(), &trash).map_err(AcquisitionError::io)?;
            std::fs::remove_dir_all(trash).map_err(AcquisitionError::io)?;
            removed.push(version_id);
        }
    }
    Ok(serde_json::json!({"operation": "gc", "removed_versions": removed}))
}

fn active_version(bundle_root: &Path) -> Result<Option<String>, AcquisitionError> {
    let active = bundle_root.join("active");
    if !active.exists() {
        return Ok(None);
    }
    let target = std::fs::read_link(active).map_err(|_| AcquisitionError::Activation)?;
    let version = target
        .file_name()
        .and_then(|name| name.to_str())
        .ok_or(AcquisitionError::Activation)?;
    if !valid_version_id(version) {
        return Err(AcquisitionError::Activation);
    }
    Ok(Some(version.to_owned()))
}

fn reject_link_or_non_file(path: &Path) -> Result<(), AcquisitionError> {
    let metadata = std::fs::symlink_metadata(path).map_err(AcquisitionError::io)?;
    if metadata.file_type().is_file() {
        Ok(())
    } else {
        Err(AcquisitionError::Staging)
    }
}

fn reject_link_or_non_directory(path: &Path) -> Result<(), AcquisitionError> {
    let metadata = std::fs::symlink_metadata(path).map_err(AcquisitionError::io)?;
    if metadata.file_type().is_dir() {
        Ok(())
    } else {
        Err(AcquisitionError::Staging)
    }
}

fn valid_version_id(value: &str) -> bool {
    value.len() == 64
        && value
            .bytes()
            .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
}

#[cfg(unix)]
fn sync_directory(path: &Path) -> Result<(), AcquisitionError> {
    File::open(path)
        .and_then(|directory| directory.sync_all())
        .map_err(AcquisitionError::io)
}

#[cfg(not(unix))]
fn sync_directory(_: &Path) -> Result<(), AcquisitionError> {
    Ok(())
}

struct StagingDirectory {
    path: PathBuf,
    committed: bool,
}

impl StagingDirectory {
    fn create(path: PathBuf) -> Result<Self, AcquisitionError> {
        std::fs::create_dir(&path).map_err(AcquisitionError::io)?;
        Ok(Self {
            path,
            committed: false,
        })
    }

    fn path(&self) -> &Path {
        &self.path
    }

    fn commit(&mut self) {
        self.committed = true;
    }
}

impl Drop for StagingDirectory {
    fn drop(&mut self) {
        if !self.committed {
            let _ = std::fs::remove_dir_all(&self.path);
        }
    }
}
