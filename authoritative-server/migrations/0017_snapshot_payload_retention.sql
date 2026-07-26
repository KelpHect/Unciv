-- Keep immutable snapshot identity/hash metadata for the complete revision
-- chain while allowing an explicit retention job to remove only payload bytes.
ALTER TABLE game_snapshots
    ADD CONSTRAINT game_snapshots_blob_identity
    UNIQUE (game_id, revision, compressed_size, payload_hash);

CREATE TABLE game_snapshot_blobs (
    game_id UUID NOT NULL,
    revision BIGINT NOT NULL CHECK (revision >= 0),
    compressed_size BIGINT NOT NULL CHECK (compressed_size BETWEEN 1 AND 16777216),
    payload_hash CHAR(64) NOT NULL,
    payload BYTEA NOT NULL,
    PRIMARY KEY (game_id, revision),
    CONSTRAINT game_snapshot_blobs_size_matches
        CHECK (compressed_size = octet_length(payload)),
    FOREIGN KEY (game_id, revision, compressed_size, payload_hash)
        REFERENCES game_snapshots(game_id, revision, compressed_size, payload_hash)
        ON DELETE RESTRICT
);

INSERT INTO game_snapshot_blobs
    (game_id, revision, compressed_size, payload_hash, payload)
SELECT game_id, revision, compressed_size, payload_hash, payload
FROM game_snapshots;

ALTER TABLE game_snapshots
    DROP CONSTRAINT game_snapshots_payload_size_matches,
    DROP CONSTRAINT game_snapshots_identity_size_matches,
    DROP COLUMN payload,
    ADD COLUMN payload_retention_status TEXT NOT NULL DEFAULT 'retained'
        CHECK (payload_retention_status IN ('retained', 'compacted')),
    ADD COLUMN compacted_at TIMESTAMPTZ,
    ADD CONSTRAINT game_snapshots_retention_shape CHECK (
        (payload_retention_status = 'retained' AND compacted_at IS NULL)
        OR
        (payload_retention_status = 'compacted' AND compacted_at IS NOT NULL)
    ),
    ADD CONSTRAINT game_snapshots_identity_size_matches
        CHECK (codec <> 'identity' OR compressed_size = uncompressed_size);

COMMENT ON TABLE game_snapshot_blobs IS
    'Retained immutable compressed payload bytes. Deletion is allowed only through the reviewed retention transaction; snapshot and revision metadata remain append-only.';
