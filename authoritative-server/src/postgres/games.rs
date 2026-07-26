use super::*;

impl PostgresGameRepository {
    const MAX_PROJECTION_DELTA_REVISION_SPAN: u64 = 64;

    /// Returns a safe metadata projection. It deliberately excludes the
    /// canonical snapshot: callers must later use a player-scoped projection
    /// endpoint rather than receiving serialized `GameInfo`.
    pub async fn game_metadata(
        &self,
        actor_account_id: Uuid,
        game_id: Uuid,
    ) -> Result<GameMetadata, CommitError> {
        let membership = sqlx::query(
            "SELECT role, civilization_id FROM game_members WHERE game_id = $1 AND account_id = $2",
        )
        .bind(game_id)
        .bind(actor_account_id)
        .fetch_optional(&self.pool)
        .await
        .map_err(CommitError::storage)?;
        let membership = match membership {
            Some(membership) => membership,
            None => {
                let game_exists: bool =
                    sqlx::query_scalar("SELECT EXISTS(SELECT 1 FROM games WHERE id = $1)")
                        .bind(game_id)
                        .fetch_one(&self.pool)
                        .await
                        .map_err(CommitError::storage)?;
                return Err(if game_exists {
                    CommitError::Unauthorized
                } else {
                    CommitError::NotFound
                });
            }
        };
        let role: String = membership.get("role");
        let civilization_id: Option<String> = membership.get("civilization_id");
        let row = sqlx::query(
            "SELECT g.unavailable_at IS NOT NULL AS is_unavailable, g.lifecycle_status, g.head_revision, r.canonical_state_hash FROM games g JOIN game_revisions r ON r.game_id = g.id AND r.revision = g.head_revision WHERE g.id = $1",
        )
        .bind(game_id)
        .fetch_optional(&self.pool)
        .await
        .map_err(CommitError::storage)?
        .ok_or(CommitError::NotFound)?;
        if row.get::<bool, _>("is_unavailable") {
            return Err(CommitError::GameUnavailable);
        }
        Ok(GameMetadata {
            game_id,
            committed_revision: u64::try_from(row.get::<i64, _>("head_revision"))
                .expect("revision is non-negative"),
            canonical_state_hash: row.get("canonical_state_hash"),
            role,
            civilization_id,
            lifecycle_status: row.get("lifecycle_status"),
        })
    }

    /// Lists only games represented by server-side membership records. The
    /// page contains revision metadata, never snapshots or player projections;
    /// clients bootstrap an available game through its scoped projection.
    pub async fn list_games(
        &self,
        actor_account_id: Uuid,
        after: Option<Uuid>,
        limit: u32,
    ) -> Result<GamePage, CommitError> {
        if !(1..=100).contains(&limit) {
            return Err(CommitError::InvalidCommand);
        }
        let fetch_limit = i64::from(limit) + 1;
        let rows = sqlx::query(
            "SELECT g.id AS game_id, g.head_revision, r.canonical_state_hash, gm.role, gm.civilization_id, g.lifecycle_status, (g.unavailable_at IS NULL AND g.lifecycle_status <> 'archived') AS available FROM game_members gm JOIN games g ON g.id=gm.game_id JOIN game_revisions r ON r.game_id=g.id AND r.revision=g.head_revision WHERE gm.account_id=$1 AND ($2::uuid IS NULL OR g.id>$2) ORDER BY g.id LIMIT $3",
        )
        .bind(actor_account_id)
        .bind(after)
        .bind(fetch_limit)
        .fetch_all(&self.pool)
        .await
        .map_err(CommitError::storage)?;
        let has_more = rows.len() > limit as usize;
        let games = rows
            .into_iter()
            .take(limit as usize)
            .map(|row| GameSummary {
                game_id: row.get("game_id"),
                committed_revision: u64::try_from(row.get::<i64, _>("head_revision"))
                    .expect("revision is non-negative"),
                canonical_state_hash: row.get("canonical_state_hash"),
                role: row.get("role"),
                civilization_id: row.get("civilization_id"),
                available: row.get("available"),
                lifecycle_status: row.get("lifecycle_status"),
            })
            .collect::<Vec<_>>();
        let next_cursor = has_more.then(|| games.last().expect("non-empty limited page").game_id);
        Ok(GamePage { games, next_cursor })
    }

    /// Builds a player-scoped view from one consistent canonical head. The
    /// full snapshot crosses only the private worker boundary and is never
    /// returned by this public repository operation.
    pub async fn game_projection(
        &self,
        worker: &EngineWorkerClient,
        actor_account_id: Uuid,
        game_id: Uuid,
    ) -> Result<GameProjection, CommitError> {
        let row = sqlx::query(
            "SELECT g.unavailable_at IS NOT NULL AS is_unavailable, g.lifecycle_status, g.head_revision, r.canonical_state_hash AS revision_state_hash, s.revision AS snapshot_revision, b.payload, s.codec, s.compressed_size, s.uncompressed_size, s.protocol_version AS snapshot_protocol_version, s.validation_status, s.payload_hash, s.canonical_state_hash AS snapshot_state_hash, m.manifest, gm.role, gm.civilization_id FROM games g JOIN game_members gm ON gm.game_id=g.id AND gm.account_id=$2 JOIN game_revisions r ON r.game_id=g.id AND r.revision=g.head_revision JOIN game_snapshots s ON s.game_id=g.id AND s.revision=g.head_revision JOIN game_snapshot_blobs b ON b.game_id=s.game_id AND b.revision=s.revision JOIN ruleset_manifests m ON m.hash=g.ruleset_manifest_hash WHERE g.id=$1",
        )
        .bind(game_id)
        .bind(actor_account_id)
        .fetch_optional(&self.pool)
        .await
        .map_err(CommitError::storage)?
            .ok_or(CommitError::Unauthorized)?;
        self.project_row(worker, actor_account_id, game_id, row)
            .await
    }

    pub async fn game_projection_delta(
        &self,
        worker: &EngineWorkerClient,
        actor_account_id: Uuid,
        game_id: Uuid,
        base_revision: u64,
        base_canonical_state_hash: &str,
        base_projection_hash: &str,
    ) -> Result<GameProjectionDelta, CommitError> {
        if !valid_projection_hash(base_canonical_state_hash)
            || !valid_projection_hash(base_projection_hash)
        {
            return Err(CommitError::ProjectionDeltaUnavailable);
        }
        let base = self
            .game_projection_at_revision(worker, actor_account_id, game_id, base_revision)
            .await?;
        if base.canonical_state_hash != base_canonical_state_hash
            || base.projection_hash != base_projection_hash
        {
            return Err(CommitError::ProjectionDeltaUnavailable);
        }
        let target = self
            .game_projection(worker, actor_account_id, game_id)
            .await?;
        let revision_span = target
            .committed_revision
            .checked_sub(base_revision)
            .ok_or(CommitError::ProjectionDeltaUnavailable)?;
        if revision_span > Self::MAX_PROJECTION_DELTA_REVISION_SPAN {
            return Err(CommitError::ProjectionDeltaUnavailable);
        }
        let base_json = serde_json::to_value(&base.projection).map_err(|_| CommitError::Storage)?;
        let target_json =
            serde_json::to_value(&target.projection).map_err(|_| CommitError::Storage)?;
        let operations =
            crate::projection_delta::projection_delta_operations(&base_json, &target_json)
                .ok_or(CommitError::ProjectionDeltaUnavailable)?;
        let operation_bytes = serde_json::to_vec(&operations)
            .map_err(|_| CommitError::Storage)?
            .len();
        let full_bytes = serde_json::to_vec(&target.projection)
            .map_err(|_| CommitError::Storage)?
            .len();
        if !operations.is_empty() && operation_bytes >= full_bytes {
            return Err(CommitError::ProjectionDeltaUnavailable);
        }
        Ok(GameProjectionDelta {
            game_id,
            projection_version: target.projection_version,
            base_revision,
            base_canonical_state_hash: base.canonical_state_hash,
            base_projection_hash: base.projection_hash,
            committed_revision: target.committed_revision,
            canonical_state_hash: target.canonical_state_hash,
            projection_hash: target.projection_hash,
            operations,
        })
    }

    async fn game_projection_at_revision(
        &self,
        worker: &EngineWorkerClient,
        actor_account_id: Uuid,
        game_id: Uuid,
        revision: u64,
    ) -> Result<GameProjection, CommitError> {
        let revision =
            i64::try_from(revision).map_err(|_| CommitError::ProjectionDeltaUnavailable)?;
        let row = sqlx::query(
            "SELECT g.unavailable_at IS NOT NULL AS is_unavailable, g.lifecycle_status, r.revision AS head_revision, r.canonical_state_hash AS revision_state_hash, s.revision AS snapshot_revision, b.payload, s.codec, s.compressed_size, s.uncompressed_size, s.protocol_version AS snapshot_protocol_version, s.validation_status, s.payload_hash, s.canonical_state_hash AS snapshot_state_hash, m.manifest, gm.role, gm.civilization_id FROM games g JOIN game_members gm ON gm.game_id=g.id AND gm.account_id=$2 JOIN game_revisions r ON r.game_id=g.id AND r.revision=$3 JOIN game_snapshots s ON s.game_id=g.id AND s.revision=r.revision JOIN game_snapshot_blobs b ON b.game_id=s.game_id AND b.revision=s.revision JOIN ruleset_manifests m ON m.hash=g.ruleset_manifest_hash WHERE g.id=$1",
        )
        .bind(game_id)
        .bind(actor_account_id)
        .bind(revision)
        .fetch_optional(&self.pool)
        .await
        .map_err(CommitError::storage)?
        .ok_or(CommitError::ProjectionDeltaUnavailable)?;
        self.project_row(worker, actor_account_id, game_id, row)
            .await
    }

    async fn project_row(
        &self,
        worker: &EngineWorkerClient,
        actor_account_id: Uuid,
        game_id: Uuid,
        row: PgRow,
    ) -> Result<GameProjection, CommitError> {
        if row.get::<String, _>("lifecycle_status") == "archived" {
            return Err(CommitError::InvalidCommand);
        }
        let role: String = row.get("role");
        if !matches!(role.as_str(), "owner" | "player") {
            return Err(CommitError::Unauthorized);
        }
        let actor_civilization_id: Option<String> = row.get("civilization_id");
        let actor_civilization_id = actor_civilization_id.ok_or(CommitError::Unauthorized)?;
        let snapshot = self.validated_snapshot(game_id, &row).await?;
        let manifest = serde_json::from_value::<WorkerManifest>(row.get("manifest"))
            .map_err(|_| CommitError::WorkerRevisionMismatch)?;
        let projected = worker
            .project_state(
                &actor_account_id.to_string(),
                &manifest,
                &snapshot,
                &actor_civilization_id,
            )
            .await
            .map_err(|error| match error {
                crate::worker::WorkerClientError::Rejected(reason) => {
                    CommitError::WorkerRejected(reason)
                }
                other => {
                    eprintln!("authoritative worker projection failure: {other}");
                    CommitError::WorkerRevisionMismatch
                }
            })?;
        Ok(GameProjection {
            game_id,
            projection_version: PROJECTION_VERSION,
            committed_revision: u64::try_from(row.get::<i64, _>("head_revision"))
                .expect("revision is non-negative"),
            canonical_state_hash: row.get("revision_state_hash"),
            projection_hash: state_hash(
                &serde_json::to_vec(&projected.projection)
                    .expect("worker projection JSON value is serializable"),
            ),
            projection: projected.projection,
        })
    }
}

fn valid_projection_hash(value: &str) -> bool {
    value.len() == 64
        && value
            .bytes()
            .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
}

#[cfg(test)]
mod tests {
    use super::valid_projection_hash;

    #[test]
    fn projection_hashes_are_exact_lowercase_sha256_values() {
        assert!(valid_projection_hash(&"a5".repeat(32)));
        assert!(!valid_projection_hash(&"A5".repeat(32)));
        assert!(!valid_projection_hash(&"a5".repeat(31)));
        assert!(!valid_projection_hash(&format!("{}g", "a".repeat(63))));
    }
}
