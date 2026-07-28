CREATE TABLE legacy_game_imports (
    operation_id UUID PRIMARY KEY,
    owner_account_id UUID NOT NULL REFERENCES accounts(id) ON DELETE RESTRICT,
    operator_label TEXT NOT NULL CHECK (length(operator_label) BETWEEN 1 AND 128),
    legacy_origin TEXT NOT NULL CHECK (length(legacy_origin) BETWEEN 1 AND 256),
    legacy_game_id TEXT NOT NULL CHECK (length(legacy_game_id) BETWEEN 1 AND 256),
    ruleset_manifest_hash CHAR(64) NOT NULL REFERENCES ruleset_manifests(hash) ON DELETE RESTRICT,
    selected_candidate_index INTEGER NOT NULL CHECK (selected_candidate_index >= 0),
    selected_source_label TEXT NOT NULL CHECK (length(selected_source_label) BETWEEN 1 AND 256),
    selected_source_path_hash CHAR(64) NOT NULL,
    selected_source_hash CHAR(64) NOT NULL,
    candidate_report JSONB NOT NULL CHECK (jsonb_typeof(candidate_report) = 'object'),
    projection_report JSONB NOT NULL CHECK (jsonb_typeof(projection_report) = 'object'),
    request JSONB NOT NULL CHECK (jsonb_typeof(request) = 'object'),
    game_id UUID NOT NULL UNIQUE REFERENCES games(id) ON DELETE RESTRICT,
    canonical_state_hash CHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (legacy_origin, legacy_game_id)
);

CREATE INDEX legacy_game_imports_owner_created_idx
    ON legacy_game_imports (owner_account_id, created_at DESC);

CREATE FUNCTION reject_legacy_game_import_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'legacy game import provenance is append-only';
END;
$$;

CREATE TRIGGER legacy_game_imports_append_only
    BEFORE UPDATE OR DELETE ON legacy_game_imports
    FOR EACH ROW EXECUTE FUNCTION reject_legacy_game_import_mutation();
