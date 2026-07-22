ALTER TABLE game_snapshots
    ADD CONSTRAINT game_snapshots_supported_codec
        CHECK (codec IN ('identity', 'zstd'));
