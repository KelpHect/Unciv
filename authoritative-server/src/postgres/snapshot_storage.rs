use sqlx::{Postgres, Transaction};
use uuid::Uuid;

use super::{CommitError, StoredSnapshot};

pub(super) async fn insert_snapshot(
    tx: &mut Transaction<'_, Postgres>,
    game_id: Uuid,
    revision: i64,
    engine_build: &str,
    manifest_hash: &str,
    stored: &StoredSnapshot,
) -> Result<(), CommitError> {
    sqlx::query(
        "INSERT INTO game_snapshots
         (game_id, revision, engine_build, ruleset_manifest_hash, codec,
          compressed_size, uncompressed_size, canonical_state_hash, payload_hash)
         VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9)",
    )
    .bind(game_id)
    .bind(revision)
    .bind(engine_build)
    .bind(manifest_hash)
    .bind(stored.codec)
    .bind(stored.compressed_size)
    .bind(stored.uncompressed_size)
    .bind(&stored.canonical_state_hash)
    .bind(&stored.payload_hash)
    .execute(&mut **tx)
    .await
    .map_err(CommitError::storage)?;
    sqlx::query(
        "INSERT INTO game_snapshot_blobs
         (game_id, revision, compressed_size, payload_hash, payload)
         VALUES ($1,$2,$3,$4,$5)",
    )
    .bind(game_id)
    .bind(revision)
    .bind(stored.compressed_size)
    .bind(&stored.payload_hash)
    .bind(&stored.payload)
    .execute(&mut **tx)
    .await
    .map_err(CommitError::storage)?;
    Ok(())
}
