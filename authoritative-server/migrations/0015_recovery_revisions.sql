ALTER TABLE game_revisions
    ADD COLUMN revision_kind TEXT NOT NULL DEFAULT 'command';

UPDATE game_revisions
SET revision_kind = 'genesis'
WHERE revision = 0;

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
            AND revision_kind = 'recovery'
            AND parent_revision IS NOT NULL
            AND command_id IS NULL
        )
    );

CREATE TABLE game_recovery_events (
    game_id UUID NOT NULL REFERENCES games(id),
    revision BIGINT NOT NULL,
    source_revision BIGINT NOT NULL CHECK (source_revision >= 0),
    recovered_head_revision BIGINT NOT NULL CHECK (recovered_head_revision >= source_revision),
    commands_replayed BIGINT NOT NULL CHECK (commands_replayed >= 0),
    canonical_state_hash CHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (game_id, revision),
    FOREIGN KEY (game_id, revision) REFERENCES game_revisions(game_id, revision)
);

COMMENT ON TABLE game_recovery_events IS
    'Append-only operator recovery audit. Recovery publishes a new revision and never rewrites damaged history.';
