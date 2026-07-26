ALTER TABLE game_commands
    ADD COLUMN actor_civilization_id TEXT,
    ADD COLUMN replay_identity_available BOOLEAN NOT NULL DEFAULT TRUE;

UPDATE game_commands AS command
SET actor_civilization_id = member.civilization_id
FROM game_members AS member
WHERE member.game_id = command.game_id
  AND member.account_id = command.account_id
  AND member.civilization_id IS NOT NULL;

UPDATE game_commands
SET replay_identity_available = FALSE
WHERE actor_civilization_id IS NULL;

ALTER TABLE game_commands
    ADD CONSTRAINT game_commands_replay_identity_complete
    CHECK (
        (
            replay_identity_available
            AND actor_civilization_id IS NOT NULL
            AND btrim(actor_civilization_id) <> ''
        )
        OR (
            NOT replay_identity_available
            AND actor_civilization_id IS NULL
        )
    );

COMMENT ON COLUMN game_commands.actor_civilization_id IS
    'Immutable actor identity used for deterministic journal replay. NULL is retained only for pre-migration history that cannot be reconstructed safely.';

COMMENT ON COLUMN game_commands.replay_identity_available IS
    'FALSE only for pre-migration history whose actor identity cannot be proven; new commands must retain a nonempty actor civilization.';
