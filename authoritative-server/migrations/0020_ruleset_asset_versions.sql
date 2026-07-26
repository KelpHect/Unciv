-- Operator-installed immutable worker asset versions. A version may be removed
-- only after no game references its manifest and it is not the active
-- filesystem target.
CREATE TABLE ruleset_asset_versions (
    version_id CHAR(64) PRIMARY KEY,
    manifest_hash CHAR(64) NOT NULL REFERENCES ruleset_manifests(hash),
    installed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ruleset_asset_versions_manifest_idx
    ON ruleset_asset_versions (manifest_hash);
