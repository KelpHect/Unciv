-- Cold snapshot payloads may be moved out of PostgreSQL only after a verified
-- Lockwell object exists. Snapshot identity, hashes, and revision metadata remain
-- in PostgreSQL so the authority and integrity chain stay queryable.
CREATE TABLE game_snapshot_archives (
    game_id UUID NOT NULL,
    revision BIGINT NOT NULL CHECK (revision >= 0),
    object_key TEXT NOT NULL CHECK (char_length(object_key) BETWEEN 1 AND 512),
    object_size BIGINT NOT NULL CHECK (object_size BETWEEN 1 AND 16777216),
    payload_hash CHAR(64) NOT NULL,
    archived_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    verified_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (game_id, revision),
    UNIQUE (object_key),
    FOREIGN KEY (game_id, revision)
        REFERENCES game_snapshots(game_id, revision)
        ON DELETE RESTRICT
    -- object_size/payload_hash describe the verified external bytes. They are
    -- intentionally not a foreign key to mutable snapshot metadata: archival
    -- may replace the PostgreSQL payload representation with a delta while
    -- preserving the same revision row.
);

CREATE INDEX game_snapshot_archives_game_revision_idx
    ON game_snapshot_archives (game_id, revision DESC);

COMMENT ON TABLE game_snapshot_archives IS
    'Verified cold snapshot payload locations. The corresponding PostgreSQL blob is deleted only after the object hash and size are verified.';

GRANT SELECT, INSERT, UPDATE ON game_snapshot_archives TO unciv_runtime;
