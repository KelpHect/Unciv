ALTER TABLE game_commands
    ADD COLUMN server_time_millis BIGINT,
    ADD COLUMN replay_time_available BOOLEAN NOT NULL DEFAULT TRUE;

UPDATE game_commands
SET replay_time_available = FALSE
WHERE server_time_millis IS NULL;

ALTER TABLE game_commands
    ADD CONSTRAINT game_commands_replay_time_complete
    CHECK (
        (
            replay_time_available
            AND server_time_millis IS NOT NULL
            AND server_time_millis >= 0
        )
        OR (
            NOT replay_time_available
            AND server_time_millis IS NULL
        )
    );

ALTER TABLE game_creation_operations
    ADD COLUMN server_seed BIGINT,
    ADD COLUMN server_time_millis BIGINT,
    ADD COLUMN replay_context_available BOOLEAN NOT NULL DEFAULT TRUE;

UPDATE game_creation_operations
SET replay_context_available = FALSE
WHERE server_seed IS NULL OR server_time_millis IS NULL;

ALTER TABLE game_creation_operations
    ADD CONSTRAINT game_creation_operations_replay_context_complete
    CHECK (
        (
            replay_context_available
            AND server_seed IS NOT NULL
            AND server_time_millis IS NOT NULL
            AND server_time_millis >= 0
        )
        OR (
            NOT replay_context_available
            AND server_seed IS NULL
            AND server_time_millis IS NULL
        )
    );

COMMENT ON COLUMN game_commands.server_time_millis IS
    'Control-plane timestamp fixed for the original worker execution and deterministic replay.';

COMMENT ON COLUMN game_creation_operations.server_seed IS
    'Secret control-plane map seed retained server-side for deterministic revision-zero recovery.';

COMMENT ON COLUMN game_creation_operations.server_time_millis IS
    'Control-plane timestamp fixed for deterministic revision-zero recovery.';
