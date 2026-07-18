-- Server-side session records. Store only a SHA-256/HMAC-derived token digest,
-- never the bearer token itself. Rotation creates a new row and revokes parent.
CREATE TABLE sessions (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES accounts(id),
    token_digest CHAR(64) NOT NULL UNIQUE,
    parent_session_id UUID REFERENCES sessions(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    last_used_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    user_agent TEXT,
    ip_prefix INET
);

CREATE INDEX sessions_active_account_idx
    ON sessions (account_id, expires_at)
    WHERE revoked_at IS NULL;
