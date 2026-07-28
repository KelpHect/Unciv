CREATE TABLE game_chat_messages (
    game_id UUID NOT NULL REFERENCES games(id) ON DELETE CASCADE,
    message_id UUID NOT NULL,
    sender_account_id UUID NOT NULL REFERENCES accounts(id) ON DELETE RESTRICT,
    body TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (game_id, message_id),
    CHECK (octet_length(body) BETWEEN 1 AND 1000)
);

CREATE INDEX game_chat_messages_game_created
    ON game_chat_messages (game_id, created_at DESC, message_id DESC);

GRANT SELECT, INSERT, UPDATE, DELETE ON social_friend_requests,
    social_friendships, game_chat_messages TO unciv_runtime;
