CREATE TABLE game_creation_operations (
    operation_id UUID PRIMARY KEY,
    actor_account_id UUID NOT NULL REFERENCES accounts(id) ON DELETE RESTRICT,
    request JSONB NOT NULL,
    game_id UUID NOT NULL UNIQUE REFERENCES games(id) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX game_creation_operations_actor_created_idx
    ON game_creation_operations (actor_account_id, created_at DESC);
