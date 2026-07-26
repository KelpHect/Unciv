use serde::{Deserialize, Serialize};

use crate::worker::{WorkerManifest, WorkerRuleset};

use super::AcquisitionError;

const MAX_MODS: usize = 64;
const MAX_URL_BYTES: usize = 2_048;

#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
pub struct AcquisitionPolicy {
    pub schema_version: u16,
    pub engine_build: String,
    pub base_ruleset: WorkerRuleset,
    pub mods: Vec<AllowedModArchive>,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
pub struct AllowedModArchive {
    pub ruleset: WorkerRuleset,
    pub url: String,
    pub allowed_host: String,
    pub archive_sha256: String,
    #[serde(default)]
    pub archive_root: Option<String>,
    #[serde(default)]
    pub bearer_token_env: Option<String>,
}

impl AcquisitionPolicy {
    pub fn validate(&self) -> Result<(), AcquisitionError> {
        if self.schema_version != 1 || self.mods.len() > MAX_MODS {
            return Err(AcquisitionError::InvalidPolicy);
        }
        let manifest = self.manifest();
        if !manifest.is_valid()
            || !safe_ruleset_directory(&manifest.base_ruleset.name)
            || manifest
                .mods
                .iter()
                .any(|ruleset| !safe_ruleset_directory(&ruleset.name))
        {
            return Err(AcquisitionError::InvalidPolicy);
        }
        let mut archive_hashes = std::collections::HashSet::new();
        for item in &self.mods {
            let url =
                reqwest::Url::parse(&item.url).map_err(|_| AcquisitionError::InvalidPolicy)?;
            if item.url.len() > MAX_URL_BYTES
                || url.scheme() != "https"
                || url.username() != ""
                || url.password().is_some()
                || url.fragment().is_some()
                || url.host_str() != Some(item.allowed_host.as_str())
                || !safe_host(&item.allowed_host)
                || !lowercase_sha256(&item.archive_sha256)
                || !archive_hashes.insert(item.archive_sha256.as_str())
                || item
                    .archive_root
                    .as_deref()
                    .is_some_and(|root| !safe_archive_root(root))
                || item
                    .bearer_token_env
                    .as_deref()
                    .is_some_and(|name| !safe_environment_name(name))
            {
                return Err(AcquisitionError::InvalidPolicy);
            }
        }
        Ok(())
    }

    pub fn manifest(&self) -> WorkerManifest {
        WorkerManifest {
            engine_build: self.engine_build.clone(),
            base_ruleset: self.base_ruleset.clone(),
            mods: self.mods.iter().map(|item| item.ruleset.clone()).collect(),
        }
    }

    pub fn manifest_hash(&self) -> Result<String, AcquisitionError> {
        self.validate()?;
        serde_json::to_vec(&self.manifest())
            .map(|bytes| crate::state_hash(&bytes))
            .map_err(|_| AcquisitionError::InvalidPolicy)
    }
}

fn safe_host(value: &str) -> bool {
    !value.is_empty()
        && value.len() <= 253
        && value.bytes().all(|byte| {
            byte.is_ascii_alphanumeric() || matches!(byte, b'.' | b'-' | b':' | b'[' | b']')
        })
}

fn safe_ruleset_directory(value: &str) -> bool {
    !value.is_empty()
        && value.len() <= 128
        && value
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || b" ._()+'-".contains(&byte))
        && value
            .bytes()
            .next()
            .is_some_and(|byte| byte.is_ascii_alphanumeric())
}

fn safe_archive_root(value: &str) -> bool {
    !value.is_empty()
        && value.len() <= 128
        && !value.contains(['/', '\\', ':', '\0'])
        && value != "."
        && value != ".."
        && value.chars().all(|character| !character.is_control())
}

fn safe_environment_name(value: &str) -> bool {
    !value.is_empty()
        && value.len() <= 128
        && value
            .bytes()
            .all(|byte| byte.is_ascii_uppercase() || byte.is_ascii_digit() || byte == b'_')
}

fn lowercase_sha256(value: &str) -> bool {
    value.len() == 64
        && value
            .bytes()
            .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn policy() -> AcquisitionPolicy {
        AcquisitionPolicy {
            schema_version: 1,
            engine_build: "engine-1".to_owned(),
            base_ruleset: WorkerRuleset {
                name: "Base".to_owned(),
                sha256: "a".repeat(64),
            },
            mods: vec![AllowedModArchive {
                ruleset: WorkerRuleset {
                    name: "Example Mod".to_owned(),
                    sha256: "b".repeat(64),
                },
                url: "https://mods.example.test/releases/example.zip".to_owned(),
                allowed_host: "mods.example.test".to_owned(),
                archive_sha256: "c".repeat(64),
                archive_root: Some("example-release".to_owned()),
                bearer_token_env: Some("UNCIV_MOD_DOWNLOAD_TOKEN".to_owned()),
            }],
        }
    }

    #[test]
    fn policy_is_closed_https_only_and_identity_bound() {
        let valid = policy();
        assert!(valid.validate().is_ok());
        assert_eq!(valid.manifest().mods.len(), 1);

        for invalid in [
            AcquisitionPolicy {
                mods: vec![AllowedModArchive {
                    url: "http://mods.example.test/example.zip".to_owned(),
                    ..valid.mods[0].clone()
                }],
                ..valid.clone()
            },
            AcquisitionPolicy {
                mods: vec![AllowedModArchive {
                    allowed_host: "other.example.test".to_owned(),
                    ..valid.mods[0].clone()
                }],
                ..valid.clone()
            },
            AcquisitionPolicy {
                mods: vec![AllowedModArchive {
                    archive_root: Some("../escape".to_owned()),
                    ..valid.mods[0].clone()
                }],
                ..valid.clone()
            },
        ] {
            assert!(invalid.validate().is_err());
        }
    }
}
