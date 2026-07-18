//! Authentication primitives. Passwords are only stored as Argon2id PHC
//! strings; session credentials are designed to be stored as digests.

use argon2::{
    password_hash::{rand_core::OsRng, PasswordHash, PasswordHasher, PasswordVerifier, SaltString},
    Argon2,
};
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

fn validate_password(password: &str) -> Result<(), PasswordError> {
    if password.chars().count() < MINIMUM_PASSWORD_LENGTH {
        return Err(PasswordError::TooShort);
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::{PasswordError, PasswordService};

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
}
