#!/usr/bin/env bash

set -euo pipefail

die() {
  printf 'Error: %s\n' "$*" >&2
  exit 1
}

: "${EPOCA_VERIFY_APPLICATION_IMAGE:?EPOCA_VERIFY_APPLICATION_IMAGE is required}"
: "${EPOCA_VERIFY_RUNTIME_JDBC_URL:?EPOCA_VERIFY_RUNTIME_JDBC_URL is required}"
: "${EPOCA_VERIFY_RUNTIME_JDBC_USER:?EPOCA_VERIFY_RUNTIME_JDBC_USER is required}"
: "${EPOCA_VERIFY_RUNTIME_JDBC_PASSWORD:?EPOCA_VERIFY_RUNTIME_JDBC_PASSWORD is required}"
: "${EPOCA_VERIFY_RUNTIME_JDBC_LOGIN_EMAIL:?EPOCA_VERIFY_RUNTIME_JDBC_LOGIN_EMAIL is required}"
: "${EPOCA_VERIFY_RUNTIME_JDBC_LOGIN_PASSWORD:?EPOCA_VERIFY_RUNTIME_JDBC_LOGIN_PASSWORD is required}"

command -v docker >/dev/null 2>&1 || die 'docker is required'
command -v curl >/dev/null 2>&1 || die 'curl is required'

if [[ ! "$EPOCA_VERIFY_RUNTIME_JDBC_URL" =~ ^jdbc:postgresql://host[.]docker[.]internal:([0-9]{1,5})/([A-Za-z0-9_]+)$ ]]; then
  die 'EPOCA_VERIFY_RUNTIME_JDBC_URL must target a disposable database through host.docker.internal with no URL options'
fi
database_port=${BASH_REMATCH[1]}
database_name=${BASH_REMATCH[2]}
if [ "$database_port" -lt 1 ] || [ "$database_port" -gt 65535 ]; then
  die 'EPOCA_VERIFY_RUNTIME_JDBC_URL contains an invalid port'
fi
if [[ ! "$database_name" =~ (^|_)stack_.*_gate(_|$) ]]; then
  die 'EPOCA_VERIFY_RUNTIME_JDBC_URL must name a disposable backend verification gate database'
fi

for json_value in "$EPOCA_VERIFY_RUNTIME_JDBC_LOGIN_EMAIL" "$EPOCA_VERIFY_RUNTIME_JDBC_LOGIN_PASSWORD"; do
  case "$json_value" in
    *'"'*|*'\'*|*$'\n'*|*$'\r'*) die 'Runtime JDBC login credentials contain unsupported JSON characters' ;;
  esac
done

docker image inspect "$EPOCA_VERIFY_APPLICATION_IMAGE" >/dev/null 2>&1 || \
  die 'EPOCA_VERIFY_APPLICATION_IMAGE must already exist locally; this gate never pulls an image'

temporary_root=$(mktemp -d "${TMPDIR:-/tmp}/stack-runtime-jdbc.XXXXXX")
container_name="stack-runtime-jdbc-$$-$RANDOM"
container_id=
passed=false

cleanup() {
  local original_status=$?
  local cleanup_status=0
  trap - EXIT
  set +e
  if [ -n "$container_id" ] && [ "$passed" != true ] && \
    docker container inspect "$container_id" >/dev/null 2>&1; then
    printf 'Runtime JDBC container log (last 200 lines):\n' >&2
    docker logs "$container_id" 2>&1 | tail -200 >&2 || true
  fi
  if [ -n "$container_id" ]; then
    docker stop --time 20 "$container_id" >/dev/null 2>&1 || cleanup_status=1
  fi
  rm -rf -- "$temporary_root" || cleanup_status=1
  if [ "$cleanup_status" -ne 0 ]; then
    printf 'Error: Runtime JDBC container cleanup failed\n' >&2
  fi
  if [ "$original_status" -ne 0 ]; then
    exit "$original_status"
  fi
  if [ "$cleanup_status" -eq 0 ] && [ "$passed" = true ]; then
    printf 'Runtime JDBC compatibility GREEN: instrumented application completed a database-backed login\n'
  fi
  exit "$cleanup_status"
}
trap cleanup EXIT

container_id=$(docker run -d --rm --platform linux/amd64 \
  --name "$container_name" \
  --label pl.iterators.epoca.stack.runtime-jdbc=true \
  --add-host host.docker.internal:host-gateway \
  -p 127.0.0.1::9000 \
  -e PORT=9000 \
  -e DB_URL="$EPOCA_VERIFY_RUNTIME_JDBC_URL" \
  -e DB_USER="$EPOCA_VERIFY_RUNTIME_JDBC_USER" \
  -e DB_PASSWORD="$EPOCA_VERIFY_RUNTIME_JDBC_PASSWORD" \
  -e APP_ENVIRONMENT=stack-runtime-jdbc-gate \
  -e LOGIN_ACCESS_TOKEN_VALID_FOR=30m \
  -e KAMON_ENABLED=true \
  -e KAMON_DATADOG_AGENT_ENABLED=false \
  -e KAMON_DATADOG_TRACE_AGENT_ENABLED=false \
  -e KAMON_DATADOG_API_ENABLED=false \
  "$EPOCA_VERIFY_APPLICATION_IMAGE")
[[ "$container_id" =~ ^[0-9a-f]{64}$ ]] || die 'Docker did not return an exact runtime JDBC container ID'

health=
for _ in $(seq 1 90); do
  health=$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' \
    "$container_id" 2>/dev/null || true)
  case "$health" in
    healthy) break ;;
    unhealthy|exited|dead) die "Runtime JDBC container entered state: $health" ;;
  esac
  sleep 2
done
[ "$health" = healthy ] || die 'Runtime JDBC container did not become healthy'

backend_binding=$(docker port "$container_id" 9000/tcp)
if [[ ! "$backend_binding" =~ ^127[.]0[.]0[.]1:([0-9]+)$ ]]; then
  die 'Runtime JDBC container is not bound to loopback only'
fi
backend_port=${BASH_REMATCH[1]}

printf '{"email":"%s","password":"%s"}\n' \
  "$EPOCA_VERIFY_RUNTIME_JDBC_LOGIN_EMAIL" "$EPOCA_VERIFY_RUNTIME_JDBC_LOGIN_PASSWORD" \
  >"$temporary_root/login-request.json"

http_status=$(curl -sS --max-time 45 \
  -o "$temporary_root/login-response.json" \
  -w '%{http_code}' \
  -H 'content-type: application/json' \
  -H 'device-id: 52600000-0000-4000-8000-000000000001' \
  --data-binary "@$temporary_root/login-request.json" \
  "http://127.0.0.1:$backend_port/api/v1/auth/login")

[ "$http_status" = 200 ] || die "Instrumented runtime could not complete a database-backed login: HTTP $http_status"

passed=true
