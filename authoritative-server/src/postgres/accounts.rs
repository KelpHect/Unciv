use super::*;

impl PostgresGameRepository {
    /// Creates an account with a normalized username and a per-password Argon2id
    /// hash. The transaction never writes a plaintext password or bearer token.
    pub async fn register_account(
        &self,
        username: &str,
        password: &str,
    ) -> Result<Account, AuthError> {
        let username_normalized = normalize_username(username)?;
        let password_hash = PasswordService.hash(password)?;
        let account = Account {
            id: Uuid::new_v4(),
            username_normalized,
        };
        match sqlx::query(
            "INSERT INTO accounts (id, username_normalized, password_hash) VALUES ($1, $2, $3)",
        )
        .bind(account.id)
        .bind(&account.username_normalized)
        .bind(password_hash)
        .execute(&self.pool)
        .await
        {
            Ok(_) => Ok(account),
            Err(error)
                if error
                    .as_database_error()
                    .and_then(|database| database.code())
                    .as_deref()
                    == Some("23505") =>
            {
                Err(AuthError::UsernameTaken)
            }
            Err(_) => Err(AuthError::Storage),
        }
    }

    /// Verifies credentials without exposing whether the username or password
    /// was wrong. Disabled status is kept distinct for server-side audit/UI
    /// policy; public handlers must still return a generic login rejection.
    pub async fn authenticate_account(
        &self,
        username: &str,
        password: &str,
    ) -> Result<Account, AuthError> {
        let username_normalized =
            normalize_username(username).map_err(|_| AuthError::InvalidCredentials)?;
        let row = sqlx::query("SELECT id, username_normalized, password_hash, disabled_at IS NOT NULL AS disabled FROM accounts WHERE username_normalized = $1")
            .bind(username_normalized)
            .fetch_optional(&self.pool)
            .await
            .map_err(|_| AuthError::Storage)?
            .ok_or(AuthError::InvalidCredentials)?;
        let password_hash: String = row.get("password_hash");
        if !PasswordService
            .verify(password, &password_hash)
            .map_err(|_| AuthError::InvalidCredentials)?
        {
            return Err(AuthError::InvalidCredentials);
        }
        if row.get::<bool, _>("disabled") {
            return Err(AuthError::AccountDisabled);
        }
        Ok(Account {
            id: row.get("id"),
            username_normalized: row.get("username_normalized"),
        })
    }

    /// Creates a revocable 30-day session. The raw token is returned once and
    /// never appears in SQL or logs; only its SHA-256 digest is persisted.
    pub async fn issue_session(&self, account_id: Uuid) -> Result<SessionCredential, AuthError> {
        self.issue_session_with_policy(account_id, SessionPolicy::default())
            .await
    }

    /// Serializes issuance on the account row and evicts the least-recently
    /// used live sessions before insertion so every replica enforces one bound.
    pub async fn issue_session_with_policy(
        &self,
        account_id: Uuid,
        policy: SessionPolicy,
    ) -> Result<SessionCredential, AuthError> {
        let credential = SessionCredential::generate();
        let mut tx = self.pool.begin().await.map_err(|_| AuthError::Storage)?;
        let enabled: Option<bool> =
            sqlx::query_scalar("SELECT disabled_at IS NULL FROM accounts WHERE id=$1 FOR UPDATE")
                .bind(account_id)
                .fetch_optional(&mut *tx)
                .await
                .map_err(|_| AuthError::Storage)?;
        match enabled {
            Some(true) => {}
            Some(false) => return Err(AuthError::AccountDisabled),
            None => return Err(AuthError::InvalidCredentials),
        }
        sqlx::query(
            "UPDATE sessions SET revoked_at=now(), revoked_reason='session_limit'
             WHERE id IN (
               SELECT id FROM sessions
               WHERE account_id=$1 AND revoked_at IS NULL AND expires_at>now()
               ORDER BY COALESCE(last_used_at, created_at) DESC, created_at DESC, id DESC
               OFFSET $2
             )",
        )
        .bind(account_id)
        .bind(i64::try_from(policy.max_active_sessions() - 1).expect("session limit fits BIGINT"))
        .execute(&mut *tx)
        .await
        .map_err(|_| AuthError::Storage)?;
        sqlx::query("INSERT INTO sessions (id, account_id, token_digest, expires_at) VALUES ($1, $2, $3, now() + interval '30 days')")
            .bind(Uuid::new_v4())
            .bind(account_id)
            .bind(&credential.digest)
            .execute(&mut *tx)
            .await
            .map_err(|_| AuthError::Storage)?;
        tx.commit().await.map_err(|_| AuthError::Storage)?;
        Ok(credential)
    }

    /// Resolves a non-revoked, non-expired bearer session and refreshes only
    /// its server-side activity timestamp. Authentication never trusts an ID
    /// supplied alongside the token.
    pub async fn authenticate_session(&self, bearer_token: &str) -> Result<Account, AuthError> {
        let digest = token_digest(bearer_token);
        let mut tx = self.pool.begin().await.map_err(|_| AuthError::Storage)?;
        let row = sqlx::query("SELECT a.id, a.username_normalized FROM sessions s JOIN accounts a ON a.id = s.account_id WHERE s.token_digest = $1 AND s.revoked_at IS NULL AND s.expires_at > now() AND a.disabled_at IS NULL FOR UPDATE")
            .bind(digest)
            .fetch_optional(&mut *tx)
            .await
            .map_err(|_| AuthError::Storage)?
            .ok_or(AuthError::InvalidCredentials)?;
        sqlx::query("UPDATE sessions SET last_used_at = now() WHERE token_digest = $1")
            .bind(token_digest(bearer_token))
            .execute(&mut *tx)
            .await
            .map_err(|_| AuthError::Storage)?;
        tx.commit().await.map_err(|_| AuthError::Storage)?;
        Ok(Account {
            id: row.get("id"),
            username_normalized: row.get("username_normalized"),
        })
    }

    pub async fn revoke_session(&self, bearer_token: &str) -> Result<(), AuthError> {
        sqlx::query(
            "UPDATE sessions SET revoked_at=now(), revoked_reason='logout' WHERE token_digest=$1 AND revoked_at IS NULL",
        )
        .bind(token_digest(bearer_token))
        .execute(&self.pool)
        .await
        .map_err(|_| AuthError::Storage)?;
        Ok(())
    }

    pub async fn revoke_all_sessions(&self, account_id: Uuid) -> Result<(), AuthError> {
        sqlx::query(
            "UPDATE sessions
             SET revoked_at=COALESCE(revoked_at, now()),
                 revoked_reason=COALESCE(revoked_reason, 'logout')
             WHERE account_id=$1",
        )
        .bind(account_id)
        .execute(&self.pool)
        .await
        .map_err(|_| AuthError::Storage)?;
        Ok(())
    }

    /// Rotates a live session atomically. The successor keeps a parent pointer
    /// for audit/revocation chains while the presented credential is revoked
    /// before the transaction becomes visible.
    pub async fn rotate_session(&self, bearer_token: &str) -> Result<SessionCredential, AuthError> {
        let digest = token_digest(bearer_token);
        let mut tx = self.pool.begin().await.map_err(|_| AuthError::Storage)?;
        let account_id: Uuid = sqlx::query_scalar(
            "SELECT account_id FROM sessions WHERE token_digest = $1 AND revoked_at IS NULL AND expires_at > now() FOR UPDATE",
        )
        .bind(&digest)
        .fetch_optional(&mut *tx)
        .await
        .map_err(|_| AuthError::Storage)?
        .ok_or(AuthError::InvalidCredentials)?;
        let credential = SessionCredential::generate();
        sqlx::query(
            "INSERT INTO sessions (id, account_id, token_digest, parent_session_id, expires_at) SELECT $1, $2, $3, id, now() + interval '30 days' FROM sessions WHERE token_digest = $4",
        )
        .bind(Uuid::new_v4())
        .bind(account_id)
        .bind(&credential.digest)
        .bind(&digest)
        .execute(&mut *tx)
        .await
        .map_err(|_| AuthError::Storage)?;
        sqlx::query(
            "UPDATE sessions SET revoked_at=now(), revoked_reason='rotation' WHERE token_digest=$1",
        )
        .bind(&digest)
        .execute(&mut *tx)
        .await
        .map_err(|_| AuthError::Storage)?;
        tx.commit().await.map_err(|_| AuthError::Storage)?;
        Ok(credential)
    }

    /// Replaces a password only after re-verifying it under an account row
    /// lock. Every existing session is revoked atomically and one replacement
    /// credential is issued so a stolen token cannot survive the change.
    pub async fn change_password(
        &self,
        account_id: Uuid,
        current_password: &str,
        new_password: &str,
    ) -> Result<SessionCredential, AuthError> {
        let new_hash = PasswordService.hash(new_password)?;
        let replacement = SessionCredential::generate();
        let mut tx = self.pool.begin().await.map_err(|_| AuthError::Storage)?;
        let row = sqlx::query(
            "SELECT password_hash, disabled_at IS NOT NULL AS disabled FROM accounts WHERE id=$1 FOR UPDATE",
        )
        .bind(account_id)
        .fetch_optional(&mut *tx)
        .await
        .map_err(|_| AuthError::Storage)?
        .ok_or(AuthError::InvalidCredentials)?;
        if row.get::<bool, _>("disabled") {
            return Err(AuthError::AccountDisabled);
        }
        let stored_hash: String = row.get("password_hash");
        if !PasswordService
            .verify(current_password, &stored_hash)
            .map_err(|_| AuthError::InvalidCredentials)?
        {
            return Err(AuthError::InvalidCredentials);
        }
        if current_password == new_password {
            return Err(AuthError::InvalidPassword(PasswordError::Unchanged));
        }
        sqlx::query("UPDATE accounts SET password_hash=$2, password_changed_at=now() WHERE id=$1")
            .bind(account_id)
            .bind(new_hash)
            .execute(&mut *tx)
            .await
            .map_err(|_| AuthError::Storage)?;
        sqlx::query(
            "UPDATE sessions SET revoked_at=COALESCE(revoked_at, now()), revoked_reason=COALESCE(revoked_reason, 'password_change') WHERE account_id=$1",
        )
        .bind(account_id)
        .execute(&mut *tx)
        .await
        .map_err(|_| AuthError::Storage)?;
        sqlx::query("INSERT INTO sessions (id, account_id, token_digest, expires_at) VALUES ($1, $2, $3, now() + interval '30 days')")
            .bind(Uuid::new_v4())
            .bind(account_id)
            .bind(&replacement.digest)
            .execute(&mut *tx)
            .await
            .map_err(|_| AuthError::Storage)?;
        tx.commit().await.map_err(|_| AuthError::Storage)?;
        Ok(replacement)
    }

    /// Disables login and revokes every session without deleting immutable
    /// game history or membership foreign keys.
    pub async fn disable_account(&self, account_id: Uuid, password: &str) -> Result<(), AuthError> {
        self.close_account(account_id, password, false).await
    }

    /// Pseudonymizes the username and destroys the login credential while
    /// retaining the stable UUID required by command/audit history.
    pub async fn delete_account(&self, account_id: Uuid, password: &str) -> Result<(), AuthError> {
        self.close_account(account_id, password, true).await
    }

    async fn close_account(
        &self,
        account_id: Uuid,
        password: &str,
        delete: bool,
    ) -> Result<(), AuthError> {
        let mut tx = self.pool.begin().await.map_err(|_| AuthError::Storage)?;
        let row = sqlx::query(
            "SELECT password_hash, disabled_at IS NOT NULL AS disabled FROM accounts WHERE id=$1 FOR UPDATE",
        )
        .bind(account_id)
        .fetch_optional(&mut *tx)
        .await
        .map_err(|_| AuthError::Storage)?
        .ok_or(AuthError::InvalidCredentials)?;
        if row.get::<bool, _>("disabled") {
            return Err(AuthError::AccountDisabled);
        }
        let stored_hash: String = row.get("password_hash");
        if !PasswordService
            .verify(password, &stored_hash)
            .map_err(|_| AuthError::InvalidCredentials)?
        {
            return Err(AuthError::InvalidCredentials);
        }
        if delete {
            sqlx::query(
                "UPDATE accounts SET username_normalized=$2, password_hash='!deleted!', disabled_at=now(), disabled_reason='self_deleted', deleted_at=now() WHERE id=$1",
            )
            .bind(account_id)
            .bind(format!("deleted-{account_id}"))
            .execute(&mut *tx)
            .await
            .map_err(|_| AuthError::Storage)?;
        } else {
            sqlx::query(
                "UPDATE accounts SET disabled_at=now(), disabled_reason='self_disabled' WHERE id=$1",
            )
            .bind(account_id)
            .execute(&mut *tx)
            .await
            .map_err(|_| AuthError::Storage)?;
        }
        let revoked_reason = if delete {
            "account_deleted"
        } else {
            "account_disabled"
        };
        sqlx::query(
            "UPDATE sessions SET revoked_at=COALESCE(revoked_at, now()), revoked_reason=COALESCE(revoked_reason, $2) WHERE account_id=$1",
        )
        .bind(account_id)
        .bind(revoked_reason)
        .execute(&mut *tx)
        .await
        .map_err(|_| AuthError::Storage)?;
        tx.commit().await.map_err(|_| AuthError::Storage)
    }
}
