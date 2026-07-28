ALTER TABLE sessions
    ADD COLUMN revoked_reason TEXT
    CHECK (
        revoked_reason IS NULL
        OR revoked_reason IN (
            'logout',
            'rotation',
            'password_change',
            'account_recovery',
            'account_disabled',
            'account_deleted',
            'session_limit'
        )
    );

CREATE TABLE account_recovery_codes (
    account_id UUID NOT NULL REFERENCES accounts(id),
    batch_id UUID NOT NULL,
    code_digest CHAR(64) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    used_at TIMESTAMPTZ,
    PRIMARY KEY (account_id, batch_id, code_digest),
    CHECK (expires_at > created_at)
);

CREATE INDEX account_recovery_codes_active_idx
    ON account_recovery_codes (account_id, expires_at)
    WHERE used_at IS NULL;

COMMENT ON TABLE account_recovery_codes IS
    'One-time account recovery secrets. Only SHA-256 digests are stored; consuming one invalidates its complete batch.';
