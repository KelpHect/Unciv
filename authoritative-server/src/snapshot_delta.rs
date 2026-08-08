use std::collections::HashMap;
use std::io::{Cursor, Read};

use sha2::{Digest, Sha256};

use crate::{MAX_SNAPSHOT_BYTES, state_hash};

const MAGIC: &[u8; 8] = b"UCVDLT01";
const MIN_CHUNK: usize = 2 * 1024;
const MAX_CHUNK: usize = 16 * 1024;
const ROLLING_WINDOW: usize = 64;
const BOUNDARY_MASK: u64 = 0x1fff;
const MAX_OPERATIONS: usize = 1_000_000;
const COPY_OPERATION: u8 = 0;
const LITERAL_OPERATION: u8 = 1;

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum DeltaError {
    Empty,
    TooLarge,
    InvalidFormat,
    BaseMismatch,
    TargetMismatch,
    Codec,
}

#[derive(Debug, Clone, PartialEq, Eq)]
enum Operation {
    Copy { offset: u32, length: u32 },
    Literal(Vec<u8>),
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SnapshotDelta {
    pub base_revision: u64,
    pub base_state_hash: String,
    pub target_state_hash: String,
    pub target_size: u32,
    pub payload: Vec<u8>,
}

impl SnapshotDelta {
    /// Builds a zstd-compressed, independently verifiable delta against one base
    /// snapshot. Callers should retain a full checkpoint whenever this result is
    /// not smaller than a normal full snapshot.
    pub fn encode(
        base_revision: u64,
        base: &[u8],
        target: &[u8],
        compression_level: i32,
    ) -> Result<Self, DeltaError> {
        validate_snapshot(base)?;
        validate_snapshot(target)?;
        let operations = build_operations(base, target);
        if operations.len() > MAX_OPERATIONS {
            return Err(DeltaError::TooLarge);
        }
        let raw = serialize_operations(&operations, base_revision, base, target)?;
        let payload = zstd::stream::encode_all(Cursor::new(raw), compression_level)
            .map_err(|_| DeltaError::Codec)?;
        if payload.is_empty() || payload.len() > MAX_SNAPSHOT_BYTES {
            return Err(DeltaError::TooLarge);
        }
        Ok(Self {
            base_revision,
            base_state_hash: state_hash(base),
            target_state_hash: state_hash(target),
            target_size: u32::try_from(target.len()).map_err(|_| DeltaError::TooLarge)?,
            payload,
        })
    }

    pub fn decode(&self, base: &[u8]) -> Result<Vec<u8>, DeltaError> {
        validate_snapshot(base)?;
        if state_hash(base) != self.base_state_hash {
            return Err(DeltaError::BaseMismatch);
        }
        let decoder = zstd::stream::read::Decoder::new(Cursor::new(&self.payload))
            .map_err(|_| DeltaError::Codec)?;
        let mut raw = Vec::new();
        decoder
            .take((MAX_SNAPSHOT_BYTES + 1) as u64)
            .read_to_end(&mut raw)
            .map_err(|_| DeltaError::Codec)?;
        let (base_revision, encoded_base_hash, encoded_target_hash, target_size, operations) =
            deserialize_operations(&raw)?;
        if base_revision != self.base_revision
            || encoded_base_hash != self.base_state_hash
            || encoded_target_hash != self.target_state_hash
            || target_size != self.target_size
        {
            return Err(DeltaError::TargetMismatch);
        }
        let mut target = Vec::with_capacity(target_size as usize);
        for operation in operations {
            match operation {
                Operation::Copy { offset, length } => {
                    let end = usize::try_from(offset)
                        .ok()
                        .and_then(|offset| offset.checked_add(length as usize))
                        .ok_or(DeltaError::InvalidFormat)?;
                    if end > base.len() {
                        return Err(DeltaError::InvalidFormat);
                    }
                    target.extend_from_slice(&base[offset as usize..end]);
                }
                Operation::Literal(bytes) => target.extend_from_slice(&bytes),
            }
            if target.len() > MAX_SNAPSHOT_BYTES {
                return Err(DeltaError::TooLarge);
            }
        }
        if target.len() != self.target_size as usize
            || state_hash(&target) != self.target_state_hash
        {
            return Err(DeltaError::TargetMismatch);
        }
        Ok(target)
    }
}

fn validate_snapshot(snapshot: &[u8]) -> Result<(), DeltaError> {
    if snapshot.is_empty() {
        return Err(DeltaError::Empty);
    }
    if snapshot.len() > MAX_SNAPSHOT_BYTES {
        return Err(DeltaError::TooLarge);
    }
    Ok(())
}

fn build_operations(base: &[u8], target: &[u8]) -> Vec<Operation> {
    let base_chunks = chunk_ranges(base);
    let mut index: HashMap<(u64, usize), Vec<usize>> = HashMap::new();
    for (offset, length) in base_chunks {
        index
            .entry((chunk_hash(&base[offset..offset + length]), length))
            .or_default()
            .push(offset);
    }

    let mut operations = Vec::new();
    let mut literal = Vec::new();
    for (offset, length) in chunk_ranges(target) {
        let chunk = &target[offset..offset + length];
        let copy = index.get(&(chunk_hash(chunk), length)).and_then(|offsets| {
            offsets
                .iter()
                .copied()
                .find(|base_offset| &base[*base_offset..*base_offset + length] == chunk)
        });
        if let Some(base_offset) = copy {
            flush_literal(&mut operations, &mut literal);
            operations.push(Operation::Copy {
                offset: u32::try_from(base_offset).unwrap_or(u32::MAX),
                length: u32::try_from(length).unwrap_or(u32::MAX),
            });
        } else {
            literal.extend_from_slice(chunk);
        }
    }
    flush_literal(&mut operations, &mut literal);
    operations
}

fn flush_literal(operations: &mut Vec<Operation>, literal: &mut Vec<u8>) {
    if !literal.is_empty() {
        operations.push(Operation::Literal(std::mem::take(literal)));
    }
}

fn chunk_ranges(bytes: &[u8]) -> Vec<(usize, usize)> {
    let mut ranges = Vec::new();
    let mut start = 0;
    let base = 257_u64;
    let base_power = (0..ROLLING_WINDOW).fold(1_u64, |value, _| value.wrapping_mul(base));
    while start < bytes.len() {
        let max_end = (start + MAX_CHUNK).min(bytes.len());
        let mut hash = 0_u64;
        let mut end = start;
        while end < max_end {
            hash = hash.wrapping_mul(base).wrapping_add(u64::from(bytes[end]));
            if end - start >= ROLLING_WINDOW {
                hash = hash
                    .wrapping_sub(u64::from(bytes[end - ROLLING_WINDOW]).wrapping_mul(base_power));
            }
            end += 1;
            if end - start >= MIN_CHUNK && (hash & BOUNDARY_MASK) == 0 {
                break;
            }
        }
        ranges.push((start, end - start));
        start = end;
    }
    ranges
}

fn chunk_hash(bytes: &[u8]) -> u64 {
    let digest = Sha256::digest(bytes);
    u64::from_le_bytes(digest[..8].try_into().expect("eight digest bytes"))
}

fn serialize_operations(
    operations: &[Operation],
    base_revision: u64,
    base: &[u8],
    target: &[u8],
) -> Result<Vec<u8>, DeltaError> {
    let mut raw = Vec::with_capacity(target.len().min(MAX_SNAPSHOT_BYTES));
    raw.extend_from_slice(MAGIC);
    raw.extend_from_slice(&base_revision.to_le_bytes());
    write_hash(&mut raw, &state_hash(base));
    write_hash(&mut raw, &state_hash(target));
    raw.extend_from_slice(
        &u32::try_from(target.len())
            .map_err(|_| DeltaError::TooLarge)?
            .to_le_bytes(),
    );
    raw.extend_from_slice(
        &u32::try_from(operations.len())
            .map_err(|_| DeltaError::TooLarge)?
            .to_le_bytes(),
    );
    for operation in operations {
        match operation {
            Operation::Copy { offset, length } => {
                raw.push(COPY_OPERATION);
                raw.extend_from_slice(&offset.to_le_bytes());
                raw.extend_from_slice(&length.to_le_bytes());
            }
            Operation::Literal(bytes) => {
                raw.push(LITERAL_OPERATION);
                raw.extend_from_slice(
                    &u32::try_from(bytes.len())
                        .map_err(|_| DeltaError::TooLarge)?
                        .to_le_bytes(),
                );
                raw.extend_from_slice(bytes);
            }
        }
        if raw.len() > MAX_SNAPSHOT_BYTES {
            return Err(DeltaError::TooLarge);
        }
    }
    Ok(raw)
}

fn deserialize_operations(
    raw: &[u8],
) -> Result<(u64, String, String, u32, Vec<Operation>), DeltaError> {
    let mut cursor = 0;
    if raw.len() < MAGIC.len() + 8 + 64 + 64 + 8 || &raw[..MAGIC.len()] != MAGIC {
        return Err(DeltaError::InvalidFormat);
    }
    cursor += MAGIC.len();
    let base_revision = read_u64(raw, &mut cursor)?;
    let base_hash = read_hash(raw, &mut cursor)?;
    let target_hash = read_hash(raw, &mut cursor)?;
    let target_size = read_u32(raw, &mut cursor)?;
    let operation_count =
        usize::try_from(read_u32(raw, &mut cursor)?).map_err(|_| DeltaError::InvalidFormat)?;
    if operation_count > MAX_OPERATIONS {
        return Err(DeltaError::TooLarge);
    }
    let mut operations = Vec::with_capacity(operation_count);
    for _ in 0..operation_count {
        let tag = *raw.get(cursor).ok_or(DeltaError::InvalidFormat)?;
        cursor += 1;
        match tag {
            COPY_OPERATION => operations.push(Operation::Copy {
                offset: read_u32(raw, &mut cursor)?,
                length: read_u32(raw, &mut cursor)?,
            }),
            LITERAL_OPERATION => {
                let length = usize::try_from(read_u32(raw, &mut cursor)?)
                    .map_err(|_| DeltaError::InvalidFormat)?;
                let end = cursor
                    .checked_add(length)
                    .ok_or(DeltaError::InvalidFormat)?;
                let bytes = raw
                    .get(cursor..end)
                    .ok_or(DeltaError::InvalidFormat)?
                    .to_vec();
                cursor = end;
                operations.push(Operation::Literal(bytes));
            }
            _ => return Err(DeltaError::InvalidFormat),
        }
    }
    if cursor != raw.len() {
        return Err(DeltaError::InvalidFormat);
    }
    Ok((
        base_revision,
        base_hash,
        target_hash,
        target_size,
        operations,
    ))
}

fn write_hash(output: &mut Vec<u8>, hash: &str) {
    output.extend_from_slice(hash.as_bytes());
}

fn read_hash(bytes: &[u8], cursor: &mut usize) -> Result<String, DeltaError> {
    let end = cursor.checked_add(64).ok_or(DeltaError::InvalidFormat)?;
    let hash = bytes.get(*cursor..end).ok_or(DeltaError::InvalidFormat)?;
    *cursor = end;
    let hash = std::str::from_utf8(hash).map_err(|_| DeltaError::InvalidFormat)?;
    if !hash.bytes().all(|byte| byte.is_ascii_hexdigit()) {
        return Err(DeltaError::InvalidFormat);
    }
    Ok(hash.to_owned())
}

fn read_u32(bytes: &[u8], cursor: &mut usize) -> Result<u32, DeltaError> {
    let end = cursor.checked_add(4).ok_or(DeltaError::InvalidFormat)?;
    let value = u32::from_le_bytes(
        bytes
            .get(*cursor..end)
            .ok_or(DeltaError::InvalidFormat)?
            .try_into()
            .unwrap(),
    );
    *cursor = end;
    Ok(value)
}

fn read_u64(bytes: &[u8], cursor: &mut usize) -> Result<u64, DeltaError> {
    let end = cursor.checked_add(8).ok_or(DeltaError::InvalidFormat)?;
    let value = u64::from_le_bytes(
        bytes
            .get(*cursor..end)
            .ok_or(DeltaError::InvalidFormat)?
            .try_into()
            .unwrap(),
    );
    *cursor = end;
    Ok(value)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn insertion_reconstructs_exact_target() {
        let base = br#"{"turn":42,"units":[1,2,3],"cities":["Rome"]}"#.repeat(10_000);
        let mut target = base.clone();
        target.splice(20_000..20_000, br#"{"new":true},"#.iter().copied());
        let delta = SnapshotDelta::encode(17, &base, &target, 9).unwrap();
        assert_eq!(delta.decode(&base).unwrap(), target);
        assert_eq!(delta.base_revision, 17);
        assert!(delta.payload.len() < base.len() / 10);
    }

    #[test]
    fn wrong_base_and_corruption_fail_closed() {
        let base = b"base".repeat(10_000);
        let target = b"target".repeat(10_000);
        let mut delta = SnapshotDelta::encode(1, &base, &target, 9).unwrap();
        assert_eq!(delta.decode(b"other"), Err(DeltaError::BaseMismatch));
        let middle = delta.payload.len() / 2;
        delta.payload[middle] ^= 0x55;
        assert!(matches!(
            delta.decode(&base),
            Err(DeltaError::Codec | DeltaError::TargetMismatch | DeltaError::InvalidFormat)
        ));
    }

    #[test]
    fn random_data_is_still_bounded_and_correct() {
        let base: Vec<u8> = (0..100_000).map(|index| (index % 251) as u8).collect();
        let target: Vec<u8> = (0..100_000)
            .map(|index| ((index * 17) % 251) as u8)
            .collect();
        let delta = SnapshotDelta::encode(0, &base, &target, 9).unwrap();
        assert_eq!(delta.decode(&base).unwrap(), target);
    }
}
