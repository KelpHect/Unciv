ALTER TABLE game_outbox
    ADD COLUMN dead_lettered_at TIMESTAMPTZ,
    ADD COLUMN dead_letter_reason TEXT,
    ADD CONSTRAINT game_outbox_dead_letter_consistency CHECK (
        (dead_lettered_at IS NULL AND dead_letter_reason IS NULL)
        OR
        (dead_lettered_at IS NOT NULL
         AND dead_letter_reason IS NOT NULL
         AND length(dead_letter_reason) BETWEEN 1 AND 500
         AND delivered_at IS NULL
         AND claimed_at IS NULL
         AND claim_token IS NULL)
    );

DROP INDEX game_outbox_dispatch_idx;

CREATE INDEX game_outbox_dispatch_idx
    ON game_outbox (available_at, id)
    WHERE delivered_at IS NULL AND dead_lettered_at IS NULL;

CREATE INDEX game_outbox_dead_letter_idx
    ON game_outbox (dead_lettered_at, id)
    WHERE dead_lettered_at IS NOT NULL;

CREATE TABLE game_outbox_receipts (
    outbox_id BIGINT PRIMARY KEY,
    game_id UUID NOT NULL REFERENCES games(id),
    revision BIGINT NOT NULL CHECK (revision >= 0),
    topic TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    delivered_at TIMESTAMPTZ NOT NULL,
    attempt_count INTEGER NOT NULL CHECK (attempt_count >= 1),
    compacted_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX game_outbox_receipts_game_revision_idx
    ON game_outbox_receipts (game_id, revision, topic);

CREATE TABLE outbox_operator_audit (
    id BIGSERIAL PRIMARY KEY,
    action TEXT NOT NULL CHECK (action IN ('compact_delivered', 'requeue_dead_letter')),
    outbox_id BIGINT,
    affected_count INTEGER NOT NULL CHECK (affected_count >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
