use super::*;

#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, utoipa::ToSchema)]
pub struct PublicRulesetIdentity {
    pub name: String,
    pub sha256: String,
}

#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, utoipa::ToSchema)]
pub struct RulesetManifestSummary {
    pub manifest_hash: String,
    pub engine_build: String,
    pub base_ruleset: PublicRulesetIdentity,
    pub mods: Vec<PublicRulesetIdentity>,
}

#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, utoipa::ToSchema)]
pub struct RulesetManifestPage {
    pub manifests: Vec<RulesetManifestSummary>,
    pub next_cursor: Option<String>,
}

impl PostgresGameRepository {
    /// Lists only bounded public identities for manifests already installed by
    /// operators. Raw manifest JSON and ruleset bytes never cross this API.
    pub async fn list_ruleset_manifests(
        &self,
        after: Option<&str>,
        limit: u32,
    ) -> Result<RulesetManifestPage, CommitError> {
        if !(1..=100).contains(&limit) || after.is_some_and(|value| !is_sha256(value)) {
            return Err(CommitError::InvalidCommand);
        }
        let rows = sqlx::query(
            "SELECT hash, engine_build, manifest FROM ruleset_manifests WHERE ($1::text IS NULL OR hash > $1) ORDER BY hash LIMIT $2",
        )
        .bind(after)
        .bind(i64::from(limit) + 1)
        .fetch_all(&self.pool)
        .await
        .map_err(CommitError::storage)?;

        let has_more = rows.len() > limit as usize;
        let mut manifests = rows
            .into_iter()
            .take(limit as usize)
            .map(public_manifest)
            .collect::<Result<Vec<_>, _>>()?;
        let next_cursor = has_more.then(|| {
            manifests
                .last()
                .expect("a non-empty page has a final manifest")
                .manifest_hash
                .clone()
        });
        manifests.shrink_to_fit();
        Ok(RulesetManifestPage {
            manifests,
            next_cursor,
        })
    }
}

fn public_manifest(row: PgRow) -> Result<RulesetManifestSummary, CommitError> {
    let manifest_hash = row.get::<String, _>("hash").trim_end().to_owned();
    let stored_engine_build: String = row.get("engine_build");
    let manifest: WorkerManifest =
        serde_json::from_value(row.get("manifest")).map_err(|_| CommitError::InvalidCommand)?;
    if !is_sha256(&manifest_hash)
        || manifest.engine_build != stored_engine_build
        || !is_bounded_name(&manifest.engine_build)
        || !is_bounded_ruleset(&manifest.base_ruleset)
        || manifest.mods.len() > 64
        || manifest.mods.iter().any(|item| !is_bounded_ruleset(item))
    {
        return Err(CommitError::InvalidCommand);
    }
    let mut identities = std::collections::HashSet::new();
    if !identities.insert(manifest.base_ruleset.name.as_str())
        || manifest
            .mods
            .iter()
            .any(|item| !identities.insert(item.name.as_str()))
    {
        return Err(CommitError::InvalidCommand);
    }
    Ok(RulesetManifestSummary {
        manifest_hash,
        engine_build: manifest.engine_build,
        base_ruleset: public_identity(manifest.base_ruleset),
        mods: manifest.mods.into_iter().map(public_identity).collect(),
    })
}

fn public_identity(ruleset: crate::worker::WorkerRuleset) -> PublicRulesetIdentity {
    PublicRulesetIdentity {
        name: ruleset.name,
        sha256: ruleset.sha256,
    }
}

fn is_bounded_ruleset(ruleset: &crate::worker::WorkerRuleset) -> bool {
    is_bounded_name(&ruleset.name) && is_sha256(&ruleset.sha256)
}

fn is_bounded_name(value: &str) -> bool {
    !value.is_empty()
        && value.len() <= 128
        && value.chars().all(|character| !character.is_control())
}

fn is_sha256(value: &str) -> bool {
    value.len() == 64 && value.bytes().all(|byte| byte.is_ascii_hexdigit())
}
