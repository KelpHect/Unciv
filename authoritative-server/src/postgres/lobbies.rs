use super::*;

#[derive(Clone)]
pub struct LobbyCreateConfiguration {
    pub display_name: String,
    pub human_slots: u8,
    pub password_hash: Option<String>,
    pub password_identity: Option<String>,
    pub available_civilizations: Vec<String>,
}

impl PostgresGameRepository {
    pub(super) async fn insert_lobby(
        &self,
        tx: &mut sqlx::Transaction<'_, sqlx::Postgres>,
        game_id: Uuid,
        owner_account_id: Uuid,
        setup: &crate::worker::WorkerGameSetup,
        configuration: &LobbyCreateConfiguration,
    ) -> Result<(), CommitError> {
        sqlx::query("UPDATE games SET display_name=$2 WHERE id=$1")
            .bind(game_id)
            .bind(&configuration.display_name)
            .execute(&mut **tx)
            .await
            .map_err(CommitError::storage)?;
        sqlx::query(
            "INSERT INTO game_lobbies
                (game_id, owner_account_id, human_slots, setup, password_hash,
                 available_civilizations)
             VALUES ($1, $2, $3, $4, $5, $6)",
        )
        .bind(game_id)
        .bind(owner_account_id)
        .bind(i16::from(configuration.human_slots))
        .bind(serde_json::to_value(setup).map_err(|_| CommitError::Storage)?)
        .bind(&configuration.password_hash)
        .bind(&configuration.available_civilizations)
        .execute(&mut **tx)
        .await
        .map_err(CommitError::storage)?;
        sqlx::query(
            "INSERT INTO game_lobby_readiness (game_id, account_id, ready)
             VALUES ($1, $2, FALSE)",
        )
        .bind(game_id)
        .bind(owner_account_id)
        .execute(&mut **tx)
        .await
        .map_err(CommitError::storage)?;
        Ok(())
    }

    pub async fn list_open_lobbies(
        &self,
        actor_account_id: Uuid,
        after: Option<Uuid>,
        limit: u32,
    ) -> Result<LobbyPage, CommitError> {
        if !(1..=100).contains(&limit) {
            return Err(CommitError::InvalidCommand);
        }
        let rows = sqlx::query(
            "SELECT l.game_id
             FROM game_lobbies l
             WHERE l.started_at IS NULL
               AND l.closed_at IS NULL
               AND ($1::uuid IS NULL OR l.game_id > $1)
             ORDER BY l.game_id
             LIMIT $2",
        )
        .bind(after)
        .bind(i64::from(limit) + 1)
        .fetch_all(&self.pool)
        .await
        .map_err(CommitError::storage)?;
        let has_more = rows.len() > limit as usize;
        let ids = rows
            .into_iter()
            .take(limit as usize)
            .map(|row| row.get::<Uuid, _>("game_id"))
            .collect::<Vec<_>>();
        let mut lobbies = Vec::with_capacity(ids.len());
        for game_id in ids {
            lobbies.push(self.lobby_summary(actor_account_id, game_id).await?);
        }
        let next_cursor = has_more.then(|| {
            lobbies
                .last()
                .expect("a paginated lobby page is non-empty")
                .game_id
        });
        Ok(LobbyPage {
            lobbies,
            next_cursor,
        })
    }

    pub async fn lobby_summary(
        &self,
        actor_account_id: Uuid,
        game_id: Uuid,
    ) -> Result<LobbySummary, CommitError> {
        let row = sqlx::query(
            "SELECT g.display_name, g.ruleset_manifest_hash, g.head_revision,
                    r.canonical_state_hash, l.human_slots, l.available_civilizations,
                    l.password_hash IS NOT NULL AS password_required,
                    l.lobby_revision, l.started_at IS NOT NULL AS started,
                    l.setup, owner.username_normalized AS owner_username
             FROM game_lobbies l
             JOIN games g ON g.id=l.game_id
             JOIN game_revisions r ON r.game_id=g.id AND r.revision=g.head_revision
             JOIN accounts owner ON owner.id=l.owner_account_id
             WHERE l.game_id=$1 AND l.closed_at IS NULL",
        )
        .bind(game_id)
        .fetch_optional(&self.pool)
        .await
        .map_err(CommitError::storage)?
        .ok_or(CommitError::NotFound)?;
        let member_rows = sqlx::query(
            "SELECT a.username_normalized, gm.role, gm.civilization_id,
                    COALESCE(r.ready, FALSE) AS ready
             FROM game_members gm
             JOIN accounts a ON a.id=gm.account_id
             LEFT JOIN game_lobby_readiness r
               ON r.game_id=gm.game_id AND r.account_id=gm.account_id
             WHERE gm.game_id=$1 AND gm.role IN ('owner', 'player')
             ORDER BY CASE gm.role WHEN 'owner' THEN 0 ELSE 1 END, a.username_normalized",
        )
        .bind(game_id)
        .fetch_all(&self.pool)
        .await
        .map_err(CommitError::storage)?;
        let members = member_rows
            .into_iter()
            .map(|member| LobbyMemberSummary {
                username: member.get("username_normalized"),
                role: member.get("role"),
                civilization_id: member
                    .get::<Option<String>, _>("civilization_id")
                    .unwrap_or_default(),
                ready: member.get("ready"),
            })
            .collect::<Vec<_>>();
        let actor = sqlx::query(
            "SELECT gm.role, COALESCE(r.ready, FALSE) AS ready
             FROM game_members gm
             LEFT JOIN game_lobby_readiness r
               ON r.game_id=gm.game_id AND r.account_id=gm.account_id
             WHERE gm.game_id=$1 AND gm.account_id=$2
               AND gm.role IN ('owner', 'player')",
        )
        .bind(game_id)
        .bind(actor_account_id)
        .fetch_optional(&self.pool)
        .await
        .map_err(CommitError::storage)?;
        Ok(LobbySummary {
            game_id,
            committed_revision: u64::try_from(row.get::<i64, _>("head_revision"))
                .expect("revision is non-negative"),
            canonical_state_hash: row.get("canonical_state_hash"),
            display_name: row.get("display_name"),
            owner_username: row.get("owner_username"),
            ruleset_manifest_hash: row.get("ruleset_manifest_hash"),
            human_slots: u8::try_from(row.get::<i16, _>("human_slots"))
                .expect("lobby slot constraint fits u8"),
            occupied_slots: u8::try_from(members.len()).expect("lobby member count fits u8"),
            password_required: row.get("password_required"),
            lobby_revision: u64::try_from(row.get::<i64, _>("lobby_revision"))
                .expect("lobby revision is non-negative"),
            started: row.get("started"),
            actor_role: actor.as_ref().map(|member| member.get("role")),
            actor_ready: actor.as_ref().map(|member| member.get("ready")),
            setup: row.get("setup"),
            available_civilizations: row.get("available_civilizations"),
            members,
        })
    }

    pub async fn authorize_lobby_join(
        &self,
        actor_account_id: Uuid,
        game_id: Uuid,
        civilization_id: &str,
        password: Option<&str>,
    ) -> Result<(), CommitError> {
        if civilization_id.is_empty()
            || civilization_id.len() > 128
            || civilization_id.chars().any(char::is_control)
        {
            return Err(CommitError::InvalidCommand);
        }
        let row = sqlx::query(
            "SELECT l.password_hash, l.human_slots,
                    l.started_at IS NOT NULL AS started,
                    l.closed_at IS NOT NULL AS closed,
                    EXISTS(
                        SELECT 1 FROM game_members gm
                        WHERE gm.game_id=l.game_id AND gm.account_id=$2
                    ) AS already_member,
                    EXISTS(
                        SELECT 1 FROM game_members gm
                        WHERE gm.game_id=l.game_id
                          AND gm.civilization_id=$3
                          AND gm.role IN ('owner', 'player')
                    ) AS civilization_taken,
                    (
                        SELECT count(*) FROM game_members gm
                        WHERE gm.game_id=l.game_id AND gm.role IN ('owner', 'player')
                    ) AS occupied_slots
             FROM game_lobbies l
             WHERE l.game_id=$1",
        )
        .bind(game_id)
        .bind(actor_account_id)
        .bind(civilization_id)
        .fetch_optional(&self.pool)
        .await
        .map_err(CommitError::storage)?
        .ok_or(CommitError::NotFound)?;
        if row.get::<bool, _>("started")
            || row.get::<bool, _>("closed")
            || row.get::<bool, _>("already_member")
            || row.get::<bool, _>("civilization_taken")
            || row.get::<i64, _>("occupied_slots") >= i64::from(row.get::<i16, _>("human_slots"))
        {
            return Err(CommitError::InvalidCommand);
        }
        if let Some(password_hash) = row.get::<Option<String>, _>("password_hash") {
            let supplied = password.ok_or(CommitError::Unauthorized)?;
            let accepted = PasswordService
                .verify(supplied, &password_hash)
                .map_err(|_| CommitError::Unauthorized)?;
            if !accepted {
                return Err(CommitError::Unauthorized);
            }
        }
        Ok(())
    }

    pub async fn set_lobby_ready(
        &self,
        actor_account_id: Uuid,
        game_id: Uuid,
        expected_revision: u64,
        ready: bool,
    ) -> Result<LobbySummary, CommitError> {
        let expected_revision =
            i64::try_from(expected_revision).map_err(|_| CommitError::InvalidCommand)?;
        let mut tx = self.pool.begin().await.map_err(CommitError::storage)?;
        let revision: i64 = sqlx::query_scalar(
            "SELECT lobby_revision FROM game_lobbies
             WHERE game_id=$1 AND started_at IS NULL AND closed_at IS NULL
             FOR UPDATE",
        )
        .bind(game_id)
        .fetch_optional(&mut *tx)
        .await
        .map_err(CommitError::storage)?
        .ok_or(CommitError::InvalidCommand)?;
        if revision != expected_revision {
            return Err(CommitError::Stale {
                expected: u64::try_from(expected_revision).expect("non-negative"),
                actual: u64::try_from(revision).expect("non-negative"),
            });
        }
        let changed = sqlx::query(
            "UPDATE game_lobby_readiness
             SET ready=$3, updated_at=now()
             WHERE game_id=$1 AND account_id=$2 AND ready IS DISTINCT FROM $3",
        )
        .bind(game_id)
        .bind(actor_account_id)
        .bind(ready)
        .execute(&mut *tx)
        .await
        .map_err(CommitError::storage)?;
        if changed.rows_affected() != 1 {
            let member: bool = sqlx::query_scalar(
                "SELECT EXISTS(
                    SELECT 1 FROM game_lobby_readiness
                    WHERE game_id=$1 AND account_id=$2
                )",
            )
            .bind(game_id)
            .bind(actor_account_id)
            .fetch_one(&mut *tx)
            .await
            .map_err(CommitError::storage)?;
            if !member {
                return Err(CommitError::Unauthorized);
            }
        } else {
            sqlx::query("UPDATE game_lobbies SET lobby_revision=lobby_revision+1 WHERE game_id=$1")
                .bind(game_id)
                .execute(&mut *tx)
                .await
                .map_err(CommitError::storage)?;
        }
        tx.commit().await.map_err(CommitError::storage)?;
        self.lobby_summary(actor_account_id, game_id).await
    }

    pub async fn start_lobby(
        &self,
        actor_account_id: Uuid,
        game_id: Uuid,
        expected_revision: u64,
    ) -> Result<LobbySummary, CommitError> {
        let expected_revision =
            i64::try_from(expected_revision).map_err(|_| CommitError::InvalidCommand)?;
        let mut tx = self.pool.begin().await.map_err(CommitError::storage)?;
        let row = sqlx::query(
            "SELECT owner_account_id, human_slots, lobby_revision
             FROM game_lobbies
             WHERE game_id=$1 AND started_at IS NULL AND closed_at IS NULL
             FOR UPDATE",
        )
        .bind(game_id)
        .fetch_optional(&mut *tx)
        .await
        .map_err(CommitError::storage)?
        .ok_or(CommitError::InvalidCommand)?;
        if row.get::<Uuid, _>("owner_account_id") != actor_account_id {
            return Err(CommitError::Unauthorized);
        }
        let actual_revision = row.get::<i64, _>("lobby_revision");
        if actual_revision != expected_revision {
            return Err(CommitError::Stale {
                expected: u64::try_from(expected_revision).expect("non-negative"),
                actual: u64::try_from(actual_revision).expect("non-negative"),
            });
        }
        let member_count: i64 =
            sqlx::query_scalar("SELECT count(*) FROM game_lobby_readiness WHERE game_id=$1")
                .bind(game_id)
                .fetch_one(&mut *tx)
                .await
                .map_err(CommitError::storage)?;
        let ready_count: i64 = sqlx::query_scalar(
            "SELECT count(*) FROM game_lobby_readiness WHERE game_id=$1 AND ready",
        )
        .bind(game_id)
        .fetch_one(&mut *tx)
        .await
        .map_err(CommitError::storage)?;
        let required = i64::from(row.get::<i16, _>("human_slots"));
        if member_count != required || ready_count != required {
            return Err(CommitError::InvalidCommand);
        }
        sqlx::query(
            "UPDATE game_lobbies
             SET started_at=now(), lobby_revision=lobby_revision+1
             WHERE game_id=$1",
        )
        .bind(game_id)
        .execute(&mut *tx)
        .await
        .map_err(CommitError::storage)?;
        tx.commit().await.map_err(CommitError::storage)?;
        self.lobby_summary(actor_account_id, game_id).await
    }

    pub(super) async fn lobby_started(&self, game_id: Uuid) -> Result<bool, CommitError> {
        sqlx::query_scalar::<_, bool>(
            "SELECT started_at IS NOT NULL FROM game_lobbies WHERE game_id=$1",
        )
        .bind(game_id)
        .fetch_optional(&self.pool)
        .await
        .map_err(CommitError::storage)?
        // Imported/legacy games have no lobby row and are already active.
        .map_or(Ok(true), Ok)
    }
}
