use super::*;

impl PostgresGameRepository {
    async fn quarantine_corrupt_snapshot(
        &self,
        game_id: Uuid,
        revision: i64,
    ) -> Result<(), CommitError> {
        let mut tx = self.pool.begin().await.map_err(CommitError::storage)?;
        sqlx::query(
            "UPDATE game_snapshots SET validation_status='corrupt' WHERE game_id=$1 AND revision=$2",
        )
        .bind(game_id)
        .bind(revision)
        .execute(&mut *tx)
        .await
        .map_err(CommitError::storage)?;
        sqlx::query(
            "UPDATE games SET unavailable_at=COALESCE(unavailable_at, now()), unavailable_reason=COALESCE(unavailable_reason, 'corrupt_canonical_snapshot') WHERE id=$1",
        )
        .bind(game_id)
        .execute(&mut *tx)
        .await
        .map_err(CommitError::storage)?;
        tx.commit().await.map_err(CommitError::storage)
    }

    pub(super) async fn validated_snapshot(
        &self,
        game_id: Uuid,
        row: &PgRow,
    ) -> Result<String, CommitError> {
        if row.get::<bool, _>("is_unavailable") {
            return Err(CommitError::GameUnavailable);
        }
        let snapshot_revision: i64 = row.get("snapshot_revision");
        let codec: String = row.get("codec");
        let declared_compressed_size: i64 = row.get("compressed_size");
        if codec == "zstd_delta" {
            let archive = sqlx::query(
                "SELECT object_key, object_size, payload_hash AS archive_payload_hash,
                        archive_codec, base_revision, base_state_hash
                 FROM game_snapshot_archives WHERE game_id=$1 AND revision=$2",
            )
            .bind(game_id)
            .bind(snapshot_revision)
            .fetch_optional(&self.pool)
            .await
            .map_err(CommitError::storage)?
            .ok_or(CommitError::RecoveryEvidenceMissing)?;
            let Some(store) = &self.object_store else {
                return Err(CommitError::RecoveryEvidenceMissing);
            };
            let key: String = archive.get("object_key");
            let archive_size: i64 = archive.get("object_size");
            let archive_payload_hash: String = archive.get("archive_payload_hash");
            let archive_codec: String = archive.get("archive_codec");
            let object_payload = store
                .get(&key)
                .await
                .map_err(|_| CommitError::RecoveryEvidenceMissing)?;
            let payload = unarchive_object_payload(&object_payload)?;
            let base_revision: i64 = archive.get("base_revision");
            let base_state_hash: String = archive.get("base_state_hash");
            let base = self
                .canonical_snapshot_at_revision(game_id, base_revision)
                .await?;
            if archive_codec != "delta"
                || object_payload.len() as i64 != archive_size
                || object_payload.len() as i64 != declared_compressed_size
                || state_hash(&object_payload) != archive_payload_hash
                || state_hash(&object_payload) != row.get::<String, _>("payload_hash")
            {
                return Err(CommitError::RecoveryEvidenceMissing);
            }
            let delta = crate::snapshot_delta::SnapshotDelta {
                base_revision: u64::try_from(base_revision)
                    .map_err(|_| CommitError::RecoveryEvidenceMissing)?,
                base_state_hash,
                target_state_hash: row.get("snapshot_state_hash"),
                target_size: u32::try_from(row.get::<i64, _>("uncompressed_size"))
                    .map_err(|_| CommitError::RecoveryEvidenceMissing)?,
                payload,
            };
            let snapshot = delta
                .decode(&base)
                .map_err(|_| CommitError::RecoveryEvidenceMissing)?;
            return String::from_utf8(snapshot).map_err(|_| CommitError::RecoveryEvidenceMissing);
        }
        let object_payload: Vec<u8> = match row.try_get::<Option<Vec<u8>>, _>("payload") {
            Ok(Some(payload)) => payload,
            Ok(None) => {
                self.archived_snapshot_payload(game_id, snapshot_revision)
                    .await?
            }
            Err(_) => row.get("payload"),
        };
        if object_payload.len() as i64 != declared_compressed_size
            || state_hash(&object_payload) != row.get::<String, _>("payload_hash")
        {
            self.quarantine_corrupt_snapshot(game_id, snapshot_revision)
                .await?;
            return Err(CommitError::GameUnavailable);
        }
        let payload = unarchive_object_payload(&object_payload)?;
        let declared_uncompressed_size: i64 = row.get("uncompressed_size");
        let codec: String = row.get("codec");
        let protocol_version: i32 = row.get("snapshot_protocol_version");
        let validation_status: String = row.get("validation_status");
        let payload_hash: String = row.get("payload_hash");
        let snapshot_state_hash: String = row.get("snapshot_state_hash");
        let revision_state_hash: String = row.get("revision_state_hash");
        let payload_hash_valid = payload_hash == state_hash(&object_payload);
        let decoded = decode_snapshot(
            &codec,
            &payload,
            i64::try_from(payload.len()).map_err(|_| CommitError::RecoveryEvidenceMissing)?,
            declared_uncompressed_size,
        );
        let canonical_hash = decoded.as_ref().ok().map(|bytes| state_hash(bytes));
        let valid = protocol_version == i32::from(PROTOCOL_VERSION)
            && validation_status == "valid"
            && payload_hash_valid
            && canonical_hash.as_ref() == Some(&snapshot_state_hash)
            && canonical_hash.as_ref() == Some(&revision_state_hash)
            && decoded
                .as_ref()
                .is_ok_and(|bytes| std::str::from_utf8(bytes).is_ok());
        if !valid {
            self.quarantine_corrupt_snapshot(game_id, snapshot_revision)
                .await?;
            return Err(CommitError::GameUnavailable);
        }
        Ok(
            String::from_utf8(decoded.expect("snapshot codec was validated"))
                .expect("UTF-8 was validated"),
        )
    }

    pub(super) fn canonical_snapshot_at_revision<'a>(
        &'a self,
        game_id: Uuid,
        revision: i64,
    ) -> futures_util::future::BoxFuture<'a, Result<Vec<u8>, CommitError>> {
        self.canonical_snapshot_at_revision_bounded(game_id, revision, 0)
    }

    fn canonical_snapshot_at_revision_bounded<'a>(
        &'a self,
        game_id: Uuid,
        revision: i64,
        depth: u8,
    ) -> futures_util::future::BoxFuture<'a, Result<Vec<u8>, CommitError>> {
        Box::pin(async move {
            if depth > 64 {
                return Err(CommitError::RecoveryEvidenceMissing);
            }
            let row = sqlx::query(
                "SELECT s.codec, s.compressed_size, s.uncompressed_size,
                        s.payload_hash, s.canonical_state_hash,
                        s.protocol_version, s.validation_status,
                        s.payload_retention_status,
                        b.payload, a.object_key, a.archive_codec,
                        a.base_revision, a.base_state_hash
                 FROM game_snapshots s
                 LEFT JOIN game_snapshot_blobs b
                   ON b.game_id=s.game_id AND b.revision=s.revision
                 LEFT JOIN game_snapshot_archives a
                   ON a.game_id=s.game_id AND a.revision=s.revision
                 WHERE s.game_id=$1 AND s.revision=$2",
            )
            .bind(game_id)
            .bind(revision)
            .fetch_optional(&self.pool)
            .await
            .map_err(CommitError::storage)?
            .ok_or(CommitError::RecoveryEvidenceMissing)?;
            let object_payload: Vec<u8> = match row.try_get::<Option<Vec<u8>>, _>("payload") {
                Ok(Some(payload)) => payload,
                Ok(None) => {
                    let Some(store) = &self.object_store else {
                        return Err(CommitError::RecoveryEvidenceMissing);
                    };
                    let key: String = row
                        .try_get("object_key")
                        .map_err(|_| CommitError::RecoveryEvidenceMissing)?;
                    store
                        .get(&key)
                        .await
                        .map_err(|_| CommitError::RecoveryEvidenceMissing)?
                }
                Err(_) => row.get("payload"),
            };
            if object_payload.len() as i64 != row.get::<i64, _>("compressed_size")
                || state_hash(&object_payload) != row.get::<String, _>("payload_hash")
            {
                return Err(CommitError::RecoveryEvidenceMissing);
            }
            let payload = unarchive_object_payload(&object_payload)?;
            let codec: String = row.get("codec");
            let archive_codec: Option<String> = row
                .try_get::<Option<String>, _>("archive_codec")
                .ok()
                .flatten();
            if row.get::<i32, _>("protocol_version") != i32::from(PROTOCOL_VERSION)
                || row.get::<String, _>("validation_status") != "valid"
            {
                return Err(CommitError::RecoveryEvidenceMissing);
            }
            if codec == "zstd_delta" || archive_codec.as_deref() == Some("delta") {
                if codec != "zstd_delta" || archive_codec.as_deref() != Some("delta") {
                    return Err(CommitError::RecoveryEvidenceMissing);
                }
                let base_revision: i64 = row
                    .try_get::<Option<i64>, _>("base_revision")
                    .map_err(|_| CommitError::RecoveryEvidenceMissing)?
                    .ok_or(CommitError::RecoveryEvidenceMissing)?;
                let base_state_hash: String = row
                    .try_get::<Option<String>, _>("base_state_hash")
                    .map_err(|_| CommitError::RecoveryEvidenceMissing)?
                    .ok_or(CommitError::RecoveryEvidenceMissing)?;
                let base = self
                    .canonical_snapshot_at_revision_bounded(game_id, base_revision, depth + 1)
                    .await?;
                let delta = crate::snapshot_delta::SnapshotDelta {
                    base_revision: u64::try_from(base_revision)
                        .map_err(|_| CommitError::RecoveryEvidenceMissing)?,
                    base_state_hash,
                    target_state_hash: row.get("canonical_state_hash"),
                    target_size: u32::try_from(row.get::<i64, _>("uncompressed_size"))
                        .map_err(|_| CommitError::RecoveryEvidenceMissing)?,
                    payload,
                };
                return delta
                    .decode(&base)
                    .map_err(|_| CommitError::RecoveryEvidenceMissing);
            }
            let snapshot = decode_snapshot(
                &codec,
                &payload,
                i64::try_from(payload.len()).map_err(|_| CommitError::RecoveryEvidenceMissing)?,
                row.get("uncompressed_size"),
            )
            .map_err(|_| CommitError::RecoveryEvidenceMissing)?;
            if state_hash(&snapshot) != row.get::<String, _>("canonical_state_hash")
                || row.get::<String, _>("payload_retention_status") == "retained"
                    && archive_codec.is_some()
            {
                return Err(CommitError::RecoveryEvidenceMissing);
            }
            Ok(snapshot)
        })
    }

    async fn archived_snapshot_payload(
        &self,
        game_id: Uuid,
        revision: i64,
    ) -> Result<Vec<u8>, CommitError> {
        let Some(store) = &self.object_store else {
            return Err(CommitError::RecoveryEvidenceMissing);
        };
        let key: Option<String> = sqlx::query_scalar(
            "SELECT object_key FROM game_snapshot_archives WHERE game_id=$1 AND revision=$2",
        )
        .bind(game_id)
        .bind(revision)
        .fetch_optional(&self.pool)
        .await
        .map_err(CommitError::storage)?;
        let key = key.ok_or(CommitError::RecoveryEvidenceMissing)?;
        store
            .get(&key)
            .await
            .map_err(|_| CommitError::RecoveryEvidenceMissing)
    }

    /// Revalidates the canonical head without invoking a worker. Operators and
    /// restore drills can use this to prove stored bytes match every recorded
    /// integrity field. A failure quarantines the game instead of falling back
    /// to client state or silently rewriting history.
    pub async fn validate_canonical_head(&self, game_id: Uuid) -> Result<(), CommitError> {
        let row = sqlx::query(
            "SELECT g.unavailable_at IS NOT NULL AS is_unavailable,
                    r.canonical_state_hash AS revision_state_hash,
                    s.revision AS snapshot_revision, b.payload, s.codec,
                    s.compressed_size, s.uncompressed_size,
                    s.protocol_version AS snapshot_protocol_version,
                    s.validation_status, s.payload_hash,
                    s.canonical_state_hash AS snapshot_state_hash
             FROM games g
             JOIN game_revisions r
               ON r.game_id=g.id AND r.revision=g.head_revision
             JOIN game_snapshots s
               ON s.game_id=g.id AND s.revision=g.head_revision
             LEFT JOIN game_snapshot_blobs b
               ON b.game_id=s.game_id AND b.revision=s.revision
             WHERE g.id=$1",
        )
        .bind(game_id)
        .fetch_optional(&self.pool)
        .await
        .map_err(CommitError::storage)?
        .ok_or(CommitError::NotFound)?;
        self.validated_snapshot(game_id, &row).await.map(|_| ())
    }
}
