CREATE UNIQUE INDEX game_outbox_one_revision_event
    ON game_outbox (game_id, revision)
    WHERE topic IN ('game.revision.committed', 'game.revision.recovered');

CREATE TABLE game_repair_events (
    id BIGSERIAL PRIMARY KEY,
    game_id UUID NOT NULL REFERENCES games(id),
    action TEXT NOT NULL CHECK (action IN ('outbox_backfill', 'quarantine')),
    revision BIGINT CHECK (revision IS NULL OR revision >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT game_repair_events_shape CHECK (
        (action = 'outbox_backfill' AND revision IS NOT NULL)
        OR (action = 'quarantine' AND revision IS NULL)
    )
);

CREATE UNIQUE INDEX game_repair_events_once
    ON game_repair_events (game_id, action, COALESCE(revision, -1));

COMMENT ON TABLE game_repair_events IS
    'Append-only operator repair audit. Repairs reconstruct only deterministic outbox hints or quarantine damaged games; canonical history is never rewritten.';
