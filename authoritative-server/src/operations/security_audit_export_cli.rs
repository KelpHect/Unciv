use std::{
    fs::OpenOptions,
    io::{BufWriter, Write},
    path::PathBuf,
    process::ExitCode,
};

use serde::Serialize;

use crate::{
    postgres::{PostgresGameRepository, SecurityAuditExportEvent},
    state_hash,
};

const GENESIS_HASH: &str = "0000000000000000000000000000000000000000000000000000000000000000";

#[derive(Serialize)]
struct ChainedEvent<'a> {
    record_type: &'static str,
    previous_hash: &'a str,
    event: &'a SecurityAuditExportEvent,
    record_hash: &'a str,
}

#[derive(Debug, Serialize)]
struct ExportManifest {
    record_type: &'static str,
    format: &'static str,
    through_id: i64,
    event_count: u64,
    first_event_id: Option<i64>,
    last_event_id: Option<i64>,
    final_record_hash: String,
}

pub async fn run_security_audit_export_cli() -> ExitCode {
    let Some(output_path) = parse_output_path() else {
        return usage();
    };
    let Ok(database_url) = std::env::var("UNCIV_V3_AUDIT_DATABASE_URL") else {
        eprintln!("UNCIV_V3_AUDIT_DATABASE_URL is required for security audit export");
        return ExitCode::FAILURE;
    };
    let Ok(repository) = PostgresGameRepository::connect(&database_url).await else {
        eprintln!("failed to connect with the isolated audit database identity");
        return ExitCode::FAILURE;
    };
    let Ok(file) = OpenOptions::new()
        .write(true)
        .create_new(true)
        .open(&output_path)
    else {
        eprintln!("audit export destination must be a new file");
        return ExitCode::FAILURE;
    };
    let Ok(sync_file) = file.try_clone() else {
        eprintln!("failed to prepare durable audit export output");
        return ExitCode::FAILURE;
    };
    match export(&repository, BufWriter::new(file)).await {
        Ok(manifest) => {
            if sync_file.sync_all().is_err() {
                eprintln!("failed to durably synchronize the audit export");
                return ExitCode::FAILURE;
            }
            eprintln!(
                "exported {} immutable security audit events through id {} with final hash {}",
                manifest.event_count, manifest.through_id, manifest.final_record_hash
            );
            ExitCode::SUCCESS
        }
        Err(()) => {
            eprintln!("security audit export failed closed; quarantine the partial output");
            ExitCode::FAILURE
        }
    }
}

fn parse_output_path() -> Option<PathBuf> {
    let mut arguments = std::env::args().skip(1);
    match (
        arguments.next().as_deref(),
        arguments.next(),
        arguments.next(),
    ) {
        (Some("--output"), Some(path), None) if !path.trim().is_empty() => {
            Some(PathBuf::from(path))
        }
        _ => None,
    }
}

async fn export<W: Write>(
    repository: &PostgresGameRepository,
    mut output: W,
) -> Result<ExportManifest, ()> {
    let through_id = repository
        .security_audit_export_high_watermark()
        .await
        .map_err(|_| ())?;
    let mut cursor = 0_i64;
    let mut count = 0_u64;
    let mut first_id = None;
    let mut chain_hash = GENESIS_HASH.to_owned();
    loop {
        let page = repository
            .security_audit_export_page(cursor, through_id)
            .await
            .map_err(|_| ())?;
        if page.is_empty() {
            break;
        }
        for event in &page {
            let record_hash = hash_record(&chain_hash, event);
            write_json_line(
                &mut output,
                &ChainedEvent {
                    record_type: "security_audit_event",
                    previous_hash: &chain_hash,
                    event,
                    record_hash: &record_hash,
                },
            )?;
            first_id.get_or_insert(event.id);
            cursor = event.id;
            count = count.checked_add(1).ok_or(())?;
            chain_hash = record_hash;
        }
    }
    let manifest = ExportManifest {
        record_type: "security_audit_manifest",
        format: "unciv-security-audit-ndjson-v1",
        through_id,
        event_count: count,
        first_event_id: first_id,
        last_event_id: (count > 0).then_some(cursor),
        final_record_hash: chain_hash,
    };
    write_json_line(&mut output, &manifest)?;
    output.flush().map_err(|_| ())?;
    Ok(manifest)
}

fn hash_record(previous_hash: &str, event: &SecurityAuditExportEvent) -> String {
    let event_json = serde_json::to_vec(event).expect("audit export event is serializable");
    let mut material = Vec::with_capacity(previous_hash.len() + 1 + event_json.len());
    material.extend_from_slice(previous_hash.as_bytes());
    material.push(b'\n');
    material.extend_from_slice(&event_json);
    state_hash(&material)
}

fn write_json_line(output: &mut impl Write, value: &impl Serialize) -> Result<(), ()> {
    serde_json::to_writer(&mut *output, value).map_err(|_| ())?;
    output.write_all(b"\n").map_err(|_| ())
}

fn usage() -> ExitCode {
    eprintln!("usage: unciv-v3-export-security-audit --output <new-file.ndjson>");
    ExitCode::FAILURE
}

#[cfg(test)]
mod tests {
    use super::*;
    use uuid::Uuid;

    #[test]
    fn record_chain_is_deterministic_and_order_sensitive() {
        let event = SecurityAuditExportEvent {
            id: 7,
            account_id: Some(Uuid::nil()),
            event_type: "login".to_owned(),
            outcome: "rejected".to_owned(),
            source_ip_prefix: Some("192.0.2.0/24".to_owned()),
            identity_hash: Some("a".repeat(64)),
            created_at_utc: "2026-07-28T00:00:00.000000Z".to_owned(),
        };
        let first = hash_record(GENESIS_HASH, &event);
        assert_eq!(first, hash_record(GENESIS_HASH, &event));
        assert_ne!(first, hash_record(&first, &event));
    }
}
