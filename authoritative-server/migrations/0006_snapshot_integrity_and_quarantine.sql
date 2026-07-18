-- Snapshot bytes are bounded by the private worker frame. Existing identity
-- snapshots are marked valid because their hashes were computed on insert;
-- every read still revalidates bytes before worker execution.
ALTER TABLE game_snapshots
    ADD COLUMN protocol_version INTEGER NOT NULL DEFAULT 3 CHECK (protocol_version = 3),
    ADD COLUMN validation_status TEXT NOT NULL DEFAULT 'valid'
        CHECK (validation_status IN ('valid', 'corrupt')),
    ADD CONSTRAINT game_snapshots_compressed_size_limit
        CHECK (compressed_size <= 16777216),
    ADD CONSTRAINT game_snapshots_uncompressed_size_limit
        CHECK (uncompressed_size <= 16777216),
    ADD CONSTRAINT game_snapshots_payload_size_matches
        CHECK (compressed_size = octet_length(payload)),
    ADD CONSTRAINT game_snapshots_identity_size_matches
        CHECK (codec <> 'identity' OR compressed_size = uncompressed_size);

ALTER TABLE games
    ADD COLUMN unavailable_at TIMESTAMPTZ,
    ADD COLUMN unavailable_reason TEXT,
    ADD CONSTRAINT games_unavailability_is_complete CHECK (
        (unavailable_at IS NULL AND unavailable_reason IS NULL)
        OR (unavailable_at IS NOT NULL AND unavailable_reason IS NOT NULL)
    );
