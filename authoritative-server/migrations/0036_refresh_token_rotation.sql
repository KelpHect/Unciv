-- Access tokens authenticate API requests. Refresh tokens are separate opaque
-- credentials and are consumed exactly once during rotation. Existing sessions
-- are made backward-compatible by treating their access digest as a legacy
-- refresh digest; newly issued sessions always receive independent secrets.
ALTER TABLE sessions
    ADD COLUMN refresh_token_digest CHAR(64),
    ADD COLUMN refresh_expires_at TIMESTAMPTZ,
    ADD COLUMN refresh_used_at TIMESTAMPTZ;

UPDATE sessions
SET refresh_token_digest = token_digest,
    refresh_expires_at = expires_at
WHERE refresh_token_digest IS NULL;

ALTER TABLE sessions
    ALTER COLUMN refresh_token_digest SET NOT NULL,
    ALTER COLUMN refresh_expires_at SET NOT NULL,
    ADD CONSTRAINT sessions_refresh_digest_unique UNIQUE (refresh_token_digest),
    ADD CONSTRAINT sessions_refresh_expiry_after_creation
        CHECK (refresh_expires_at > created_at);

CREATE INDEX sessions_refresh_active_idx
    ON sessions (refresh_token_digest, refresh_expires_at)
    WHERE revoked_at IS NULL AND refresh_used_at IS NULL;

COMMENT ON COLUMN sessions.refresh_token_digest IS
    'SHA-256 digest of a separate opaque refresh credential; the raw value is never stored.';
COMMENT ON COLUMN sessions.refresh_used_at IS
    'One-time refresh consumption timestamp; non-null means the refresh credential cannot be replayed.';
