use super::{MAX_ARTIFACT_BYTES, ReleaseBundleError};
use serde::Deserialize;
use std::{collections::HashSet, fs::File, path::Path};

const MAX_SBOM_BYTES: u64 = 32 * 1024 * 1024;
const MAX_SBOM_PACKAGES: usize = 100_000;
const EXPECTED_DOCUMENT_NAME: &str = "unciv-authoritative-v3-release-bundle";

#[derive(Deserialize)]
struct SpdxDocument {
    #[serde(rename = "spdxVersion")]
    spdx_version: String,
    #[serde(rename = "dataLicense")]
    data_license: String,
    #[serde(rename = "SPDXID")]
    spdx_id: String,
    name: String,
    #[serde(rename = "documentNamespace")]
    document_namespace: String,
    #[serde(rename = "creationInfo")]
    creation_info: CreationInfo,
    packages: Vec<SpdxPackage>,
    #[serde(rename = "documentDescribes")]
    #[serde(default)]
    document_describes: Vec<String>,
}

#[derive(Deserialize)]
struct CreationInfo {
    created: String,
    creators: Vec<String>,
}

#[derive(Deserialize)]
struct SpdxPackage {
    #[serde(rename = "SPDXID")]
    spdx_id: String,
    name: String,
}

pub(super) fn validate(path: &Path) -> Result<(), ReleaseBundleError> {
    let metadata = path.symlink_metadata()?;
    if !metadata.file_type().is_file()
        || metadata.len() == 0
        || metadata.len() > MAX_SBOM_BYTES
        || metadata.len() > MAX_ARTIFACT_BYTES
    {
        return Err(ReleaseBundleError::Policy);
    }
    let document: SpdxDocument = serde_json::from_reader(File::open(path)?)?;
    if document.spdx_version != "SPDX-2.3"
        || document.data_license != "CC0-1.0"
        || document.spdx_id != "SPDXRef-DOCUMENT"
        || document.name != EXPECTED_DOCUMENT_NAME
        || !document.document_namespace.starts_with("https://")
        || document.document_namespace.len() > 2_048
        || !valid_created(&document.creation_info.created)
        || document.creation_info.creators.is_empty()
        || document.creation_info.creators.len() > 64
        || document
            .creation_info
            .creators
            .iter()
            .any(|creator| creator.is_empty() || creator.len() > 512)
        || document.packages.is_empty()
        || document.packages.len() > MAX_SBOM_PACKAGES
        || document.document_describes.len() > MAX_SBOM_PACKAGES
    {
        return Err(ReleaseBundleError::Policy);
    }

    let mut package_ids = HashSet::with_capacity(document.packages.len());
    for package in &document.packages {
        if !valid_spdx_id(&package.spdx_id)
            || package.name.is_empty()
            || package.name.len() > 1_024
            || !package_ids.insert(package.spdx_id.as_str())
        {
            return Err(ReleaseBundleError::Policy);
        }
    }
    if document
        .document_describes
        .iter()
        .any(|id| !package_ids.contains(id.as_str()))
    {
        return Err(ReleaseBundleError::Policy);
    }
    Ok(())
}

fn valid_spdx_id(value: &str) -> bool {
    value.strip_prefix("SPDXRef-").is_some_and(|suffix| {
        !suffix.is_empty()
            && suffix.len() <= 256
            && suffix
                .bytes()
                .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'.' | b'-'))
    })
}

fn valid_created(value: &str) -> bool {
    value.len() == 20
        && value.as_bytes().get(4) == Some(&b'-')
        && value.as_bytes().get(7) == Some(&b'-')
        && value.as_bytes().get(10) == Some(&b'T')
        && value.as_bytes().get(13) == Some(&b':')
        && value.as_bytes().get(16) == Some(&b':')
        && value.ends_with('Z')
        && value.bytes().enumerate().all(|(index, byte)| {
            matches!(index, 4 | 7 | 10 | 13 | 16 | 19) || byte.is_ascii_digit()
        })
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;

    #[test]
    fn accepts_syft_spdx_without_optional_document_describes() {
        let root = test_root("syft");
        fs::create_dir(&root).unwrap();
        let path = root.join("sbom.json");
        fs::write(
            &path,
            br#"{
              "spdxVersion":"SPDX-2.3",
              "dataLicense":"CC0-1.0",
              "SPDXID":"SPDXRef-DOCUMENT",
              "name":"unciv-authoritative-v3-release-bundle",
              "documentNamespace":"https://anchore.com/syft/dir/unciv-test",
              "creationInfo":{"created":"2026-07-28T12:00:00Z","creators":["Tool: syft-1.49.0"]},
              "packages":[{"SPDXID":"SPDXRef-Package-binary-server-1","name":"server"}]
            }"#,
        )
        .unwrap();
        assert!(validate(&path).is_ok());
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn rejects_unrelated_or_dangling_spdx_documents() {
        let root = test_root("dangling");
        let _ = fs::remove_dir_all(&root);
        fs::create_dir(&root).unwrap();
        let path = root.join("sbom.json");
        fs::write(
            &path,
            br#"{
              "spdxVersion":"SPDX-2.3",
              "dataLicense":"CC0-1.0",
              "SPDXID":"SPDXRef-DOCUMENT",
              "name":"unciv-authoritative-v3-release-bundle",
              "documentNamespace":"https://example.invalid/sbom/1",
              "creationInfo":{"created":"2026-07-28T12:00:00Z","creators":["Tool: test"]},
              "packages":[{"SPDXID":"SPDXRef-Package","name":"package"}],
              "documentDescribes":["SPDXRef-Missing"]
            }"#,
        )
        .unwrap();
        assert!(matches!(validate(&path), Err(ReleaseBundleError::Policy)));
        fs::remove_dir_all(root).unwrap();
    }

    fn test_root(case: &str) -> std::path::PathBuf {
        std::env::temp_dir().join(format!("unciv-spdx-policy-{}-{case}", std::process::id()))
    }
}
