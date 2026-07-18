ALTER TABLE game_outbox
    ADD COLUMN available_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    ADD COLUMN attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    ADD COLUMN claimed_at TIMESTAMPTZ,
    ADD COLUMN claim_token UUID,
    ADD COLUMN last_error TEXT;

CREATE INDEX game_outbox_dispatch_idx
    ON game_outbox (available_at, id)
    WHERE delivered_at IS NULL;
