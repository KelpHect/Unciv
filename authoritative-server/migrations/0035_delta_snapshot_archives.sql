-- Delta archive objects are still independently addressed and hashed, but are
-- reconstructed from a retained full checkpoint with a bounded base revision.
ALTER TABLE game_snapshots
    DROP CONSTRAINT game_snapshots_supported_codec;
ALTER TABLE game_snapshots
    ADD CONSTRAINT game_snapshots_supported_codec
        CHECK (codec IN ('identity', 'zstd', 'zstd_delta'));

ALTER TABLE game_snapshot_archives
    ADD COLUMN archive_codec TEXT NOT NULL DEFAULT 'full'
        CHECK (archive_codec IN ('full', 'delta')),
    ADD COLUMN base_revision BIGINT,
    ADD COLUMN base_state_hash CHAR(64),
    ADD CONSTRAINT game_snapshot_archives_base_shape CHECK (
        (archive_codec = 'full' AND base_revision IS NULL AND base_state_hash IS NULL)
        OR
        (archive_codec = 'delta' AND base_revision IS NOT NULL AND base_revision < revision
            AND base_state_hash IS NOT NULL)
    );

COMMENT ON COLUMN game_snapshot_archives.archive_codec IS
    'full stores a normal snapshot codec; delta stores the bounded checkpoint-relative UCVDLT01 format.';
