#!/bin/sh
set -eu

database_url=${UNCIV_V3_AUDIT_DATABASE_URL:?UNCIV_V3_AUDIT_DATABASE_URL is required}
data_root=${UNCIV_V3_POSTGRES_DATA_ROOT:-/var/lib/unciv-authoritative/postgresql}
wal_root=${UNCIV_V3_WAL_ARCHIVE_ROOT:-/var/backups/unciv-authoritative/wal}
backup_root=${UNCIV_V3_BASE_BACKUP_ROOT:-/var/backups/unciv-authoritative/base}
warn_percent=${UNCIV_V3_CAPACITY_WARN_PERCENT:-80}
critical_percent=${UNCIV_V3_CAPACITY_CRITICAL_PERCENT:-90}
database_warn_bytes=${UNCIV_V3_DATABASE_WARN_BYTES:-10737418240}

case "$warn_percent:$critical_percent:$database_warn_bytes" in
    *[!0-9:]*)
        echo "capacity thresholds must be unsigned integers" >&2
        exit 64
        ;;
esac
if [ "$warn_percent" -lt 1 ] ||
    [ "$critical_percent" -le "$warn_percent" ] ||
    [ "$critical_percent" -gt 99 ] ||
    [ "$database_warn_bytes" -lt 1 ]
then
    echo "capacity thresholds are incoherent" >&2
    exit 64
fi

status=0
filesystem_json=
for entry in "data:$data_root" "wal:$wal_root" "backup:$backup_root"; do
    label=${entry%%:*}
    path=${entry#*:}
    if [ ! -d "$path" ] || [ -L "$path" ]; then
        echo "capacity path is absent or unsafe: $label" >&2
        exit 2
    fi
    set -- $(df -Pk "$path" | tail -n 1)
    total_kb=$2
    used_kb=$3
    available_kb=$4
    used_percent=$5
    used_percent=${used_percent%%%}
    if [ "$used_percent" -ge "$critical_percent" ]; then
        status=2
    elif [ "$used_percent" -ge "$warn_percent" ] && [ "$status" -lt 1 ]; then
        status=1
    fi
    item=$(printf \
        '{"name":"%s","total_kb":%s,"used_kb":%s,"available_kb":%s,"used_percent":%s}' \
        "$label" "$total_kb" "$used_kb" "$available_kb" "$used_percent")
    if [ -n "$filesystem_json" ]; then
        filesystem_json=$filesystem_json,$item
    else
        filesystem_json=$item
    fi
done

database_state=$(psql "$database_url" --no-password --tuples-only --no-align \
    --set ON_ERROR_STOP=1 \
    --command \
    "SELECT pg_database_size(current_database()), (SELECT count(*) FROM game_outbox WHERE delivered_at IS NULL), (SELECT count(*) FROM game_snapshots)")
database_bytes=${database_state%%|*}
remaining=${database_state#*|}
outbox_pending=${remaining%%|*}
snapshot_count=${remaining#*|}
if [ "$database_bytes" -ge "$database_warn_bytes" ] && [ "$status" -lt 1 ]; then
    status=1
fi

case "$status" in
    0) state=ok ;;
    1) state=warning ;;
    *) state=critical ;;
esac
printf \
    '{"status":"%s","filesystems":[%s],"database_bytes":%s,"database_warn_bytes":%s,"outbox_pending":%s,"snapshot_count":%s}\n' \
    "$state" "$filesystem_json" "$database_bytes" "$database_warn_bytes" \
    "$outbox_pending" "$snapshot_count"
exit "$status"
