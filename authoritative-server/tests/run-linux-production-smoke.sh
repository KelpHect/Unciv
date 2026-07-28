#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "usage: $0 <verified-release-bundle>" >&2
  exit 2
fi

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
bundle_root="$(cd "$1" && pwd)"
postgres_image='postgres:19beta2-alpine@sha256:bc62313e826eb44d5f608425b7665962b72820e686da017799e906604bfeb8a5'
container="unciv-v3-linux-smoke-${GITHUB_RUN_ID:-local}-$$"
work_root="$(mktemp -d)"
worker_pid=''
api_pid=''

cleanup() {
  if [[ -n "$api_pid" ]]; then
    kill "$api_pid" 2>/dev/null || true
    wait "$api_pid" 2>/dev/null || true
  fi
  if [[ -n "$worker_pid" ]]; then
    kill "$worker_pid" 2>/dev/null || true
    wait "$worker_pid" 2>/dev/null || true
  fi
  if [[ "$container" == unciv-v3-linux-smoke-* ]]; then
    docker rm --force "$container" >/dev/null 2>&1 || true
  fi
  if [[ "$work_root" == /tmp/tmp.* ]]; then
    rm -rf -- "$work_root"
  fi
}
trap cleanup EXIT

free_port() {
  python3 - <<'PY'
import socket
with socket.socket() as listener:
    listener.bind(("127.0.0.1", 0))
    print(listener.getsockname()[1])
PY
}

wait_for_postgres() {
  for _ in $(seq 1 90); do
    local logs
    logs="$(docker logs "$container" 2>&1 || true)"
    if grep -Fq 'PostgreSQL init process complete; ready for start up.' <<<"$logs" &&
      docker exec "$container" pg_isready -U postgres -d unciv_authoritative \
        >/dev/null 2>&1; then
      return
    fi
    sleep 0.5
  done
  docker logs "$container" >&2
  echo "PostgreSQL readiness deadline expired" >&2
  exit 1
}

wait_for_ready() {
  local expected_status="$1"
  local expected_http="$2"
  for _ in $(seq 1 90); do
    local response_file="$work_root/ready.json"
    local status
    status="$(curl --silent --show-error --output "$response_file" \
      --write-out '%{http_code}' "http://127.0.0.1:${api_port}/readyz" || true)"
    if [[ "$status" == "$expected_http" ]] &&
      jq -e --arg expected "$expected_status" '.status == $expected' \
        "$response_file" >/dev/null 2>&1; then
      return
    fi
    if ! kill -0 "$api_pid" 2>/dev/null; then
      cat "$work_root/api.err" >&2
      echo "authoritative API exited before expected readiness" >&2
      exit 1
    fi
    sleep 0.5
  done
  cat "$work_root/api.err" >&2
  echo "authoritative API readiness transition expired" >&2
  exit 1
}

wait_for_worker() {
  for _ in $(seq 1 90); do
    if (echo >/dev/tcp/127.0.0.1/"$worker_port") 2>/dev/null; then
      return
    fi
    if ! kill -0 "$worker_pid" 2>/dev/null; then
      cat "$work_root/worker.err" >&2
      echo "authoritative engine worker exited before accepting connections" >&2
      exit 1
    fi
    sleep 0.5
  done
  cat "$work_root/worker.err" >&2
  echo "authoritative engine worker readiness deadline expired" >&2
  exit 1
}

start_worker() {
  (
    cd "$repository_root/android/assets"
    export UNCIV_ENGINE_WORKER_PORT="$worker_port"
    export UNCIV_ENGINE_WORKER_SECRET="$worker_secret"
    export UNCIV_V3_RELEASE_BUNDLE_ID="$bundle_id"
    export UNCIV_ENGINE_WORKER_SOCKET_TIMEOUT_MS=5000
    export UNCIV_ENGINE_WORKER_COMMAND_TIMEOUT_MS=30000
    exec java -Djava.awt.headless=true -Xms64m -Xmx384m \
      -jar "$bundle_root/worker/UncivAuthoritativeWorker.jar"
  ) >"$work_root/worker.out" 2>"$work_root/worker.err" &
  worker_pid=$!
}

"$bundle_root/bin/unciv-v3-bundle" verify "$bundle_root" >/dev/null
test -x "$bundle_root/bin/unciv-v3-rulesets"
bundle_id="$(jq -er '.bundle_id' "$bundle_root/bundle-manifest.json")"
worker_secret="$(openssl rand -hex 32)"
worker_port="$(free_port)"
api_port="$(free_port)"
metrics_port="$(free_port)"

docker run --detach --rm --name "$container" \
  --publish 127.0.0.1::5432 \
  --mount "type=bind,source=${repository_root}/authoritative-server/postgresql,target=/qualification,readonly" \
  --env POSTGRES_PASSWORD=linux-smoke-only \
  --env POSTGRES_DB=unciv_authoritative \
  "$postgres_image" >/dev/null
wait_for_postgres
postgres_port="$(
  docker port "$container" 5432/tcp | sed -E 's/.*:([0-9]+)$/\1/' | head -n 1
)"
docker exec --user postgres "$container" psql -v ON_ERROR_STOP=1 \
  --set runtime_password=linux-smoke-runtime \
  --set migration_password=linux-smoke-migrate \
  --set backup_password=linux-smoke-backup \
  --set restore_password=linux-smoke-restore \
  --set audit_password=linux-smoke-audit \
  -d postgres -f /qualification/bootstrap-roles.sql >/dev/null
migration_url="postgresql://unciv_migrate:linux-smoke-migrate@127.0.0.1:${postgres_port}/unciv_authoritative?sslmode=disable"
database_url="postgresql://unciv_runtime:linux-smoke-runtime@127.0.0.1:${postgres_port}/unciv_authoritative?sslmode=disable"

UNCIV_V3_MIGRATION_DATABASE_URL="$migration_url" \
  "$bundle_root/bin/unciv-v3-migrate" >/dev/null

start_worker
wait_for_worker
export UNCIV_V3_RELEASE_BUNDLE_ROOT="$bundle_root"
export UNCIV_V3_RELEASE_BUNDLE_ID="$bundle_id"
export UNCIV_ENGINE_WORKER_JAR="$bundle_root/worker/UncivAuthoritativeWorker.jar"
export UNCIV_V3_BIND="127.0.0.1:${api_port}"
export UNCIV_V3_METRICS_BIND="127.0.0.1:${metrics_port}"
export UNCIV_V3_DATABASE_URL="$database_url"
export UNCIV_ENGINE_WORKER_ADDR="127.0.0.1:${worker_port}"
export UNCIV_ENGINE_WORKER_SECRET="$worker_secret"
export UNCIV_V3_TRUSTED_PROXY=disabled
"$bundle_root/bin/unciv-authoritative-server" \
  >"$work_root/api.out" 2>"$work_root/api.err" &
api_pid=$!

wait_for_ready ready 200
jq -e '
  .status == "ready" and
  .postgres == "ready" and
  .engine_worker == "ready"
' "$work_root/ready.json" >/dev/null
curl --fail --silent --show-error "http://127.0.0.1:${api_port}/healthz" |
  jq -e '.status == "ok" and .protocol_version == 3' >/dev/null
register_status="$(
  curl --silent --show-error --output "$work_root/register.json" \
    --write-out '%{http_code}' \
    --header 'Content-Type: application/json' \
    --data '{"username":"linux-smoke","password":"Linux-Smoke-Only-Password-47!"}' \
    "http://127.0.0.1:${api_port}/api/v3/auth/register"
)"
test "$register_status" = 201

kill "$worker_pid"
wait "$worker_pid" 2>/dev/null || true
worker_pid=''
wait_for_ready unready 503
jq -e '
  .postgres == "ready" and
  .engine_worker == "unready"
' "$work_root/ready.json" >/dev/null

start_worker
wait_for_worker
wait_for_ready ready 200

jq -n \
  --arg bundle_id "$bundle_id" \
  --arg postgres_image "$postgres_image" \
  '{
    qualified: true,
    release_bundle_id: $bundle_id,
    postgres_image: $postgres_image,
    health: "ok",
    initial_readiness: "ready",
    worker_failure_readiness: "unready",
    worker_restart_readiness: "ready",
    registration: "created"
  }'
