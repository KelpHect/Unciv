use super::*;

use crate::projection::ReplayProjection;

impl PostgresGameRepository {
    /// Lists all revisions for a game with their turn numbers and timestamps.
    /// Accessible without membership for public games.
    pub async fn list_revisions(&self, game_id: Uuid) -> Result<RevisionList, CommitError> {
        let rows = sqlx::query(
            "SELECT r.revision, r.revision_kind,
                    to_char(r.created_at, 'YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"') AS created_at_str
             FROM game_revisions r
             WHERE r.game_id = $1
             ORDER BY r.revision ASC",
        )
        .bind(game_id)
        .fetch_all(&self.pool)
        .await
        .map_err(CommitError::storage)?;
        let revisions: Vec<RevisionSummary> = rows
            .into_iter()
            .map(|row| RevisionSummary {
                revision: u64::try_from(row.get::<i64, _>("revision"))
                    .expect("revision is non-negative"),
                revision_kind: row.get("revision_kind"),
                created_at: row.get("created_at_str"),
            })
            .collect();
        Ok(RevisionList { revisions })
    }

    /// Returns a full no-fog-of-war replay projection at the given revision.
    /// For public games, accessible without membership.
    pub async fn replay_projection(
        &self,
        worker: &EngineWorkerClient,
        actor_id: &str,
        game_id: Uuid,
        revision: u64,
    ) -> Result<ReplayGameProjection, CommitError> {
        let row = sqlx::query(
            "SELECT g.visibility, g.unavailable_at IS NOT NULL AS is_unavailable,
                    r.canonical_state_hash AS revision_state_hash,
                    s.revision AS snapshot_revision, b.payload, s.codec, s.compressed_size,
                    s.uncompressed_size, s.protocol_version AS snapshot_protocol_version,
                    s.validation_status, s.payload_hash, s.canonical_state_hash AS snapshot_state_hash,
                    m.manifest
             FROM games g
             JOIN game_revisions r ON r.game_id = g.id AND r.revision = $2
             JOIN game_snapshots s ON s.game_id = g.id AND s.revision = $2
             LEFT JOIN game_snapshot_blobs b ON b.game_id = s.game_id AND b.revision = s.revision
             JOIN ruleset_manifests m ON m.hash = g.ruleset_manifest_hash
             WHERE g.id = $1",
        )
        .bind(game_id)
        .bind(i64::try_from(revision).expect("revision fits in i64"))
        .fetch_optional(&self.pool)
        .await
        .map_err(CommitError::storage)?
            .ok_or(CommitError::NotFound)?;
        let snapshot = self.validated_snapshot(game_id, &row).await?;
        let manifest_value: serde_json::Value = row.get("manifest");
        let worker_manifest: WorkerManifest = serde_json::from_value(manifest_value)
            .map_err(|_| CommitError::WorkerRevisionMismatch)?;
        let projected = worker
            .project_replay_state(actor_id, &worker_manifest, &snapshot)
            .await
            .map_err(|error| match error {
                crate::worker::WorkerClientError::Rejected(reason) => {
                    CommitError::WorkerRejected(reason)
                }
                _ => CommitError::WorkerRevisionMismatch,
            })?;
        let projection_bytes =
            serde_json::to_vec(&projected).expect("worker replay projection is serializable");
        Ok(ReplayGameProjection {
            game_id,
            projection_version: REPLAY_PROJECTION_VERSION,
            committed_revision: revision,
            canonical_state_hash: row.get("revision_state_hash"),
            projection_hash: state_hash(&projection_bytes),
            projection: projected,
        })
    }

    /// Checks whether a game is publicly visible.
    pub async fn is_public_game(&self, game_id: Uuid) -> Result<bool, CommitError> {
        let visibility: Option<String> =
            sqlx::query_scalar("SELECT visibility FROM games WHERE id = $1")
                .bind(game_id)
                .fetch_optional(&self.pool)
                .await
                .map_err(CommitError::storage)?;
        Ok(visibility.as_deref() == Some("public"))
    }

    /// Lists public matches for the public matches directory.
    pub async fn list_public_matches(
        &self,
        limit: u32,
        offset: u32,
    ) -> Result<Vec<PublicMatchSummary>, CommitError> {
        let rows = sqlx::query(
            "SELECT g.id, g.display_name, g.lifecycle_status, g.head_revision,
                    to_char(g.created_at, 'YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"') AS created_at_str
             FROM games g
             WHERE g.visibility = 'public'
             ORDER BY g.created_at DESC
             LIMIT $1 OFFSET $2",
        )
        .bind(i32::try_from(limit).unwrap_or(100))
        .bind(i32::try_from(offset).unwrap_or(0))
        .fetch_all(&self.pool)
        .await
        .map_err(CommitError::storage)?;
        let matches = rows
            .into_iter()
            .map(|row| PublicMatchSummary {
                game_id: row.get("id"),
                display_name: row.get("display_name"),
                lifecycle_status: row.get("lifecycle_status"),
                head_revision: u64::try_from(row.get::<i64, _>("head_revision"))
                    .expect("revision is non-negative"),
                created_at: row.get("created_at_str"),
            })
            .collect();
        Ok(matches)
    }
}

pub const REPLAY_PROJECTION_VERSION: u16 = 1;

#[derive(Clone, Debug, serde::Serialize, utoipa::ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct ReplayGameProjection {
    pub game_id: Uuid,
    pub projection_version: u16,
    pub committed_revision: u64,
    pub canonical_state_hash: String,
    pub projection_hash: String,
    pub projection: ReplayProjection,
}

#[derive(Clone, Debug, serde::Serialize, utoipa::ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct RevisionSummary {
    pub revision: u64,
    pub revision_kind: String,
    pub created_at: String,
}

#[derive(Clone, Debug, serde::Serialize, utoipa::ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct RevisionList {
    pub revisions: Vec<RevisionSummary>,
}

#[derive(Clone, Debug, serde::Serialize, utoipa::ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct PublicMatchSummary {
    pub game_id: Uuid,
    pub display_name: String,
    pub lifecycle_status: String,
    pub head_revision: u64,
    pub created_at: String,
}
