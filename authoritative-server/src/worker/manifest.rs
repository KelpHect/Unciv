use serde::{Deserialize, Serialize};

#[derive(Clone, Debug, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct WorkerManifest {
    pub engine_build: String,
    pub base_ruleset: WorkerRuleset,
    pub mods: Vec<WorkerRuleset>,
}

#[derive(Clone, Debug, Deserialize, Serialize, PartialEq, Eq)]
#[serde(deny_unknown_fields)]
pub struct WorkerRuleset {
    pub name: String,
    pub sha256: String,
}

impl WorkerManifest {
    pub(crate) fn is_valid(&self) -> bool {
        bounded_name(&self.engine_build)
            && self.base_ruleset.is_valid()
            && self.mods.len() <= 64
            && self.mods.iter().all(WorkerRuleset::is_valid)
            && {
                let mut names = std::collections::HashSet::new();
                names.insert(self.base_ruleset.name.as_str())
                    && self.mods.iter().all(|ruleset| names.insert(&ruleset.name))
            }
    }
}

impl WorkerRuleset {
    pub(crate) fn is_valid(&self) -> bool {
        bounded_name(&self.name)
            && self.sha256.len() == 64
            && self
                .sha256
                .bytes()
                .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
    }
}

fn bounded_name(value: &str) -> bool {
    !value.is_empty()
        && value.len() <= 128
        && value.chars().all(|character| !character.is_control())
}
