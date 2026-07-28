CREATE TABLE websocket_connection_leases (
    lease_id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    replica_id UUID NOT NULL,
    acquired_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    renewed_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    expires_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT websocket_connection_lease_expiry
        CHECK (expires_at > acquired_at)
);

CREATE INDEX websocket_connection_leases_account_expiry_idx
    ON websocket_connection_leases (account_id, expires_at);
CREATE INDEX websocket_connection_leases_expiry_idx
    ON websocket_connection_leases (expires_at);

GRANT SELECT, INSERT, UPDATE, DELETE ON websocket_connection_leases TO unciv_runtime;
