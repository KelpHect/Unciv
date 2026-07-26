ALTER TABLE security_audit_events
    ADD CONSTRAINT security_audit_event_type_closed
    CHECK (event_type IN (
        'registration',
        'login',
        'account_security',
        'password_change',
        'account_disable',
        'account_delete'
    )),
    ADD CONSTRAINT security_audit_outcome_closed
    CHECK (outcome IN ('success', 'rejected', 'rate_limited')),
    ADD CONSTRAINT security_audit_details_empty
    CHECK (details = '{}'::jsonb);
