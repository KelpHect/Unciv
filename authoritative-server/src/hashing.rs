use sha2::{Digest, Sha256};

pub fn state_hash(snapshot: &[u8]) -> String {
    Sha256::digest(snapshot)
        .iter()
        .map(|byte| format!("{byte:02x}"))
        .collect()
}
