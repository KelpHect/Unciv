#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 2 ]]; then
  echo "usage: $0 <verified-release-bundle> <unciv-v3-load-binary>" >&2
  exit 2
fi

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
bundle_root="$(cd "$1" && pwd)"
load_binary="$(realpath "$2")"
postgres_image='postgres:19beta2-alpine@sha256:bc62313e826eb44d5f608425b7665962b72820e686da017799e906604bfeb8a5'
java_image='eclipse-temurin:21-jre@sha256:273396ed5998598ed1091e8d72711c2d36980a0e65103859c55a4e977a41ffd3'
prefix="unciv-v3-load-${GITHUB_RUN_ID:-local}-$$"
network="${prefix}-network"
postgres="${prefix}-postgres"
worker="${prefix}-worker"
api="${prefix}-api"
work_root="$(mktemp -d)"
stats_sampler_pid=""

cleanup() {
  if [[ -n "$stats_sampler_pid" ]]; then
    kill "$stats_sampler_pid" >/dev/null 2>&1 || true
    wait "$stats_sampler_pid" >/dev/null 2>&1 || true
  fi
  for container in "$api" "$worker" "$postgres"; do
    if [[ "$container" == unciv-v3-load-* ]]; then
      docker rm --force "$container" >/dev/null 2>&1 || true
    fi
  done
  if [[ "$network" == unciv-v3-load-*-network ]]; then
    docker network rm "$network" >/dev/null 2>&1 || true
  fi
  if [[ "$work_root" == /tmp/tmp.* ]]; then
    rm -rf -- "$work_root"
  fi
}
trap cleanup EXIT

wait_for_postgres() {
  for _ in $(seq 1 120); do
    local logs
    logs="$(docker logs "$postgres" 2>&1 || true)"
    if grep -Fq 'PostgreSQL init process complete; ready for start up.' <<<"$logs" &&
      docker exec "$postgres" pg_isready -U postgres -d unciv_authoritative \
        >/dev/null 2>&1; then
      return
    fi
    sleep 0.5
  done
  docker logs "$postgres" >&2
  echo "PostgreSQL readiness deadline expired" >&2
  exit 1
}

wait_for_container_log() {
  local container="$1"
  local marker="$2"
  for _ in $(seq 1 180); do
    local logs
    logs="$(docker logs "$container" 2>&1 || true)"
    if grep -Fq "$marker" <<<"$logs"; then
      return
    fi
    if [[ "$(docker inspect --format '{{.State.Running}}' "$container" 2>/dev/null)" != true ]]; then
      printf '%s\n' "$logs" >&2
      echo "$container exited before readiness" >&2
      exit 1
    fi
    sleep 0.5
  done
  docker logs "$container" >&2
  echo "$container readiness deadline expired" >&2
  exit 1
}

"$bundle_root/bin/unciv-v3-bundle" verify "$bundle_root" >/dev/null
test -x "$load_binary"
bundle_id="$(jq -er '.bundle_id' "$bundle_root/bundle-manifest.json")"
worker_secret="$(openssl rand -hex 32)"

docker network create --subnet 172.29.77.0/24 "$network" >/dev/null
docker run --detach --name "$postgres" --network "$network" \
  --cpus 0.25 --memory 288m --memory-swap 288m --pids-limit 128 \
  --publish 127.0.0.1::5432 \
  --mount "type=bind,source=${repository_root}/authoritative-server/postgresql,target=/qualification,readonly" \
  --env POSTGRES_PASSWORD=load-only \
  --env POSTGRES_DB=unciv_authoritative \
  "$postgres_image" >/dev/null
wait_for_postgres
postgres_port="$(
  docker port "$postgres" 5432/tcp | sed -E 's/.*:([0-9]+)$/\1/' | head -n 1
)"
docker exec --user postgres "$postgres" psql -v ON_ERROR_STOP=1 \
  --set runtime_password=load-runtime \
  --set migration_password=load-migrate \
  --set backup_password=load-backup \
  --set restore_password=load-restore \
  --set audit_password=load-audit \
  -d postgres -f /qualification/bootstrap-roles.sql >/dev/null
migration_url="postgresql://unciv_migrate:load-migrate@127.0.0.1:${postgres_port}/unciv_authoritative?sslmode=disable"
UNCIV_V3_MIGRATION_DATABASE_URL="$migration_url" \
  "$bundle_root/bin/unciv-v3-migrate" >/dev/null

jq '{
  schema_version: 1,
  engine_build: .engineBuild,
  base_ruleset: {
    name: .baseRuleset.name,
    sha256: .baseRuleset.sha256
  },
  mods: []
}' "$bundle_root/rulesets/manifest.json" >"$work_root/ruleset-policy.json"
UNCIV_V3_DATABASE_URL="$migration_url" \
  "$bundle_root/bin/unciv-v3-rulesets" acquire \
  "$work_root/ruleset-policy.json" \
  "$repository_root/android/assets" \
  "$bundle_root/worker/UncivAuthoritativeWorker.jar" \
  "$work_root/rulesets" --activate >/dev/null

docker run --detach --name "$worker" --network "$network" \
  --cpus 0.65 --memory 512m --memory-swap 512m --pids-limit 64 \
  --publish 127.0.0.1::8080 \
  --workdir /rulesets/active \
  --mount "type=bind,source=${bundle_root},target=/bundle,readonly" \
  --mount "type=bind,source=${work_root}/rulesets,target=/rulesets,readonly" \
  --env UNCIV_ENGINE_WORKER_PORT=43170 \
  --env UNCIV_ENGINE_WORKER_SECRET="$worker_secret" \
  --env UNCIV_V3_RELEASE_BUNDLE_ID="$bundle_id" \
  "$java_image" java -Djava.awt.headless=true -Xms64m -Xmx384m \
  -XX:MaxMetaspaceSize=96m -XX:MaxDirectMemorySize=32m \
  -XX:+ExitOnOutOfMemoryError \
  -jar /bundle/worker/UncivAuthoritativeWorker.jar >/dev/null
wait_for_container_log "$worker" 'Loaded 2 rulesets'

docker run --detach --name "$api" --network "container:${worker}" \
  --cpus 0.10 --memory 192m --memory-swap 192m --pids-limit 64 \
  --mount "type=bind,source=${bundle_root},target=/bundle,readonly" \
  --env UNCIV_V3_RELEASE_BUNDLE_ROOT=/bundle \
  --env UNCIV_V3_RELEASE_BUNDLE_ID="$bundle_id" \
  --env UNCIV_ENGINE_WORKER_JAR=/bundle/worker/UncivAuthoritativeWorker.jar \
  --env UNCIV_ENGINE_WORKER_ADDR=127.0.0.1:43170 \
  --env UNCIV_ENGINE_WORKER_SECRET="$worker_secret" \
  --env UNCIV_V3_DATABASE_URL="postgresql://unciv_runtime:load-runtime@${postgres}:5432/unciv_authoritative?sslmode=disable" \
  --env UNCIV_V3_BIND=0.0.0.0:8080 \
  --env UNCIV_V3_METRICS_BIND=0.0.0.0:9090 \
  --env UNCIV_V3_TRUSTED_PROXY=disabled \
  "$java_image" /bundle/bin/unciv-authoritative-server >/dev/null
wait_for_container_log "$api" 'authoritative API listening'
api_port="$(
  docker port "$worker" 8080/tcp | sed -E 's/.*:([0-9]+)$/\1/' | head -n 1
)"

(
  while true; do
    sampled_at="$(date --iso-8601=ns)"
    docker stats --no-stream --format '{{json .}}' "$postgres" "$worker" "$api" |
      jq -c --arg sampled_at "$sampled_at" '. + {sampled_at: $sampled_at}'
    sleep 1
  done
) >"$work_root/docker-stats.ndjson" &
stats_sampler_pid="$!"

database_bytes_before="$(
  docker exec --user postgres "$postgres" psql -At \
    -d unciv_authoritative -c "SELECT pg_database_size(current_database())"
)"
started="$(date +%s)"
UNCIV_V3_LOAD_BASE_URL="http://127.0.0.1:${api_port}" \
UNCIV_V3_LOAD_SCENARIOS="${UNCIV_V3_LOAD_SCENARIOS:-60}" \
UNCIV_V3_LOAD_CONTENTION="${UNCIV_V3_LOAD_CONTENTION:-8}" \
  timeout 900 "$load_binary" >"$work_root/load-report.json"
elapsed_seconds="$(( $(date +%s) - started ))"
kill "$stats_sampler_pid" >/dev/null 2>&1 || true
wait "$stats_sampler_pid" >/dev/null 2>&1 || true
stats_sampler_pid=""
database_bytes_after="$(
  docker exec --user postgres "$postgres" psql -At \
    -d unciv_authoritative -c "SELECT pg_database_size(current_database())"
)"

jq -e '
  .scenarios >= 1 and
  .committed_commands == .scenarios and
  .expected_stale_conflicts == (.scenarios * (.contention_per_game - 1)) and
  .websocket_notifications >= .committed_commands
' "$work_root/load-report.json" >/dev/null
jq -s -e '
  length >= 3 and
  all(.[]; (.CPUPerc | endswith("%")) and (.MemUsage | contains("/")))
' "$work_root/docker-stats.ndjson" >/dev/null
jq -n \
  --slurpfile load "$work_root/load-report.json" \
  --slurpfile stats "$work_root/docker-stats.ndjson" \
  --arg postgres_image "$postgres_image" \
  --arg java_image "$java_image" \
  --argjson elapsed_seconds "$elapsed_seconds" \
  --argjson database_bytes_before "$database_bytes_before" \
  --argjson database_bytes_after "$database_bytes_after" \
  'def bytes:
     capture("^(?<value>[0-9.]+)(?<unit>[A-Za-z]+)$") |
     (.value | tonumber) *
       (if .unit == "GiB" then 1073741824
        elif .unit == "MiB" then 1048576
        elif .unit == "KiB" then 1024
        elif .unit == "GB" then 1000000000
        elif .unit == "MB" then 1000000
        elif .unit == "kB" then 1000
        elif .unit == "B" then 1
        else error("unsupported docker memory unit: \(.unit)")
        end);
   def cpu: rtrimstr("%") | tonumber;
   def memory_used: split("/")[0] | gsub(" "; "") | bytes;
   ($stats | group_by(.Name) | map({
      container: .[0].Name,
      samples: length,
      peak_cpu_percent: (map(.CPUPerc | cpu) | max),
      peak_memory_bytes: (map(.MemUsage | memory_used) | max)
    })) as $peaks |
   {
    qualified: true,
    resource_budget: {
      cpu_cores: 1.0,
      memory_mib: 992,
      swap: "disabled"
    },
    postgres_image: $postgres_image,
    java_image: $java_image,
    elapsed_seconds: $elapsed_seconds,
    database_bytes_before: $database_bytes_before,
    database_bytes_after: $database_bytes_after,
    database_growth_bytes: ($database_bytes_after - $database_bytes_before),
    load: $load[0],
    peak_container_resources: $peaks,
    container_resource_samples: $stats
  }'
