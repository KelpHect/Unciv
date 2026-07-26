use std::{
    fs::{File, OpenOptions},
    io::{Read, Write},
    path::Path,
    time::Duration,
};

use reqwest::{
    StatusCode,
    blocking::{Client, Response},
    header::ACCEPT_ENCODING,
    redirect::Policy,
};
use sha2::{Digest, Sha256};
use zeroize::Zeroizing;

use super::{AcquisitionError, AllowedModArchive};

pub(super) const MAX_DOWNLOAD_BYTES: u64 = 64 * 1024 * 1024;

pub(super) fn download(
    item: &AllowedModArchive,
    destination: &Path,
) -> Result<(), AcquisitionError> {
    let client = Client::builder()
        .https_only(true)
        .redirect(Policy::none())
        .no_proxy()
        .connect_timeout(Duration::from_secs(10))
        .timeout(Duration::from_secs(120))
        .user_agent("unciv-v3-ruleset-acquisition/1")
        .build()
        .map_err(|_| AcquisitionError::DownloadRejected)?;
    let mut request = client.get(&item.url).header(ACCEPT_ENCODING, "identity");
    let token = item
        .bearer_token_env
        .as_deref()
        .map(read_bearer_token)
        .transpose()?;
    if let Some(token) = token.as_deref() {
        request = request.bearer_auth(token);
    }
    let response = request
        .send()
        .map_err(|_| AcquisitionError::DownloadRejected)?;
    validate_response(&response)?;
    let output = OpenOptions::new()
        .write(true)
        .create_new(true)
        .open(destination)
        .map_err(AcquisitionError::io)?;
    copy_bounded(response, output, &item.archive_sha256)
}

fn read_bearer_token(name: &str) -> Result<Zeroizing<String>, AcquisitionError> {
    let token =
        Zeroizing::new(std::env::var(name).map_err(|_| AcquisitionError::DownloadRejected)?);
    if token.is_empty()
        || token.len() > 4_096
        || token.chars().any(|character| character.is_control())
    {
        return Err(AcquisitionError::DownloadRejected);
    }
    Ok(token)
}

fn validate_response(response: &Response) -> Result<(), AcquisitionError> {
    if response.status() != StatusCode::OK
        || response
            .content_length()
            .is_some_and(|length| length > MAX_DOWNLOAD_BYTES)
    {
        return Err(AcquisitionError::DownloadRejected);
    }
    Ok(())
}

fn copy_bounded(
    mut input: impl Read,
    mut output: File,
    expected_sha256: &str,
) -> Result<(), AcquisitionError> {
    let mut digest = Sha256::new();
    let mut total = 0_u64;
    let mut buffer = [0_u8; 64 * 1024];
    loop {
        let read = input
            .read(&mut buffer)
            .map_err(|_| AcquisitionError::DownloadRejected)?;
        if read == 0 {
            break;
        }
        total = total
            .checked_add(read as u64)
            .ok_or(AcquisitionError::DownloadRejected)?;
        if total > MAX_DOWNLOAD_BYTES {
            return Err(AcquisitionError::DownloadRejected);
        }
        digest.update(&buffer[..read]);
        output
            .write_all(&buffer[..read])
            .map_err(AcquisitionError::io)?;
    }
    output.sync_all().map_err(AcquisitionError::io)?;
    let actual: String = digest
        .finalize()
        .iter()
        .map(|byte| format!("{byte:02x}"))
        .collect();
    if actual != expected_sha256 {
        return Err(AcquisitionError::DownloadRejected);
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn streaming_copy_enforces_hash_without_buffering_the_archive() {
        let content = b"bounded archive fixture";
        let path =
            std::env::temp_dir().join(format!("unciv-download-{}.zip", uuid::Uuid::new_v4()));
        let output = OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(&path)
            .unwrap();
        let expected = crate::state_hash(content);
        copy_bounded(content.as_slice(), output, &expected).unwrap();
        assert_eq!(std::fs::read(&path).unwrap(), content);
        std::fs::remove_file(path).unwrap();
    }
}
