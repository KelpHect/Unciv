use super::retention::should_retain;
use super::*;

use crate::object_store::PutObjectResult;

#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize)]
pub struct SnapshotArchiveReport {
    pub game_id: Uuid,
    pub head_revision: u64,
    pub candidates: u64,
    pub archived_payloads: u64,
    pub delta_payloads: u64,
    pub skipped_payloads: u64,
    pub bytes_archived: u64,
    pub dry_run: bool,
}

impl PostgresGameRepository {
    /// Archives eligible cold payloads in Lockwell and removes their PostgreSQL
    /// blobs only after a byte-for-byte GET verification. Payloads are fetched
    /// one at a time: a Huge-map match cannot make the API process materialize
    /// every snapshot in memory just to compact it.
    pub async fn archive_snapshot_payloads(
        &self,
        game_id: Uuid,
        policy: SnapshotRetentionPolicy,
        dry_run: bool,
        use_deltas: bool,
    ) -> Result<SnapshotArchiveReport, CommitError> {
        self.archive_snapshot_payloads_bounded(
            game_id,
            policy,
            dry_run,
            use_deltas,
            u64::MAX,
            u64::MAX,
        )
        .await
    }

    pub(super) async fn archive_snapshot_payloads_bounded(
        &self,
        game_id: Uuid,
        policy: SnapshotRetentionPolicy,
        dry_run: bool,
        use_deltas: bool,
        max_candidates: u64,
        max_archive_bytes: u64,
    ) -> Result<SnapshotArchiveReport, CommitError> {
        if policy.recent_revisions < 2 || policy.long_term_interval == 0 || max_candidates == 0 {
            return Err(CommitError::InvalidCommand);
        }
        let head_revision_i64: i64 =
            sqlx::query_scalar("SELECT head_revision FROM games WHERE id=$1")
                .bind(game_id)
                .fetch_optional(&self.pool)
                .await
                .map_err(CommitError::storage)?
                .ok_or(CommitError::NotFound)?;
        let head_revision = u64::try_from(head_revision_i64).map_err(|_| CommitError::Storage)?;
        let scan_limit = max_candidates
            .min(i64::MAX as u64)
            .saturating_add(policy.recent_revisions)
            .min(i64::MAX as u64);
        let limit = i64::try_from(scan_limit).map_err(|_| CommitError::Storage)?;
        let rows = sqlx::query(
            "SELECT s.revision, s.codec, s.compressed_size, s.uncompressed_size,
                    s.payload_hash, r.revision_kind,
                    c.payload->'command'->>'type' AS command_type
             FROM game_snapshots s
             JOIN game_revisions r
               ON r.game_id=s.game_id AND r.revision=s.revision
             LEFT JOIN game_commands c
               ON c.game_id=r.game_id AND c.revision=r.revision
             WHERE s.game_id=$1 AND s.payload_retention_status='retained'
               AND EXISTS (
                   SELECT 1 FROM game_snapshot_blobs b
                   WHERE b.game_id=s.game_id AND b.revision=s.revision
               )
             ORDER BY s.revision DESC
             LIMIT $2",
        )
        .bind(game_id)
        .bind(limit)
        .fetch_all(&self.pool)
        .await
        .map_err(CommitError::storage)?;
        let mut report = SnapshotArchiveReport {
            game_id,
            head_revision,
            candidates: 0,
            archived_payloads: 0,
            delta_payloads: 0,
            skipped_payloads: 0,
            bytes_archived: 0,
            dry_run,
        };
        for row in rows {
            if report.archived_payloads >= max_candidates
                || report.bytes_archived >= max_archive_bytes
            {
                break;
            }
            let revision =
                u64::try_from(row.get::<i64, _>("revision")).map_err(|_| CommitError::Storage)?;
            let revision_kind: String = row.get("revision_kind");
            let command_type: Option<String> = row.get("command_type");
            if should_retain(
                revision,
                head_revision,
                &revision_kind,
                command_type.as_deref(),
                policy,
            ) {
                report.skipped_payloads += 1;
                continue;
            }
            report.candidates += 1;
            let revision_i64 = i64::try_from(revision).map_err(|_| CommitError::Storage)?;
            let payload: Vec<u8> = sqlx::query_scalar(
                "SELECT payload FROM game_snapshot_blobs
                 WHERE game_id=$1 AND revision=$2",
            )
            .bind(game_id)
            .bind(revision_i64)
            .fetch_optional(&self.pool)
            .await
            .map_err(CommitError::storage)?
            .ok_or(CommitError::RecoveryEvidenceMissing)?;
            let payload_hash: String = row.get("payload_hash");
            let compressed_size: i64 = row.get("compressed_size");
            if payload.len() as i64 != compressed_size || state_hash(&payload) != payload_hash {
                return Err(CommitError::RecoveryEvidenceMissing);
            }
            let target = decode_snapshot(
                row.get("codec"),
                &payload,
                compressed_size,
                row.get("uncompressed_size"),
            )
            .map_err(|_| CommitError::RecoveryEvidenceMissing)?;
            let (archive_payload, archive_codec, base_revision, base_state_hash) = if use_deltas {
                let base_revision = revision - revision % policy.long_term_interval;
                let base = self
                    .canonical_snapshot_at_revision(
                        game_id,
                        i64::try_from(base_revision).map_err(|_| CommitError::Storage)?,
                    )
                    .await?;
                let delta =
                    crate::snapshot_delta::SnapshotDelta::encode(base_revision, &base, &target, 9)
                        .map_err(|_| CommitError::Storage)?;
                if delta.payload.len() < payload.len() {
                    let base_hash = state_hash(&base);
                    (delta.payload, "delta", Some(base_revision), Some(base_hash))
                } else {
                    (payload.clone(), "full", None, None)
                }
            } else {
                (payload.clone(), "full", None, None)
            };
            // Delta objects use a small envelope so old raw objects remain
            // distinguishable. Full objects stay byte-for-byte equal to the
            // existing compressed payload and do not grow past the snapshot cap.
            let object_payload = if archive_codec == "delta" {
                super::archive_object_payload(&archive_payload)
            } else {
                archive_payload.clone()
            };
            let archive_payload_hash = state_hash(&object_payload);
            let archive_size =
                i64::try_from(object_payload.len()).map_err(|_| CommitError::Storage)?;
            if archive_size <= 0 || archive_size > MAX_SNAPSHOT_BYTES as i64 {
                return Err(CommitError::SnapshotTooLarge);
            }
            let archive_size_u64 = object_payload.len() as u64;
            if archive_size_u64 > max_archive_bytes.saturating_sub(report.bytes_archived) {
                report.skipped_payloads += 1;
                continue;
            }
            report.bytes_archived += archive_size_u64;
            if dry_run {
                continue;
            }
            let store = self.object_store.as_ref().ok_or(CommitError::Storage)?;
            let key = archive_object_key(game_id, revision);
            let put_result = store
                .put_if_absent(&key, object_payload.clone())
                .await
                .map_err(|_| CommitError::Storage)?;
            let created = matches!(put_result, PutObjectResult::Created);
            let archived = match store.get(&key).await {
                Ok(archived) => archived,
                Err(_) => {
                    if created {
                        let _ = store.delete(&key).await;
                    }
                    return Err(CommitError::Storage);
                }
            };
            if archived.len() as i64 != archive_size
                || state_hash(&archived) != archive_payload_hash
                || archived != object_payload
            {
                if created {
                    let _ = store.delete(&key).await;
                }
                return Err(CommitError::RecoveryEvidenceMissing);
            }
            let database_result: Result<(), CommitError> = async {
                let mut tx = self.pool.begin().await.map_err(CommitError::storage)?;

                // There is deliberately no composite archive FK to mutable snapshot
                // size/hash metadata. This row is the verified external evidence;
                // the game/revision FK remains the immutable identity anchor.
                sqlx::query(
                    "INSERT INTO game_snapshot_archives
                     (game_id, revision, object_key, object_size, payload_hash,
                      archive_codec, base_revision, base_state_hash, verified_at)
                     VALUES ($1,$2,$3,$4,$5,$6,$7,$8,now())
                     ON CONFLICT (game_id, revision) DO UPDATE
                     SET object_key=EXCLUDED.object_key,
                         object_size=EXCLUDED.object_size,
                         payload_hash=EXCLUDED.payload_hash,
                         archive_codec=EXCLUDED.archive_codec,
                         base_revision=EXCLUDED.base_revision,
                         base_state_hash=EXCLUDED.base_state_hash,
                         verified_at=EXCLUDED.verified_at",
                )
                .bind(game_id)
                .bind(revision_i64)
                .bind(&key)
                .bind(archive_size)
                .bind(&archive_payload_hash)
                .bind(archive_codec)
                .bind(base_revision.map(|value| value as i64))
                .bind(base_state_hash)
                .execute(&mut *tx)
                .await
                .map_err(CommitError::storage)?;
                let deleted =
                    sqlx::query("DELETE FROM game_snapshot_blobs WHERE game_id=$1 AND revision=$2")
                        .bind(game_id)
                        .bind(revision_i64)
                        .execute(&mut *tx)
                        .await
                        .map_err(CommitError::storage)?;
                if deleted.rows_affected() != 1 {
                    return Err(CommitError::Storage);
                }
                let updated = sqlx::query(
                    "UPDATE game_snapshots
                     SET codec=CASE WHEN $3='delta' THEN 'zstd_delta' ELSE codec END,
                         compressed_size=$4, payload_hash=$5,
                         payload_retention_status='compacted', compacted_at=now()
                     WHERE game_id=$1 AND revision=$2 AND payload_retention_status='retained'",
                )
                .bind(game_id)
                .bind(revision_i64)
                .bind(archive_codec)
                .bind(archive_size)
                .bind(&archive_payload_hash)
                .execute(&mut *tx)
                .await
                .map_err(CommitError::storage)?;
                if updated.rows_affected() != 1 {
                    return Err(CommitError::Storage);
                }
                tx.commit().await.map_err(CommitError::storage)
            }
            .await;
            if let Err(error) = database_result {
                // A newly-created object has no other owner. Remove it on a
                // failed metadata transaction so retries do not leak storage.
                // Pre-existing idempotent objects are never deleted here.
                if created {
                    let _ = store.delete(&key).await;
                }
                return Err(error);
            }
            report.archived_payloads += 1;
            if archive_codec == "delta" {
                report.delta_payloads += 1;
            }
        }
        Ok(report)
    }
}

pub(super) fn archive_object_key(game_id: Uuid, revision: u64) -> String {
    format!("games/{game_id}/snapshots/{revision}.bin")
}
