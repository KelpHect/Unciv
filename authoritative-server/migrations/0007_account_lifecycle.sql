-- Account lifecycle preserves UUID foreign keys used by immutable command and
-- game history while allowing credentials and identifying usernames to be
-- revoked or pseudonymized transactionally.
ALTER TABLE accounts
    ADD COLUMN password_changed_at TIMESTAMPTZ,
    ADD COLUMN disabled_reason TEXT,
    ADD COLUMN deleted_at TIMESTAMPTZ;

UPDATE accounts
SET disabled_reason = 'legacy_disabled'
WHERE disabled_at IS NOT NULL AND disabled_reason IS NULL;

ALTER TABLE accounts
    ADD CONSTRAINT accounts_disable_state_is_complete CHECK (
        (disabled_at IS NULL AND disabled_reason IS NULL)
        OR (disabled_at IS NOT NULL AND disabled_reason IS NOT NULL)
    ),
    ADD CONSTRAINT accounts_deleted_accounts_are_disabled CHECK (
        deleted_at IS NULL OR disabled_at IS NOT NULL
    );
