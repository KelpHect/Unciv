-- API v3 canonical state. Run with a PostgreSQL migration runner; the Rust
-- in-memory repository is only a contract-test double, not a production store.
CREATE TABLE accounts (
    id UUID PRIMARY KEY,
    username_normalized TEXT NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    disabled_at TIMESTAMPTZ
);

CREATE TABLE ruleset_manifests (
    hash CHAR(64) PRIMARY KEY,
    engine_build TEXT NOT NULL,
    manifest JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE games (
    id UUID PRIMARY KEY,
    head_revision BIGINT NOT NULL DEFAULT 0 CHECK (head_revision >= 0),
    ruleset_manifest_hash CHAR(64) NOT NULL REFERENCES ruleset_manifests(hash),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE game_members (
    game_id UUID NOT NULL REFERENCES games(id),
    account_id UUID NOT NULL REFERENCES accounts(id),
    role TEXT NOT NULL CHECK (role IN ('owner', 'player', 'spectator', 'admin')),
    civilization_id TEXT,
    PRIMARY KEY (game_id, account_id)
);

CREATE TABLE game_snapshots (
    game_id UUID NOT NULL REFERENCES games(id),
    revision BIGINT NOT NULL CHECK (revision >= 0),
    engine_build TEXT NOT NULL,
    ruleset_manifest_hash CHAR(64) NOT NULL REFERENCES ruleset_manifests(hash),
    codec TEXT NOT NULL,
    compressed_size BIGINT NOT NULL CHECK (compressed_size >= 0),
    uncompressed_size BIGINT NOT NULL CHECK (uncompressed_size >= 0),
    canonical_state_hash CHAR(64) NOT NULL,
    payload_hash CHAR(64) NOT NULL,
    payload BYTEA NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (game_id, revision)
);

CREATE TABLE game_commands (
    game_id UUID NOT NULL REFERENCES games(id),
    command_id UUID NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 0),
    account_id UUID NOT NULL REFERENCES accounts(id),
    payload JSONB NOT NULL,
    accepted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (game_id, command_id),
    UNIQUE (game_id, revision)
);

CREATE TABLE game_revisions (
    game_id UUID NOT NULL REFERENCES games(id),
    revision BIGINT NOT NULL CHECK (revision >= 0),
    parent_revision BIGINT,
    command_id UUID,
    snapshot_revision BIGINT NOT NULL,
    canonical_state_hash CHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (game_id, revision),
    FOREIGN KEY (game_id, snapshot_revision) REFERENCES game_snapshots(game_id, revision)
);

CREATE TABLE game_outbox (
    id BIGSERIAL PRIMARY KEY,
    game_id UUID NOT NULL REFERENCES games(id),
    revision BIGINT NOT NULL,
    topic TEXT NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    delivered_at TIMESTAMPTZ
);
