#!/bin/sh
set -eu

umask 077

database_url=${UNCIV_V3_BACKUP_DATABASE_URL:?UNCIV_V3_BACKUP_DATABASE_URL is required}
backup_root=${UNCIV_V3_BASE_BACKUP_ROOT:?UNCIV_V3_BASE_BACKUP_ROOT is required}

if [ ! -d "$backup_root" ] || [ -L "$backup_root" ]; then
    echo "backup root must be an existing non-symlink directory" >&2
    exit 73
fi

timestamp=$(date -u +%Y%m%dT%H%M%SZ)
final_path=$backup_root/base-$timestamp
if [ -e "$final_path" ] || [ -L "$final_path" ]; then
    echo "backup destination already exists" >&2
    exit 73
fi

staging=$(mktemp -d "$backup_root/.base-$timestamp.XXXXXX")
cleanup() {
    rm -rf -- "$staging"
}
trap cleanup EXIT HUP INT TERM

pg_basebackup \
    --dbname="$database_url" \
    --pgdata="$staging" \
    --format=plain \
    --wal-method=stream \
    --checkpoint=fast \
    --manifest-checksums=SHA256 \
    --no-password \
    --verbose
pg_verifybackup --exit-on-error "$staging"

mv -- "$staging" "$final_path"
trap - EXIT HUP INT TERM
printf '%s\n' "$final_path"
