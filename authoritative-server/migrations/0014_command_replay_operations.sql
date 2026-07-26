ALTER TABLE game_commands
    ADD COLUMN replay_operation JSONB,
    ADD COLUMN replay_operation_available BOOLEAN NOT NULL DEFAULT TRUE;

UPDATE game_commands
SET replay_operation_available = FALSE
WHERE replay_operation IS NULL;

ALTER TABLE game_commands
    ADD CONSTRAINT game_commands_replay_operation_complete
    CHECK (
        (
            replay_operation_available
            AND replay_operation IS NOT NULL
            AND jsonb_typeof(replay_operation) = 'object'
            AND replay_operation ? 'type'
            AND NOT (replay_operation ? 'snapshot')
        )
        OR (
            NOT replay_operation_available
            AND replay_operation IS NULL
        )
    );

COMMENT ON COLUMN game_commands.replay_operation IS
    'Exact private worker operation without its prior snapshot; replay injects the validated recovery snapshot.';
