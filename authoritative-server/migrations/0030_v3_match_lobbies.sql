-- Server-owned public/private pregame lobbies. Canonical revision zero may be
-- prepared before play, but gameplay remains unavailable until the owner starts
-- a complete, ready lobby.
--
-- Public protocol v4 changes only lobby/setup intent. The canonical snapshot
-- representation is unchanged, so verified v3 snapshots are relabelled rather
-- than rewritten.
ALTER TABLE game_snapshots
    DROP CONSTRAINT game_snapshots_protocol_version_check;
UPDATE game_snapshots SET protocol_version = 4 WHERE protocol_version = 3;
ALTER TABLE game_snapshots
    ALTER COLUMN protocol_version SET DEFAULT 4,
    ADD CONSTRAINT game_snapshots_protocol_version_check
        CHECK (protocol_version = 4);

ALTER TABLE games
    ADD COLUMN display_name TEXT NOT NULL DEFAULT 'Unciv multiplayer game'
        CHECK (
            char_length(display_name) BETWEEN 1 AND 80
            AND display_name !~ '[[:cntrl:]]'
        );

CREATE TABLE game_lobbies (
    game_id UUID PRIMARY KEY REFERENCES games(id) ON DELETE RESTRICT,
    owner_account_id UUID NOT NULL REFERENCES accounts(id) ON DELETE RESTRICT,
    human_slots SMALLINT NOT NULL CHECK (human_slots BETWEEN 1 AND 16),
    setup JSONB NOT NULL,
    available_civilizations TEXT[] NOT NULL
        CHECK (
            cardinality(available_civilizations) BETWEEN human_slots AND 64
            AND array_position(available_civilizations, NULL) IS NULL
        ),
    password_hash TEXT,
    lobby_revision BIGINT NOT NULL DEFAULT 0 CHECK (lobby_revision >= 0),
    started_at TIMESTAMPTZ,
    closed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (started_at IS NULL OR closed_at IS NULL)
);

CREATE INDEX game_lobbies_browser_idx
    ON game_lobbies (created_at DESC, game_id)
    WHERE started_at IS NULL AND closed_at IS NULL;

CREATE TABLE game_lobby_readiness (
    game_id UUID NOT NULL REFERENCES game_lobbies(game_id) ON DELETE RESTRICT,
    account_id UUID NOT NULL REFERENCES accounts(id) ON DELETE RESTRICT,
    ready BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (game_id, account_id),
    FOREIGN KEY (game_id, account_id)
        REFERENCES game_members(game_id, account_id) ON DELETE RESTRICT
);

CREATE UNIQUE INDEX game_members_one_civilization
    ON game_members (game_id, civilization_id)
    WHERE role IN ('owner', 'player') AND civilization_id IS NOT NULL;

COMMENT ON TABLE game_lobbies IS
    'Server-owned V3 pregame lobby settings, password verifier, slot capacity, and start gate.';
COMMENT ON TABLE game_lobby_readiness IS
    'Per-account immutable-identity readiness state for one V3 lobby membership.';

GRANT SELECT, INSERT, UPDATE ON game_lobbies, game_lobby_readiness TO unciv_runtime;
