use super::*;

#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize)]
pub struct SnapshotReencodeReport {
    pub game_id: Uuid,
    pub scanned_payloads: u64,
    pub rewritten_payloads: u64,
    pub bytes_before: u64,
    pub bytes_after: u64,
    pub dry_run: bool,
}

impl PostgresGameRepository {
    /// Re-encodes retained full snapshots with the configured zstd level. It
    /// never rewrites canonical state or revision identity and skips rows where
    /// the new payload is not smaller. The blob delete/update/insert sequence is
    /// one transaction, protected by the game row lock.
    pub async fn reencode_snapshot_payloads(
        &self,
        game_id: Uuid,
        dry_run: bool,
    ) -> Result<SnapshotReencodeReport, CommitError> {
        let mut tx = self.pool.begin().await.map_err(CommitError::storage)?;
        sqlx::query("SELECT id FROM games WHERE id=$1 FOR UPDATE")
            .bind(game_id)
            .fetch_optional(&mut *tx)
            .await
            .map_err(CommitError::storage)?
            .ok_or(CommitError::NotFound)?;
        let rows = sqlx::query(
            "SELECT s.revision, s.codec, s.compressed_size, s.uncompressed_size,
                    s.canonical_state_hash, s.payload_hash, b.payload
             FROM game_snapshots s
             JOIN game_snapshot_blobs b
               ON b.game_id=s.game_id AND b.revision=s.revision
             WHERE s.game_id=$1 AND s.payload_retention_status='retained'
             ORDER BY s.revision",
        )
        .bind(game_id)
        .fetch_all(&mut *tx)
        .await
        .map_err(CommitError::storage)?;
        let mut report = SnapshotReencodeReport {
            game_id,
            scanned_payloads: 0,
            rewritten_payloads: 0,
            bytes_before: 0,
            bytes_after: 0,
            dry_run,
        };
        for row in rows {
            report.scanned_payloads += 1;
            let revision: i64 = row.get("revision");
            let payload: Vec<u8> = row.get("payload");
            let compressed_size: i64 = row.get("compressed_size");
            report.bytes_before +=
                u64::try_from(compressed_size).map_err(|_| CommitError::Storage)?;
            if payload.len() as i64 != compressed_size
                || state_hash(&payload) != row.get::<String, _>("payload_hash")
            {
                return Err(CommitError::RecoveryEvidenceMissing);
            }
            let decoded = decode_snapshot(
                row.get("codec"),
                &payload,
                compressed_size,
                row.get("uncompressed_size"),
            )
            .map_err(|_| CommitError::RecoveryEvidenceMissing)?;
            if state_hash(&decoded) != row.get::<String, _>("canonical_state_hash") {
                return Err(CommitError::RecoveryEvidenceMissing);
            }
            let rewritten = encode_snapshot(&decoded).map_err(|_| CommitError::Storage)?;
            if rewritten.payload.len() >= payload.len() {
                report.bytes_after += payload.len() as u64;
                continue;
            }
            report.rewritten_payloads += 1;
            report.bytes_after += rewritten.payload.len() as u64;
            if dry_run {
                continue;
            }
            let deleted =
                sqlx::query("DELETE FROM game_snapshot_blobs WHERE game_id=$1 AND revision=$2")
                    .bind(game_id)
                    .bind(revision)
                    .execute(&mut *tx)
                    .await
                    .map_err(CommitError::storage)?;
            if deleted.rows_affected() != 1 {
                return Err(CommitError::Storage);
            }
            sqlx::query(
                "UPDATE game_snapshots
                 SET codec=$3, compressed_size=$4, uncompressed_size=$5,
                     payload_hash=$6
                 WHERE game_id=$1 AND revision=$2 AND payload_retention_status='retained'",
            )
            .bind(game_id)
            .bind(revision)
            .bind(rewritten.codec)
            .bind(rewritten.compressed_size)
            .bind(rewritten.uncompressed_size)
            .bind(&rewritten.payload_hash)
            .execute(&mut *tx)
            .await
            .map_err(CommitError::storage)?;
            sqlx::query(
                "INSERT INTO game_snapshot_blobs
                 (game_id, revision, compressed_size, payload_hash, payload)
                 VALUES ($1,$2,$3,$4,$5)",
            )
            .bind(game_id)
            .bind(revision)
            .bind(rewritten.compressed_size)
            .bind(&rewritten.payload_hash)
            .bind(&rewritten.payload)
            .execute(&mut *tx)
            .await
            .map_err(CommitError::storage)?;
        }
        if dry_run {
            tx.rollback().await.map_err(CommitError::storage)?;
        } else {
            tx.commit().await.map_err(CommitError::storage)?;
        }
        Ok(report)
    }
}
