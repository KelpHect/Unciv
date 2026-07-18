//! Authentication primitives. Passwords are only stored as Argon2id PHC
//! strings; session credentials are designed to be stored as digests.

use argon2::{
    password_hash::{rand_core::OsRng, PasswordHash, PasswordHasher, PasswordVerifier, SaltString},
    Argon2,
};
use rand_core::RngCore;
use sha2::{Digest, Sha256};
use thiserror::Error;

const MINIMUM_PASSWORD_LENGTH: usize = 12;

#[derive(Debug, Error, PartialEq, Eq)]
pub enum PasswordError {
    #[error("password must be at least {MINIMUM_PASSWORD_LENGTH} characters")]
    TooShort,
    #[error("stored password hash is malformed")]
    InvalidStoredHash,
    #[error("password hashing failed")]
    HashingFailed,
}

/// Produces and verifies Argon2id PHC strings. The RustCrypto default is
/// Argon2id, so the algorithm identifier is retained in every stored hash.
#[derive(Clone, Debug, Default)]
pub struct PasswordService;

impl PasswordService {
    pub fn hash(&self, password: &str) -> Result<String, PasswordError> {
        validate_password(password)?;
        let salt = SaltString::generate(&mut OsRng);
        Argon2::default()
            .hash_password(password.as_bytes(), &salt)
            .map(|value| value.to_string())
            .map_err(|_| PasswordError::HashingFailed)
    }

    /// Returns `Ok(true)` only for a valid password against a valid stored
    /// Argon2 PHC string. A wrong password deliberately has no distinct error.
    pub fn verify(&self, password: &str, stored_hash: &str) -> Result<bool, PasswordError> {
        let parsed =
            PasswordHash::new(stored_hash).map_err(|_| PasswordError::InvalidStoredHash)?;
        Ok(Argon2::default()
            .verify_password(password.as_bytes(), &parsed)
            .is_ok())
    }
}

/// A newly-issued opaque bearer token and the only value that may be persisted
/// for it. Callers return `token` once over TLS and insert only `digest` into
/// `sessions.token_digest`.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct SessionCredential {
    pub token: String,
    pub digest: String,
}

impl SessionCredential {
    pub fn generate() -> Self {
        let mut bytes = [0_u8; 32];
        OsRng.fill_bytes(&mut bytes);
        let token = hex_encode(&bytes);
        let digest = token_digest(&token);
        Self { token, digest }
    }
}

pub fn token_digest(token: &str) -> String {
    hex_encode(&Sha256::digest(token.as_bytes()))
}

fn validate_password(password: &str) -> Result<(), PasswordError> {
    if password.chars().count() < MINIMUM_PASSWORD_LENGTH {
        return Err(PasswordError::TooShort);
    }
    Ok(())
}

fn hex_encode(bytes: &[u8]) -> String {
    const HEX: &[u8; 16] = b"0123456789abcdef";
    let mut encoded = String::with_capacity(bytes.len() * 2);
    for byte in bytes {
        encoded.push(HEX[usize::from(byte >> 4)] as char);
        encoded.push(HEX[usize::from(byte & 0x0f)] as char);
    }
    encoded
}

#[cfg(test)]
mod tests {
    use super::{token_digest, PasswordError, PasswordService, SessionCredential};

    #[test]
    fn argon2id_hashes_and_verifies_passwords() {
        let passwords = PasswordService;
        let stored = passwords.hash("long-enough-password").unwrap();

        assert!(stored.starts_with("$argon2id$"));
        assert!(passwords.verify("long-enough-password", &stored).unwrap());
        assert!(!passwords.verify("not-the-password", &stored).unwrap());
    }

    #[test]
    fn short_passwords_and_malformed_hashes_are_rejected() {
        let passwords = PasswordService;
        assert_eq!(
            passwords.hash("too-short").unwrap_err(),
            PasswordError::TooShort
        );
        assert_eq!(
            passwords
                .verify("long-enough-password", "not-a-phc-string")
                .unwrap_err(),
            PasswordError::InvalidStoredHash,
        );
    }

    #[test]
    fn session_tokens_have_256_bits_of_entropy_and_only_the_digest_is_stable() {
        let first = SessionCredential::generate();
        let second = SessionCredential::generate();

        assert_eq!(first.token.len(), 64);
        assert_eq!(first.digest.len(), 64);
        assert_eq!(first.digest, token_digest(&first.token));
        assert_ne!(first.token, second.token);
        assert_ne!(first.digest, second.digest);
    }
}
