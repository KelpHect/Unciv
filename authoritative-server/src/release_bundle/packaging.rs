use super::*;
use crate::worker::WorkerManifest;
use std::{
    fs::{self, OpenOptions},
    io::Write,
};

pub fn run(args: impl Iterator<Item = String>) -> Result<(), ReleaseBundleError> {
    let args: Vec<_> = args.collect();
    match args.as_slice() {
        [command, sbom] if command == "verify-sbom" => {
            let sbom = source_path(sbom)?;
            super::sbom::validate(&sbom)?;
            println!(
                "{}",
                serde_json::to_string(&serde_json::json!({"valid": true}))?
            );
            Ok(())
        }
        [command, root] if command == "verify" => {
            let manifest = verify_bundle(Path::new(root))?;
            println!(
                "{}",
                serde_json::to_string(&serde_json::json!({
                    "valid": true,
                    "bundle_id": manifest.bundle_id,
                    "artifacts": manifest.artifacts.len(),
                }))?
            );
            Ok(())
        }
        [
            command,
            root,
            server,
            migrator,
            audit_exporter,
            worker,
            client,
            ruleset,
            sbom,
        ] if command == "create" => create(
            Path::new(root),
            PackageSources {
                server,
                migrator,
                audit_exporter,
                worker,
                client,
                ruleset,
                sbom,
            },
        ),
        _ => Err(ReleaseBundleError::Policy),
    }
}

struct PackageSources<'a> {
    server: &'a str,
    migrator: &'a str,
    audit_exporter: &'a str,
    worker: &'a str,
    client: &'a str,
    ruleset: &'a str,
    sbom: &'a str,
}

fn create(output: &Path, sources: PackageSources<'_>) -> Result<(), ReleaseBundleError> {
    let parent = output.parent().ok_or(ReleaseBundleError::Policy)?;
    fs::create_dir_all(parent)?;
    if output.exists() {
        return Err(ReleaseBundleError::Policy);
    }
    let name = output
        .file_name()
        .and_then(|value| value.to_str())
        .filter(|value| !value.is_empty())
        .ok_or(ReleaseBundleError::Policy)?;
    let staging = parent.join(format!(".{name}.staging-{}", std::process::id()));
    if staging.exists() {
        return Err(ReleaseBundleError::Policy);
    }
    fs::create_dir(&staging)?;
    let guard = StagingGuard(staging.clone());
    validate_embedded_contract(&source_path(sources.worker)?)?;
    validate_embedded_contract(&source_path(sources.client)?)?;
    copy_exact(
        &source_path(sources.server)?,
        &staging.join("bin/unciv-authoritative-server"),
    )?;
    copy_exact(
        &source_path(sources.migrator)?,
        &staging.join("bin/unciv-v3-migrate"),
    )?;
    copy_exact(
        &source_path(sources.audit_exporter)?,
        &staging.join("bin/unciv-v3-export-security-audit"),
    )?;
    copy_exact(
        &source_path(sources.worker)?,
        &staging.join("worker/UncivAuthoritativeWorker.jar"),
    )?;
    copy_exact(
        &source_path(sources.client)?,
        &staging.join("client/unciv-client"),
    )?;
    copy_exact(
        &source_path(sources.ruleset)?,
        &staging.join("rulesets/manifest.json"),
    )?;
    validate_ruleset(&staging.join("rulesets/manifest.json"))?;
    let sbom = source_path(sources.sbom)?;
    super::sbom::validate(&sbom)?;
    copy_exact(&sbom, &staging.join("evidence/sbom.spdx.json"))?;

    let manifest_dir = Path::new(env!("CARGO_MANIFEST_DIR"));
    copy_exact(
        &manifest_dir.join("openapi/api-v3.json"),
        &staging.join("contracts/api-v3.json"),
    )?;
    copy_exact(
        &manifest_dir.join("openapi/notifications-v3.json"),
        &staging.join("contracts/notifications-v3.json"),
    )?;
    copy_exact(
        &manifest_dir.join("release/compatibility.json"),
        &staging.join("contracts/compatibility.json"),
    )?;
    copy_migrations(manifest_dir, &staging)?;

    let mut manifest = ReleaseBundleManifest {
        bundle_id: String::new(),
        compatibility: compatibility_contract()?,
        artifacts: collect_artifacts(&staging)?,
    };
    manifest.bundle_id = hash_bytes(&serde_json::to_vec(&manifest)?);
    validate_manifest(&manifest)?;
    write_manifest(&staging.join(MANIFEST_NAME), &manifest)?;
    verify_bundle(&staging)?;
    fs::rename(&staging, output)?;
    std::mem::forget(guard);
    println!(
        "{}",
        serde_json::to_string(&serde_json::json!({
            "created": true,
            "bundle_id": manifest.bundle_id,
            "artifacts": manifest.artifacts.len(),
        }))?
    );
    Ok(())
}

fn validate_embedded_contract(path: &Path) -> Result<(), ReleaseBundleError> {
    let mut archive =
        zip::ZipArchive::new(File::open(path)?).map_err(|_| ReleaseBundleError::Policy)?;
    let entry = archive
        .by_name("authoritative-v3-compatibility.json")
        .map_err(|_| ReleaseBundleError::Policy)?;
    if !entry.is_file() || entry.size() > 64 * 1024 {
        return Err(ReleaseBundleError::Policy);
    }
    let mut bytes = Vec::with_capacity(entry.size() as usize);
    entry
        .take(64 * 1024 + 1)
        .read_to_end(&mut bytes)
        .map_err(ReleaseBundleError::Io)?;
    let embedded: CompatibilityContract = serde_json::from_slice(&bytes)?;
    if embedded != compatibility_contract()? {
        return Err(ReleaseBundleError::Policy);
    }
    Ok(())
}

fn copy_migrations(manifest_dir: &Path, staging: &Path) -> Result<(), ReleaseBundleError> {
    let contract = compatibility_contract()?;
    let migrations = manifest_dir.join("migrations");
    for version in 1..=contract.latest_migration_version {
        let prefix = format!("{version:04}_");
        let matches: Vec<_> = fs::read_dir(&migrations)?
            .filter_map(Result::ok)
            .filter(|entry| {
                entry
                    .file_name()
                    .to_str()
                    .is_some_and(|name| name.starts_with(&prefix) && name.ends_with(".sql"))
            })
            .collect();
        if matches.len() != 1 {
            return Err(ReleaseBundleError::Policy);
        }
        let source = matches[0].path();
        copy_exact(
            &source,
            &staging.join("migrations").join(matches[0].file_name()),
        )?;
    }
    let sql_count = fs::read_dir(&migrations)?
        .filter_map(Result::ok)
        .filter(|entry| entry.path().extension().is_some_and(|value| value == "sql"))
        .count();
    if sql_count != contract.latest_migration_version as usize {
        return Err(ReleaseBundleError::Policy);
    }
    Ok(())
}

fn copy_exact(source: &Path, target: &Path) -> Result<(), ReleaseBundleError> {
    let source = source_path(source.to_str().ok_or(ReleaseBundleError::Policy)?)?;
    fs::create_dir_all(target.parent().ok_or(ReleaseBundleError::Policy)?)?;
    let input = File::open(source)?;
    let mut output = OpenOptions::new()
        .write(true)
        .create_new(true)
        .open(target)?;
    let copied = io::copy(&mut input.take(MAX_ARTIFACT_BYTES + 1), &mut output)?;
    if copied > MAX_ARTIFACT_BYTES {
        return Err(ReleaseBundleError::Policy);
    }
    output.flush()?;
    output.sync_all()?;
    Ok(())
}

fn validate_ruleset(path: &Path) -> Result<(), ReleaseBundleError> {
    let metadata = path.metadata()?;
    if metadata.len() > 64 * 1024 {
        return Err(ReleaseBundleError::Policy);
    }
    let manifest: WorkerManifest = serde_json::from_reader(File::open(path)?)?;
    if !manifest.is_valid() {
        return Err(ReleaseBundleError::Policy);
    }
    Ok(())
}

fn write_manifest(path: &Path, manifest: &ReleaseBundleManifest) -> Result<(), ReleaseBundleError> {
    let mut output = OpenOptions::new().write(true).create_new(true).open(path)?;
    serde_json::to_writer_pretty(&mut output, manifest)?;
    output.write_all(b"\n")?;
    output.flush()?;
    output.sync_all()?;
    Ok(())
}

struct StagingGuard(PathBuf);

impl Drop for StagingGuard {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.0);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::worker::{WorkerManifest, WorkerRuleset};

    #[test]
    fn bundle_verification_rejects_tampering_and_extra_files() {
        let root = std::env::temp_dir().join(format!(
            "unciv-release-bundle-{}-{}",
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ));
        fs::create_dir(&root).unwrap();
        let sources = root.join("sources");
        fs::create_dir(&sources).unwrap();
        fs::write(sources.join("server"), "server").unwrap();
        fs::write(sources.join("migrator"), "migrator").unwrap();
        fs::write(sources.join("audit-exporter"), "audit-exporter").unwrap();
        create_compatible_zip(&sources.join("worker"));
        create_compatible_zip(&sources.join("client"));
        let ruleset = WorkerManifest {
            engine_build: "engine-1".to_owned(),
            base_ruleset: WorkerRuleset {
                name: "base".to_owned(),
                sha256: "a".repeat(64),
            },
            mods: Vec::new(),
        };
        fs::write(
            sources.join("ruleset.json"),
            serde_json::to_vec(&ruleset).unwrap(),
        )
        .unwrap();
        fs::write(
            sources.join("sbom.json"),
            br#"{
              "spdxVersion":"SPDX-2.3",
              "dataLicense":"CC0-1.0",
              "SPDXID":"SPDXRef-DOCUMENT",
              "name":"unciv-authoritative-v3-release-bundle",
              "documentNamespace":"https://example.invalid/unciv/sbom/test",
              "creationInfo":{"created":"2026-07-28T12:00:00Z","creators":["Tool: test"]},
              "packages":[{"SPDXID":"SPDXRef-Unciv","name":"Unciv authoritative v3"}],
              "documentDescribes":["SPDXRef-Unciv"]
            }"#,
        )
        .unwrap();
        let bundle = root.join("bundle");
        create(
            &bundle,
            PackageSources {
                server: sources.join("server").to_str().unwrap(),
                migrator: sources.join("migrator").to_str().unwrap(),
                audit_exporter: sources.join("audit-exporter").to_str().unwrap(),
                worker: sources.join("worker").to_str().unwrap(),
                client: sources.join("client").to_str().unwrap(),
                ruleset: sources.join("ruleset.json").to_str().unwrap(),
                sbom: sources.join("sbom.json").to_str().unwrap(),
            },
        )
        .unwrap();
        assert!(verify_bundle(&bundle).is_ok());
        let sbom_path = bundle.join("evidence/sbom.spdx.json");
        let original_sbom = fs::read(&sbom_path).unwrap();
        fs::remove_file(&sbom_path).unwrap();
        assert!(matches!(
            verify_bundle(&bundle),
            Err(ReleaseBundleError::Policy)
        ));
        fs::write(sbom_path, original_sbom).unwrap();
        fs::write(bundle.join("client/unciv-client"), "tampered").unwrap();
        assert!(matches!(
            verify_bundle(&bundle),
            Err(ReleaseBundleError::Policy)
        ));
        fs::write(bundle.join("client/unciv-client"), "client").unwrap();
        fs::write(bundle.join("unexpected"), "extra").unwrap();
        assert!(matches!(
            verify_bundle(&bundle),
            Err(ReleaseBundleError::Policy)
        ));
        fs::remove_dir_all(root).unwrap();
    }

    fn create_compatible_zip(path: &Path) {
        let output = File::create(path).unwrap();
        let mut archive = zip::ZipWriter::new(output);
        archive
            .start_file(
                "authoritative-v3-compatibility.json",
                zip::write::SimpleFileOptions::default(),
            )
            .unwrap();
        archive
            .write_all(include_bytes!("../../release/compatibility.json"))
            .unwrap();
        archive.finish().unwrap();
    }
}
