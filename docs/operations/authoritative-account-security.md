# Authoritative account recovery and session policy

The API permits at most eight active sessions per account by default.
`UNCIV_V3_MAX_ACTIVE_SESSIONS` may set a deployment-wide bound from 1 through
32. Login issuance locks the account row, retains the most recently used
sessions, revokes older sessions with reason `session_limit`, and inserts the
new token in one PostgreSQL transaction. Every replica therefore observes the
same bound. Only SHA-256 token digests are stored.

## Recovery codes

An authenticated player generates a replacement batch with:

```text
POST /api/v3/account/recovery-codes
Authorization: Bearer <session>
{"password":"<current password>"}
```

The response contains eight independently generated 256-bit lowercase
hexadecimal codes and their 90-day lifetime. It is the only time plaintext
codes are returned. Store them outside the device that holds the normal
session. Generating a batch invalidates every older batch.

Recovery uses:

```text
POST /api/v3/auth/recover
{"username":"<name>","recovery_code":"<one code>","new_password":"<new password>"}
```

One valid code atomically invalidates its entire batch, changes the password,
revokes every existing session with reason `account_recovery`, and issues one
replacement session. A replay, expired code, wrong code, unknown account, or
disabled account receives the same `invalid_credentials` response and changes
nothing. The endpoint has durable source and source-plus-identity throttles;
security audit rows retain only bounded network prefixes and one-way identity
hashes.

There is no email reset, security-question reset, operator override, or client
save recovery. If a player loses both the password and all recovery codes, the
account cannot be recovered through API v3. Operators must not bypass this by
editing account rows. A future owner-reviewed identity migration requires its
own threat model and audited protocol.

## Credential response

For a suspected stolen session, change the password or use a recovery code;
both revoke every prior session. Account disable/delete also revoke all
sessions with a durable reason. Investigate credential stuffing through the
procedure in `authoritative-incident-response.md`.

Passwords, bearer tokens, and recovery codes must never enter logs, traces,
worker frames, projections, incident records, or audit metadata. The account
repository is the only recovery-code consumer. The Kotlin rules worker and all
game projection types have no recovery-code or password field.
