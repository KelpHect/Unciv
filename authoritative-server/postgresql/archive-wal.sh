#!/bin/sh
set -eu

umask 077

if [ "$#" -ne 2 ]; then
    echo "usage: archive-wal.sh SOURCE_PATH WAL_FILE" >&2
    exit 64
fi

source_path=$1
wal_file=$2
archive_root=${UNCIV_V3_WAL_ARCHIVE_ROOT:?UNCIV_V3_WAL_ARCHIVE_ROOT is required}

if ! printf '%s\n' "$wal_file" |
    grep -Eq '^([0-9A-F]{24}|[0-9A-F]{24}\.[0-9A-F]{8}\.backup|[0-9A-F]{8}\.history)$'
then
    echo "refusing unsafe WAL archive name" >&2
    exit 64
fi

if [ ! -f "$source_path" ] || [ -L "$source_path" ]; then
    echo "WAL source must be a regular non-symlink file" >&2
    exit 66
fi
if [ ! -d "$archive_root" ] || [ -L "$archive_root" ]; then
    echo "WAL archive root must be an existing non-symlink directory" >&2
    exit 73
fi

target=$archive_root/$wal_file
if [ -e "$target" ] || [ -L "$target" ]; then
    if [ -f "$target" ] && [ ! -L "$target" ] && cmp -s "$source_path" "$target"; then
        exit 0
    fi
    echo "refusing to replace a differing or unsafe archived WAL file" >&2
    exit 74
fi

staging=$(mktemp "$archive_root/.wal-$wal_file.XXXXXX")
cleanup() {
    rm -f -- "$staging"
}
trap cleanup EXIT HUP INT TERM

cp -- "$source_path" "$staging"
chmod 0600 "$staging"
if command -v sync >/dev/null 2>&1; then
    sync -f "$staging" 2>/dev/null || sync "$staging" 2>/dev/null || true
fi
mv -- "$staging" "$target"
if command -v sync >/dev/null 2>&1; then
    sync -f "$archive_root" 2>/dev/null || true
fi
trap - EXIT HUP INT TERM
