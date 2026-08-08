use std::io::{Cursor, Read};

use crate::{MAX_SNAPSHOT_BYTES, state_hash};

const ZSTD_CODEC: &str = "zstd";
const MAX_ZSTD_WINDOW_LOG: u32 = 24;

/// Snapshot zstd compression level, configurable via UNCIV_V3_SNAPSHOT_ZSTD_LEVEL (1..=22).
/// Defaults to 9: on typical snapshot sizes this is ~24% smaller than level 3
/// (the historical default) at near-identical wall-clock encode time.
/// Decoding is level-agnostic -- the level is embedded in each zstd frame -- so
/// old and new rows interoperate and this can change freely without a migration.
fn zstd_level() -> i32 {
    std::env::var("UNCIV_V3_SNAPSHOT_ZSTD_LEVEL")
        .ok()
        .and_then(|value| value.parse::<i32>().ok())
        .filter(|level| (1..=22).contains(level))
        .unwrap_or(9)
}

#[derive(Debug, PartialEq, Eq)]
pub(super) enum SnapshotCodecError {
    Empty,
    TooLarge,
    InvalidSize,
    UnsupportedCodec,
    Codec,
}

pub(super) struct StoredSnapshot {
    pub codec: &'static str,
    pub compressed_size: i64,
    pub uncompressed_size: i64,
    pub canonical_state_hash: String,
    pub payload_hash: String,
    pub payload: Vec<u8>,
}

pub(super) fn encode_snapshot(snapshot: &[u8]) -> Result<StoredSnapshot, SnapshotCodecError> {
    if snapshot.is_empty() {
        return Err(SnapshotCodecError::Empty);
    }
    if snapshot.len() > MAX_SNAPSHOT_BYTES {
        return Err(SnapshotCodecError::TooLarge);
    }
    let payload = zstd::stream::encode_all(Cursor::new(snapshot), zstd_level())
        .map_err(|_| SnapshotCodecError::Codec)?;
    if payload.is_empty() || payload.len() > MAX_SNAPSHOT_BYTES {
        return Err(SnapshotCodecError::TooLarge);
    }
    Ok(StoredSnapshot {
        codec: ZSTD_CODEC,
        compressed_size: i64::try_from(payload.len()).expect("snapshot length fits BIGINT"),
        uncompressed_size: i64::try_from(snapshot.len()).expect("snapshot length fits BIGINT"),
        canonical_state_hash: state_hash(snapshot),
        payload_hash: state_hash(&payload),
        payload,
    })
}

pub(super) fn decode_snapshot(
    codec: &str,
    payload: &[u8],
    declared_compressed_size: i64,
    declared_uncompressed_size: i64,
) -> Result<Vec<u8>, SnapshotCodecError> {
    if payload.is_empty() {
        return Err(SnapshotCodecError::Empty);
    }
    if payload.len() > MAX_SNAPSHOT_BYTES
        || declared_compressed_size != payload.len() as i64
        || !(1..=MAX_SNAPSHOT_BYTES as i64).contains(&declared_uncompressed_size)
    {
        return Err(SnapshotCodecError::InvalidSize);
    }
    let decoded = match codec {
        "identity" => {
            if declared_uncompressed_size != declared_compressed_size {
                return Err(SnapshotCodecError::InvalidSize);
            }
            payload.to_vec()
        }
        ZSTD_CODEC => {
            let mut decoder = zstd::stream::read::Decoder::new(Cursor::new(payload))
                .map_err(|_| SnapshotCodecError::Codec)?;
            decoder
                .window_log_max(MAX_ZSTD_WINDOW_LOG)
                .map_err(|_| SnapshotCodecError::Codec)?;
            let mut decoded = Vec::with_capacity(
                usize::try_from(declared_uncompressed_size)
                    .map_err(|_| SnapshotCodecError::InvalidSize)?,
            );
            decoder
                .take((MAX_SNAPSHOT_BYTES + 1) as u64)
                .read_to_end(&mut decoded)
                .map_err(|_| SnapshotCodecError::Codec)?;
            decoded
        }
        _ => return Err(SnapshotCodecError::UnsupportedCodec),
    };
    if decoded.len() > MAX_SNAPSHOT_BYTES || decoded.len() as i64 != declared_uncompressed_size {
        return Err(SnapshotCodecError::InvalidSize);
    }
    Ok(decoded)
}

#[cfg(test)]
mod tests {
    use super::*;
    use proptest::prelude::*;
    use proptest::test_runner::RngSeed;

    #[test]
    fn zstd_round_trip_keeps_separate_payload_and_canonical_hashes() {
        let canonical = br#"{"turn":42,"civilizations":["Rome","Greece"]}"#.repeat(200);
        let stored = encode_snapshot(&canonical).unwrap();
        assert_eq!(stored.codec, "zstd");
        assert!(stored.payload.len() < canonical.len());
        assert_eq!(stored.canonical_state_hash, state_hash(&canonical));
        assert_eq!(stored.payload_hash, state_hash(&stored.payload));
        assert_ne!(stored.payload_hash, stored.canonical_state_hash);
        assert_eq!(
            decode_snapshot(
                stored.codec,
                &stored.payload,
                stored.compressed_size,
                stored.uncompressed_size,
            ),
            Ok(canonical)
        );
    }

    #[test]
    fn identity_compatibility_is_size_checked() {
        assert_eq!(
            decode_snapshot("identity", b"legacy", 6, 6),
            Ok(b"legacy".to_vec())
        );
        assert_eq!(
            decode_snapshot("identity", b"legacy", 6, 7),
            Err(SnapshotCodecError::InvalidSize)
        );
        assert_eq!(
            decode_snapshot("unknown", b"legacy", 6, 6),
            Err(SnapshotCodecError::UnsupportedCodec)
        );
    }

    #[test]
    fn decompression_is_bounded_even_when_the_frame_exceeds_its_claim() {
        let oversized = vec![b'x'; MAX_SNAPSHOT_BYTES + 1];
        let payload = zstd::stream::encode_all(Cursor::new(oversized), zstd_level()).unwrap();
        assert_eq!(
            decode_snapshot(
                "zstd",
                &payload,
                payload.len() as i64,
                MAX_SNAPSHOT_BYTES as i64,
            ),
            Err(SnapshotCodecError::InvalidSize)
        );
    }

    proptest! {
        #![proptest_config(ProptestConfig {
            cases: 128,
            rng_seed: RngSeed::Fixed(0x554E_4349_5650_3302),
            ..ProptestConfig::default()
        })]

        #[test]
        fn arbitrary_snapshots_round_trip_with_exact_hashes(
            canonical in prop::collection::vec(any::<u8>(), 1..16_384),
        ) {
            let stored = encode_snapshot(&canonical).unwrap();
            prop_assert_eq!(stored.canonical_state_hash, state_hash(&canonical));
            prop_assert_eq!(stored.payload_hash, state_hash(&stored.payload));
            prop_assert_eq!(
                decode_snapshot(
                    stored.codec,
                    &stored.payload,
                    stored.compressed_size,
                    stored.uncompressed_size,
                ),
                Ok(canonical),
            );
        }

        #[test]
        fn declared_size_mismatches_and_arbitrary_frames_fail_bounded(
            payload in prop::collection::vec(any::<u8>(), 1..8_192),
            compressed_delta in -3_i64..=3,
            uncompressed_size in -3_i64..32_768,
        ) {
            let declared_compressed = payload.len() as i64 + compressed_delta;
            let result = decode_snapshot(
                "zstd",
                &payload,
                declared_compressed,
                uncompressed_size,
            );
            if let Ok(decoded) = result {
                prop_assert!(decoded.len() <= MAX_SNAPSHOT_BYTES);
                prop_assert_eq!(decoded.len() as i64, uncompressed_size);
                prop_assert_eq!(declared_compressed, payload.len() as i64);
            }
        }
    }
}
