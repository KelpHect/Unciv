CREATE TABLE api_rate_limits (
    bucket_hash CHAR(64) PRIMARY KEY,
    window_started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    request_count INTEGER NOT NULL DEFAULT 0 CHECK (request_count >= 0),
    blocked_until TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE security_audit_events (
    id BIGSERIAL PRIMARY KEY,
    account_id UUID REFERENCES accounts(id),
    event_type TEXT NOT NULL,
    outcome TEXT NOT NULL,
    source_ip_prefix INET,
    identity_hash CHAR(64),
    details JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX security_audit_events_created_idx
    ON security_audit_events (created_at DESC);

CREATE INDEX security_audit_events_account_idx
    ON security_audit_events (account_id, created_at DESC)
    WHERE account_id IS NOT NULL;
