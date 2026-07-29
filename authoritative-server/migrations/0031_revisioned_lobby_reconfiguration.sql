-- Pregame settings and faction changes append canonical worker-built state;
-- they never rewrite revision zero or accept a client snapshot.
ALTER TABLE game_revisions
    DROP CONSTRAINT game_revisions_kind_shape;

ALTER TABLE game_revisions
    ADD CONSTRAINT game_revisions_kind_shape
    CHECK (
        (
            revision = 0
            AND revision_kind = 'genesis'
            AND parent_revision IS NULL
            AND command_id IS NULL
        )
        OR (
            revision > 0
            AND revision_kind = 'command'
            AND parent_revision IS NOT NULL
            AND command_id IS NOT NULL
        )
        OR (
            revision > 0
            AND revision_kind IN ('recovery', 'rewind', 'lobby_reconfiguration')
            AND parent_revision IS NOT NULL
            AND command_id IS NULL
        )
    );

ALTER TABLE game_lobbies
    ADD COLUMN password_identity CHAR(64);

UPDATE game_lobbies lobby
SET password_identity = operation.request ->> 'password_identity'
FROM game_creation_operations operation
WHERE operation.game_id = lobby.game_id
  AND lobby.password_hash IS NOT NULL;

ALTER TABLE game_lobbies
    ADD CONSTRAINT game_lobbies_password_identity_shape
    CHECK ((password_hash IS NULL) = (password_identity IS NULL));

CREATE TABLE game_lobby_reconfiguration_operations (
    game_id UUID NOT NULL REFERENCES games(id) ON DELETE RESTRICT,
    operation_id UUID NOT NULL,
    actor_account_id UUID NOT NULL REFERENCES accounts(id) ON DELETE RESTRICT,
    request JSONB NOT NULL,
    committed_revision BIGINT NOT NULL CHECK (committed_revision > 0),
    lobby_revision BIGINT NOT NULL CHECK (lobby_revision > 0),
    canonical_state_hash CHAR(64) NOT NULL,
    server_seed BIGINT NOT NULL,
    server_time_millis BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (game_id, operation_id),
    UNIQUE (game_id, committed_revision),
    FOREIGN KEY (game_id, committed_revision)
        REFERENCES game_revisions(game_id, revision) ON DELETE RESTRICT
);

COMMENT ON TABLE game_lobby_reconfiguration_operations IS
    'Idempotent owner settings and per-member faction changes whose canonical snapshots are rebuilt by the private worker.';
