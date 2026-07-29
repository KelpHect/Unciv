-- Player-approved whole-game rewind. Historical revisions remain immutable;
-- unanimous approval publishes the selected start-of-turn snapshot as a new
-- canonical head with revision_kind='rewind'.
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
            AND revision_kind IN ('recovery', 'rewind')
            AND parent_revision IS NOT NULL
            AND command_id IS NULL
        )
    );

DROP INDEX game_outbox_one_revision_event;
CREATE UNIQUE INDEX game_outbox_one_revision_event
    ON game_outbox (game_id, revision)
    WHERE topic IN (
        'game.revision.committed',
        'game.revision.recovered',
        'game.revision.rewound'
    );

CREATE TABLE game_rewind_requests (
    game_id UUID NOT NULL REFERENCES games(id) ON DELETE RESTRICT,
    request_id UUID NOT NULL,
    proposed_by UUID NOT NULL REFERENCES accounts(id) ON DELETE RESTRICT,
    expected_head_revision BIGINT NOT NULL CHECK (expected_head_revision > 0),
    target_revision BIGINT NOT NULL CHECK (target_revision >= 0),
    status TEXT NOT NULL DEFAULT 'pending'
        CHECK (status IN ('pending', 'applied', 'rejected', 'stale')),
    applied_revision BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at TIMESTAMPTZ,
    PRIMARY KEY (game_id, request_id),
    FOREIGN KEY (game_id, expected_head_revision)
        REFERENCES game_revisions(game_id, revision) ON DELETE RESTRICT,
    FOREIGN KEY (game_id, target_revision)
        REFERENCES game_revisions(game_id, revision) ON DELETE RESTRICT,
    FOREIGN KEY (game_id, applied_revision)
        REFERENCES game_revisions(game_id, revision) ON DELETE RESTRICT,
    CONSTRAINT game_rewind_requests_target_precedes_head
        CHECK (target_revision < expected_head_revision),
    CONSTRAINT game_rewind_requests_resolution_shape CHECK (
        (status = 'pending' AND applied_revision IS NULL AND resolved_at IS NULL)
        OR
        (status = 'applied' AND applied_revision IS NOT NULL AND resolved_at IS NOT NULL)
        OR
        (status IN ('rejected', 'stale')
            AND applied_revision IS NULL
            AND resolved_at IS NOT NULL)
    )
);

CREATE UNIQUE INDEX game_rewind_requests_one_pending
    ON game_rewind_requests(game_id)
    WHERE status = 'pending';

CREATE TABLE game_rewind_electorate (
    game_id UUID NOT NULL,
    request_id UUID NOT NULL,
    account_id UUID NOT NULL REFERENCES accounts(id) ON DELETE RESTRICT,
    PRIMARY KEY (game_id, request_id, account_id),
    FOREIGN KEY (game_id, request_id)
        REFERENCES game_rewind_requests(game_id, request_id) ON DELETE RESTRICT
);

CREATE TABLE game_rewind_votes (
    game_id UUID NOT NULL,
    request_id UUID NOT NULL,
    account_id UUID NOT NULL,
    approved BOOLEAN NOT NULL,
    voted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (game_id, request_id, account_id),
    FOREIGN KEY (game_id, request_id, account_id)
        REFERENCES game_rewind_electorate(game_id, request_id, account_id)
        ON DELETE RESTRICT
);

COMMENT ON TABLE game_rewind_requests IS
    'Append-only player-consensus requests for restoring one retained start-of-turn canonical snapshot as a new head.';
COMMENT ON TABLE game_rewind_electorate IS
    'Frozen active-human electorate captured when a rewind is proposed.';
COMMENT ON TABLE game_rewind_votes IS
    'Immutable per-account vote; changed retries are rejected.';

GRANT SELECT, INSERT, UPDATE ON
    game_rewind_requests, game_rewind_electorate, game_rewind_votes
    TO unciv_runtime;
