use super::*;

impl PostgresGameRepository {
    /// Replaces every prior recovery batch after re-verifying the password.
    /// Plaintext codes are returned once; only their SHA-256 digests are stored.
    pub async fn replace_recovery_codes(
        &self,
        account_id: Uuid,
        password: &str,
    ) -> Result<RecoveryCodeBatch, AuthError> {
        let batch = RecoveryCodeBatch::generate();
        let batch_id = Uuid::new_v4();
        let mut tx = self.pool.begin().await.map_err(|_| AuthError::Storage)?;
        let row = sqlx::query(
            "SELECT password_hash, disabled_at IS NOT NULL AS disabled
             FROM accounts WHERE id=$1 FOR UPDATE",
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

        sqlx::query(
            "UPDATE account_recovery_codes
             SET used_at=COALESCE(used_at, now())
             WHERE account_id=$1",
        )
        .bind(account_id)
        .execute(&mut *tx)
        .await
        .map_err(|_| AuthError::Storage)?;
        for code in &batch.codes {
            sqlx::query(
                "INSERT INTO account_recovery_codes
                 (account_id, batch_id, code_digest, expires_at)
                 VALUES ($1, $2, $3, now() + interval '90 days')",
            )
            .bind(account_id)
            .bind(batch_id)
            .bind(token_digest(code))
            .execute(&mut *tx)
            .await
            .map_err(|_| AuthError::Storage)?;
        }
        tx.commit().await.map_err(|_| AuthError::Storage)?;
        Ok(batch)
    }

    /// Consumes one live code under the account lock, invalidates its complete
    /// batch, replaces the password, revokes every session, and returns one new
    /// session atomically. There is no operator override or client-state input.
    pub async fn recover_account(
        &self,
        username: &str,
        recovery_code: &str,
        new_password: &str,
    ) -> Result<(Account, SessionCredential), AuthError> {
        let username_normalized =
            normalize_username(username).map_err(|_| AuthError::InvalidCredentials)?;
        if recovery_code.len() != 64
            || !recovery_code
                .bytes()
                .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
        {
            return Err(AuthError::InvalidCredentials);
        }
        let new_hash = PasswordService.hash(new_password)?;
        let replacement = SessionCredential::generate();
        let digest = token_digest(recovery_code);
        let mut tx = self.pool.begin().await.map_err(|_| AuthError::Storage)?;
        let row = sqlx::query(
            "SELECT id, username_normalized, disabled_at IS NOT NULL AS disabled
             FROM accounts WHERE username_normalized=$1 FOR UPDATE",
        )
        .bind(&username_normalized)
        .fetch_optional(&mut *tx)
        .await
        .map_err(|_| AuthError::Storage)?
        .ok_or(AuthError::InvalidCredentials)?;
        if row.get::<bool, _>("disabled") {
            return Err(AuthError::InvalidCredentials);
        }
        let account = Account {
            id: row.get("id"),
            username_normalized: row.get("username_normalized"),
        };
        let batch_id: Uuid = sqlx::query_scalar(
            "SELECT batch_id FROM account_recovery_codes
             WHERE account_id=$1 AND code_digest=$2
               AND used_at IS NULL AND expires_at>now()
             FOR UPDATE",
        )
        .bind(account.id)
        .bind(digest)
        .fetch_optional(&mut *tx)
        .await
        .map_err(|_| AuthError::Storage)?
        .ok_or(AuthError::InvalidCredentials)?;

        sqlx::query(
            "UPDATE account_recovery_codes SET used_at=now()
             WHERE account_id=$1 AND batch_id=$2 AND used_at IS NULL",
        )
        .bind(account.id)
        .bind(batch_id)
        .execute(&mut *tx)
        .await
        .map_err(|_| AuthError::Storage)?;
        sqlx::query(
            "UPDATE accounts
             SET password_hash=$2, password_changed_at=now()
             WHERE id=$1",
        )
        .bind(account.id)
        .bind(new_hash)
        .execute(&mut *tx)
        .await
        .map_err(|_| AuthError::Storage)?;
        sqlx::query(
            "UPDATE sessions
             SET revoked_at=COALESCE(revoked_at, now()),
                 revoked_reason=COALESCE(revoked_reason, 'account_recovery')
             WHERE account_id=$1",
        )
        .bind(account.id)
        .execute(&mut *tx)
        .await
        .map_err(|_| AuthError::Storage)?;
        sqlx::query(
            "INSERT INTO sessions
             (id, account_id, token_digest, refresh_token_digest, expires_at, refresh_expires_at)
             VALUES ($1, $2, $3, $4, now() + interval '30 days', now() + interval '90 days')",
        )
        .bind(Uuid::new_v4())
        .bind(account.id)
        .bind(&replacement.digest)
        .bind(&replacement.refresh_digest)
        .execute(&mut *tx)
        .await
        .map_err(|_| AuthError::Storage)?;
        tx.commit().await.map_err(|_| AuthError::Storage)?;
        Ok((account, replacement))
    }
}
