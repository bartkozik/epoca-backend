#!/usr/bin/env bash

set +x
set -euo pipefail

APP_DIR=/app/epoca
ENV_FILE="$APP_DIR/.env.sh"

die() {
  printf 'Error: %s\n' "$*" >&2
  exit 2
}

[ "${CI_COMMIT_REF_NAME:-}" = feature/CT-532-demo-seed ] || \
  die 'Fingerprint capture is restricted to feature/CT-532-demo-seed'
[ -n "${CI_PROJECT_DIR:-}" ] && [ -d "$CI_PROJECT_DIR" ] || \
  die 'CI_PROJECT_DIR is missing or invalid'
[ -n "${CI_COMMIT_SHA:-}" ] || die 'CI_COMMIT_SHA is missing'
case "$CI_COMMIT_SHA" in
  *[!0-9A-Fa-f]*) die 'CI_COMMIT_SHA is invalid' ;;
esac
[ "${#CI_COMMIT_SHA}" -eq 40 ] || die 'CI_COMMIT_SHA is invalid'
[ -f "$ENV_FILE" ] || die 'Staging environment file is missing'

cd "$APP_DIR"
set +u
set -a
# shellcheck disable=SC1091
. "$ENV_FILE"
set +a
set -u

DB_CONTAINER_ID=$(docker-compose ps -q db | sed -n '1p')
case "$DB_CONTAINER_ID" in
  ''|*[!0-9A-Fa-f]*) die 'No valid running staging compose container was found' ;;
esac
[ "$(docker inspect --format '{{.State.Running}}' "$DB_CONTAINER_ID")" = true ] || \
  die 'Staging database container is not running'

SEED_CONTAINER_DIR="/tmp/ct532-demo-seed-$CI_COMMIT_SHA"
docker exec "$DB_CONTAINER_ID" mkdir -p "$SEED_CONTAINER_DIR"
docker cp "$CI_PROJECT_DIR/scripts/demo-seed/." "$DB_CONTAINER_ID:$SEED_CONTAINER_DIR"

docker exec "$DB_CONTAINER_ID" sh -lc '
  set -eu
  seed_dir=$1
  : "${POSTGRES_DB:?POSTGRES_DB is missing in the database container}"
  : "${POSTGRES_USER:?POSTGRES_USER is missing in the database container}"
  : "${POSTGRES_PASSWORD:?POSTGRES_PASSWORD is missing in the database container}"
  [ "$POSTGRES_DB" = epoca ] || {
    printf "%s\n" "Error: staging database name is not epoca" >&2
    exit 2
  }
  export DB_URL="jdbc:postgresql://127.0.0.1:5432/$POSTGRES_DB"
  export DB_USER="$POSTGRES_USER"
  export DB_PASSWORD="$POSTGRES_PASSWORD"
  exec bash "$seed_dir/capture-staging-fingerprint.sh" \
    --confirm-organization iteratorstesting
' sh "$SEED_CONTAINER_DIR"
