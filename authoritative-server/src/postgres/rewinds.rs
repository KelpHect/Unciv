use sqlx::Row;
use uuid::Uuid;

use super::{
    CommitError, EngineWorkerClient, PostgresGameRepository, WorkerManifest, json,
    snapshot_storage, stored_snapshot,
};

#[derive(Clone, Debug, serde::Serialize, utoipa::ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct RewindCheckpoint {
    pub revision: u64,
    pub turn: i32,
}

#[derive(Clone, Debug, serde::Serialize, utoipa::ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct RewindStatus {
    pub request_id: Uuid,
    pub expected_head_revision: u64,
    pub target_revision: u64,
    pub status: String,
    pub approvals: u32,
    pub required_approvals: u32,
    pub actor_approved: Option<bool>,
    pub applied_revision: Option<u64>,
}

#[derive(Clone, Debug)]
pub struct RewindRequest {
    pub request_id: Uuid,
    pub expected_head_revision: u64,
    pub target_revision: u64,
}

impl PostgresGameRepository {
    pub async fn rewind_checkpoints(
        &self,
        worker: &EngineWorkerClient,
        actor_account_id: Uuid,
        game_id: Uuid,
        limit: u32,
    ) -> Result<Vec<RewindCheckpoint>, CommitError> {
        let rows = sqlx::query(
            "SELECT g.unavailable_at IS NOT NULL AS is_unavailable,
                    r.revision, r.canonical_state_hash AS revision_state_hash,
                    s.revision AS snapshot_revision, b.payload, s.codec,
                    s.compressed_size, s.uncompressed_size,
                    s.protocol_version AS snapshot_protocol_version,
                    s.validation_status, s.payload_hash,
                    s.canonical_state_hash AS snapshot_state_hash, m.manifest
             FROM games g
             JOIN game_members gm ON gm.game_id=g.id AND gm.account_id=$2
                  AND gm.role IN ('owner','player')
             JOIN game_revisions r ON r.game_id=g.id AND r.revision < g.head_revision
             JOIN game_snapshots s ON s.game_id=r.game_id AND s.revision=r.snapshot_revision
             JOIN game_snapshot_blobs b ON b.game_id=s.game_id AND b.revision=s.revision
             JOIN ruleset_manifests m ON m.hash=g.ruleset_manifest_hash
             LEFT JOIN game_commands c ON c.game_id=r.game_id AND c.command_id=r.command_id
             WHERE g.id=$1 AND g.lifecycle_status='active'
               AND (r.revision=0 OR c.payload->'command'->>'type'='end_turn')
             ORDER BY r.revision DESC LIMIT $3",
        )
        .bind(game_id)
        .bind(actor_account_id)
        .bind(i64::from(limit.clamp(1, 50)))
        .fetch_all(&self.pool)
        .await
        .map_err(CommitError::storage)?;
        if rows.is_empty() {
            let authorized: bool = sqlx::query_scalar(
                "SELECT EXISTS(SELECT 1 FROM game_members WHERE game_id=$1 AND account_id=$2 AND role IN ('owner','player'))",
            )
            .bind(game_id)
            .bind(actor_account_id)
            .fetch_one(&self.pool)
            .await
            .map_err(CommitError::storage)?;
            if !authorized {
                return Err(CommitError::Unauthorized);
            }
        }
        let mut checkpoints = Vec::with_capacity(rows.len());
        for row in rows {
            let snapshot = self.validated_snapshot(game_id, &row).await?;
            let manifest = serde_json::from_value::<WorkerManifest>(row.get("manifest"))
                .map_err(|_| CommitError::WorkerRevisionMismatch)?;
            let projected = worker
                .project_spectator_state(&actor_account_id.to_string(), &manifest, &snapshot)
                .await
                .map_err(|_| CommitError::WorkerRevisionMismatch)?;
            checkpoints.push(RewindCheckpoint {
                revision: u64::try_from(row.get::<i64, _>("revision"))
                    .map_err(|_| CommitError::Storage)?,
                turn: projected.projection.turn,
            });
        }
        Ok(checkpoints)
    }

    pub async fn propose_rewind(
        &self,
        worker: &EngineWorkerClient,
        actor_account_id: Uuid,
        game_id: Uuid,
        request: RewindRequest,
    ) -> Result<RewindStatus, CommitError> {
        let mut tx = self.pool.begin().await.map_err(CommitError::storage)?;
        let game =
            sqlx::query("SELECT head_revision, lifecycle_status FROM games WHERE id=$1 FOR UPDATE")
                .bind(game_id)
                .fetch_optional(&mut *tx)
                .await
                .map_err(CommitError::storage)?
                .ok_or(CommitError::NotFound)?;
        let head =
            u64::try_from(game.get::<i64, _>("head_revision")).map_err(|_| CommitError::Storage)?;
        if head != request.expected_head_revision {
            return Err(CommitError::Stale {
                expected: request.expected_head_revision,
                actual: head,
            });
        }
        if game.get::<String, _>("lifecycle_status") != "active"
            || request.request_id.is_nil()
            || request.target_revision >= head
        {
            return Err(CommitError::InvalidCommand);
        }
        let members = sqlx::query_scalar::<_, Uuid>(
            "SELECT account_id FROM game_members
             WHERE game_id=$1 AND role IN ('owner','player') ORDER BY account_id",
        )
        .bind(game_id)
        .fetch_all(&mut *tx)
        .await
        .map_err(CommitError::storage)?;
        if !members.contains(&actor_account_id) {
            return Err(CommitError::Unauthorized);
        }
        let target_ok: bool = sqlx::query_scalar(
            "SELECT EXISTS(
               SELECT 1 FROM game_revisions r
               LEFT JOIN game_commands c ON c.game_id=r.game_id AND c.command_id=r.command_id
               JOIN game_snapshot_blobs b ON b.game_id=r.game_id AND b.revision=r.snapshot_revision
               WHERE r.game_id=$1 AND r.revision=$2
                 AND (r.revision=0 OR c.payload->'command'->>'type'='end_turn'))",
        )
        .bind(game_id)
        .bind(i64::try_from(request.target_revision).map_err(|_| CommitError::InvalidCommand)?)
        .fetch_one(&mut *tx)
        .await
        .map_err(CommitError::storage)?;
        if !target_ok {
            return Err(CommitError::InvalidCommand);
        }
        sqlx::query(
            "INSERT INTO game_rewind_requests
             (game_id,request_id,proposed_by,expected_head_revision,target_revision)
             VALUES ($1,$2,$3,$4,$5)",
        )
        .bind(game_id)
        .bind(request.request_id)
        .bind(actor_account_id)
        .bind(i64::try_from(head).map_err(|_| CommitError::InvalidCommand)?)
        .bind(i64::try_from(request.target_revision).map_err(|_| CommitError::InvalidCommand)?)
        .execute(&mut *tx)
        .await
        .map_err(CommitError::storage)?;
        for member in &members {
            sqlx::query(
                "INSERT INTO game_rewind_electorate(game_id,request_id,account_id)
                 VALUES ($1,$2,$3)",
            )
            .bind(game_id)
            .bind(request.request_id)
            .bind(member)
            .execute(&mut *tx)
            .await
            .map_err(CommitError::storage)?;
        }
        sqlx::query(
            "INSERT INTO game_rewind_votes(game_id,request_id,account_id,approved)
             VALUES ($1,$2,$3,true)",
        )
        .bind(game_id)
        .bind(request.request_id)
        .bind(actor_account_id)
        .execute(&mut *tx)
        .await
        .map_err(CommitError::storage)?;
        sqlx::query(
            "INSERT INTO game_outbox(game_id,revision,topic,payload)
             VALUES($1,$2,'game.rewind.changed',$3)",
        )
        .bind(game_id)
        .bind(i64::try_from(head).map_err(|_| CommitError::InvalidCommand)?)
        .bind(json!({"game_id": game_id, "request_id": request.request_id, "status": "pending"}))
        .execute(&mut *tx)
        .await
        .map_err(CommitError::storage)?;
        tx.commit().await.map_err(CommitError::storage)?;
        if members.len() == 1 {
            self.finalize_rewind(worker, actor_account_id, game_id, request.request_id)
                .await
        } else {
            self.rewind_status(actor_account_id, game_id, request.request_id)
                .await
        }
    }

    pub async fn vote_rewind(
        &self,
        worker: &EngineWorkerClient,
        actor_account_id: Uuid,
        game_id: Uuid,
        request_id: Uuid,
        approved: bool,
    ) -> Result<RewindStatus, CommitError> {
        let mut tx = self.pool.begin().await.map_err(CommitError::storage)?;
        let request = sqlx::query(
            "SELECT status,expected_head_revision FROM game_rewind_requests
             WHERE game_id=$1 AND request_id=$2 FOR UPDATE",
        )
        .bind(game_id)
        .bind(request_id)
        .fetch_optional(&mut *tx)
        .await
        .map_err(CommitError::storage)?
        .ok_or(CommitError::NotFound)?;
        if request.get::<String, _>("status") != "pending" {
            let prior_vote: Option<bool> = sqlx::query_scalar(
                "SELECT approved FROM game_rewind_votes
                 WHERE game_id=$1 AND request_id=$2 AND account_id=$3",
            )
            .bind(game_id)
            .bind(request_id)
            .bind(actor_account_id)
            .fetch_optional(&mut *tx)
            .await
            .map_err(CommitError::storage)?;
            if prior_vote.is_some_and(|prior| prior != approved) {
                return Err(CommitError::IdempotencyConflict);
            }
            drop(tx);
            return self
                .rewind_status(actor_account_id, game_id, request_id)
                .await;
        }
        let eligible: bool = sqlx::query_scalar(
            "SELECT EXISTS(SELECT 1 FROM game_rewind_electorate
             WHERE game_id=$1 AND request_id=$2 AND account_id=$3)",
        )
        .bind(game_id)
        .bind(request_id)
        .bind(actor_account_id)
        .fetch_one(&mut *tx)
        .await
        .map_err(CommitError::storage)?;
        if !eligible {
            return Err(CommitError::Unauthorized);
        }
        let existing: Option<bool> = sqlx::query_scalar(
            "SELECT approved FROM game_rewind_votes
             WHERE game_id=$1 AND request_id=$2 AND account_id=$3",
        )
        .bind(game_id)
        .bind(request_id)
        .bind(actor_account_id)
        .fetch_optional(&mut *tx)
        .await
        .map_err(CommitError::storage)?;
        let inserted_vote = existing.is_none();
        if let Some(existing) = existing {
            if existing != approved {
                return Err(CommitError::IdempotencyConflict);
            }
        } else {
            sqlx::query(
                "INSERT INTO game_rewind_votes(game_id,request_id,account_id,approved)
                 VALUES ($1,$2,$3,$4)",
            )
            .bind(game_id)
            .bind(request_id)
            .bind(actor_account_id)
            .bind(approved)
            .execute(&mut *tx)
            .await
            .map_err(CommitError::storage)?;
        }
        if !approved {
            sqlx::query(
                "UPDATE game_rewind_requests SET status='rejected',resolved_at=now()
                 WHERE game_id=$1 AND request_id=$2",
            )
            .bind(game_id)
            .bind(request_id)
            .execute(&mut *tx)
            .await
            .map_err(CommitError::storage)?;
        }
        if inserted_vote {
            sqlx::query(
                "INSERT INTO game_outbox(game_id,revision,topic,payload)
                 VALUES($1,$2,'game.rewind.changed',$3)",
            )
            .bind(game_id)
            .bind(request.get::<i64, _>("expected_head_revision"))
            .bind(json!({
                "game_id": game_id, "request_id": request_id,
                "status": if approved { "pending" } else { "rejected" }
            }))
            .execute(&mut *tx)
            .await
            .map_err(CommitError::storage)?;
        }
        tx.commit().await.map_err(CommitError::storage)?;
        if approved {
            let complete: bool = sqlx::query_scalar(
                "SELECT NOT EXISTS(
                   SELECT 1 FROM game_rewind_electorate e
                   LEFT JOIN game_rewind_votes v ON v.game_id=e.game_id
                    AND v.request_id=e.request_id AND v.account_id=e.account_id
                   WHERE e.game_id=$1 AND e.request_id=$2
                    AND COALESCE(v.approved,false)=false)",
            )
            .bind(game_id)
            .bind(request_id)
            .fetch_one(&self.pool)
            .await
            .map_err(CommitError::storage)?;
            if complete {
                return self
                    .finalize_rewind(worker, actor_account_id, game_id, request_id)
                    .await;
            }
        }
        self.rewind_status(actor_account_id, game_id, request_id)
            .await
    }

    pub async fn rewind_status(
        &self,
        actor_account_id: Uuid,
        game_id: Uuid,
        request_id: Uuid,
    ) -> Result<RewindStatus, CommitError> {
        let row = sqlx::query(
            "SELECT r.request_id,r.expected_head_revision,r.target_revision,r.status,
                    r.applied_revision,
                    count(v.account_id) FILTER (WHERE v.approved) AS approvals,
                    count(e.account_id) AS required,
                    bool_or(v.approved) FILTER (WHERE v.account_id=$3) AS actor_approved
             FROM game_rewind_requests r
             JOIN game_rewind_electorate e USING(game_id,request_id)
             LEFT JOIN game_rewind_votes v ON v.game_id=e.game_id
              AND v.request_id=e.request_id AND v.account_id=e.account_id
             WHERE r.game_id=$1 AND r.request_id=$2
               AND EXISTS(SELECT 1 FROM game_rewind_electorate mine
                 WHERE mine.game_id=r.game_id AND mine.request_id=r.request_id
                   AND mine.account_id=$3)
             GROUP BY r.game_id,r.request_id",
        )
        .bind(game_id)
        .bind(request_id)
        .bind(actor_account_id)
        .fetch_optional(&self.pool)
        .await
        .map_err(CommitError::storage)?
        .ok_or(CommitError::Unauthorized)?;
        status_from_row(&row)
    }

    pub async fn current_rewind(
        &self,
        actor_account_id: Uuid,
        game_id: Uuid,
    ) -> Result<RewindStatus, CommitError> {
        let request_id: Uuid = sqlx::query_scalar(
            "SELECT r.request_id FROM game_rewind_requests r
             JOIN game_rewind_electorate e USING(game_id,request_id)
             WHERE r.game_id=$1 AND e.account_id=$2
             ORDER BY (r.status='pending') DESC,r.created_at DESC LIMIT 1",
        )
        .bind(game_id)
        .bind(actor_account_id)
        .fetch_optional(&self.pool)
        .await
        .map_err(CommitError::storage)?
        .ok_or(CommitError::NotFound)?;
        self.rewind_status(actor_account_id, game_id, request_id)
            .await
    }

    async fn finalize_rewind(
        &self,
        worker: &EngineWorkerClient,
        actor_account_id: Uuid,
        game_id: Uuid,
        request_id: Uuid,
    ) -> Result<RewindStatus, CommitError> {
        let source = sqlx::query(
            "SELECT g.unavailable_at IS NOT NULL AS is_unavailable,
                    r.canonical_state_hash AS revision_state_hash,
                    s.revision AS snapshot_revision,b.payload,s.codec,s.compressed_size,
                    s.uncompressed_size,s.protocol_version AS snapshot_protocol_version,
                    s.validation_status,s.payload_hash,
                    s.canonical_state_hash AS snapshot_state_hash,m.manifest
             FROM game_rewind_requests q
             JOIN games g ON g.id=q.game_id
             JOIN game_revisions r ON r.game_id=q.game_id AND r.revision=q.target_revision
             JOIN game_snapshots s ON s.game_id=r.game_id AND s.revision=r.snapshot_revision
             JOIN game_snapshot_blobs b ON b.game_id=s.game_id AND b.revision=s.revision
             JOIN ruleset_manifests m ON m.hash=g.ruleset_manifest_hash
             WHERE q.game_id=$1 AND q.request_id=$2 AND q.status='pending'",
        )
        .bind(game_id)
        .bind(request_id)
        .fetch_optional(&self.pool)
        .await
        .map_err(CommitError::storage)?
        .ok_or(CommitError::InvalidCommand)?;
        let snapshot = self.validated_snapshot(game_id, &source).await?;
        let manifest = serde_json::from_value::<WorkerManifest>(source.get("manifest"))
            .map_err(|_| CommitError::WorkerRevisionMismatch)?;
        worker
            .project_spectator_state(&actor_account_id.to_string(), &manifest, &snapshot)
            .await
            .map_err(|_| CommitError::WorkerRevisionMismatch)?;
        let stored = stored_snapshot(snapshot.as_bytes())?;

        let mut tx = self.pool.begin().await.map_err(CommitError::storage)?;
        let row = sqlx::query(
            "SELECT q.expected_head_revision,q.target_revision,g.head_revision,
                    g.ruleset_manifest_hash,m.engine_build
             FROM game_rewind_requests q JOIN games g ON g.id=q.game_id
             JOIN ruleset_manifests m ON m.hash=g.ruleset_manifest_hash
             WHERE q.game_id=$1 AND q.request_id=$2 AND q.status='pending'
             FOR UPDATE OF q,g",
        )
        .bind(game_id)
        .bind(request_id)
        .fetch_optional(&mut *tx)
        .await
        .map_err(CommitError::storage)?
        .ok_or(CommitError::InvalidCommand)?;
        let expected = row.get::<i64, _>("expected_head_revision");
        let head = row.get::<i64, _>("head_revision");
        let electorate_matches: bool = sqlx::query_scalar(
            "SELECT NOT EXISTS(
               (SELECT account_id FROM game_members WHERE game_id=$1 AND role IN ('owner','player')
                EXCEPT SELECT account_id FROM game_rewind_electorate WHERE game_id=$1 AND request_id=$2)
               UNION ALL
               (SELECT account_id FROM game_rewind_electorate WHERE game_id=$1 AND request_id=$2
                EXCEPT SELECT account_id FROM game_members WHERE game_id=$1 AND role IN ('owner','player')))",
        )
        .bind(game_id)
        .bind(request_id)
        .fetch_one(&mut *tx)
        .await
        .map_err(CommitError::storage)?;
        if head != expected || !electorate_matches {
            sqlx::query(
                "UPDATE game_rewind_requests SET status='stale',resolved_at=now()
                 WHERE game_id=$1 AND request_id=$2",
            )
            .bind(game_id)
            .bind(request_id)
            .execute(&mut *tx)
            .await
            .map_err(CommitError::storage)?;
            tx.commit().await.map_err(CommitError::storage)?;
            return self
                .rewind_status(actor_account_id, game_id, request_id)
                .await;
        }
        let revision = head.checked_add(1).ok_or(CommitError::Storage)?;
        snapshot_storage::insert_snapshot(
            &mut tx,
            game_id,
            revision,
            row.get("engine_build"),
            row.get("ruleset_manifest_hash"),
            &stored,
        )
        .await?;
        sqlx::query(
            "INSERT INTO game_revisions
             (game_id,revision,parent_revision,command_id,snapshot_revision,
              canonical_state_hash,revision_kind)
             VALUES($1,$2,$3,NULL,$2,$4,'rewind')",
        )
        .bind(game_id)
        .bind(revision)
        .bind(head)
        .bind(&stored.canonical_state_hash)
        .execute(&mut *tx)
        .await
        .map_err(CommitError::storage)?;
        sqlx::query(
            "INSERT INTO game_outbox(game_id,revision,topic,payload)
             VALUES($1,$2,'game.revision.rewound',$3)",
        )
        .bind(game_id)
        .bind(revision)
        .bind(json!({
            "game_id": game_id, "revision": revision,
            "rewound_from_revision": head,
            "target_revision": row.get::<i64, _>("target_revision"),
            "state_hash": stored.canonical_state_hash,
        }))
        .execute(&mut *tx)
        .await
        .map_err(CommitError::storage)?;
        sqlx::query("UPDATE games SET head_revision=$2 WHERE id=$1 AND head_revision=$3")
            .bind(game_id)
            .bind(revision)
            .bind(head)
            .execute(&mut *tx)
            .await
            .map_err(CommitError::storage)?;
        sqlx::query(
            "UPDATE game_rewind_requests
             SET status='applied',applied_revision=$3,resolved_at=now()
             WHERE game_id=$1 AND request_id=$2",
        )
        .bind(game_id)
        .bind(request_id)
        .bind(revision)
        .execute(&mut *tx)
        .await
        .map_err(CommitError::storage)?;
        tx.commit().await.map_err(CommitError::storage)?;
        self.rewind_status(actor_account_id, game_id, request_id)
            .await
    }
}

fn status_from_row(row: &sqlx::postgres::PgRow) -> Result<RewindStatus, CommitError> {
    Ok(RewindStatus {
        request_id: row.get("request_id"),
        expected_head_revision: u64::try_from(row.get::<i64, _>("expected_head_revision"))
            .map_err(|_| CommitError::Storage)?,
        target_revision: u64::try_from(row.get::<i64, _>("target_revision"))
            .map_err(|_| CommitError::Storage)?,
        status: row.get("status"),
        approvals: u32::try_from(row.get::<i64, _>("approvals"))
            .map_err(|_| CommitError::Storage)?,
        required_approvals: u32::try_from(row.get::<i64, _>("required"))
            .map_err(|_| CommitError::Storage)?,
        actor_approved: row.get("actor_approved"),
        applied_revision: row
            .get::<Option<i64>, _>("applied_revision")
            .map(u64::try_from)
            .transpose()
            .map_err(|_| CommitError::Storage)?,
    })
}
