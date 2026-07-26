use std::{
    collections::HashSet,
    fs::{File, OpenOptions},
    io::Read,
    path::{Component, Path, PathBuf},
};

use sha2::{Digest, Sha256};
use zip::{CompressionMethod, ZipArchive};

use super::AcquisitionError;

const MAX_ARCHIVE_ENTRIES: usize = 16_384;
const MAX_ENTRY_BYTES: u64 = 16 * 1024 * 1024;
const MAX_TOTAL_BYTES: u64 = 512 * 1024 * 1024;

pub(super) fn extract_ruleset_json(
    archive_path: &Path,
    archive_root: Option<&str>,
    destination: &Path,
) -> Result<String, AcquisitionError> {
    let input = File::open(archive_path).map_err(AcquisitionError::io)?;
    let mut archive = ZipArchive::new(input).map_err(|_| AcquisitionError::ArchiveRejected)?;
    if archive.is_empty() || archive.len() > MAX_ARCHIVE_ENTRIES {
        return Err(AcquisitionError::ArchiveRejected);
    }
    std::fs::create_dir_all(destination).map_err(AcquisitionError::io)?;
    let expected_prefix = match archive_root {
        Some(root) => PathBuf::from(root).join("jsons"),
        None => PathBuf::from("jsons"),
    };
    let mut seen = HashSet::new();
    let mut total_uncompressed = 0_u64;
    let mut total_compressed = 0_u64;
    let mut extracted_files = 0_usize;
    for index in 0..archive.len() {
        let mut entry = archive
            .by_index(index)
            .map_err(|_| AcquisitionError::ArchiveRejected)?;
        let raw_name = entry.name().to_owned();
        if raw_name.contains(['\\', '\0']) {
            return Err(AcquisitionError::ArchiveRejected);
        }
        let enclosed = entry
            .enclosed_name()
            .ok_or(AcquisitionError::ArchiveRejected)?
            .to_path_buf();
        validate_components(&enclosed)?;
        let collision_key = enclosed.to_string_lossy().to_lowercase();
        if !seen.insert(collision_key) || is_link_or_special(entry.unix_mode(), entry.is_dir()) {
            return Err(AcquisitionError::ArchiveRejected);
        }
        if !matches!(
            entry.compression(),
            CompressionMethod::Stored | CompressionMethod::Deflated
        ) || entry.size() > MAX_ENTRY_BYTES
        {
            return Err(AcquisitionError::ArchiveRejected);
        }
        total_uncompressed = total_uncompressed
            .checked_add(entry.size())
            .ok_or(AcquisitionError::ArchiveRejected)?;
        total_compressed = total_compressed
            .checked_add(entry.compressed_size())
            .ok_or(AcquisitionError::ArchiveRejected)?;
        if total_uncompressed > MAX_TOTAL_BYTES
            || total_compressed > super::download::MAX_DOWNLOAD_BYTES
        {
            return Err(AcquisitionError::ArchiveRejected);
        }
        let Ok(relative) = enclosed.strip_prefix(&expected_prefix) else {
            continue;
        };
        if relative.as_os_str().is_empty() {
            continue;
        }
        let output_path = destination.join(relative);
        if entry.is_dir() {
            std::fs::create_dir_all(&output_path).map_err(AcquisitionError::io)?;
            continue;
        }
        let parent = output_path
            .parent()
            .ok_or(AcquisitionError::ArchiveRejected)?;
        std::fs::create_dir_all(parent).map_err(AcquisitionError::io)?;
        let mut output = OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(&output_path)
            .map_err(AcquisitionError::io)?;
        let copied = std::io::copy(&mut entry.by_ref().take(MAX_ENTRY_BYTES + 1), &mut output)
            .map_err(|_| AcquisitionError::ArchiveRejected)?;
        if copied != entry.size() || copied > MAX_ENTRY_BYTES {
            return Err(AcquisitionError::ArchiveRejected);
        }
        output.sync_all().map_err(AcquisitionError::io)?;
        extracted_files += 1;
    }
    if extracted_files == 0 {
        return Err(AcquisitionError::ArchiveRejected);
    }
    hash_directory(destination)
}

pub(super) fn hash_directory(root: &Path) -> Result<String, AcquisitionError> {
    let mut files = Vec::new();
    collect_files(root, root, &mut files)?;
    files.sort_by(|left, right| left.0.cmp(&right.0));
    if files.is_empty() {
        return Err(AcquisitionError::ArchiveRejected);
    }
    let mut digest = Sha256::new();
    for (relative, path) in files {
        let relative = relative
            .to_str()
            .ok_or(AcquisitionError::ArchiveRejected)?
            .replace('\\', "/");
        let path_bytes = relative.as_bytes();
        let metadata = std::fs::symlink_metadata(&path).map_err(AcquisitionError::io)?;
        if !metadata.file_type().is_file() {
            return Err(AcquisitionError::ArchiveRejected);
        }
        digest.update((path_bytes.len() as u32).to_be_bytes());
        digest.update(path_bytes);
        digest.update(metadata.len().to_be_bytes());
        let mut input = File::open(path).map_err(AcquisitionError::io)?;
        let mut buffer = [0_u8; 64 * 1024];
        loop {
            let read = input.read(&mut buffer).map_err(AcquisitionError::io)?;
            if read == 0 {
                break;
            }
            digest.update(&buffer[..read]);
        }
    }
    Ok(digest
        .finalize()
        .iter()
        .map(|byte| format!("{byte:02x}"))
        .collect())
}

fn collect_files(
    root: &Path,
    current: &Path,
    files: &mut Vec<(PathBuf, PathBuf)>,
) -> Result<(), AcquisitionError> {
    for entry in std::fs::read_dir(current).map_err(AcquisitionError::io)? {
        let entry = entry.map_err(AcquisitionError::io)?;
        let path = entry.path();
        let metadata = std::fs::symlink_metadata(&path).map_err(AcquisitionError::io)?;
        if metadata.file_type().is_symlink() {
            return Err(AcquisitionError::ArchiveRejected);
        }
        if metadata.is_dir() {
            collect_files(root, &path, files)?;
        } else if metadata.is_file() {
            files.push((
                path.strip_prefix(root)
                    .map_err(|_| AcquisitionError::ArchiveRejected)?
                    .to_path_buf(),
                path,
            ));
        } else {
            return Err(AcquisitionError::ArchiveRejected);
        }
    }
    Ok(())
}

fn validate_components(path: &Path) -> Result<(), AcquisitionError> {
    if path
        .components()
        .all(|component| matches!(component, Component::Normal(value) if !value.is_empty()))
    {
        Ok(())
    } else {
        Err(AcquisitionError::ArchiveRejected)
    }
}

fn is_link_or_special(mode: Option<u32>, is_directory: bool) -> bool {
    let Some(mode) = mode else {
        return false;
    };
    let file_type = mode & 0o170000;
    file_type != 0 && file_type != 0o100000 && !(is_directory && file_type == 0o040000)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;
    use zip::write::SimpleFileOptions;

    fn archive(entries: &[(&str, &[u8], Option<u32>)]) -> (PathBuf, PathBuf) {
        let archive_path =
            std::env::temp_dir().join(format!("unciv-archive-{}.zip", uuid::Uuid::new_v4()));
        let output_path =
            std::env::temp_dir().join(format!("unciv-extract-{}", uuid::Uuid::new_v4()));
        let output = File::create(&archive_path).unwrap();
        let mut writer = zip::ZipWriter::new(output);
        for (name, bytes, mode) in entries {
            let mut options =
                SimpleFileOptions::default().compression_method(CompressionMethod::Deflated);
            if let Some(mode) = mode {
                options = options.unix_permissions(*mode);
            }
            writer.start_file(*name, options).unwrap();
            writer.write_all(bytes).unwrap();
        }
        writer.finish().unwrap();
        (archive_path, output_path)
    }

    #[test]
    fn extraction_is_rooted_and_hashes_exact_json_bytes() {
        let (archive, output) = archive(&[
            ("release/README.md", b"ignored", None),
            ("release/jsons/Rules.json", br#"{"ok":true}"#, None),
            ("release/jsons/nested/Data.json", b"[]", None),
        ]);
        let hash = extract_ruleset_json(&archive, Some("release"), &output).unwrap();
        assert_eq!(hash, hash_directory(&output).unwrap());
        assert!(!output.join("README.md").exists());
        std::fs::remove_file(archive).unwrap();
        std::fs::remove_dir_all(output).unwrap();
    }

    #[test]
    fn traversal_and_links_are_rejected() {
        let (traversal_archive, traversal_output) =
            archive(&[("../escape", b"x".as_slice(), None)]);
        assert!(extract_ruleset_json(&traversal_archive, None, &traversal_output).is_err());
        std::fs::remove_file(traversal_archive).unwrap();
        if traversal_output.exists() {
            std::fs::remove_dir_all(traversal_output).unwrap();
        }

        let link_archive =
            std::env::temp_dir().join(format!("unciv-link-{}.zip", uuid::Uuid::new_v4()));
        let link_output =
            std::env::temp_dir().join(format!("unciv-link-out-{}", uuid::Uuid::new_v4()));
        let mut writer = zip::ZipWriter::new(File::create(&link_archive).unwrap());
        writer
            .add_symlink(
                "jsons/link",
                "target",
                SimpleFileOptions::default().unix_permissions(0o777),
            )
            .unwrap();
        writer.finish().unwrap();
        assert!(extract_ruleset_json(&link_archive, None, &link_output).is_err());
        std::fs::remove_file(link_archive).unwrap();
        if link_output.exists() {
            std::fs::remove_dir_all(link_output).unwrap();
        }
    }
}
