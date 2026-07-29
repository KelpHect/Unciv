#!/bin/sh
set -eu

psql --set=ON_ERROR_STOP=1 \
  --set=runtime_password="$UNCIV_V3_RUNTIME_DB_PASSWORD" \
  --set=migration_password="$UNCIV_V3_MIGRATION_DB_PASSWORD" \
  --set=backup_password="$UNCIV_V3_BACKUP_DB_PASSWORD" \
  --set=restore_password="$UNCIV_V3_RESTORE_DB_PASSWORD" \
  --set=audit_password="$UNCIV_V3_AUDIT_DB_PASSWORD" \
  --dbname="$POSTGRES_DB" \
  --file=/docker-entrypoint-initdb.d/bootstrap-roles.sql
