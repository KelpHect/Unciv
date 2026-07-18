use super::*;

impl PostgresGameRepository {
    /// Creates a new canonical game exclusively through the private Kotlin
    /// worker. The caller supplies a previously stored manifest hash; it cannot
    /// upload a revision-zero save or choose an unpinned ruleset payload.
    pub async fn create_authoritative_game(
        &self,
        worker: &EngineWorkerClient,
        owner_account_id: Uuid,
        game_id: Uuid,
        ruleset_manifest_hash: String,
    ) -> Result<(), CommitError> {
        let manifest: serde_json::Value =
            sqlx::query_scalar("SELECT manifest FROM ruleset_manifests WHERE hash = $1")
                .bind(&ruleset_manifest_hash)
                .fetch_optional(&self.pool)
                .await
                .map_err(CommitError::storage)?
                .ok_or(CommitError::NotFound)?;
        let manifest: WorkerManifest =
            serde_json::from_value(manifest).map_err(|_| CommitError::WorkerRevisionMismatch)?;
        // Defaults are a deliberately minimal setup intent. The worker is the
        // sole component that turns it into a GameInfo through GameStarter.
        let created = worker
            .create_game(&owner_account_id.to_string(), &manifest, "{}")
            .await
            .map_err(|_| CommitError::WorkerRevisionMismatch)?;
        let proposal = created.proposal;
        if proposal.snapshot.is_empty() || proposal.snapshot.len() > MAX_SNAPSHOT_BYTES {
            return Err(CommitError::SnapshotTooLarge);
        }
        if state_hash(&proposal.snapshot) != proposal.canonical_state_hash {
            return Err(CommitError::InvalidSnapshotHash);
        }
        self.create_game(NewGame {
            game_id,
            owner_account_id,
            ruleset_manifest_hash,
            snapshot: proposal.snapshot,
            owner_civilization_id: created.owner_civilization_id,
        })
        .await
    }

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
            "SELECT g.unavailable_at IS NOT NULL AS is_unavailable, g.head_revision, r.canonical_state_hash FROM games g JOIN game_revisions r ON r.game_id = g.id AND r.revision = g.head_revision WHERE g.id = $1",
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
            "SELECT g.id AS game_id, g.head_revision, r.canonical_state_hash, gm.role, gm.civilization_id, g.unavailable_at IS NULL AS available FROM game_members gm JOIN games g ON g.id=gm.game_id JOIN game_revisions r ON r.game_id=g.id AND r.revision=g.head_revision WHERE gm.account_id=$1 AND ($2::uuid IS NULL OR g.id>$2) ORDER BY g.id LIMIT $3",
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
            "SELECT g.unavailable_at IS NOT NULL AS is_unavailable, g.head_revision, r.canonical_state_hash AS revision_state_hash, s.revision AS snapshot_revision, s.payload, s.codec, s.compressed_size, s.uncompressed_size, s.protocol_version AS snapshot_protocol_version, s.validation_status, s.payload_hash, s.canonical_state_hash AS snapshot_state_hash, m.manifest, gm.role, gm.civilization_id FROM games g JOIN game_members gm ON gm.game_id=g.id AND gm.account_id=$2 JOIN game_revisions r ON r.game_id=g.id AND r.revision=g.head_revision JOIN game_snapshots s ON s.game_id=g.id AND s.revision=g.head_revision JOIN ruleset_manifests m ON m.hash=g.ruleset_manifest_hash WHERE g.id=$1",
        )
        .bind(game_id)
        .bind(actor_account_id)
        .fetch_optional(&self.pool)
        .await
        .map_err(CommitError::storage)?
        .ok_or(CommitError::Unauthorized)?;
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
