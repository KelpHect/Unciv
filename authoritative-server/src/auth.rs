//! Authentication primitives. Passwords are only stored as Argon2id PHC
//! strings; session credentials are designed to be stored as digests.

use argon2::{
    Argon2,
    password_hash::{PasswordHash, PasswordHasher, PasswordVerifier, SaltString, rand_core::OsRng},
};
use rand_core::RngCore;
use sha2::{Digest, Sha256};
use thiserror::Error;
use uuid::Uuid;

const MINIMUM_PASSWORD_LENGTH: usize = 12;
const MINIMUM_USERNAME_LENGTH: usize = 3;
const MAXIMUM_USERNAME_LENGTH: usize = 32;
const DEFAULT_MAX_ACTIVE_SESSIONS: usize = 8;
const MIN_ACTIVE_SESSIONS: usize = 1;
const MAX_ACTIVE_SESSIONS: usize = 32;
pub const RECOVERY_CODE_COUNT: usize = 8;

#[derive(Debug, Error, PartialEq, Eq)]
pub enum PasswordError {
    #[error("password must be at least {MINIMUM_PASSWORD_LENGTH} characters")]
    TooShort,
    #[error("new password must differ from the current password")]
    Unchanged,
    #[error("stored password hash is malformed")]
    InvalidStoredHash,
    #[error("password hashing failed")]
    HashingFailed,
}

#[derive(Debug, Error, PartialEq, Eq)]
pub enum UsernameError {
    #[error(
        "username must be between {MINIMUM_USERNAME_LENGTH} and {MAXIMUM_USERNAME_LENGTH} characters"
    )]
    InvalidLength,
    #[error("username may contain only lowercase letters, digits, underscores, and hyphens")]
    InvalidCharacters,
}

#[derive(Debug, Error, PartialEq, Eq)]
pub enum AuthError {
    #[error(transparent)]
    InvalidUsername(#[from] UsernameError),
    #[error(transparent)]
    InvalidPassword(#[from] PasswordError),
    #[error("username is already registered")]
    UsernameTaken,
    #[error("invalid credentials")]
    InvalidCredentials,
    #[error("account is disabled")]
    AccountDisabled,
    #[error("authentication request rate limit exceeded")]
    RateLimited,
    #[error("authentication storage failure")]
    Storage,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Account {
    pub id: Uuid,
    pub username_normalized: String,
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

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct SessionPolicy {
    max_active_sessions: usize,
}

impl Default for SessionPolicy {
    fn default() -> Self {
        Self {
            max_active_sessions: DEFAULT_MAX_ACTIVE_SESSIONS,
        }
    }
}

impl SessionPolicy {
    pub fn try_with_max_active_sessions(max_active_sessions: usize) -> Result<Self, &'static str> {
        Self::from_max_active_sessions(max_active_sessions)
            .map_err(|_| "active-session limit must be between 1 and 32")
    }

    pub const fn max_active_sessions(self) -> usize {
        self.max_active_sessions
    }

    pub fn from_environment() -> Result<Self, &'static str> {
        match std::env::var("UNCIV_V3_MAX_ACTIVE_SESSIONS") {
            Ok(value) => value
                .parse()
                .ok()
                .and_then(|value| Self::from_max_active_sessions(value).ok())
                .ok_or("UNCIV_V3_MAX_ACTIVE_SESSIONS must be between 1 and 32"),
            Err(std::env::VarError::NotPresent) => Ok(Self::default()),
            Err(std::env::VarError::NotUnicode(_)) => {
                Err("UNCIV_V3_MAX_ACTIVE_SESSIONS must be Unicode")
            }
        }
    }

    fn from_max_active_sessions(max_active_sessions: usize) -> Result<Self, ()> {
        if !(MIN_ACTIVE_SESSIONS..=MAX_ACTIVE_SESSIONS).contains(&max_active_sessions) {
            return Err(());
        }
        Ok(Self {
            max_active_sessions,
        })
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct RecoveryCodeBatch {
    pub codes: Vec<String>,
}

impl RecoveryCodeBatch {
    pub fn generate() -> Self {
        Self {
            codes: (0..RECOVERY_CODE_COUNT)
                .map(|_| SessionCredential::generate().token)
                .collect(),
        }
    }
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

/// Canonicalizes the deliberately small initial username character set. This
/// makes database uniqueness and login matching deterministic without relying
/// on database collation or Unicode case-folding behavior.
pub fn normalize_username(username: &str) -> Result<String, UsernameError> {
    let normalized = username.trim().to_ascii_lowercase();
    let length = normalized.chars().count();
    if !(MINIMUM_USERNAME_LENGTH..=MAXIMUM_USERNAME_LENGTH).contains(&length) {
        return Err(UsernameError::InvalidLength);
    }
    if !normalized.bytes().all(|byte| {
        byte.is_ascii_lowercase() || byte.is_ascii_digit() || matches!(byte, b'_' | b'-')
    }) {
        return Err(UsernameError::InvalidCharacters);
    }
    Ok(normalized)
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
    use super::{
        PasswordError, PasswordService, RECOVERY_CODE_COUNT, RecoveryCodeBatch, SessionCredential,
        SessionPolicy, UsernameError, normalize_username, token_digest,
    };

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

    #[test]
    fn session_policy_is_bounded_and_recovery_codes_are_independent_secrets() {
        assert_eq!(SessionPolicy::default().max_active_sessions(), 8);
        assert!(SessionPolicy::try_with_max_active_sessions(1).is_ok());
        assert!(SessionPolicy::try_with_max_active_sessions(32).is_ok());
        assert!(SessionPolicy::try_with_max_active_sessions(0).is_err());
        assert!(SessionPolicy::try_with_max_active_sessions(33).is_err());

        let batch = RecoveryCodeBatch::generate();
        assert_eq!(batch.codes.len(), RECOVERY_CODE_COUNT);
        assert!(batch.codes.iter().all(|code| code.len() == 64));
        let unique = batch.codes.iter().collect::<std::collections::HashSet<_>>();
        assert_eq!(unique.len(), RECOVERY_CODE_COUNT);
    }

    #[test]
    fn usernames_are_normalized_into_a_collation_independent_namespace() {
        assert_eq!(normalize_username("  Player-One ").unwrap(), "player-one");
        assert_eq!(
            normalize_username("ab").unwrap_err(),
            UsernameError::InvalidLength
        );
        assert_eq!(
            normalize_username("player one").unwrap_err(),
            UsernameError::InvalidCharacters
        );
    }
}
