#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
REPO_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/../.." && pwd -P)
ACCEPTED_DATA="$REPO_ROOT/scripts/backend-verification/accepted/data.tsv"
ACCEPTED_MIGRATIONS="$REPO_ROOT/scripts/backend-verification/accepted/migrations.sha256"
ACCEPTED_DUMP="$REPO_ROOT/scripts/backend-verification/accepted/pre-refresh.sql.gz"
POSTGRES_IMAGE='postgres@sha256:1adb50e5c24f550a9e68457a2ce60e9e4103dfc43c3b36e98310168165b443a1'
RUN_ID="run_$$_${RANDOM}"
SEEN_ACCEPTED_DATA=false
SEEN_ACCEPTED_MIGRATIONS=false
SEEN_DUMP=false
SEEN_RUN_ID=false

die() {
  printf 'Error: %s\n' "$*" >&2
  exit 1
}

require_option_value() {
  if [ "$#" -lt 2 ] || [ -z "$2" ]; then
    die "Missing value for $1"
  fi
  case "$2" in
    --*) die "Missing value for $1" ;;
  esac
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --accepted-data)
      require_option_value "$@"
      [ "$SEEN_ACCEPTED_DATA" = false ] || die 'Duplicate option: --accepted-data'
      SEEN_ACCEPTED_DATA=true
      ACCEPTED_DATA=$2
      shift 2
      ;;
    --accepted-migrations)
      require_option_value "$@"
      [ "$SEEN_ACCEPTED_MIGRATIONS" = false ] || die 'Duplicate option: --accepted-migrations'
      SEEN_ACCEPTED_MIGRATIONS=true
      ACCEPTED_MIGRATIONS=$2
      shift 2
      ;;
    --dump)
      require_option_value "$@"
      [ "$SEEN_DUMP" = false ] || die 'Duplicate option: --dump'
      SEEN_DUMP=true
      ACCEPTED_DUMP=$2
      shift 2
      ;;
    --run-id)
      require_option_value "$@"
      [ "$SEEN_RUN_ID" = false ] || die 'Duplicate option: --run-id'
      SEEN_RUN_ID=true
      RUN_ID=$2
      shift 2
      ;;
    *) die "Unknown option: $1" ;;
  esac
done

[[ "$RUN_ID" =~ ^[a-z0-9][a-z0-9_]{0,37}$ ]] || \
  die '--run-id must start with a lowercase letter or digit and contain 1-38 lowercase letters, digits, or underscores'
[ -f "$ACCEPTED_DATA" ] || die 'Accepted data snapshot is required'
[ -f "$ACCEPTED_MIGRATIONS" ] || die 'Accepted migration manifest is required'
[ -f "$ACCEPTED_DUMP" ] || die 'Accepted pre-refresh dump is required'
if [ -e "$REPO_ROOT/.jvmopts" ] || [ -L "$REPO_ROOT/.jvmopts" ]; then
  [ -f "$REPO_ROOT/.jvmopts" ] && [ -r "$REPO_ROOT/.jvmopts" ] || \
    die 'Repository .jvmopts cannot be safely inspected'
  if grep -Eq -- '(^|[[:space:]])-Dflyway[.]' "$REPO_ROOT/.jvmopts"; then
    die 'Repository .jvmopts contains forbidden Flyway system properties'
  else
    JVMOPTS_GREP_STATUS=$?
    [ "$JVMOPTS_GREP_STATUS" -eq 1 ] || die 'Repository .jvmopts cannot be safely inspected'
  fi
fi
if [ -e "$REPO_ROOT/.sbtopts" ] || [ -L "$REPO_ROOT/.sbtopts" ]; then
  [ -f "$REPO_ROOT/.sbtopts" ] && [ -r "$REPO_ROOT/.sbtopts" ] || \
    die 'Repository .sbtopts cannot be safely inspected'
  if grep -Eq -- '(^|[[:space:]])(-J)?-Dflyway[.]' "$REPO_ROOT/.sbtopts"; then
    die 'Repository .sbtopts contains forbidden Flyway system properties'
  else
    SBTOPTS_GREP_STATUS=$?
    [ "$SBTOPTS_GREP_STATUS" -eq 1 ] || die 'Repository .sbtopts cannot be safely inspected'
  fi
  if grep -Eq -- '(^|[[:space:]])(-client|--client|-no-server|--no-server|--jvm-client)([[:space:]]|$)' \
    "$REPO_ROOT/.sbtopts"; then
    die 'Repository .sbtopts contains forbidden sbt client options'
  else
    SBTOPTS_GREP_STATUS=$?
    [ "$SBTOPTS_GREP_STATUS" -eq 1 ] || die 'Repository .sbtopts cannot be safely inspected'
  fi
fi
command -v docker >/dev/null 2>&1 || die 'docker is required'
command -v sbt >/dev/null 2>&1 || die 'sbt is required'
command -v shasum >/dev/null 2>&1 || die 'shasum is required'
command -v gzip >/dev/null 2>&1 || die 'gzip is required'

CONTAINER_NAME="epoca-stack-migrations-$RUN_ID"
EMPTY_DATABASE="stack_migration_empty_$RUN_ID"
RESTORED_DATABASE="stack_migration_restored_$RUN_ID"
RUN_LABEL="pl.iterators.epoca.stack.migration-run=$RUN_ID"
EVIDENCE_ROOT="$REPO_ROOT/target/backend-verification/migrations"
mkdir -p "$EVIDENCE_ROOT"
WORK_DIR=$(mktemp -d "$EVIDENCE_ROOT/$RUN_ID.XXXXXXXXXXXXXXXX")
RUN_NONCE=$(printf '%s' "$WORK_DIR" | shasum -a 256 | awk '{print substr($1, 1, 32)}')
[[ "$RUN_NONCE" =~ ^[0-9a-f]{32}$ ]] || die 'Could not create a safe migration invocation nonce'
NONCE_LABEL="pl.iterators.epoca.stack.migration-nonce=$RUN_NONCE"
CID_FILE="$WORK_DIR/container.cid"
CONTROLLED_SBT_ETC_FILE="$WORK_DIR/controlled-sbtopts"
: >"$CONTROLLED_SBT_ETC_FILE"
CONTAINER_CREATE_ATTEMPTED=false
CONTAINER_STARTED=false

cleanup() {
  local original_status=$?
  local cleanup_status=0
  local candidate_id
  local discovered_ids=
  local inspect_record
  local actual_id
  local owner_label
  local run_label
  local invocation_nonce
  local actual_name
  local running
  local owned_container_id=
  local owned_container_running=false
  trap - EXIT INT TERM
  set +e
  if [ "$CONTAINER_CREATE_ATTEMPTED" = true ]; then
    if [ -s "$CID_FILE" ]; then
      candidate_id=$(sed -n '1p' "$CID_FILE")
      if [[ "$candidate_id" =~ ^[0-9a-f]{64}$ ]] && \
        [ "$(wc -c <"$CID_FILE" | tr -d '[:space:]')" = '64' ]; then
        discovered_ids=$candidate_id
      else
        cleanup_status=1
      fi
    fi
    if candidate_id=$(docker ps -a \
      --filter "label=$RUN_LABEL" \
      --filter "label=$NONCE_LABEL" \
      --format '{{.ID}}'); then
      if [ -n "$candidate_id" ]; then
        discovered_ids=$(printf '%s\n%s\n' "$discovered_ids" "$candidate_id" | awk 'NF && !seen[$0]++')
      fi
    else
      cleanup_status=1
    fi

    while IFS= read -r candidate_id; do
      [ -n "$candidate_id" ] || continue
      if ! inspect_record=$(docker inspect --format \
        '{{.Id}}|{{ index .Config.Labels "pl.iterators.epoca.stack.owner" }}|{{ index .Config.Labels "pl.iterators.epoca.stack.migration-run" }}|{{ index .Config.Labels "pl.iterators.epoca.stack.migration-nonce" }}|{{.Name}}|{{.State.Running}}' \
        "$candidate_id"); then
        cleanup_status=1
        continue
      fi
      IFS='|' read -r actual_id owner_label run_label invocation_nonce actual_name running <<<"$inspect_record"
      if [[ "$actual_id" =~ ^[0-9a-f]{64}$ ]] && \
        [ "$owner_label" = migration-harness ] && \
        [ "$run_label" = "$RUN_ID" ] && \
        [ "$invocation_nonce" = "$RUN_NONCE" ] && \
        [ "$actual_name" = "/$CONTAINER_NAME" ]; then
        if [ -n "$owned_container_id" ] && [ "$owned_container_id" != "$actual_id" ]; then
          cleanup_status=1
        else
          owned_container_id=$actual_id
          owned_container_running=$running
        fi
      else
        cleanup_status=1
      fi
    done <<<"$discovered_ids"

    if [ -n "$owned_container_id" ]; then
      if [ "$owned_container_running" = true ]; then
        docker exec "$owned_container_id" psql -X --set ON_ERROR_STOP=1 \
          --username postgres --dbname postgres --command \
          "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname IN ('$EMPTY_DATABASE', '$RESTORED_DATABASE') AND pid <> pg_backend_pid();" \
          >/dev/null 2>&1 || cleanup_status=1
        docker exec "$owned_container_id" psql -X --set ON_ERROR_STOP=1 \
          --username postgres --dbname postgres --command \
          "DROP DATABASE IF EXISTS \"$EMPTY_DATABASE\";" \
          >/dev/null 2>&1 || cleanup_status=1
        docker exec "$owned_container_id" psql -X --set ON_ERROR_STOP=1 \
          --username postgres --dbname postgres --command \
          "DROP DATABASE IF EXISTS \"$RESTORED_DATABASE\";" \
          >/dev/null 2>&1 || cleanup_status=1
      fi
      docker rm --force "$owned_container_id" >/dev/null 2>&1 || cleanup_status=1
    elif [ "$CONTAINER_STARTED" = true ] || [ -s "$CID_FILE" ]; then
      cleanup_status=1
    fi
  fi
  if [ "$cleanup_status" -ne 0 ]; then
    printf 'Error: Failed to clean up the task-owned migration databases or container\n' >&2
    if [ "$original_status" -eq 0 ]; then
      exit "$cleanup_status"
    fi
  fi
  exit "$original_status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

CURRENT_MIGRATIONS="$WORK_DIR/migrations.sha256"
(
  cd "$REPO_ROOT"
  find src/main/resources/db/migration -type f -name '*.sql' -print0 |
    sort -z |
    xargs -0 shasum -a 256
) >"$CURRENT_MIGRATIONS"
# Versioned SQL remains frozen; this reviewed repeatable only switches its schema qualifier.
EXPECTED_MIGRATIONS="$WORK_DIR/expected-migrations.sha256"
sed 's/^319575a43de03f2bb044e319c9a7238c02d3e21d02546470195c870e93c5910e  src\/main\/resources\/db\/migration\/R__add_work_unit_effective_project_index.sql$/f75c0db40b0d4c30a28fdb436508f16c420a5e6c3f518fdf577e21f9f6d99eda  src\/main\/resources\/db\/migration\/R__add_work_unit_effective_project_index.sql/' "$ACCEPTED_MIGRATIONS" >"$EXPECTED_MIGRATIONS"
if ! cmp -s "$EXPECTED_MIGRATIONS" "$CURRENT_MIGRATIONS"; then
  diff -u "$ACCEPTED_MIGRATIONS" "$CURRENT_MIGRATIONS" >&2 || true
  die 'Migration SQL checksums differ from the accepted manifest'
fi
gzip -t "$ACCEPTED_DUMP"

CONTAINER_CREATE_ATTEMPTED=true
docker run --detach \
  --cidfile "$CID_FILE" \
  --name "$CONTAINER_NAME" \
  --label 'pl.iterators.epoca.stack.owner=migration-harness' \
  --label "$RUN_LABEL" \
  --label "$NONCE_LABEL" \
  --env POSTGRES_USER=postgres \
  --env POSTGRES_PASSWORD=postgres \
  --env POSTGRES_DB=postgres \
  --publish 127.0.0.1::5432 \
  "$POSTGRES_IMAGE" >"$WORK_DIR/docker-run.stdout"
CONTAINER_STARTED=true
CONTAINER_ID=$(sed -n '1p' "$CID_FILE")
[[ "$CONTAINER_ID" =~ ^[0-9a-f]{64}$ ]] || die 'Docker did not record an exact migration container ID'

READY=false
for _ in $(seq 1 60); do
  if docker exec "$CONTAINER_NAME" pg_isready --username postgres --dbname postgres >/dev/null 2>&1; then
    READY=true
    break
  fi
  sleep 1
done
[ "$READY" = true ] || die 'PostgreSQL 13.4 did not become ready within 60 seconds'

SERVER_VERSION=$(docker exec "$CONTAINER_NAME" psql -X --no-align --tuples-only \
  --username postgres --dbname postgres --command 'SHOW server_version;' | sed 's/[[:space:]]*$//')
SERVER_VERSION_NUM=$(docker exec "$CONTAINER_NAME" psql -X --no-align --tuples-only \
  --username postgres --dbname postgres --command 'SHOW server_version_num;' | tr -d '[:space:]')
[ "$SERVER_VERSION_NUM" = '130004' ] || die "Expected PostgreSQL 13.4, got $SERVER_VERSION"
RESTART_COUNT=$(docker inspect --format '{{.RestartCount}}' "$CONTAINER_NAME")
[ "$RESTART_COUNT" = '0' ] || die "PostgreSQL container restart count is $RESTART_COUNT"

PUBLISHED_ADDRESS=$(docker port "$CONTAINER_NAME" 5432/tcp)
[[ "$PUBLISHED_ADDRESS" =~ ^127[.]0[.]0[.]1:([0-9]{1,5})$ ]] || \
  die "PostgreSQL must publish exactly one loopback port, got: $PUBLISHED_ADDRESS"
HOST_PORT=${BASH_REMATCH[1]}
JDBC_PREFIX="jdbc:postgresql://127.0.0.1:$HOST_PORT"

docker exec "$CONTAINER_NAME" createdb --username postgres "$EMPTY_DATABASE"
docker exec "$CONTAINER_NAME" createdb --username postgres "$RESTORED_DATABASE"
gzip -dc "$ACCEPTED_DUMP" |
  docker exec --interactive "$CONTAINER_NAME" psql -X --set ON_ERROR_STOP=1 \
    --username postgres --dbname "$RESTORED_DATABASE" >/dev/null

snapshot_database() {
  local database_name=$1
  local output_name=$2
  docker run --rm \
    --label 'pl.iterators.epoca.stack.owner=migration-harness-client' \
    --label "$RUN_LABEL" \
    --label "$NONCE_LABEL" \
    --network "container:$CONTAINER_NAME" \
    --env PGPASSWORD=postgres \
    --volume "$REPO_ROOT:/workspace:ro" \
    --volume "$WORK_DIR:/evidence" \
    "$POSTGRES_IMAGE" \
    bash /workspace/scripts/backend-verification/snapshot-database.sh \
      --url "postgresql://postgres@127.0.0.1:5432/$database_name" \
      --output "/evidence/$output_name"
}

snapshot_lineage() {
  local database_name=$1
  local output_file=$2
  printf 'version\tdescription\tscript\tchecksum\tsuccess\n' >"$output_file"
  docker exec "$CONTAINER_NAME" psql -X --no-align --tuples-only \
    --field-separator $'\t' --set ON_ERROR_STOP=1 \
    --username postgres --dbname "$database_name" --command \
    "SELECT coalesce(version, '<null>'), description, script, coalesce(checksum::text, '<null>'), success::text FROM public.flyway_schema_history ORDER BY installed_rank;" \
    >>"$output_file"
  [ "$(($(wc -l <"$output_file") - 1))" -eq 25 ] || \
    die "Expected exactly 25 Flyway history rows in $database_name"
  if awk -F '\t' 'NR > 1 && $5 != "true" && $5 != "t" { found = 1 } END { exit found ? 0 : 1 }' "$output_file"; then
    die "Flyway history contains an unsuccessful row in $database_name"
  fi
}

run_sbt_flyway() {
  local database_name=$1
  local ignore_pattern=$2
  local phase=$3
  local log_file=$4
  local sbt_expression=$5
  local controlled_sbt_opts='-Xmx4G -Duser.timezone=GMT'
  if [ "$ignore_pattern" = '*:pending' ]; then
    controlled_sbt_opts="$controlled_sbt_opts -Dflyway.ignoreMigrationPatterns=*:pending"
  elif [ "$ignore_pattern" != strict ]; then
    die 'Unknown Flyway ignore-pattern policy'
  fi
  if ! env \
    -u JAVA_TOOL_OPTIONS \
    -u JDK_JAVA_OPTIONS \
    -u _JAVA_OPTIONS \
    -u JAVA_OPTS \
    -u JVM_OPTS \
    -u SBT_NATIVE_CLIENT \
    -u SBTN_CMD \
    DB_URL="$JDBC_PREFIX/$database_name" \
    DB_MIGRATION_BOOTSTRAP=false \
    TEST_DB_MIGRATION_BOOTSTRAP=false \
    DB_USER=postgres \
    DB_PASSWORD=postgres \
    TEST_DB_URL="$JDBC_PREFIX/$database_name" \
    TEST_DB_USER=postgres \
    TEST_DB_PASSWORD=postgres \
    SBT_ETC_FILE="$CONTROLLED_SBT_ETC_FILE" \
    SBT_OPTS="$controlled_sbt_opts" \
    sbt --server "$sbt_expression" >"$log_file" 2>&1; then
    printf 'Error: Flyway command failed during %s; see %s\n' \
      "$phase" "${log_file#"$REPO_ROOT/"}" >&2
    tail -n 80 "$log_file" >&2
    return 1
  fi
}

run_strict_flyway_path() {
  local database_name=$1
  local log_file=$2
  run_sbt_flyway "$database_name" strict 'restored strict validation/migration' \
    "$log_file" 'flywayValidate; flywayMigrate; flywayValidate'
}

run_empty_flyway_path() {
  local database_name=$1
  local preflight_log=$2
  local strict_log=$3
  run_sbt_flyway "$database_name" '*:pending' 'empty preflight' \
    "$preflight_log" 'flywayValidate'
  run_sbt_flyway "$database_name" strict 'empty strict migration/validation' \
    "$strict_log" 'flywayMigrate; flywayValidate'
}

snapshot_database "$RESTORED_DATABASE" restored-before.tsv
if ! cmp -s "$ACCEPTED_DATA" "$WORK_DIR/restored-before.tsv"; then
  diff -u "$ACCEPTED_DATA" "$WORK_DIR/restored-before.tsv" >&2 || true
  die 'Restored data snapshot does not match accepted data snapshot'
fi
snapshot_lineage "$RESTORED_DATABASE" "$WORK_DIR/restored-lineage-before.tsv"

run_empty_flyway_path "$EMPTY_DATABASE" "$WORK_DIR/empty-preflight.log" "$WORK_DIR/empty-flyway.log"
snapshot_lineage "$EMPTY_DATABASE" "$WORK_DIR/empty-lineage-after.tsv"

run_strict_flyway_path "$RESTORED_DATABASE" "$WORK_DIR/restored-flyway.log"
snapshot_database "$RESTORED_DATABASE" restored-after.tsv
snapshot_lineage "$RESTORED_DATABASE" "$WORK_DIR/restored-lineage-after.tsv"

if ! cmp -s "$WORK_DIR/restored-before.tsv" "$WORK_DIR/restored-after.tsv"; then
  diff -u "$WORK_DIR/restored-before.tsv" "$WORK_DIR/restored-after.tsv" >&2 || true
  die 'Flyway changed non-Flyway data in the restored database'
fi
if ! cmp -s "$WORK_DIR/restored-lineage-before.tsv" "$WORK_DIR/restored-lineage-after.tsv"; then
  diff -u "$WORK_DIR/restored-lineage-before.tsv" "$WORK_DIR/restored-lineage-after.tsv" >&2 || true
  die 'Flyway lineage differs after the restored-database migration path'
fi
if ! cmp -s "$WORK_DIR/restored-lineage-before.tsv" "$WORK_DIR/empty-lineage-after.tsv"; then
  diff -u "$WORK_DIR/restored-lineage-before.tsv" "$WORK_DIR/empty-lineage-after.tsv" >&2 || true
  die 'Flyway lineage differs between restored and empty database paths'
fi

printf 'PASS: PostgreSQL %s, 25 successful Flyway rows, empty/restored validation, and exact non-Flyway data parity\n' \
  "$SERVER_VERSION"
