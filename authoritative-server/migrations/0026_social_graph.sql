CREATE TABLE social_friend_requests (
    request_id UUID PRIMARY KEY,
    requester_account_id UUID NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    recipient_account_id UUID NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (requester_account_id <> recipient_account_id)
);

CREATE UNIQUE INDEX social_friend_requests_pair
    ON social_friend_requests (
        LEAST(requester_account_id, recipient_account_id),
        GREATEST(requester_account_id, recipient_account_id)
    );

CREATE INDEX social_friend_requests_recipient_created
    ON social_friend_requests (recipient_account_id, created_at, request_id);

CREATE TABLE social_friendships (
    account_low UUID NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    account_high UUID NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (account_low, account_high),
    CHECK (account_low < account_high)
);

CREATE INDEX social_friendships_high
    ON social_friendships (account_high, account_low);
