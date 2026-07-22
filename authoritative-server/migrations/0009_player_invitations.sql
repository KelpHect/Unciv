CREATE TABLE game_player_invitations (
    game_id UUID NOT NULL REFERENCES games(id),
    invitation_id UUID NOT NULL,
    invited_account_id UUID NOT NULL REFERENCES accounts(id),
    invited_by_account_id UUID NOT NULL REFERENCES accounts(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    consumed_at TIMESTAMPTZ,
    consumed_revision BIGINT,
    PRIMARY KEY (game_id, invitation_id),
    CHECK ((consumed_at IS NULL) = (consumed_revision IS NULL))
);

CREATE UNIQUE INDEX game_player_invitations_one_pending_target
    ON game_player_invitations (game_id, invited_account_id)
    WHERE consumed_at IS NULL;

