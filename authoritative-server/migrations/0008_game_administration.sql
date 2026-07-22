ALTER TABLE games
    ADD COLUMN lifecycle_status TEXT NOT NULL DEFAULT 'active'
        CHECK (lifecycle_status IN ('active', 'closed', 'archived')),
    ADD COLUMN lifecycle_status_changed_at TIMESTAMPTZ NOT NULL DEFAULT now();

CREATE UNIQUE INDEX game_members_one_owner
    ON game_members (game_id)
    WHERE role = 'owner';

CREATE TABLE game_admin_operations (
    game_id UUID NOT NULL REFERENCES games(id),
    operation_id UUID NOT NULL,
    actor_account_id UUID NOT NULL REFERENCES accounts(id),
    operation_kind TEXT NOT NULL
        CHECK (operation_kind IN ('transfer_ownership', 'close_game', 'archive_game')),
    request JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (game_id, operation_id)
);
