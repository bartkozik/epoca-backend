#!/usr/bin/env bash

set -euo pipefail

TEST_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
REPO_ROOT=$(CDPATH= cd -- "$TEST_DIR/../../.." && pwd -P)
MIGRATION_DIR="$REPO_ROOT/src/main/resources/db/migration"
RUNNER="$REPO_ROOT/scripts/demo-seed/run.sh"
DB_USER_VALUE=postgres
DB_PASSWORD_VALUE=postgres
CASE_PREFIX=epoca_ct532_case_
CASE_COUNTER=0
CREATED_DATABASES=
TEMP_DIRECTORIES=
CURRENT_CASE_CHILD_PIDS=
CURRENT_CASE_UNREAPED_CHILD_PIDS=
ADVISORY_HOLDER_CONTROL_OPEN=false
RELATION_HOLDER_CONTROL_OPEN=false
TIMEOUT_RELATION_HOLDER_CONTROL_OPEN=false
PROJECT_CLEANUP_WRITER_CONTROL_OPEN=false
PROJECT_CLEANUP_RELATION_HOLDER_CONTROL_OPEN=false
MAINTENANCE_DB=postgres
INTEGRATION_SQL=false
SQL_HOST=${PGHOST:-127.0.0.1}
SQL_PORT=${PGPORT:-5432}
MIGRATION_LIST=
CLEANUP_APPLICATION_NAME_REGEX='^ct532_(advisory_holder|blocked_apply|blocked_dry_run|blocked_verify|cleanup_blocked_seed|cleanup_blocked_update|cleanup_blocked_writer|cleanup_lock_owner_seed|cleanup_relation_holder|cleanup_update_holder|cleanup_update_seed|cleanup_writer|lock_owner_verify|relation_holder|timeout_relation_holder|timeout_verify)$'

fail() {
  printf 'FAIL: %s\n' "$*" >&2
  exit 1
}

validate_integration_sql_environment() {
  [ "${PGHOST-}" = postgres ] || fail 'integration SQL requires PGHOST=postgres'
  [ "${PGPORT-}" = 5432 ] || fail 'integration SQL requires PGPORT=5432'
  [ "${PGUSER-}" = postgres ] || fail 'integration SQL requires PGUSER=postgres'
  [ "${PGPASSWORD-}" = postgres ] || fail 'integration SQL requires PGPASSWORD=postgres'
  [ "${PGDATABASE-}" = postgres ] || fail 'integration SQL requires PGDATABASE=postgres'
  [ "$MAINTENANCE_DB" = postgres ] || fail 'integration SQL requires maintenance database postgres'
  [ "$CASE_PREFIX" = epoca_ct532_case_ ] || fail 'integration SQL requires the epoca_ct532_case_ disposable prefix'

  for libpq_selector in \
    PGHOSTADDR PGSERVICE PGSERVICEFILE PGPASSFILE PGOPTIONS PGAPPNAME \
    PGCONNECT_TIMEOUT PGCLIENTENCODING PGSSLMODE PGREQUIRESSL \
    PGSSLCOMPRESSION PGSSLCERT PGSSLKEY PGSSLROOTCERT PGSSLCRL \
    PGSSLCRLDIR PGCHANNELBINDING PGREQUIREPEER PGGSSENCMODE \
    PGKRBSRVNAME PGGSSLIB PGTARGETSESSIONATTRS PGREALM; do
    eval "libpq_selector_is_set=\${$libpq_selector+x}"
    [ "$libpq_selector_is_set" != x ] || \
      fail "integration SQL requires $libpq_selector to be unset"
  done
}

for initial_arg in "$@"; do
  if [ "$initial_arg" = --integration-sql ]; then
    INTEGRATION_SQL=true
  fi
done

if [ "$INTEGRATION_SQL" = true ]; then
  validate_integration_sql_environment
fi

psql_command() {
  PGPASSWORD="$DB_PASSWORD_VALUE" command psql \
    --host="$SQL_HOST" --port="$SQL_PORT" --username="$DB_USER_VALUE" "$@"
}

createdb_command() {
  PGPASSWORD="$DB_PASSWORD_VALUE" command createdb \
    --host="$SQL_HOST" --port="$SQL_PORT" --username="$DB_USER_VALUE" "$@"
}

dropdb_command() {
  PGPASSWORD="$DB_PASSWORD_VALUE" command dropdb \
    --host="$SQL_HOST" --port="$SQL_PORT" --username="$DB_USER_VALUE" "$@"
}

validate_migration_catalog() {
  migration_candidates=$(find "$MIGRATION_DIR" -maxdepth 1 -type f -name '*.sql' -print | LC_ALL=C sort)
  [ -n "$migration_candidates" ] || fail 'no Flyway migrations found'

  while IFS= read -r migration_path; do
    migration_name=${migration_path##*/}
    if ! [[ "$migration_name" =~ ^V[1-9][0-9]*__.+[.]sql$ || "$migration_name" =~ ^R__.+[.]sql$ ]]; then
      fail "unsupported Flyway migration filename: $migration_name"
    fi
  done <<EOF
$migration_candidates
EOF

  versioned_migrations=$(printf '%s\n' "$migration_candidates" | awk -F/ '$NF ~ /^V[1-9][0-9]*__.+[.]sql$/ { print }' | sort -V)
  repeatable_migrations=$(printf '%s\n' "$migration_candidates" | awk -F/ '$NF ~ /^R__.+[.]sql$/ { print }' | LC_ALL=C sort)
  MIGRATION_LIST=$(printf '%s\n%s\n' "$versioned_migrations" "$repeatable_migrations" | sed '/^$/d')
}

validate_migration_catalog

cleanup() {
  cleanup_status=$?

  if [ "$ADVISORY_HOLDER_CONTROL_OPEN" = true ]; then
    printf 'SELECT pg_catalog.pg_advisory_unlock(%s);\n\\q\n' \
      "pg_catalog.hashtextextended('CT-532:iteratorstesting', 0)" >&8 2>/dev/null || true
    exec 8>&- 2>/dev/null || true
    ADVISORY_HOLDER_CONTROL_OPEN=false
  fi
  if [ "$RELATION_HOLDER_CONTROL_OPEN" = true ]; then
    printf '%s\n' 'ROLLBACK;' '\q' >&7 2>/dev/null || true
    exec 7>&- 2>/dev/null || true
    RELATION_HOLDER_CONTROL_OPEN=false
  fi
  if [ "$TIMEOUT_RELATION_HOLDER_CONTROL_OPEN" = true ]; then
    printf '%s\n' 'ROLLBACK;' '\q' >&6 2>/dev/null || true
    exec 6>&- 2>/dev/null || true
    TIMEOUT_RELATION_HOLDER_CONTROL_OPEN=false
  fi
  if [ "$PROJECT_CLEANUP_WRITER_CONTROL_OPEN" = true ]; then
    printf '%s\n' 'ROLLBACK;' '\q' >&5 2>/dev/null || true
    exec 5>&- 2>/dev/null || true
    PROJECT_CLEANUP_WRITER_CONTROL_OPEN=false
  fi
  if [ "$PROJECT_CLEANUP_RELATION_HOLDER_CONTROL_OPEN" = true ]; then
    printf '%s\n' 'ROLLBACK;' '\q' >&4 2>/dev/null || true
    exec 4>&- 2>/dev/null || true
    PROJECT_CLEANUP_RELATION_HOLDER_CONTROL_OPEN=false
  fi

  for cleanup_child_pid in $CURRENT_CASE_UNREAPED_CHILD_PIDS; do
    if is_current_case_child "$cleanup_child_pid" && is_unreaped_current_case_child "$cleanup_child_pid"; then
      wait_for_background_bounded "$cleanup_child_pid" /dev/null \
        'cleanup of current-case child' 40 >/dev/null 2>&1 || true
    fi
  done

  for cleanup_database in $CREATED_DATABASES; do
    case "$cleanup_database" in
      ${CASE_PREFIX}[0-9]*_[0-9]*) ;;
      *)
        printf 'REFUSED CLEANUP: invalid database name %s\n' "$cleanup_database" >&2
        continue
        ;;
    esac
    if ! [[ "$cleanup_database" =~ ^epoca_ct532_case_[0-9]+_[0-9]+$ ]]; then
      printf 'REFUSED CLEANUP: invalid database name %s\n' "$cleanup_database" >&2
      continue
    fi
    cleanup_attempt=0
    cleanup_activity_count=unknown
    while [ "$cleanup_attempt" -lt 40 ]; do
      if cleanup_activity_count=$(psql_command -X --set=ON_ERROR_STOP=1 \
          --dbname="$MAINTENANCE_DB" --no-align --tuples-only --quiet --command \
          "SELECT pg_catalog.count(*)
             FROM pg_catalog.pg_stat_activity
            WHERE datname = '$cleanup_database'
              AND backend_type = 'client backend'
              AND application_name ~ '$CLEANUP_APPLICATION_NAME_REGEX'
              AND pid <> pg_catalog.pg_backend_pid();" 2>/dev/null) && \
          [ "$cleanup_activity_count" = 0 ]; then
        break
      fi
      cleanup_attempt=$((cleanup_attempt + 1))
      sleep 0.05
    done

    if [ "$cleanup_activity_count" != 0 ]; then
      psql_command -X --set=ON_ERROR_STOP=1 --dbname="$MAINTENANCE_DB" \
        --quiet --command \
        "SELECT pg_catalog.pg_terminate_backend(pid)
           FROM pg_catalog.pg_stat_activity
          WHERE datname = '$cleanup_database'
            AND backend_type = 'client backend'
            AND application_name ~ '$CLEANUP_APPLICATION_NAME_REGEX'
            AND pid <> pg_catalog.pg_backend_pid();" >/dev/null 2>&1 || true
    fi

    cleanup_attempt=0
    cleanup_activity_count=unknown
    while [ "$cleanup_attempt" -lt 40 ]; do
      if cleanup_activity_count=$(psql_command -X --set=ON_ERROR_STOP=1 \
          --dbname="$MAINTENANCE_DB" --no-align --tuples-only --quiet --command \
          "SELECT pg_catalog.count(*)
             FROM pg_catalog.pg_stat_activity
            WHERE datname = '$cleanup_database'
              AND backend_type = 'client backend';" 2>/dev/null) && \
          [ "$cleanup_activity_count" = 0 ]; then
        break
      fi
      cleanup_attempt=$((cleanup_attempt + 1))
      sleep 0.05
    done
    if [ "$cleanup_activity_count" = 0 ]; then
      dropdb_command --if-exists --maintenance-db="$MAINTENANCE_DB" \
        "$cleanup_database" >/dev/null 2>&1 || true
    else
      printf 'REFUSED CLEANUP: database %s still has client backends\n' "$cleanup_database" >&2
    fi
  done
  for cleanup_directory in $TEMP_DIRECTORIES; do
    case "$cleanup_directory" in
      /tmp/epoca_ct532_lock.*) rm -rf "$cleanup_directory" ;;
      *) printf 'REFUSED CLEANUP: invalid temporary directory %s\n' "$cleanup_directory" >&2 ;;
    esac
  done
  exit "$cleanup_status"
}

trap cleanup EXIT HUP INT TERM

if [ "$INTEGRATION_SQL" = false ] && \
  psql_command -X --set=ON_ERROR_STOP=1 --dbname=postgres \
  --no-align --tuples-only --quiet \
  --command "SELECT 1 FROM pg_database WHERE datname = 'epoca_ct532';" | grep -qx 1; then
  MAINTENANCE_DB=epoca_ct532
fi

new_database() {
  CASE_COUNTER=$((CASE_COUNTER + 1))
  NEW_DATABASE="${CASE_PREFIX}$$_${CASE_COUNTER}"
  if ! [[ "$NEW_DATABASE" =~ ^epoca_ct532_case_[0-9]+_[0-9]+$ ]]; then
    fail "generated unsafe database name: $NEW_DATABASE"
  fi
  createdb_command --maintenance-db="$MAINTENANCE_DB" --template=template0 "$NEW_DATABASE"
  CREATED_DATABASES="$CREATED_DATABASES $NEW_DATABASE"
}

clone_database() {
  source_database=$1
  CASE_COUNTER=$((CASE_COUNTER + 1))
  NEW_DATABASE="${CASE_PREFIX}$$_${CASE_COUNTER}"
  if ! [[ "$NEW_DATABASE" =~ ^epoca_ct532_case_[0-9]+_[0-9]+$ ]]; then
    fail "generated unsafe database name: $NEW_DATABASE"
  fi
  createdb_command --maintenance-db="$MAINTENANCE_DB" \
    --template="$source_database" "$NEW_DATABASE"
  CREATED_DATABASES="$CREATED_DATABASES $NEW_DATABASE"
}

psql_case() {
  database=$1
  shift
  psql_command -X --set=ON_ERROR_STOP=1 --dbname="$database" "$@"
}

wait_for_query_value() {
  database=$1
  expected_value=$2
  query=$3
  label=$4
  attempt=0
  while [ "$attempt" -lt 100 ]; do
    actual_value=$(query_value "$database" "$query")
    if [ "$actual_value" = "$expected_value" ]; then
      return 0
    fi
    attempt=$((attempt + 1))
    sleep 0.05
  done
  fail "$label did not become observable within the bounded polling window (expected '$expected_value', got '$actual_value')"
}

register_current_case_child() {
  CURRENT_CASE_CHILD_PIDS="$CURRENT_CASE_CHILD_PIDS $1"
  CURRENT_CASE_UNREAPED_CHILD_PIDS="$CURRENT_CASE_UNREAPED_CHILD_PIDS $1"
}

is_current_case_child() {
  candidate_pid=$1
  case " $CURRENT_CASE_CHILD_PIDS " in
    *" $candidate_pid "*) return 0 ;;
    *) return 1 ;;
  esac
}

is_unreaped_current_case_child() {
  candidate_pid=$1
  case " $CURRENT_CASE_UNREAPED_CHILD_PIDS " in
    *" $candidate_pid "*) return 0 ;;
    *) return 1 ;;
  esac
}

mark_current_case_child_reaped() {
  reaped_pid=$1
  remaining_pids=
  for candidate_pid in $CURRENT_CASE_UNREAPED_CHILD_PIDS; do
    if [ "$candidate_pid" != "$reaped_pid" ]; then
      remaining_pids="$remaining_pids $candidate_pid"
    fi
  done
  CURRENT_CASE_UNREAPED_CHILD_PIDS=$remaining_pids
}

is_running_shell_child() {
  candidate_pid=$1
  is_current_case_child "$candidate_pid" || return 1
  is_unreaped_current_case_child "$candidate_pid" || return 1
  for running_pid in $(jobs -pr); do
    if [ "$running_pid" = "$candidate_pid" ]; then
      kill -0 "$candidate_pid" 2>/dev/null
      return $?
    fi
  done
  return 1
}

wait_for_background_bounded() {
  process_id=$1
  output_file=$2
  label=$3
  poll_limit=$4

  if ! is_current_case_child "$process_id" || ! is_unreaped_current_case_child "$process_id"; then
    printf 'REFUSED SIGNAL: %s PID %s is not an unreaped child of the current test case\n' \
      "$label" "$process_id" >&2
    return 125
  fi

  poll_attempt=0
  timed_out=false
  while is_running_shell_child "$process_id"; do
    if [ "$poll_attempt" -ge "$poll_limit" ]; then
      timed_out=true
      break
    fi
    poll_attempt=$((poll_attempt + 1))
    sleep 0.05
  done

  if [ "$timed_out" = true ]; then
    if is_running_shell_child "$process_id"; then
      kill -TERM "$process_id"
    fi
    reap_attempt=0
    while is_running_shell_child "$process_id" && [ "$reap_attempt" -lt 20 ]; do
      reap_attempt=$((reap_attempt + 1))
      sleep 0.05
    done
    if is_running_shell_child "$process_id"; then
      kill -KILL "$process_id"
    fi
    reap_attempt=0
    while is_running_shell_child "$process_id" && [ "$reap_attempt" -lt 20 ]; do
      reap_attempt=$((reap_attempt + 1))
      sleep 0.05
    done
    if is_running_shell_child "$process_id"; then
      printf '%s\n' "$(sed -n '1,160p' "$output_file")" >&2
      printf 'FAIL: %s could not be reaped after bounded TERM/KILL cleanup\n' "$label" >&2
      return 126
    fi
  fi

  # The child is no longer running, so this reap cannot block.
  if wait "$process_id"; then
    process_status=0
  else
    process_status=$?
  fi
  mark_current_case_child_reaped "$process_id"

  if [ "$timed_out" = true ]; then
    return 124
  fi
  return "$process_status"
}

wait_for_background_success() {
  process_id=$1
  output_file=$2
  label=$3
  if wait_for_background_bounded "$process_id" "$output_file" "$label" 300; then
    process_status=0
  else
    process_status=$?
  fi
  if [ "$process_status" -ne 0 ]; then
    printf '%s\n' "$(sed -n '1,160p' "$output_file")" >&2
    fail "$label exited with status $process_status"
  fi
}

apply_migrations() {
  database=$1
  for migration in $MIGRATION_LIST; do
    # This psql-only harness uses public; substitute Flyway's schema placeholder
    # in the input stream while keeping the immutable migration files untouched.
    sed 's/${flyway:defaultSchema}/public/g' "$migration" |
      psql_case "$database" --single-transaction --file - >/dev/null
  done
}

run_local_seed_sql() {
  database=$1
  operation=$2
  apply=$3
  seed_today=$4
  read_tcp_database_identity "$database"
  psql_command -X --set=ON_ERROR_STOP=1 --dbname="$database" \
    --set 'target=local' \
    --set "operation=$operation" \
    --set 'organization_slug=iteratorstesting' \
    --set "seed_today=$seed_today" \
    --set 'demo_viewer_email=' \
    --set 'expected_organization_id=' \
    --set "expected_database_name=$identity_database" \
    --set "expected_system_identifier=$identity_system_identifier" \
    --set "expected_server_address=$identity_server_address" \
    --set "expected_server_port=$identity_server_port" \
    --set 'sql_test_mode=true' \
    --set 'test_force_verification_failure=false' \
    --set 'standalone_verify=false' \
    --set "apply=$apply" \
    --file "$REPO_ROOT/scripts/demo-seed/seed.sql"
}

run_local_verify_sql() {
  database=$1
  seed_today=$2
  read_tcp_database_identity "$database"
  psql_command -X --set=ON_ERROR_STOP=1 --dbname="$database" \
    --set 'target=local' \
    --set 'operation=verify' \
    --set 'organization_slug=iteratorstesting' \
    --set "seed_today=$seed_today" \
    --set 'demo_viewer_email=' \
    --set 'expected_organization_id=' \
    --set "expected_database_name=$identity_database" \
    --set "expected_system_identifier=$identity_system_identifier" \
    --set "expected_server_address=$identity_server_address" \
    --set "expected_server_port=$identity_server_port" \
    --set 'sql_test_mode=true' \
    --set 'test_force_verification_failure=false' \
    --set 'standalone_verify=true' \
    --file "$REPO_ROOT/scripts/demo-seed/verify.sql"
}

run_apply_today() {
  database=$1
  seed_today=$2
  if [ "$INTEGRATION_SQL" = true ]; then
    run_local_seed_sql "$database" apply true "$seed_today"
  else
    DB_URL="jdbc:postgresql://127.0.0.1:5432/$database" \
      DB_USER="$DB_USER_VALUE" \
      DB_PASSWORD="$DB_PASSWORD_VALUE" \
      "$RUNNER" local --apply --confirm-local-database "$database" --today "$seed_today"
  fi
}

run_apply() {
  run_apply_today "$1" 2026-05-18
}

run_dry_run_today() {
  database=$1
  seed_today=$2
  if [ "$INTEGRATION_SQL" = true ]; then
    run_local_seed_sql "$database" dry-run false "$seed_today"
  else
    DB_URL="jdbc:postgresql://127.0.0.1:5432/$database" \
      DB_USER="$DB_USER_VALUE" \
      DB_PASSWORD="$DB_PASSWORD_VALUE" \
      "$RUNNER" local --dry-run --today "$seed_today"
  fi
}

run_dry_run() {
  run_dry_run_today "$1" 2026-05-18
}

run_seed_sql_test() {
  database=$1
  target=$2
  sql_test_mode=$3
  force_verification_failure=$4
  read_tcp_database_identity "$database"
  psql_command -X --set=ON_ERROR_STOP=1 --dbname="$database" \
    --set "target=$target" \
    --set 'operation=apply' \
    --set 'organization_slug=iteratorstesting' \
    --set 'seed_today=2026-06-15' \
    --set 'demo_viewer_email=' \
    --set 'expected_organization_id=' \
    --set "expected_database_name=$identity_database" \
    --set "expected_system_identifier=$identity_system_identifier" \
    --set "expected_server_address=$identity_server_address" \
    --set "expected_server_port=$identity_server_port" \
    --set "sql_test_mode=$sql_test_mode" \
    --set "test_force_verification_failure=$force_verification_failure" \
    --set 'standalone_verify=false' \
    --set 'apply=true' \
    --file "$REPO_ROOT/scripts/demo-seed/seed.sql"
}

run_standalone_verify_today() {
  database=$1
  seed_today=$2
  if [ "$INTEGRATION_SQL" = true ]; then
    run_local_verify_sql "$database" "$seed_today"
  else
    DB_URL="jdbc:postgresql://127.0.0.1:5432/$database" \
      DB_USER="$DB_USER_VALUE" \
      DB_PASSWORD="$DB_PASSWORD_VALUE" \
      "$RUNNER" verify local --today "$seed_today"
  fi
}

run_standalone_verify() {
  run_standalone_verify_today "$1" 2026-05-18
}

run_apply_with_pgoptions() {
  database=$1
  pgoptions=$2
  if [ "$INTEGRATION_SQL" = true ]; then
    PGOPTIONS="$pgoptions" run_local_seed_sql "$database" apply true 2026-05-18
  else
    PGOPTIONS="$pgoptions" \
      DB_URL="jdbc:postgresql://127.0.0.1:5432/$database" \
      DB_USER="$DB_USER_VALUE" \
      DB_PASSWORD="$DB_PASSWORD_VALUE" \
      "$RUNNER" local --apply --confirm-local-database "$database" --today 2026-05-18
  fi
}

run_verify_with_pgoptions() {
  database=$1
  pgoptions=$2
  if [ "$INTEGRATION_SQL" = true ]; then
    PGOPTIONS="$pgoptions" run_local_verify_sql "$database" 2026-05-18
  else
    PGOPTIONS="$pgoptions" \
      DB_URL="jdbc:postgresql://127.0.0.1:5432/$database" \
      DB_USER="$DB_USER_VALUE" \
      DB_PASSWORD="$DB_PASSWORD_VALUE" \
      "$RUNNER" verify local --today 2026-05-18
  fi
}

query_value_with_pgoptions() {
  database=$1
  pgoptions=$2
  query=$3
  PGOPTIONS="$pgoptions" psql_command -X --set=ON_ERROR_STOP=1 --dbname="$database" \
    --no-align --tuples-only --quiet --command "$query"
}

read_tcp_database_identity() {
  database=$1
  database_identity=$(psql_command -X --set=ON_ERROR_STOP=1 \
    --dbname="$database" --no-align --tuples-only --quiet --command \
    "SELECT pg_catalog.current_database()::pg_catalog.text || '|' ||
            (pg_catalog.pg_control_system()).system_identifier::pg_catalog.text || '|' ||
            pg_catalog.host(pg_catalog.inet_server_addr()) || '|' ||
            pg_catalog.inet_server_port()::pg_catalog.text;")
  IFS='|' read -r identity_database identity_system_identifier identity_server_address identity_server_port <<<"$database_identity"
}

run_staging_seed() {
  database=$1
  operation=$2
  apply=$3
  expected_organization_id=$4
  viewer_email=$5
  organization_slug=$6
  read_tcp_database_identity "$database"
  psql_command -X --set=ON_ERROR_STOP=1 --dbname="$database" \
    --set 'target=staging' \
    --set "operation=$operation" \
    --set "organization_slug=$organization_slug" \
    --set 'seed_today=2026-05-18' \
    --set "demo_viewer_email=$viewer_email" \
    --set "expected_organization_id=$expected_organization_id" \
    --set "expected_database_name=$identity_database" \
    --set "expected_system_identifier=$identity_system_identifier" \
    --set "expected_server_address=$identity_server_address" \
    --set "expected_server_port=$identity_server_port" \
    --set 'sql_test_mode=false' \
    --set 'standalone_verify=false' \
    --set "apply=$apply" \
    --file "$REPO_ROOT/scripts/demo-seed/seed.sql"
}

run_staging_verify() {
  database=$1
  expected_organization_id=$2
  viewer_email=$3
  organization_slug=$4
  read_tcp_database_identity "$database"
  psql_command -X --set=ON_ERROR_STOP=1 --dbname="$database" \
    --set 'target=staging' \
    --set 'operation=verify' \
    --set "organization_slug=$organization_slug" \
    --set 'seed_today=2026-05-18' \
    --set "demo_viewer_email=$viewer_email" \
    --set "expected_organization_id=$expected_organization_id" \
    --set "expected_database_name=$identity_database" \
    --set "expected_system_identifier=$identity_system_identifier" \
    --set "expected_server_address=$identity_server_address" \
    --set "expected_server_port=$identity_server_port" \
    --set 'sql_test_mode=false' \
    --set 'standalone_verify=true' \
    --file "$REPO_ROOT/scripts/demo-seed/verify.sql"
}

query_value() {
  database=$1
  query=$2
  psql_case "$database" --no-align --tuples-only --quiet --command "$query"
}

assert_query_eq() {
  database=$1
  expected=$2
  query=$3
  label=$4
  actual=$(query_value "$database" "$query")
  if [ "$actual" != "$expected" ]; then
    fail "$label (expected '$expected', got '$actual')"
  fi
}

assert_change_plan_eq() {
  output=$1
  entity=$2
  operation=$3
  expected=$4
  label=$5
  actual=$(printf '%s\n' "$output" | awk -F '|' -v wanted_entity="$entity" -v wanted_operation="$operation" '
    {
      for (field_index = 1; field_index <= NF; field_index++) {
        gsub(/^[[:space:]]+|[[:space:]]+$/, "", $field_index)
      }
      if ($1 == wanted_entity && $2 == wanted_operation) print $3
    }
  ')
  if [ "$actual" != "$expected" ]; then
    fail "$label (expected change plan '$expected', got '$actual')"
  fi
}

assert_change_plan_positive() {
  output=$1
  entity=$2
  operation=$3
  label=$4
  actual=$(printf '%s\n' "$output" | awk -F '|' -v wanted_entity="$entity" -v wanted_operation="$operation" '
    {
      for (field_index = 1; field_index <= NF; field_index++) {
        gsub(/^[[:space:]]+|[[:space:]]+$/, "", $field_index)
      }
      if ($1 == wanted_entity && $2 == wanted_operation) print $3
    }
  ')
  case "$actual" in
    ''|*[!0-9]*) fail "$label (expected a positive integer change plan, got '$actual')" ;;
    0) fail "$label (expected a positive integer change plan, got zero)" ;;
  esac
}

assert_summary_field_eq() {
  local output=$1
  local field_name=$2
  local expected=$3
  local label=$4
  local actual
  actual=$(printf '%s\n' "$output" | awk -F '|' -v wanted_field="$field_name" '
    function trim(value) {
      gsub(/^[[:space:]]+|[[:space:]]+$/, "", value)
      return value
    }
    {
      for (field_index = 1; field_index <= NF; field_index++) {
        if (trim($field_index) == wanted_field) {
          wanted_index = field_index
          getline
          getline
          print trim($wanted_index)
          exit
        }
      }
    }
  ')
  if [ "$actual" != "$expected" ]; then
    fail "$label (expected summary '$expected', got '$actual')"
  fi
}

snapshot_core() {
  local database=$1
  query_value "$database" "
    SELECT entity || '|' || row_id || '|' || pg_catalog.md5(row_json)
    FROM (
      SELECT 'organization'::TEXT AS entity,
             row_value.id::TEXT AS row_id,
             pg_catalog.row_to_json(row_value)::TEXT AS row_json
      FROM (
        SELECT id, name, slug, created_at, created_by, modified_at, modified_by, archived_at
        FROM organization
        WHERE slug = 'iteratorstesting'
      ) row_value
      UNION ALL
      SELECT 'user', row_value.id::TEXT, pg_catalog.row_to_json(row_value)::TEXT
      FROM (
        SELECT users.id, users.first_name, users.last_name, users.avatar, users.color,
               users.is_super_admin, users.created_at, users.modified_at, users.archived_at
        FROM \"user\" users
        WHERE EXISTS (
          SELECT 1
          FROM user_identity identity
          WHERE identity.user_id = users.id
            AND identity.value LIKE 'demo+%@epoca.example.invalid'
        )
      ) row_value
      UNION ALL
      SELECT 'identity', row_value.id::TEXT, pg_catalog.row_to_json(row_value)::TEXT
      FROM (
        SELECT identity.id, identity.user_id, identity.type, identity.value, identity.main,
               identity.device_id, identity.confirmed, identity.confirmation_code, identity.created_at
        FROM user_identity identity
        WHERE identity.value LIKE 'demo+%@epoca.example.invalid'
      ) row_value
      UNION ALL
      SELECT 'membership', row_value.id::TEXT, pg_catalog.row_to_json(row_value)::TEXT
      FROM (
        SELECT membership.id, membership.organization_id, membership.user_id,
               membership.roles, membership.archived_at
        FROM organization_user membership
        JOIN organization organization_row ON organization_row.id = membership.organization_id
        WHERE organization_row.slug = 'iteratorstesting'
          AND EXISTS (
            SELECT 1
            FROM user_identity identity
            WHERE identity.user_id = membership.user_id
              AND identity.value LIKE 'demo+%@epoca.example.invalid'
          )
      ) row_value
      UNION ALL
      SELECT 'client', row_value.id::TEXT, pg_catalog.row_to_json(row_value)::TEXT
      FROM (
        SELECT client_row.*
        FROM client client_row
        JOIN organization organization_row ON organization_row.id = client_row.organization_id
        WHERE organization_row.slug = 'iteratorstesting'
      ) row_value
      UNION ALL
      SELECT 'project', row_value.id::TEXT, pg_catalog.row_to_json(row_value)::TEXT
      FROM (
        SELECT project_row.*
        FROM project project_row
        JOIN organization organization_row ON organization_row.id = project_row.organization_id
        WHERE organization_row.slug = 'iteratorstesting'
      ) row_value
      UNION ALL
      SELECT 'project_user', row_value.id::TEXT, pg_catalog.row_to_json(row_value)::TEXT
      FROM (
        SELECT membership.*
        FROM project_user membership
        JOIN project project_row ON project_row.id = membership.project_id
        JOIN organization organization_row ON organization_row.id = project_row.organization_id
        WHERE organization_row.slug = 'iteratorstesting'
      ) row_value
      UNION ALL
      SELECT 'work_unit', row_value.id::TEXT, pg_catalog.row_to_json(row_value)::TEXT
      FROM (
        SELECT work_unit_row.*
        FROM work_unit work_unit_row
        JOIN organization organization_row ON organization_row.id = work_unit_row.organization_id
        WHERE organization_row.slug = 'iteratorstesting'
      ) row_value
    ) core_rows
    ORDER BY entity COLLATE \"C\", row_id COLLATE \"C\";"
}

assert_core_snapshot_contract() {
  local snapshot=$1
  local label=$2
  local invalid_line
  local sorted_snapshot
  invalid_line=$(printf '%s\n' "$snapshot" | awk -F '|' '
    NF != 3 || $1 !~ /^(organization|user|identity|membership|client|project|project_user|work_unit)$/ ||
      $2 !~ /^[0-9a-f-]+$/ || length($3) != 32 || $3 !~ /^[0-9a-f]+$/ {
        if (!found) print
        found = 1
      }
  ')
  [ -z "$invalid_line" ] || \
    fail "$label must contain only entity, row id, and stable row digest"
  sorted_snapshot=$(printf '%s\n' "$snapshot" | LC_ALL=C sort -t '|' -k1,1 -k2,2)
  [ "$snapshot" = "$sorted_snapshot" ] || \
    fail "$label must be ordered with bytewise C collation"
}

assert_core_snapshot_unchanged() {
  local before_snapshot=$1
  local after_snapshot=$2
  local label=$3
  local snapshot_directory
  [ "$before_snapshot" != "$after_snapshot" ] || return 0

  snapshot_directory=$(mktemp -d "/tmp/epoca_ct532_lock.XXXXXX")
  TEMP_DIRECTORIES="$TEMP_DIRECTORIES $snapshot_directory"
  printf '%s\n' "$before_snapshot" >"$snapshot_directory/before"
  printf '%s\n' "$after_snapshot" >"$snapshot_directory/after"
  diff -u "$snapshot_directory/before" "$snapshot_directory/after" \
    >"$snapshot_directory/diff" || true
  printf 'Changed core snapshot rows (entity|row_id|row_digest):\n' >&2
  sed -n '1,80p' "$snapshot_directory/diff" >&2
  fail "$label"
}

snapshot_rows() {
  database=$1
  query_value "$database" "
    SELECT entity || '|' || COALESCE(digest, 'empty')
    FROM (
      SELECT 'organization' AS entity, md5(COALESCE(string_agg(row_to_json(row_value)::text, E'\\n' ORDER BY row_value.id), '')) AS digest
      FROM (SELECT * FROM organization) row_value
      UNION ALL
      SELECT 'user', md5(COALESCE(string_agg(row_to_json(row_value)::text, E'\\n' ORDER BY row_value.id), ''))
      FROM (SELECT * FROM \"user\") row_value
      UNION ALL
      SELECT 'identity', md5(COALESCE(string_agg(row_to_json(row_value)::text, E'\\n' ORDER BY row_value.id), ''))
      FROM (SELECT * FROM user_identity) row_value
      UNION ALL
      SELECT 'membership', md5(COALESCE(string_agg(row_to_json(row_value)::text, E'\\n' ORDER BY row_value.id), ''))
      FROM (SELECT * FROM organization_user) row_value
      UNION ALL
      SELECT 'client', md5(COALESCE(string_agg(row_to_json(row_value)::text, E'\\n' ORDER BY row_value.id), ''))
      FROM (SELECT * FROM client) row_value
      UNION ALL
      SELECT 'project', md5(COALESCE(string_agg(row_to_json(row_value)::text, E'\\n' ORDER BY row_value.id), ''))
      FROM (SELECT * FROM project) row_value
      UNION ALL
      SELECT 'project_user', md5(COALESCE(string_agg(row_to_json(row_value)::text, E'\\n' ORDER BY row_value.id), ''))
      FROM (SELECT * FROM project_user) row_value
      UNION ALL
      SELECT 'work_unit', md5(COALESCE(string_agg(row_to_json(row_value)::text, E'\\n' ORDER BY row_value.id), ''))
      FROM (SELECT * FROM work_unit) row_value
      UNION ALL
      SELECT 'work_unit_idempotency', md5(COALESCE(string_agg(row_to_json(row_value)::text, E'\\n' ORDER BY row_value.claim_id), ''))
      FROM (SELECT * FROM work_unit_idempotency) row_value
      UNION ALL
      SELECT 'password', md5(COALESCE(string_agg(row_to_json(row_value)::text, E'\\n' ORDER BY row_value.user_identity_id), ''))
      FROM (SELECT * FROM user_password) row_value
      UNION ALL
      SELECT 'login_token', md5(COALESCE(string_agg(row_to_json(row_value)::text, E'\\n' ORDER BY row_value.id), ''))
      FROM (SELECT * FROM user_login_token) row_value
      UNION ALL
      SELECT 'refresh_token', md5(COALESCE(string_agg(row_to_json(row_value)::text, E'\\n' ORDER BY row_value.user_id, row_value.device_id), ''))
      FROM (SELECT * FROM refresh_token) row_value
      UNION ALL
      SELECT 'invitation', md5(COALESCE(string_agg(row_to_json(row_value)::text, E'\\n' ORDER BY row_value.id), ''))
      FROM (SELECT * FROM invitation) row_value
    ) snapshots
    ORDER BY entity;"
}

snapshot_foreign_table() {
  local database=$1
  local table_name=$2
  local output_path=$3
  local snapshot_query
  case "$table_name" in
    user)
      snapshot_query='SELECT row_to_json(t)::text FROM (SELECT users.* FROM public."user" users WHERE NOT EXISTS (SELECT 1 FROM demo_seed_user_catalog catalog WHERE catalog.user_id = users.id)) t ORDER BY t.id'
      ;;
    user_identity)
      snapshot_query='SELECT row_to_json(t)::text FROM (SELECT identity.* FROM public.user_identity identity WHERE NOT EXISTS (SELECT 1 FROM demo_seed_user_catalog catalog WHERE catalog.identity_id = identity.id)) t ORDER BY t.id'
      ;;
    organization)
      snapshot_query='SELECT row_to_json(t)::text FROM (SELECT organization_row.* FROM public.organization organization_row WHERE NOT EXISTS (SELECT 1 FROM demo_seed_catalog_context catalog WHERE catalog.organization_id = organization_row.id)) t ORDER BY t.id'
      ;;
    organization_user)
      snapshot_query='SELECT row_to_json(t)::text FROM (SELECT membership.* FROM public.organization_user membership WHERE NOT EXISTS (SELECT 1 FROM demo_seed_user_catalog catalog WHERE catalog.membership_id = membership.id)) t ORDER BY t.id'
      ;;
    client)
      snapshot_query='SELECT row_to_json(t)::text FROM (SELECT client_row.* FROM public.client client_row WHERE NOT EXISTS (SELECT 1 FROM demo_seed_client_catalog catalog WHERE catalog.client_id = client_row.id)) t ORDER BY t.id'
      ;;
    project)
      snapshot_query='SELECT row_to_json(t)::text FROM (SELECT project_row.* FROM public.project project_row WHERE NOT EXISTS (SELECT 1 FROM demo_seed_project_catalog catalog WHERE catalog.project_id = project_row.id)) t ORDER BY t.id'
      ;;
    project_user)
      snapshot_query='SELECT row_to_json(t)::text FROM (SELECT membership.* FROM public.project_user membership WHERE NOT EXISTS (SELECT 1 FROM demo_seed_project_user_catalog catalog WHERE catalog.project_user_id = membership.id)) t ORDER BY t.id'
      ;;
    work_unit)
      snapshot_query="SELECT row_to_json(t)::text FROM (SELECT work_unit_row.* FROM public.work_unit work_unit_row WHERE NOT EXISTS (SELECT 1 FROM demo_seed_user_catalog user_catalog CROSS JOIN demo_seed_catalog_context context JOIN demo_seed_generator_catalog generator ON generator.generator_name = 'work-unit' CROSS JOIN LATERAL pg_catalog.generate_series(1, generator.max_slot) slot(logical_slot) WHERE work_unit_row.user_id = user_catalog.user_id AND work_unit_row.id = public.uuid_generate_v5(context.namespace_uuid, 'work-unit:v' || generator.version::text || ':' || user_catalog.logical_key || ':' || work_unit_row.date::text || ':' || slot.logical_slot::text))) t ORDER BY t.id"
      ;;
    user_password)
      snapshot_query='SELECT row_to_json(t)::text FROM (SELECT password.* FROM public.user_password password WHERE NOT EXISTS (SELECT 1 FROM demo_seed_user_catalog catalog WHERE catalog.identity_id = password.user_identity_id)) t ORDER BY t.user_identity_id'
      ;;
    user_login_token)
      snapshot_query='SELECT row_to_json(t)::text FROM (SELECT token.* FROM public.user_login_token token JOIN public.user_identity identity ON identity.id = token.user_identity_id WHERE NOT EXISTS (SELECT 1 FROM demo_seed_user_catalog catalog WHERE catalog.user_id = identity.user_id)) t ORDER BY t.id'
      ;;
    refresh_token)
      snapshot_query='SELECT row_to_json(t)::text FROM (SELECT token.* FROM public.refresh_token token WHERE NOT EXISTS (SELECT 1 FROM demo_seed_user_catalog catalog WHERE catalog.user_id = token.user_id)) t ORDER BY t.user_id, t.device_id'
      ;;
    invitation)
      snapshot_query='SELECT row_to_json(t)::text FROM (SELECT * FROM public.invitation) t ORDER BY t.id'
      ;;
    work_unit_idempotency)
      snapshot_query='SELECT row_to_json(t)::text FROM (SELECT * FROM public.work_unit_idempotency) t ORDER BY t.claim_id'
      ;;
    *) fail "unsupported foreign snapshot table: $table_name" ;;
  esac

  {
    printf '%s\n' 'BEGIN;'
    printf '%s\n' 'SET LOCAL client_min_messages = warning;'
    printf '\\ir %s\n' "$REPO_ROOT/scripts/demo-seed/catalog.sql"
    printf '\\copy (%s) TO STDOUT\n' "$snapshot_query"
    printf '%s\n' 'ROLLBACK;'
  } | PGOPTIONS='-cclient_min_messages=warning' psql_case "$database" --quiet --file=- >"$output_path"
  shasum -a 256 "$output_path" | awk '{print $1}' >"$output_path.sha256"
}

snapshot_foreign_rows() {
  local database=$1
  local snapshot_directory=$2
  local snapshot_table
  mkdir -p "$snapshot_directory"
  for snapshot_table in user user_identity organization organization_user client project project_user work_unit \
      user_password user_login_token refresh_token invitation work_unit_idempotency; do
    snapshot_foreign_table "$database" "$snapshot_table" "$snapshot_directory/$snapshot_table.rows"
  done
}

assert_foreign_snapshots_equal() {
  local expected_directory=$1
  local actual_directory=$2
  local label=$3
  local snapshot_table
  local expected_hash
  local actual_hash
  for snapshot_table in user user_identity organization organization_user client project project_user work_unit \
      user_password user_login_token refresh_token invitation work_unit_idempotency; do
    expected_hash=$(cat "$expected_directory/$snapshot_table.rows.sha256")
    actual_hash=$(cat "$actual_directory/$snapshot_table.rows.sha256")
    [ "$expected_hash" = "$actual_hash" ] || fail "$label changed foreign $snapshot_table SHA-256"
    cmp -s "$expected_directory/$snapshot_table.rows" "$actual_directory/$snapshot_table.rows" || \
      fail "$label changed foreign $snapshot_table bytes"
  done
}

expect_verify_failure_unchanged() {
  database=$1
  expected_message=$2
  label=$3
  before_snapshot=$(snapshot_rows "$database")
  set +e
  failure_output=$(run_standalone_verify "$database" 2>&1)
  failure_status=$?
  set -e
  if [ "$failure_status" -eq 0 ]; then
    fail "$label standalone verification unexpectedly succeeded"
  fi
  case "$failure_output" in
    *"$expected_message"*) ;;
    *) fail "$label standalone verification returned the wrong non-secret diagnostic (expected '$expected_message')" ;;
  esac
  after_snapshot=$(snapshot_rows "$database")
  if [ "$before_snapshot" != "$after_snapshot" ]; then
    fail "$label standalone verification changed persistent rows"
  fi
  assert_query_eq "$database" 0 \
    "SELECT count(*)
       FROM pg_catalog.pg_locks
      WHERE locktype = 'advisory'
        AND database = (
            SELECT oid FROM pg_catalog.pg_database WHERE datname = pg_catalog.current_database()
        );" \
    "$label SQL-error connection close releases the session advisory lock"
  printf 'PASS: %s (standalone verify rejected without writes)\n' "$label"
}

expect_verify_failure_unchanged_today() {
  database=$1
  seed_today=$2
  expected_message=$3
  label=$4
  before_snapshot=$(snapshot_rows "$database")
  set +e
  failure_output=$(run_standalone_verify_today "$database" "$seed_today" 2>&1)
  failure_status=$?
  set -e
  if [ "$failure_status" -eq 0 ]; then
    fail "$label standalone verification unexpectedly succeeded"
  fi
  case "$failure_output" in
    *"$expected_message"*) ;;
    *) fail "$label standalone verification returned the wrong non-secret diagnostic (expected '$expected_message')" ;;
  esac
  after_snapshot=$(snapshot_rows "$database")
  if [ "$before_snapshot" != "$after_snapshot" ]; then
    fail "$label standalone verification changed persistent rows"
  fi
  assert_query_eq "$database" 0 \
    "SELECT count(*)
       FROM pg_catalog.pg_locks
      WHERE locktype = 'advisory'
        AND database = (
            SELECT oid FROM pg_catalog.pg_database WHERE datname = pg_catalog.current_database()
        );" \
    "$label SQL-error connection close releases the session advisory lock"
  printf 'PASS: %s (standalone verify rejected without writes)\n' "$label"
}

assert_core_distribution() {
  database=$1
  expected_seed_members=$2
  assert_query_eq "$database" 30 \
    "SELECT count(DISTINCT ou.user_id) FROM organization_user ou JOIN organization o ON o.id = ou.organization_id JOIN \"user\" u ON u.id = ou.user_id WHERE o.slug = 'iteratorstesting' AND o.archived_at IS NULL AND ou.archived_at IS NULL AND u.archived_at IS NULL;" \
    'thirty unique active members'
  assert_query_eq "$database" 'Employee=22,External=4,Manager=2,Owner=2' \
    "SELECT string_agg(role || '=' || role_count, ',' ORDER BY role) FROM (SELECT roles[1] AS role, count(*)::text AS role_count FROM organization_user ou JOIN organization o ON o.id = ou.organization_id JOIN \"user\" u ON u.id = ou.user_id WHERE o.slug = 'iteratorstesting' AND o.archived_at IS NULL AND ou.archived_at IS NULL AND u.archived_at IS NULL GROUP BY roles[1]) counts;" \
    'exact active role distribution'
  assert_query_eq "$database" "$expected_seed_members" \
    "SELECT count(*) FROM organization_user ou JOIN organization o ON o.id = ou.organization_id JOIN \"user\" u ON u.id = ou.user_id WHERE o.slug = 'iteratorstesting' AND o.archived_at IS NULL AND ou.archived_at IS NULL AND u.archived_at IS NULL AND EXISTS (SELECT 1 FROM user_identity ui WHERE ui.user_id = u.id AND ui.value LIKE 'demo+%@epoca.example.invalid');" \
    'exact active seed member count'
}

assert_project_seed_state() {
  database=$1
  assert_query_eq "$database" '10|2|8|8|2' \
    "WITH expected(logical_key, expected_type, expected_archived) AS (
       VALUES
         ('atelier-ops','Internal',false), ('epoca-labs','Internal',false),
         ('northstar-health','External',false), ('lumen-retail','External',false),
         ('verdant-mobility','External',false), ('harbor-finance','External',false),
         ('aurora-learning','External',false), ('pulse-commerce','External',false),
         ('orbit-logistics','External',true), ('ember-hospitality','External',true)
     ), owned AS (
       SELECT c.*, expected.expected_type, expected.expected_archived
       FROM expected
       JOIN client c ON c.id = public.uuid_generate_v5(
         '2f2ed0a0-4d42-5f32-8f49-5d36cc3a0532'::uuid,
         'client:' || expected.logical_key
       )
       JOIN organization o ON o.id = c.organization_id AND o.slug = 'iteratorstesting'
     )
     SELECT count(*) || '|' || count(*) FILTER (WHERE type = 'Internal') || '|' ||
            count(*) FILTER (WHERE type = 'External') || '|' ||
            count(*) FILTER (WHERE archived_at IS NULL) || '|' ||
            count(*) FILTER (WHERE archived_at IS NOT NULL)
     FROM owned
     WHERE type = expected_type
       AND (archived_at IS NOT NULL) = expected_archived;" \
    'exact seed client counts, types, parents, and statuses'

  assert_query_eq "$database" '16|13|3' \
    "WITH expected(project_key, client_key, project_name, expected_archived, team_size) AS (
       VALUES
         ('delivery-operations-hub','atelier-ops','Delivery Operations Hub',false,8),
         ('design-system-evolution','atelier-ops','Design System Evolution',false,6),
         ('time-intelligence','epoca-labs','Time Intelligence',false,10),
         ('reporting-sandbox','epoca-labs','Reporting Sandbox',false,5),
         ('patient-portal','northstar-health','Patient Portal',false,12),
         ('care-analytics','northstar-health','Care Analytics',false,7),
         ('omnichannel-checkout','lumen-retail','Omnichannel Checkout',false,9),
         ('retail-insights','lumen-retail','Retail Insights',false,5),
         ('fleet-companion','verdant-mobility','Fleet Companion',false,8),
         ('mobility-discovery','verdant-mobility','Mobility Discovery',true,4),
         ('risk-dashboard','harbor-finance','Risk Dashboard',false,6),
         ('open-banking-integration','harbor-finance','Open Banking Integration',false,11),
         ('learning-experience','aurora-learning','Learning Experience',false,7),
         ('commerce-growth','pulse-commerce','Commerce Growth',false,5),
         ('dispatch-optimizer','orbit-logistics','Dispatch Optimizer',true,6),
         ('guest-journey','ember-hospitality','Guest Journey',true,3)
     ), matched AS (
       SELECT p.*, expected.expected_archived, expected.team_size
       FROM expected
       JOIN project p ON p.id = public.uuid_generate_v5(
         '2f2ed0a0-4d42-5f32-8f49-5d36cc3a0532'::uuid,
         'project:' || expected.project_key
       )
       JOIN client c ON c.id = p.client_id
                    AND c.id = public.uuid_generate_v5(
                      '2f2ed0a0-4d42-5f32-8f49-5d36cc3a0532'::uuid,
                      'client:' || expected.client_key
                    )
       JOIN organization o ON o.id = p.organization_id
                          AND o.id = c.organization_id
                          AND o.slug = 'iteratorstesting'
       WHERE p.name = expected.project_name
         AND p.description IS NOT NULL
         AND p.description <> ''
         AND (p.archived_at IS NOT NULL) = expected.expected_archived
     )
     SELECT count(*) || '|' || count(*) FILTER (WHERE NOT expected_archived) || '|' ||
            count(*) FILTER (WHERE expected_archived)
     FROM matched;" \
    'exact project-to-client relationships and statuses'

  assert_query_eq "$database" 0 \
    "WITH expected(project_key, team_size) AS (
       VALUES
         ('delivery-operations-hub',8), ('design-system-evolution',6),
         ('time-intelligence',10), ('reporting-sandbox',5),
         ('patient-portal',12), ('care-analytics',7),
         ('omnichannel-checkout',9), ('retail-insights',5),
         ('fleet-companion',8), ('mobility-discovery',4),
         ('risk-dashboard',6), ('open-banking-integration',11),
         ('learning-experience',7), ('commerce-growth',5),
         ('dispatch-optimizer',6), ('guest-journey',3)
     ), seed_projects AS (
       SELECT p.id, c.type, p.archived_at, expected.team_size
       FROM expected
       JOIN project p ON p.id = public.uuid_generate_v5(
         '2f2ed0a0-4d42-5f32-8f49-5d36cc3a0532'::uuid,
         'project:' || expected.project_key
       )
       JOIN client c ON c.id = p.client_id
     ), invalid AS (
       SELECT seed_projects.id
       FROM seed_projects
       LEFT JOIN project_user pu ON pu.project_id = seed_projects.id
       LEFT JOIN organization_user ou ON ou.user_id = pu.user_id AND ou.archived_at IS NULL
       GROUP BY seed_projects.id, seed_projects.type, seed_projects.archived_at, seed_projects.team_size
       HAVING count(*) FILTER (WHERE pu.archived_at IS NULL) <> CASE WHEN seed_projects.archived_at IS NULL THEN seed_projects.team_size ELSE 0 END
          OR count(*) FILTER (WHERE pu.archived_at IS NOT NULL) <> CASE WHEN seed_projects.archived_at IS NULL THEN 0 ELSE seed_projects.team_size END
          OR count(*) FILTER (WHERE pu.archived_at IS NULL AND pu.role = 'Owner') < CASE WHEN seed_projects.archived_at IS NULL THEN 1 ELSE 0 END
          OR count(*) FILTER (WHERE pu.archived_at IS NULL AND ou.roles[1] = 'External' AND seed_projects.type <> 'External') > 0
     )
     SELECT count(*) FROM invalid;" \
    'exact active and archived project team sizes, owners, and External restrictions'

  assert_query_eq "$database" 0 \
    "SELECT count(*)
     FROM organization_user ou
     JOIN organization o ON o.id = ou.organization_id AND o.slug = 'iteratorstesting'
     JOIN user_identity ui ON ui.user_id = ou.user_id AND ui.value LIKE 'demo+%@epoca.example.invalid'
     WHERE ou.archived_at IS NULL
       AND NOT EXISTS (
         SELECT 1 FROM project_user pu
         JOIN project p ON p.id = pu.project_id AND p.archived_at IS NULL
         JOIN client c ON c.id = p.client_id AND c.archived_at IS NULL
         WHERE pu.user_id = ou.user_id AND pu.archived_at IS NULL
       );" \
    'every active seed user has an active primary project'

  assert_query_eq "$database" 0 \
    "SELECT count(*) FROM (
       SELECT pu.user_id
       FROM project_user pu
       JOIN project p ON p.id = pu.project_id AND p.archived_at IS NULL
       JOIN client c ON c.id = p.client_id AND c.archived_at IS NULL
       JOIN organization_user ou ON ou.user_id = pu.user_id AND ou.organization_id = p.organization_id AND ou.archived_at IS NULL
       WHERE pu.archived_at IS NULL AND ou.roles = ARRAY['External']::text[]
       GROUP BY pu.user_id
       HAVING count(*) > 2 OR bool_or(c.type <> 'External')
    ) invalid_external;" \
    'External seed users have at most two active external-client projects'

  assert_query_eq "$database" 0 \
    "SELECT count(*) FROM (
       SELECT pu.project_id
       FROM project_user pu
       JOIN project p ON p.id = pu.project_id AND p.archived_at IS NULL
       JOIN organization_user ou
         ON ou.user_id = pu.user_id
        AND ou.organization_id = p.organization_id
        AND ou.archived_at IS NULL
       JOIN \"user\" u ON u.id = pu.user_id AND u.archived_at IS NULL
       WHERE pu.archived_at IS NULL
         AND ou.roles = ARRAY['External']::TEXT[]
       GROUP BY pu.project_id
       HAVING count(*) > 2
     ) invalid_project;" \
    'each active project has at most two active External members'

  assert_query_eq "$database" 0 \
    "SELECT count(*)
     FROM project_user pu
     JOIN project p ON p.id = pu.project_id
     JOIN organization_user ou ON ou.user_id = pu.user_id AND ou.organization_id = p.organization_id AND ou.archived_at IS NULL
     WHERE pu.archived_at IS NULL
       AND pu.role = 'Owner'
       AND ou.roles[1] NOT IN ('Manager','Employee');" \
    'Project Owners are organizational Managers or Employees'

  assert_query_eq "$database" 0 \
    "WITH expected(project_key, owner_key) AS (
       VALUES
         ('delivery-operations-hub','jan-lewandowski'),
         ('design-system-evolution','piotr-wisniewski'),
         ('time-intelligence','alicja-kowalska'),
         ('reporting-sandbox','kamil-wojcik'),
         ('patient-portal','lena-kaminska'),
         ('care-analytics','michal-dabrowski'),
         ('omnichannel-checkout','zofia-kaczmarek'),
         ('retail-insights','tomasz-mazur'),
         ('fleet-companion','julia-krawczyk'),
         ('risk-dashboard','natalia-grabowska'),
         ('open-banking-integration','jakub-pawlak'),
         ('learning-experience','emilia-michalska'),
         ('commerce-growth','filip-nowicki')
     )
     SELECT count(*)
     FROM expected
     WHERE NOT EXISTS (
       SELECT 1
       FROM project_user pu
       WHERE pu.id = public.uuid_generate_v5(
               '2f2ed0a0-4d42-5f32-8f49-5d36cc3a0532'::uuid,
               'project-user:' || expected.project_key || ':' || expected.owner_key
             )
         AND pu.project_id = public.uuid_generate_v5(
               '2f2ed0a0-4d42-5f32-8f49-5d36cc3a0532'::uuid,
               'project:' || expected.project_key
             )
         AND pu.user_id = public.uuid_generate_v5(
               '2f2ed0a0-4d42-5f32-8f49-5d36cc3a0532'::uuid,
               'user:' || expected.owner_key
             )
         AND pu.role = 'Owner'
         AND pu.archived_at IS NULL
     );" \
    'deterministic Project Owner-to-project relationships'

  assert_query_eq "$database" 0 \
    "WITH non_employee_profile(user_key, primary_project_key) AS (
       VALUES
         ('amelia-nowak','delivery-operations-hub'),
         ('jan-lewandowski','design-system-evolution'),
         ('marta-zielinska','time-intelligence'),
         ('piotr-wisniewski','reporting-sandbox')
     ), project_key(project_key) AS (
       VALUES
         ('delivery-operations-hub'), ('design-system-evolution'),
         ('time-intelligence'), ('reporting-sandbox'),
         ('patient-portal'), ('care-analytics'),
         ('omnichannel-checkout'), ('retail-insights'),
         ('fleet-companion'), ('mobility-discovery'),
         ('risk-dashboard'), ('open-banking-integration'),
         ('learning-experience'), ('commerce-growth'),
         ('dispatch-optimizer'), ('guest-journey')
     )
     SELECT count(*)
     FROM non_employee_profile profile
     CROSS JOIN project_key project
     JOIN project_user pu
       ON pu.id = public.uuid_generate_v5(
            '2f2ed0a0-4d42-5f32-8f49-5d36cc3a0532'::uuid,
            'project-user:' || project.project_key || ':' || profile.user_key
          )
      AND pu.project_id = public.uuid_generate_v5(
            '2f2ed0a0-4d42-5f32-8f49-5d36cc3a0532'::uuid,
            'project:' || project.project_key
          )
      AND pu.user_id = public.uuid_generate_v5(
            '2f2ed0a0-4d42-5f32-8f49-5d36cc3a0532'::uuid,
            'user:' || profile.user_key
          )
     WHERE pu.archived_at IS NULL
       AND pu.role = 'Member'
       AND project.project_key <> profile.primary_project_key;" \
    'non-Employee non-External Members are primary assignments'
}

assert_work_unit_seed_state() {
  local database=$1
  local seed_today=$2
  local expected_min_date=$3
  local expected_max_date=$4
  local owned_cte
  owned_cte="WITH seed_user AS (
      SELECT users.id AS user_id,
             substring(identity.value FROM '^demo\\+(.+)@epoca\\.example\\.invalid$') AS user_key
      FROM \"user\" users
      JOIN user_identity identity ON identity.user_id = users.id
      WHERE identity.type = 'MailAddress'
        AND identity.value ~ '^demo\\+.+@epoca\\.example\\.invalid$'
    ), owned AS (
      SELECT work_unit_row.*, seed_user.user_key
      FROM work_unit work_unit_row
      JOIN seed_user ON seed_user.user_id = work_unit_row.user_id
      WHERE EXISTS (
        SELECT 1
        FROM generate_series(1, 64) AS slot(logical_slot)
        WHERE work_unit_row.id = public.uuid_generate_v5(
          '2f2ed0a0-4d42-5f32-8f49-5d36cc3a0532'::uuid,
          'work-unit:v1:' || seed_user.user_key || ':' || work_unit_row.date::text || ':' || slot.logical_slot::text
        )
      )
    ), active_owned AS (
      SELECT * FROM owned WHERE state = 'Active' AND archived_at IS NULL
    ), current_history AS (
      SELECT owned.*
      FROM owned
      JOIN project project_row ON project_row.id = owned.project_id
      WHERE owned.state = 'Archived'
        AND owned.archived_at IS NOT NULL
        AND project_row.archived_at IS NOT NULL
        AND owned.date BETWEEN '$seed_today'::date - 45 AND '$seed_today'::date - 31
    )"

  assert_query_eq "$database" '1200|100|60' \
    "$owned_cte
     SELECT count(*) FILTER (WHERE entry_type = 'Worklog') || '|' ||
            count(*) FILTER (WHERE entry_type = 'Note') || '|' ||
            (SELECT count(*) FROM current_history)
     FROM active_owned;" \
    'exact active Worklog, active Note, and archived historical Worklog counts'

  assert_query_eq "$database" "$expected_min_date|$expected_max_date" \
    "$owned_cte SELECT min(date) || '|' || max(date) FROM active_owned;" \
    'exact moving twelve-week work-unit date boundaries'

  assert_query_eq "$database" '30|13|8|4|12' \
    "$owned_cte
     SELECT count(DISTINCT user_id) || '|' || count(DISTINCT project_id) || '|' ||
            count(DISTINCT client_id) || '|' || count(DISTINCT date_trunc('month', date)) || '|' ||
            count(DISTINCT ((date - ('$seed_today'::date - 84)) / 7))
     FROM active_owned;" \
    'people, projects, clients, calendar months, and all twelve weeks are covered'

  assert_query_eq "$database" 0 \
    "$owned_cte
     SELECT count(*) FROM active_owned work_unit_row
     JOIN project project_row ON project_row.id = work_unit_row.project_id
     JOIN client client_row ON client_row.id = work_unit_row.client_id
     JOIN \"user\" users ON users.id = work_unit_row.user_id
     JOIN organization_user organization_membership
       ON organization_membership.organization_id = work_unit_row.organization_id
      AND organization_membership.user_id = work_unit_row.user_id
      AND organization_membership.archived_at IS NULL
     LEFT JOIN project_user project_membership
       ON project_membership.project_id = work_unit_row.project_id
      AND project_membership.user_id = work_unit_row.user_id
      AND project_membership.archived_at IS NULL
     WHERE work_unit_row.date < '$seed_today'::date - 84
        OR work_unit_row.date >= '$seed_today'::date
        OR project_row.archived_at IS NOT NULL
        OR client_row.archived_at IS NOT NULL
        OR users.archived_at IS NOT NULL
        OR project_membership.id IS NULL
        OR project_row.organization_id <> work_unit_row.organization_id
        OR client_row.organization_id <> work_unit_row.organization_id
        OR project_row.client_id <> work_unit_row.client_id
        OR (work_unit_row.entry_type = 'Worklog' AND (
              work_unit_row.duration < interval '15 minutes'
              OR work_unit_row.duration > interval '4 hours'
              OR mod(extract(epoch FROM work_unit_row.duration)::integer, 900) <> 0
              OR work_unit_row.task_id IS NULL
              OR work_unit_row.task_id !~ '^[A-Z]+-[0-9]+$'
           ))
        OR (work_unit_row.entry_type = 'Note' AND (
              work_unit_row.duration <> interval '0 seconds'
              OR work_unit_row.task_id IS NOT NULL
           ))
        OR work_unit_row.entry_type NOT IN ('Worklog', 'Note');" \
    'active work-unit parents, memberships, dates, durations, types, and task ids are valid'

  assert_query_eq "$database" 0 \
    "$owned_cte
     SELECT count(*) FROM (
       SELECT user_id, date
       FROM active_owned
       WHERE entry_type = 'Worklog'
       GROUP BY user_id, date
       HAVING sum(duration) > interval '12 hours'
     ) excessive_daily_time;" \
    'daily Worklog duration never exceeds twelve hours per person'

  assert_query_eq "$database" 0 \
    "$owned_cte
     SELECT count(*) FROM current_history history
     JOIN project project_row ON project_row.id = history.project_id
     WHERE history.entry_type <> 'Worklog'
        OR history.duration < interval '15 minutes'
        OR history.duration > interval '4 hours'
        OR mod(extract(epoch FROM history.duration)::integer, 900) <> 0
        OR history.date >= project_row.archived_at::date
        OR history.client_id <> project_row.client_id;" \
    'historical Worklogs precede project archival and keep valid duration and client parent'

  assert_query_eq "$database" 0 \
    "$owned_cte
     SELECT count(*) FROM work_unit_idempotency claim
     WHERE claim.target_work_unit_id IN (SELECT id FROM owned)
        OR claim.result_work_unit_id IN (SELECT id FROM owned);" \
    'seed-owned work units have no API idempotency claims'

  assert_query_eq "$database" 0 \
    "$owned_cte
     SELECT CASE WHEN
       (SELECT count(*) FROM active_owned) <> 1300
       OR (SELECT count(*) FROM active_owned WHERE date >= date_trunc('month', '$seed_today'::date)::date) = 0
       OR (SELECT count(*) FROM active_owned
           WHERE date >= (date_trunc('month', '$seed_today'::date) - interval '1 month')::date
             AND date < date_trunc('month', '$seed_today'::date)::date) = 0
       OR (SELECT count(DISTINCT project_id) FROM active_owned) <> 13
       OR (SELECT count(DISTINCT client_id) FROM active_owned) <> 8
       OR (SELECT count(DISTINCT user_id) FROM active_owned) < 22
       OR (SELECT count(DISTINCT entry_type) FROM active_owned) <> 2
       OR (SELECT count(DISTINCT date) FROM active_owned) < 30
       OR (SELECT count(*) FROM active_owned WHERE extract(isodow FROM date) IN (6, 7)) = 0
       OR (SELECT count(*) FROM active_owned WHERE extract(isodow FROM date) BETWEEN 1 AND 5) = 0
       OR (SELECT count(*) FROM active_owned WHERE description = '') > 0
       OR (SELECT count(DISTINCT description) FROM active_owned) < 16
       OR (SELECT count(*) FROM active_owned work_unit_row
           JOIN project project_row ON project_row.id = work_unit_row.project_id
           JOIN client client_row ON client_row.id = work_unit_row.client_id
           JOIN \"user\" users ON users.id = work_unit_row.user_id) <> 1300
       THEN 1 ELSE 0 END;" \
    'calendar, report, pivot, filters, and export-shaped queries return useful active data'
}

expect_apply_failure_unchanged() {
  database=$1
  expected_message=$2
  label=$3
  before_snapshot=$(snapshot_rows "$database")
  set +e
  failure_output=$(run_apply "$database" 2>&1)
  failure_status=$?
  set -e
  if [ "$failure_status" -eq 0 ]; then
    fail "$label unexpectedly succeeded"
  fi
  case "$failure_output" in
    *"$expected_message"*) ;;
    *) fail "$label returned the wrong non-secret diagnostic (expected '$expected_message')" ;;
  esac
  after_snapshot=$(snapshot_rows "$database")
  if [ "$before_snapshot" != "$after_snapshot" ]; then
    fail "$label left a partial commit"
  fi
  printf 'PASS: %s (rejected with no partial commit)\n' "$label"
}

expect_apply_failure_unchanged_with_pgoptions() {
  database=$1
  pgoptions=$2
  expected_message=$3
  label=$4
  before_snapshot=$(snapshot_rows "$database")
  set +e
  failure_output=$(run_apply_with_pgoptions "$database" "$pgoptions" 2>&1)
  failure_status=$?
  set -e
  if [ "$failure_status" -eq 0 ]; then
    fail "$label unexpectedly succeeded"
  fi
  case "$failure_output" in
    *"$expected_message"*) ;;
    *) fail "$label returned the wrong non-secret diagnostic (expected '$expected_message')" ;;
  esac
  after_snapshot=$(snapshot_rows "$database")
  if [ "$before_snapshot" != "$after_snapshot" ]; then
    fail "$label left a partial commit"
  fi
  printf 'PASS: %s (rejected with no partial commit)\n' "$label"
}

expect_staging_apply_failure_unchanged() {
  database=$1
  expected_message=$2
  label=$3
  expected_organization_id=$4
  viewer_email=$5
  organization_slug=$6
  before_snapshot=$(snapshot_rows "$database")
  set +e
  failure_output=$(run_staging_seed "$database" apply true "$expected_organization_id" "$viewer_email" "$organization_slug" 2>&1)
  failure_status=$?
  set -e
  if [ "$failure_status" -eq 0 ]; then
    fail "$label unexpectedly succeeded"
  fi
  case "$failure_output" in
    *"$expected_message"*) ;;
    *) fail "$label returned the wrong non-secret diagnostic (expected '$expected_message')" ;;
  esac
  after_snapshot=$(snapshot_rows "$database")
  if [ "$before_snapshot" != "$after_snapshot" ]; then
    fail "$label left a partial commit"
  fi
  printf 'PASS: %s (rejected with no partial commit)\n' "$label"
}

insert_active_organization() {
  database=$1
  organization_id=$2
  psql_case "$database" --quiet --command "
    INSERT INTO organization (id, name, slug, created_at, created_by, modified_at, modified_by, archived_at)
    VALUES ('$organization_id', 'Conflict Fixture', 'iteratorstesting', '2024-01-01 00:00:00+00',
            '40000000-0000-4000-8000-000000000001', '2024-01-01 00:00:00+00',
            '40000000-0000-4000-8000-000000000001', NULL);" >/dev/null
}

insert_fixture_user() {
  database=$1
  user_id=$2
  archived_at=$3
  psql_case "$database" --quiet --command "
    INSERT INTO \"user\" (id, first_name, last_name, avatar, color, is_super_admin, created_at, modified_at, archived_at)
    VALUES ('$user_id', 'Conflict', 'Fixture', NULL, '#000000', false,
            '2024-01-01 00:00:00+00', '2024-01-01 00:00:00+00', $archived_at);" >/dev/null
}

insert_staging_viewer() {
  database=$1
  organization_id=$2
  viewer_email=$3
  viewer_role=$4
  membership_archived_at=$5
  psql_case "$database" --quiet --command "
    INSERT INTO \"user\" (id, first_name, last_name, avatar, color, is_super_admin, created_at, modified_at, archived_at)
    VALUES ('60000000-0000-4000-8000-000000000001', 'Staging', 'Viewer', NULL, '#123456', false,
            '2024-01-01 00:00:00+00', '2024-01-01 00:00:00+00', NULL);
    INSERT INTO user_identity (id, user_id, type, value, main, device_id, confirmed, confirmation_code, created_at)
    VALUES ('60000000-0000-4000-8000-000000000002', '60000000-0000-4000-8000-000000000001',
            'MailAddress', '$viewer_email', true, NULL, true, NULL, '2024-01-01 00:00:00');
    INSERT INTO organization_user (id, organization_id, user_id, roles, archived_at)
    VALUES ('60000000-0000-4000-8000-000000000003', '$organization_id',
            '60000000-0000-4000-8000-000000000001', ARRAY['$viewer_role'], $membership_archived_at);" >/dev/null
}

insert_project_cleanup_fixture_client() {
  local database=$1
  local organization_id=$2
  local client_id=$3
  local created_by=$4
  psql_case "$database" --quiet --command "
    INSERT INTO client (id, name, color, organization_id, created_at, created_by, modified_at, modified_by,
                        archived_at, archived_by, logo, type, texture)
    VALUES ('$client_id', 'Cleanup Fixture Client', '#723200', '$organization_id',
            '2024-05-06 07:08:09+00', '$created_by', '2024-06-07 08:09:10+00', '$created_by',
            NULL, NULL, 'cleanup-fixture-logo', 'External', 'cleanup-fixture-texture');" >/dev/null
}

insert_project_cleanup_catalog_rows() {
  local database=$1
  local organization_id=$2
  local client_id=$3
  local created_by=$4
  local catalog_state=$5
  local predicate=$6
  local row_limit=$7
  {
    printf '%s\n' 'BEGIN;'
    printf '\\ir %s\n' "$REPO_ROOT/scripts/demo-seed/project-cleanup-catalog.sql"
    printf '%s\n' "
      INSERT INTO public.project (
          id, name, description, organization_id, client_id, created_at, created_by,
          modified_at, modified_by, archived_at, archived_by
      )
      SELECT cleanup.project_id,
             CASE WHEN '$catalog_state' = 'target' THEN cleanup.new_name ELSE cleanup.old_name END,
             CASE
                 WHEN '$catalog_state' = 'target' THEN cleanup.new_description
                 WHEN cleanup.old_description_mode = 'blank' THEN NULL
                 ELSE cleanup.old_description
             END,
             '$organization_id', '$client_id', '2024-07-08 09:10:11+00', '$created_by',
             '2024-08-09 10:11:12+00', '$created_by', NULL, NULL
      FROM demo_seed_project_cleanup_catalog AS cleanup
      WHERE $predicate
      ORDER BY cleanup.project_id
      LIMIT $row_limit;"
    printf '%s\n' 'COMMIT;'
  } | psql_case "$database" --quiet --file=- >/dev/null
}

insert_complete_staging_project_cleanup_fixture() {
  local database=$1
  local organization_id=$2
  local client_id=$3
  local created_by=$4
  insert_project_cleanup_fixture_client "$database" "$organization_id" "$client_id" "$created_by"
  insert_project_cleanup_catalog_rows "$database" "$organization_id" "$client_id" "$created_by" \
    target 'true' 12
}

query_project_cleanup_state() {
  local database=$1
  local organization_id=$2
  {
    printf '%s\n' 'BEGIN;'
    printf '\\ir %s\n' "$REPO_ROOT/scripts/demo-seed/project-cleanup-catalog.sql"
    printf '%s\n' "
      SELECT pg_catalog.count(project_row.id)::TEXT || '|' ||
             pg_catalog.count(project_row.id) FILTER (
                 WHERE project_row.name = cleanup.new_name
                   AND project_row.description IS NOT DISTINCT FROM cleanup.new_description
             )::TEXT
      FROM demo_seed_project_cleanup_catalog AS cleanup
      LEFT JOIN public.project AS project_row
        ON project_row.id = cleanup.project_id
       AND project_row.organization_id = '$organization_id';"
    printf '%s\n' 'ROLLBACK;'
  } | psql_case "$database" --quiet --no-align --tuples-only --file=-
}

insert_staging_baseline_seven() {
  database=$1
  organization_id=$2
  psql_case "$database" --quiet --command "
    INSERT INTO \"user\" (id, first_name, last_name, avatar, color, is_super_admin, created_at, modified_at, archived_at) VALUES
      ('60100000-0000-4000-8000-000000000001', 'Existing', 'Tester',   'tester-avatar', '#101010', true,  '2024-01-01 00:00:01+00', '2024-02-01 00:00:01+00', NULL),
      ('60100000-0000-4000-8000-000000000002', 'Existing', 'Manager1', NULL,            '#202020', false, '2024-01-01 00:00:02+00', '2024-02-01 00:00:02+00', NULL),
      ('60100000-0000-4000-8000-000000000003', 'Existing', 'Manager2', NULL,            '#303030', false, '2024-01-01 00:00:03+00', '2024-02-01 00:00:03+00', NULL),
      ('60100000-0000-4000-8000-000000000004', 'Existing', 'Owner1',   NULL,            '#404040', false, '2024-01-01 00:00:04+00', '2024-02-01 00:00:04+00', NULL),
      ('60100000-0000-4000-8000-000000000005', 'Existing', 'Owner2',   NULL,            '#505050', false, '2024-01-01 00:00:05+00', '2024-02-01 00:00:05+00', NULL),
      ('60100000-0000-4000-8000-000000000006', 'Existing', 'Employee1',NULL,            '#606060', false, '2024-01-01 00:00:06+00', '2024-02-01 00:00:06+00', NULL),
      ('60100000-0000-4000-8000-000000000007', 'Existing', 'Employee2',NULL,            '#707070', false, '2024-01-01 00:00:07+00', '2024-02-01 00:00:07+00', NULL);
    INSERT INTO user_identity (id, user_id, type, value, main, device_id, confirmed, confirmation_code, created_at) VALUES
      ('60200000-0000-4000-8000-000000000001', '60100000-0000-4000-8000-000000000001', 'MailAddress', 'user-63a6ddab7c69@example.test',             true, 'existing-device', true, NULL, '2024-01-01 00:00:01'),
      ('60200000-0000-4000-8000-000000000002', '60100000-0000-4000-8000-000000000002', 'MailAddress', 'user-e825d8745e64@example.test',      true, NULL, true, NULL, '2024-01-01 00:00:02'),
      ('60200000-0000-4000-8000-000000000003', '60100000-0000-4000-8000-000000000003', 'MailAddress', 'user-506bb72f3dd6@example.test',      true, NULL, true, NULL, '2024-01-01 00:00:03'),
      ('60200000-0000-4000-8000-000000000004', '60100000-0000-4000-8000-000000000004', 'MailAddress', 'user-3ffff638554e@example.test',        true, NULL, true, NULL, '2024-01-01 00:00:04'),
      ('60200000-0000-4000-8000-000000000005', '60100000-0000-4000-8000-000000000005', 'MailAddress', 'user-55d3b841489f@example.test',        true, NULL, true, NULL, '2024-01-01 00:00:05'),
      ('60200000-0000-4000-8000-000000000006', '60100000-0000-4000-8000-000000000006', 'MailAddress', 'user-f666930654e6@example.test',     true, NULL, true, NULL, '2024-01-01 00:00:06'),
      ('60200000-0000-4000-8000-000000000007', '60100000-0000-4000-8000-000000000007', 'MailAddress', 'user-6d56ee607579@example.test',     true, NULL, true, NULL, '2024-01-01 00:00:07');
    INSERT INTO organization_user (id, organization_id, user_id, roles, archived_at) VALUES
      ('60300000-0000-4000-8000-000000000001', '$organization_id', '60100000-0000-4000-8000-000000000001', ARRAY['Employee'], NULL),
      ('60300000-0000-4000-8000-000000000002', '$organization_id', '60100000-0000-4000-8000-000000000002', ARRAY['Manager'],  NULL),
      ('60300000-0000-4000-8000-000000000003', '$organization_id', '60100000-0000-4000-8000-000000000003', ARRAY['Manager'],  NULL),
      ('60300000-0000-4000-8000-000000000004', '$organization_id', '60100000-0000-4000-8000-000000000004', ARRAY['Owner'],    NULL),
      ('60300000-0000-4000-8000-000000000005', '$organization_id', '60100000-0000-4000-8000-000000000005', ARRAY['Owner'],    NULL),
      ('60300000-0000-4000-8000-000000000006', '$organization_id', '60100000-0000-4000-8000-000000000006', ARRAY['Employee'], NULL),
      ('60300000-0000-4000-8000-000000000007', '$organization_id', '60100000-0000-4000-8000-000000000007', ARRAY['Employee'], NULL);
    INSERT INTO user_password (user_identity_id, password)
    VALUES ('60200000-0000-4000-8000-000000000001', 'existing-password-hash');
    INSERT INTO user_login_token (id, user_identity_id, login_token, valid_to)
    VALUES ('60400000-0000-4000-8000-000000000001', '60200000-0000-4000-8000-000000000001', 'existing-login-token', '2027-01-01 00:00:00');
    INSERT INTO refresh_token (user_id, device_id, user_agent, value, authed_by, last_authed_at, created_at, expired_at)
    VALUES ('60100000-0000-4000-8000-000000000001', 'existing-device', 'existing-agent', 'existing-refresh-token',
            ARRAY['60200000-0000-4000-8000-000000000001']::uuid[], '2026-01-01 00:00:00', '2026-01-01 00:00:00', NULL);" >/dev/null
}

insert_three_nonseed_members() {
  database=$1
  organization_id=$2
  psql_case "$database" --quiet --command "
    INSERT INTO \"user\" (id, first_name, last_name, avatar, color, is_super_admin, created_at, modified_at, archived_at) VALUES
      ('5a000000-0000-4000-8000-000000000001', 'Shrink', 'Owner', NULL, '#111111', false, '2024-01-01 00:00:01+00', '2024-01-01 00:00:01+00', NULL),
      ('5a000000-0000-4000-8000-000000000002', 'Shrink', 'Employee', NULL, '#222222', false, '2024-01-01 00:00:02+00', '2024-01-01 00:00:02+00', NULL),
      ('5a000000-0000-4000-8000-000000000003', 'Shrink', 'External', NULL, '#333333', false, '2024-01-01 00:00:03+00', '2024-01-01 00:00:03+00', NULL);
    INSERT INTO user_identity (id, user_id, type, value, main, device_id, confirmed, confirmation_code, created_at) VALUES
      ('5a100000-0000-4000-8000-000000000001', '5a000000-0000-4000-8000-000000000001', 'MailAddress', 'shrink.owner@example.test', true, NULL, true, NULL, '2024-01-01 00:00:01'),
      ('5a100000-0000-4000-8000-000000000002', '5a000000-0000-4000-8000-000000000002', 'MailAddress', 'shrink.employee@example.test', true, NULL, true, NULL, '2024-01-01 00:00:02'),
      ('5a100000-0000-4000-8000-000000000003', '5a000000-0000-4000-8000-000000000003', 'MailAddress', 'shrink.external@example.test', true, NULL, true, NULL, '2024-01-01 00:00:03');
    INSERT INTO organization_user (id, organization_id, user_id, roles, archived_at) VALUES
      ('5a200000-0000-4000-8000-000000000001', '$organization_id', '5a000000-0000-4000-8000-000000000001', ARRAY['Owner'], NULL),
      ('5a200000-0000-4000-8000-000000000002', '$organization_id', '5a000000-0000-4000-8000-000000000002', ARRAY['Employee'], NULL),
      ('5a200000-0000-4000-8000-000000000003', '$organization_id', '5a000000-0000-4000-8000-000000000003', ARRAY['External'], NULL);" >/dev/null
}

insert_nonseed_members() {
  database=$1
  organization_id=$2
  role=$3
  member_count=$4
  fixture_key=$5
  psql_case "$database" --quiet --command "
    WITH fixture AS (
      SELECT series.member_number,
             public.uuid_generate_v5(
               '7f2ed0a0-4d42-5f32-8f49-5d36cc3a0532'::uuid,
               '$fixture_key:user:' || series.member_number::text
             ) AS user_id,
             public.uuid_generate_v5(
               '7f2ed0a0-4d42-5f32-8f49-5d36cc3a0532'::uuid,
               '$fixture_key:membership:' || series.member_number::text
             ) AS membership_id
      FROM pg_catalog.generate_series(1, $member_count) series(member_number)
    )
    INSERT INTO \"user\" (id, first_name, last_name, avatar, color, is_super_admin, created_at, modified_at, archived_at)
    SELECT user_id, 'Capacity', '$role ' || member_number::text, NULL, '#101010', false,
           '2024-01-01 00:00:00+00', '2024-01-01 00:00:00+00', NULL
    FROM fixture;

    WITH fixture AS (
      SELECT series.member_number,
             public.uuid_generate_v5(
               '7f2ed0a0-4d42-5f32-8f49-5d36cc3a0532'::uuid,
               '$fixture_key:user:' || series.member_number::text
             ) AS user_id,
             public.uuid_generate_v5(
               '7f2ed0a0-4d42-5f32-8f49-5d36cc3a0532'::uuid,
               '$fixture_key:membership:' || series.member_number::text
             ) AS membership_id
      FROM pg_catalog.generate_series(1, $member_count) series(member_number)
    )
    INSERT INTO organization_user (id, organization_id, user_id, roles, archived_at)
    SELECT membership_id, '$organization_id', user_id, ARRAY['$role'], NULL
    FROM fixture;" >/dev/null
}

insert_client_fixture() {
  database=$1
  client_id=$2
  organization_id=$3
  psql_case "$database" --quiet --command "
    INSERT INTO client (id, name, color, organization_id, created_at, created_by, modified_at, modified_by, archived_at, archived_by, logo, type, texture)
    VALUES ('$client_id', 'Catalog Parent Fixture', '#555555', '$organization_id',
            '2024-01-01 00:00:00+00', '5b000000-0000-4000-8000-000000000001',
            '2024-01-01 00:00:00+00', '5b000000-0000-4000-8000-000000000001',
            NULL, NULL, '', 'Internal', '');" >/dev/null
}

insert_project_fixture() {
  database=$1
  project_id=$2
  organization_id=$3
  client_id=$4
  psql_case "$database" --quiet --command "
    INSERT INTO project (id, name, description, organization_id, client_id, created_at, created_by, modified_at, modified_by, archived_at, archived_by)
    VALUES ('$project_id', 'Catalog Project Fixture', 'Ownership fixture', '$organization_id', '$client_id',
            '2024-01-01 00:00:00+00', '5b000000-0000-4000-8000-000000000001',
            '2024-01-01 00:00:00+00', '5b000000-0000-4000-8000-000000000001', NULL, NULL);" >/dev/null
}

case_empty_local() {
  new_database
  database=$NEW_DATABASE
  apply_migrations "$database"
  run_apply "$database"
  run_standalone_verify "$database"

  assert_query_eq "$database" 1 \
    "SELECT count(*) FROM organization WHERE slug = 'iteratorstesting' AND archived_at IS NULL;" \
    'one active target organization'
  assert_query_eq "$database" 30 \
    "SELECT count(DISTINCT ou.user_id) FROM organization_user ou JOIN organization o ON o.id = ou.organization_id JOIN \"user\" u ON u.id = ou.user_id WHERE o.slug = 'iteratorstesting' AND o.archived_at IS NULL AND ou.archived_at IS NULL AND u.archived_at IS NULL;" \
    'thirty unique active members'
  assert_query_eq "$database" 'Employee=22,External=4,Manager=2,Owner=2' \
    "SELECT string_agg(role || '=' || role_count, ',' ORDER BY role) FROM (SELECT roles[1] AS role, count(*)::text AS role_count FROM organization_user ou JOIN organization o ON o.id = ou.organization_id JOIN \"user\" u ON u.id = ou.user_id WHERE o.slug = 'iteratorstesting' AND o.archived_at IS NULL AND ou.archived_at IS NULL AND u.archived_at IS NULL GROUP BY roles[1]) counts;" \
    'exact active role distribution'
  assert_query_eq "$database" 0 \
    "SELECT count(*) FROM organization_user ou JOIN organization o ON o.id = ou.organization_id JOIN \"user\" u ON u.id = ou.user_id WHERE o.slug = 'iteratorstesting' AND o.archived_at IS NULL AND ou.archived_at IS NULL AND (u.archived_at IS NOT NULL OR cardinality(ou.roles) <> 1 OR ou.roles[1] NOT IN ('Owner','Manager','Employee','External'));" \
    'one supported role and active user per membership'
  assert_query_eq "$database" 'Manager=1,Owner=1' \
    "SELECT string_agg(ou.roles[1] || '=' || role_count, ',' ORDER BY ou.roles[1]) FROM (SELECT ou.roles, count(*)::text AS role_count FROM organization_user ou JOIN organization o ON o.id = ou.organization_id JOIN \"user\" u ON u.id = ou.user_id JOIN user_identity ui ON ui.user_id = u.id WHERE o.slug = 'iteratorstesting' AND o.archived_at IS NULL AND ou.archived_at IS NULL AND u.archived_at IS NULL AND u.is_super_admin AND ui.value LIKE 'demo+%@epoca.example.invalid' GROUP BY ou.roles) ou;" \
    'two active seed superadmins have Owner and Manager roles'
  assert_query_eq "$database" 0 \
    "SELECT count(*) FROM organization_user ou JOIN organization o ON o.id = ou.organization_id JOIN \"user\" u ON u.id = ou.user_id JOIN user_identity ui ON ui.user_id = u.id WHERE o.slug = 'iteratorstesting' AND o.archived_at IS NULL AND ou.archived_at IS NULL AND u.archived_at IS NULL AND ui.value LIKE 'demo+%@epoca.example.invalid' AND ui.value NOT IN ('demo+amelia-nowak@epoca.example.invalid','demo+jan-lewandowski@epoca.example.invalid') AND u.is_super_admin;" \
    'all other active seed users are not superadmins'
  assert_query_eq "$database" 0 \
    "SELECT count(*) FROM organization_user ou JOIN organization o ON o.id = ou.organization_id JOIN \"user\" u ON u.id = ou.user_id WHERE o.slug = 'iteratorstesting' AND o.archived_at IS NULL AND ou.archived_at IS NULL AND u.archived_at IS NULL AND EXISTS (SELECT 1 FROM user_identity ui WHERE ui.user_id = u.id AND ui.value LIKE 'demo+%@epoca.example.invalid') AND (SELECT count(*) FROM user_identity ui WHERE ui.user_id = u.id AND ui.type = 'MailAddress' AND ui.main AND ui.confirmed AND ui.confirmation_code IS NULL AND ui.device_id IS NULL AND ui.value LIKE 'demo+%@epoca.example.invalid') <> 1;" \
    'one exact-shape seed MailAddress identity'
  assert_query_eq "$database" '30|0|0|0' \
    "SELECT (SELECT count(*) FROM user_password up JOIN user_identity ui ON ui.id = up.user_identity_id WHERE ui.value LIKE 'demo+%@epoca.example.invalid') || '|' || (SELECT count(*) FROM user_login_token ult JOIN user_identity ui ON ui.id = ult.user_identity_id WHERE ui.value LIKE 'demo+%@epoca.example.invalid') || '|' || (SELECT count(*) FROM refresh_token rt JOIN user_identity ui ON ui.user_id = rt.user_id WHERE ui.value LIKE 'demo+%@epoca.example.invalid') || '|' || (SELECT count(*) FROM invitation i WHERE i.identity_value LIKE 'demo+%@epoca.example.invalid');" \
    'one managed password per seed identity and no initial tokens, refresh tokens, or invitations'

  printf 'PASS: empty-local\n'
}

case_projects() {
  new_database
  database=$NEW_DATABASE
  apply_migrations "$database"
  dry_run_before=$(snapshot_rows "$database")
  dry_run_output=$(run_dry_run "$database")
  dry_run_after=$(snapshot_rows "$database")
  [ "$dry_run_before" = "$dry_run_after" ] || fail 'project dry-run changed persistent rows'
  assert_change_plan_eq "$dry_run_output" client insert 10 'dry-run client inserts'
  assert_change_plan_eq "$dry_run_output" project insert 16 'dry-run project inserts'
  assert_change_plan_eq "$dry_run_output" project_user insert 112 'dry-run project membership inserts'
  apply_output=$(run_apply "$database")
  assert_change_plan_eq "$apply_output" client insert 10 'first apply client inserts'
  assert_change_plan_eq "$apply_output" project insert 16 'first apply project inserts'
  assert_change_plan_eq "$apply_output" project_user insert 112 'first apply project membership inserts'
  assert_project_seed_state "$database"
  run_standalone_verify "$database" >/dev/null

  organization_id=$(query_value "$database" "SELECT id FROM organization WHERE slug = 'iteratorstesting';")
  seed_user_id=$(query_value "$database" "SELECT public.uuid_generate_v5('2f2ed0a0-4d42-5f32-8f49-5d36cc3a0532'::uuid, 'user:amelia-nowak');")
  psql_case "$database" --quiet --command "
    INSERT INTO client (id, name, color, organization_id, created_at, created_by, modified_at, modified_by, archived_at, archived_by, logo, type, texture)
    VALUES ('7a000000-0000-4000-8000-000000000001', 'Atelier Operations', '#010101', '$organization_id',
            '2024-01-01 00:00:00+00', '$seed_user_id', '2024-01-02 00:00:00+00', '$seed_user_id',
            NULL, NULL, '', 'External', 'foreign-texture');
    INSERT INTO project (id, name, description, organization_id, client_id, created_at, created_by, modified_at, modified_by, archived_at, archived_by)
    VALUES ('7a000000-0000-4000-8000-000000000002', 'Delivery Operations Hub', 'Foreign same-name project', '$organization_id',
            '7a000000-0000-4000-8000-000000000001', '2024-01-01 00:00:00+00', '$seed_user_id',
            '2024-01-02 00:00:00+00', '$seed_user_id', NULL, NULL);
    INSERT INTO project_user (id, project_id, user_id, role, created_at, archived_at)
    VALUES ('7a000000-0000-4000-8000-000000000003', '7a000000-0000-4000-8000-000000000002',
            '$seed_user_id', 'Member', '2024-01-01 00:00:00+00', NULL);" >/dev/null
  foreign_before=$(query_value "$database" "
    SELECT row_to_json(row_value)::text FROM (
      SELECT 'client' AS entity, row_to_json(c)::text AS payload FROM client c WHERE c.id = '7a000000-0000-4000-8000-000000000001'
      UNION ALL SELECT 'project', row_to_json(p)::text FROM project p WHERE p.id = '7a000000-0000-4000-8000-000000000002'
      UNION ALL SELECT 'project_user', row_to_json(pu)::text FROM project_user pu WHERE pu.id = '7a000000-0000-4000-8000-000000000003'
      ORDER BY entity
    ) row_value;")
  run_apply "$database" >/dev/null
  foreign_after=$(query_value "$database" "
    SELECT row_to_json(row_value)::text FROM (
      SELECT 'client' AS entity, row_to_json(c)::text AS payload FROM client c WHERE c.id = '7a000000-0000-4000-8000-000000000001'
      UNION ALL SELECT 'project', row_to_json(p)::text FROM project p WHERE p.id = '7a000000-0000-4000-8000-000000000002'
      UNION ALL SELECT 'project_user', row_to_json(pu)::text FROM project_user pu WHERE pu.id = '7a000000-0000-4000-8000-000000000003'
      ORDER BY entity
    ) row_value;")
  [ "$foreign_before" = "$foreign_after" ] || fail 'same-name foreign client/project/team rows changed on rerun'
  assert_project_seed_state "$database"

  active_project_id=$(query_value "$database" "SELECT public.uuid_generate_v5('2f2ed0a0-4d42-5f32-8f49-5d36cc3a0532'::uuid, 'project:delivery-operations-hub');")
  update_project_id=$(query_value "$database" "SELECT public.uuid_generate_v5('2f2ed0a0-4d42-5f32-8f49-5d36cc3a0532'::uuid, 'project:design-system-evolution');")
  archived_project_id=$(query_value "$database" "SELECT public.uuid_generate_v5('2f2ed0a0-4d42-5f32-8f49-5d36cc3a0532'::uuid, 'project:dispatch-optimizer');")
  active_client_id=$(query_value "$database" "SELECT public.uuid_generate_v5('2f2ed0a0-4d42-5f32-8f49-5d36cc3a0532'::uuid, 'client:atelier-ops');")
  update_client_id=$(query_value "$database" "SELECT public.uuid_generate_v5('2f2ed0a0-4d42-5f32-8f49-5d36cc3a0532'::uuid, 'client:epoca-labs');")
  archived_client_id=$(query_value "$database" "SELECT public.uuid_generate_v5('2f2ed0a0-4d42-5f32-8f49-5d36cc3a0532'::uuid, 'client:orbit-logistics');")
  active_project_user_id=$(query_value "$database" "SELECT id FROM project_user WHERE project_id = '$active_project_id' AND archived_at IS NULL ORDER BY id LIMIT 1;")
  update_project_user_id=$(query_value "$database" "SELECT id FROM project_user WHERE project_id = '$update_project_id' AND archived_at IS NULL ORDER BY id LIMIT 1;")
  archived_project_user_id=$(query_value "$database" "SELECT id FROM project_user WHERE project_id = '$archived_project_id' AND archived_at IS NOT NULL ORDER BY id LIMIT 1;")
  created_before=$(query_value "$database" "
    SELECT md5(string_agg(entity || '|' || id || '|' || created_at, E'\\n' ORDER BY entity, id))
    FROM (
      SELECT 'client' AS entity, id::text, created_at::text FROM client WHERE id IN ('$active_client_id','$update_client_id','$archived_client_id')
      UNION ALL SELECT 'project', id::text, created_at::text FROM project WHERE id IN ('$active_project_id','$update_project_id','$archived_project_id')
      UNION ALL SELECT 'project_user', id::text, created_at::text FROM project_user WHERE id IN ('$active_project_user_id','$update_project_user_id','$archived_project_user_id')
    ) rows;")
  psql_case "$database" --quiet --command "
    UPDATE client SET archived_at = '2025-01-01 00:00:00+00', archived_by = '$seed_user_id' WHERE id = '$active_client_id';
    UPDATE client SET name = 'Drifted Client Name' WHERE id = '$update_client_id';
    UPDATE client SET archived_at = NULL, archived_by = NULL WHERE id = '$archived_client_id';
    UPDATE project SET archived_at = '2025-01-01 00:00:00+00', archived_by = '$seed_user_id' WHERE id = '$active_project_id';
    UPDATE project SET name = 'Drifted Project Name' WHERE id = '$update_project_id';
    UPDATE project SET archived_at = NULL, archived_by = NULL WHERE id = '$archived_project_id';
    UPDATE project_user SET archived_at = '2025-01-01 00:00:00+00' WHERE id = '$active_project_user_id';
    UPDATE project_user SET role = CASE role WHEN 'Owner' THEN 'Member' ELSE 'Owner' END WHERE id = '$update_project_user_id';
    UPDATE project_user SET archived_at = NULL WHERE id = '$archived_project_user_id';" >/dev/null
  repair_output=$(run_apply "$database")
  assert_change_plan_eq "$repair_output" client activate 1 'client reactivation plan'
  assert_change_plan_eq "$repair_output" client archive 1 'client archival plan'
  assert_change_plan_eq "$repair_output" client update 1 'client update plan'
  assert_change_plan_eq "$repair_output" project activate 1 'project reactivation plan'
  assert_change_plan_eq "$repair_output" project archive 1 'project archival plan'
  assert_change_plan_eq "$repair_output" project update 1 'project update plan'
  assert_change_plan_eq "$repair_output" project_user activate 1 'project membership reactivation plan'
  assert_change_plan_eq "$repair_output" project_user archive 1 'project membership archival plan'
  assert_change_plan_eq "$repair_output" project_user update 1 'project membership update plan'
  created_after=$(query_value "$database" "
    SELECT md5(string_agg(entity || '|' || id || '|' || created_at, E'\\n' ORDER BY entity, id))
    FROM (
      SELECT 'client' AS entity, id::text, created_at::text FROM client WHERE id IN ('$active_client_id','$update_client_id','$archived_client_id')
      UNION ALL SELECT 'project', id::text, created_at::text FROM project WHERE id IN ('$active_project_id','$update_project_id','$archived_project_id')
      UNION ALL SELECT 'project_user', id::text, created_at::text FROM project_user WHERE id IN ('$active_project_user_id','$update_project_user_id','$archived_project_user_id')
    ) rows;")
  [ "$created_before" = "$created_after" ] || fail 'project repair rewrote stable created_at timestamps'
  assert_project_seed_state "$database"
  run_standalone_verify "$database" >/dev/null
  printf 'PASS: projects\n'
}

case_project_cleanup() {
  CURRENT_CASE_CHILD_PIDS=
  CURRENT_CASE_UNREAPED_CHILD_PIDS=

  # Local permits an empty cleanup subset, and independent verification must preserve the catalog.
  new_database
  local_baseline_database=$NEW_DATABASE
  apply_migrations "$local_baseline_database"
  baseline_apply_output=$(run_apply "$local_baseline_database")
  assert_change_plan_eq "$baseline_apply_output" project_cleanup update 0 \
    'local cleanup absence apply plan'
  local_organization_id=$(query_value "$local_baseline_database" \
    "SELECT id FROM public.organization WHERE slug = 'iteratorstesting';")
  [ "$(query_project_cleanup_state "$local_baseline_database" "$local_organization_id")" = '0|0' ] || \
    fail 'local cleanup absence fixture unexpectedly contains a historical cleanup row'
  baseline_verify_output=$(run_standalone_verify "$local_baseline_database")
  assert_summary_field_eq "$baseline_verify_output" cleaned_project_count 0 \
    'local cleanup absence standalone verify summary'

  fixture_client_id=$(query_value "$local_baseline_database" \
    "SELECT id FROM public.client WHERE organization_id = '$local_organization_id' AND archived_at IS NULL ORDER BY id LIMIT 1;")
  fixture_user_id=$(query_value "$local_baseline_database" \
    "SELECT user_id FROM public.organization_user WHERE organization_id = '$local_organization_id' AND archived_at IS NULL ORDER BY id LIMIT 1;")

  # Both approved representations of a blank old description are exact cleanup inputs.
  clone_database "$local_baseline_database"
  blank_database=$NEW_DATABASE
  insert_project_cleanup_catalog_rows "$blank_database" "$local_organization_id" "$fixture_client_id" \
    "$fixture_user_id" old \
    "cleanup.project_id IN ('c65a2c74-4884-48bb-acd8-9602badc55de', '0c33f71d-d4da-4cfd-bbab-ec99285de371')" 2
  psql_case "$blank_database" --quiet --command \
    "UPDATE public.project SET description = '' WHERE id = '0c33f71d-d4da-4cfd-bbab-ec99285de371';" >/dev/null
  assert_query_eq "$blank_database" '1|1' \
    "SELECT pg_catalog.count(*) FILTER (WHERE description IS NULL)::TEXT || '|' ||
            pg_catalog.count(*) FILTER (WHERE description = '')::TEXT
       FROM public.project
      WHERE id IN ('c65a2c74-4884-48bb-acd8-9602badc55de', '0c33f71d-d4da-4cfd-bbab-ec99285de371');" \
    'blank cleanup fixture covers SQL NULL and empty string'
  project_before=$(query_value "$blank_database" "
    SELECT pg_catalog.md5(pg_catalog.string_agg(row_to_json(project_row)::TEXT, E'\\n' ORDER BY project_row.id))
    FROM (
      SELECT id, organization_id, client_id, created_at, created_by, modified_at, modified_by,
             archived_at, archived_by
      FROM public.project
      WHERE id IN ('c65a2c74-4884-48bb-acd8-9602badc55de', '0c33f71d-d4da-4cfd-bbab-ec99285de371')
    ) project_row;")

  dry_run_before=$(snapshot_rows "$blank_database")
  dry_run_output=$(run_dry_run "$blank_database")
  dry_run_after=$(snapshot_rows "$blank_database")
  [ "$dry_run_before" = "$dry_run_after" ] || fail 'placeholder project cleanup dry-run changed persistent rows'
  assert_change_plan_eq "$dry_run_output" project_cleanup update 2 \
    'placeholder project cleanup dry-run update plan'
  assert_summary_field_eq "$dry_run_output" cleaned_project_count 2 \
    'placeholder project cleanup dry-run verification summary'
  [ "$(query_project_cleanup_state "$blank_database" "$local_organization_id")" = '2|0' ] || \
    fail 'placeholder projects did not remain in approved old states after dry-run'

  apply_output=$(run_apply "$blank_database")
  assert_change_plan_eq "$apply_output" project_cleanup update 2 \
    'placeholder project cleanup apply update plan'
  assert_summary_field_eq "$apply_output" cleaned_project_count 2 \
    'placeholder project cleanup apply verification summary'
  [ "$(query_project_cleanup_state "$blank_database" "$local_organization_id")" = '2|2' ] || \
    fail 'approved blank placeholder projects did not reach their exact target states'
  project_after=$(query_value "$blank_database" "
    SELECT pg_catalog.md5(pg_catalog.string_agg(row_to_json(project_row)::TEXT, E'\\n' ORDER BY project_row.id))
    FROM (
      SELECT id, organization_id, client_id, created_at, created_by, modified_at, modified_by,
             archived_at, archived_by
      FROM public.project
      WHERE id IN ('c65a2c74-4884-48bb-acd8-9602badc55de', '0c33f71d-d4da-4cfd-bbab-ec99285de371')
    ) project_row;")
  [ "$project_before" = "$project_after" ] || \
    fail 'placeholder project cleanup changed identity, parent, audit, or archive fields'
  blank_verify_output=$(run_standalone_verify "$blank_database")
  assert_summary_field_eq "$blank_verify_output" cleaned_project_count 2 \
    'exact cleanup target standalone verify summary'

  rerun_before=$(snapshot_rows "$blank_database")
  rerun_output=$(run_apply "$blank_database")
  rerun_after=$(snapshot_rows "$blank_database")
  [ "$rerun_before" = "$rerun_after" ] || fail 'placeholder project cleanup rerun was not byte-stable'
  assert_change_plan_eq "$rerun_output" project_cleanup update 0 \
    'placeholder project cleanup rerun update plan'
  assert_summary_field_eq "$rerun_output" cleaned_project_count 2 \
    'placeholder project cleanup rerun verification summary'

  # Exact-description drift is rejected independently of every other cleanup guard.
  clone_database "$local_baseline_database"
  exact_description_drift_database=$NEW_DATABASE
  insert_project_cleanup_catalog_rows "$exact_description_drift_database" "$local_organization_id" \
    "$fixture_client_id" "$fixture_user_id" old \
    "cleanup.project_id = 'b3c577d9-bede-458e-a523-ca094f79cf11'" 1
  psql_case "$exact_description_drift_database" --quiet --command "
    UPDATE public.project
       SET description = 'unapproved exact-description drift'
     WHERE id = 'b3c577d9-bede-458e-a523-ca094f79cf11';" >/dev/null
  expect_apply_failure_unchanged "$exact_description_drift_database" \
    'project cleanup row has an unapproved state' \
    'cleanup exact-description drift'
  expect_verify_failure_unchanged "$exact_description_drift_database" \
    'project cleanup row outside the exact target state' \
    'cleanup exact-description drift'

  # A mixed old-name/new-description state is rejected independently of exact-description drift.
  clone_database "$local_baseline_database"
  mixed_state_database=$NEW_DATABASE
  insert_project_cleanup_catalog_rows "$mixed_state_database" "$local_organization_id" "$fixture_client_id" \
    "$fixture_user_id" old \
    "cleanup.project_id = '4389f10e-59c4-4f8a-9cb2-d82613db3283'" 1
  psql_case "$mixed_state_database" --quiet --command "
    UPDATE public.project
       SET description = 'Shared workspace for retail partners, product launches and commercial materials.'
     WHERE id = '4389f10e-59c4-4f8a-9cb2-d82613db3283';" >/dev/null
  mixed_fixture_catalog_match_count=$(
    {
      printf '%s\n' 'BEGIN;'
      printf '\\ir %s\n' "$REPO_ROOT/scripts/demo-seed/project-cleanup-catalog.sql"
      printf '%s\n' "
        SELECT pg_catalog.count(*)
        FROM demo_seed_project_cleanup_catalog AS cleanup
        JOIN public.project AS project_row
          ON project_row.id = cleanup.project_id
        WHERE cleanup.project_id = '4389f10e-59c4-4f8a-9cb2-d82613db3283'
          AND project_row.name = cleanup.old_name
          AND project_row.description IS NOT DISTINCT FROM cleanup.new_description;"
      printf '%s\n' 'ROLLBACK;'
    } | psql_case "$mixed_state_database" --quiet --no-align --tuples-only --file=-
  )
  [ "$mixed_fixture_catalog_match_count" = 1 ] || \
    fail 'mixed cleanup fixture is not the catalog old-name/new-description state'
  expect_apply_failure_unchanged "$mixed_state_database" \
    'project cleanup row has an unapproved state' \
    'cleanup mixed old-name/new-description state'
  expect_verify_failure_unchanged "$mixed_state_database" \
    'project cleanup row outside the exact target state' \
    'cleanup mixed old-name/new-description state'

  # A cleanup identifier is never adopted from another organization.
  clone_database "$local_baseline_database"
  foreign_database=$NEW_DATABASE
  foreign_organization_id='72300000-0000-4000-8000-000000000099'
  foreign_client_id='72320000-0000-4000-8000-000000000099'
  psql_case "$foreign_database" --quiet --command "
    INSERT INTO public.organization (id, name, slug, created_at, created_by, modified_at, modified_by, archived_at)
    VALUES ('$foreign_organization_id', 'Foreign Cleanup Parent', 'foreign-cleanup-parent',
            '2024-01-01 00:00:00+00', '$fixture_user_id', '2024-01-01 00:00:00+00', '$fixture_user_id', NULL);" >/dev/null
  insert_project_cleanup_fixture_client "$foreign_database" "$foreign_organization_id" \
    "$foreign_client_id" "$fixture_user_id"
  insert_project_cleanup_catalog_rows "$foreign_database" "$foreign_organization_id" "$foreign_client_id" \
    "$fixture_user_id" target "cleanup.project_id = 'b3c577d9-bede-458e-a523-ca094f79cf11'" 1
  expect_apply_failure_unchanged "$foreign_database" \
    'project cleanup id belongs to another organization' \
    'cleanup id owned by another organization'
  expect_verify_failure_unchanged "$foreign_database" \
    'project cleanup id belongs to another organization' \
    'cleanup id owned by another organization'

  # A different project may not already own a cleanup target name in the target organization.
  clone_database "$local_baseline_database"
  collision_database=$NEW_DATABASE
  insert_project_cleanup_catalog_rows "$collision_database" "$local_organization_id" "$fixture_client_id" \
    "$fixture_user_id" old "cleanup.project_id = 'b3c577d9-bede-458e-a523-ca094f79cf11'" 1
  psql_case "$collision_database" --quiet --command "
    INSERT INTO public.project (
        id, name, description, organization_id, client_id, created_at, created_by,
        modified_at, modified_by, archived_at, archived_by
    ) VALUES (
        '72330000-0000-4000-8000-000000000099', 'Learning Platform Refresh',
        'Independent collision fixture.', '$local_organization_id', '$fixture_client_id',
        '2024-01-01 00:00:00+00', '$fixture_user_id', '2024-01-01 00:00:00+00',
        '$fixture_user_id', NULL, NULL
    );" >/dev/null
  expect_apply_failure_unchanged "$collision_database" \
    'project cleanup target name is already in use' \
    'cleanup target-name collision'
  expect_verify_failure_unchanged "$collision_database" \
    'project cleanup target name is already in use' \
    'cleanup target-name collision'

  # Staging is fail-closed unless all twelve physical cleanup identifiers are present.
  new_database
  staging_database=$NEW_DATABASE
  apply_migrations "$staging_database"
  staging_organization_id='72300000-0000-4000-8000-000000000201'
  staging_client_id='72320000-0000-4000-8000-000000000201'
  staging_viewer_email='cleanup.viewer@example.test'
  insert_active_organization "$staging_database" "$staging_organization_id"
  insert_staging_viewer "$staging_database" "$staging_organization_id" "$staging_viewer_email" Owner NULL
  insert_project_cleanup_fixture_client "$staging_database" "$staging_organization_id" \
    "$staging_client_id" '60000000-0000-4000-8000-000000000001'
  insert_project_cleanup_catalog_rows "$staging_database" "$staging_organization_id" "$staging_client_id" \
    '60000000-0000-4000-8000-000000000001' target 'true' 11
  [ "$(query_project_cleanup_state "$staging_database" "$staging_organization_id")" = '11|11' ] || \
    fail 'staging missing-count fixture does not contain exactly eleven exact cleanup targets'
  for staging_operation in dry-run apply; do
    staging_before=$(snapshot_rows "$staging_database")
    set +e
    staging_failure_output=$(run_staging_seed "$staging_database" "$staging_operation" \
      "$([ "$staging_operation" = apply ] && printf true || printf false)" \
      "$staging_organization_id" "$staging_viewer_email" iteratorstesting 2>&1)
    staging_failure_status=$?
    set -e
    [ "$staging_failure_status" -ne 0 ] || fail "staging $staging_operation with eleven cleanup ids unexpectedly succeeded"
    case "$staging_failure_output" in
      *'staging project cleanup catalog is incomplete'*) ;;
      *) fail "staging $staging_operation with eleven cleanup ids returned the wrong non-secret diagnostic" ;;
    esac
    [ "$staging_before" = "$(snapshot_rows "$staging_database")" ] || \
      fail "staging $staging_operation with eleven cleanup ids changed persistent rows"
  done
  staging_verify_before=$(snapshot_rows "$staging_database")
  set +e
  staging_verify_failure=$(run_staging_verify "$staging_database" "$staging_organization_id" \
    "$staging_viewer_email" iteratorstesting 2>&1)
  staging_verify_status=$?
  set -e
  [ "$staging_verify_status" -ne 0 ] || fail 'staging standalone verify with eleven cleanup ids unexpectedly succeeded'
  case "$staging_verify_failure" in
    *'staging project cleanup catalog is incomplete'*) ;;
    *) fail 'staging standalone verify with eleven cleanup ids returned the wrong non-secret diagnostic' ;;
  esac
  [ "$staging_verify_before" = "$(snapshot_rows "$staging_database")" ] || \
    fail 'staging standalone verify with eleven cleanup ids changed persistent rows'

  # A cleanup-row SELECT FOR UPDATE wins first; seed waits at EXCLUSIVE, then rejects committed drift.
  clone_database "$local_baseline_database"
  writer_first_database=$NEW_DATABASE
  writer_first_project_id='b3c577d9-bede-458e-a523-ca094f79cf11'
  insert_project_cleanup_catalog_rows "$writer_first_database" "$local_organization_id" "$fixture_client_id" \
    "$fixture_user_id" old "cleanup.project_id = '$writer_first_project_id'" 1
  writer_first_directory=$(mktemp -d "/tmp/epoca_ct532_lock.XXXXXX")
  TEMP_DIRECTORIES="$TEMP_DIRECTORIES $writer_first_directory"
  mkfifo "$writer_first_directory/writer.in"
  PGAPPNAME=ct532_cleanup_writer \
    psql_case "$writer_first_database" --quiet \
    <"$writer_first_directory/writer.in" >"$writer_first_directory/writer.out" 2>&1 &
  cleanup_writer_pid=$!
  register_current_case_child "$cleanup_writer_pid"
  exec 5>"$writer_first_directory/writer.in"
  PROJECT_CLEANUP_WRITER_CONTROL_OPEN=true
  printf '%s\n' '\set VERBOSITY verbose' 'BEGIN;' \
    "SELECT id FROM public.project WHERE id = '$writer_first_project_id' FOR UPDATE;" >&5
  wait_for_query_value "$writer_first_database" 1 \
    "SELECT pg_catalog.count(*)
       FROM pg_catalog.pg_stat_activity AS activity
       JOIN pg_catalog.pg_locks AS lock_row ON lock_row.pid = activity.pid
      WHERE activity.datname = pg_catalog.current_database()
        AND activity.application_name = 'ct532_cleanup_writer'
        AND activity.state = 'idle in transaction'
        AND lock_row.relation = 'public.project'::pg_catalog.regclass
        AND lock_row.mode = 'RowShareLock'
        AND lock_row.granted;" \
    'cleanup row-first writer owns ROW SHARE after SELECT FOR UPDATE'
  PGAPPNAME=ct532_cleanup_blocked_seed \
    run_apply "$writer_first_database" >"$writer_first_directory/blocked-seed.out" 2>&1 &
  blocked_seed_pid=$!
  register_current_case_child "$blocked_seed_pid"
  wait_for_query_value "$writer_first_database" 1 \
    "SELECT pg_catalog.count(*)
       FROM pg_catalog.pg_stat_activity AS activity
      WHERE activity.datname = pg_catalog.current_database()
        AND activity.application_name = 'ct532_cleanup_blocked_seed'
        AND activity.wait_event_type = 'Lock'
        AND EXISTS (
            SELECT 1 FROM pg_catalog.pg_locks AS lock_row
             WHERE lock_row.pid = activity.pid
               AND lock_row.relation = 'public.project'::pg_catalog.regclass
               AND lock_row.mode = 'ExclusiveLock'
               AND NOT lock_row.granted
        );" \
    'seed waits for EXCLUSIVE before cleanup decisions while row-first writer owns ROW SHARE'
  printf '%s\n' \
    "UPDATE public.project SET description = 'committed concurrent cleanup drift' WHERE id = '$writer_first_project_id';" \
    'COMMIT;' '\q' >&5
  exec 5>&-
  PROJECT_CLEANUP_WRITER_CONTROL_OPEN=false
  wait_for_background_success "$cleanup_writer_pid" "$writer_first_directory/writer.out" \
    'cleanup row-first writer UPDATE and commit ahead of seed EXCLUSIVE'
  writer_committed_snapshot=$(snapshot_rows "$writer_first_database")
  if wait_for_background_bounded "$blocked_seed_pid" "$writer_first_directory/blocked-seed.out" \
      'cleanup seed released after row-first writer' 300; then
    blocked_seed_status=0
  else
    blocked_seed_status=$?
  fi
  [ "$blocked_seed_status" -ne 0 ] || fail 'seed unexpectedly accepted committed row-first cleanup drift'
  if grep -Eq '40P01|deadlock detected' "$writer_first_directory/writer.out" \
      "$writer_first_directory/blocked-seed.out"; then
    sed -n '1,160p' "$writer_first_directory/writer.out" >&2
    sed -n '1,160p' "$writer_first_directory/blocked-seed.out" >&2
    fail 'cleanup row-first EXCLUSIVE serialization produced a deadlock'
  fi
  grep -q 'project cleanup row has an unapproved state' "$writer_first_directory/blocked-seed.out" || {
    sed -n '1,160p' "$writer_first_directory/blocked-seed.out" >&2
    fail 'seed returned the wrong diagnostic after committed row-first cleanup drift'
  }
  [ "$writer_committed_snapshot" = "$(snapshot_rows "$writer_first_database")" ] || \
    fail 'seed left a partial commit after the row-first cleanup writer committed drift'

  # The application-shaped project-to-client order wins first; seed waits, then reconciles project drift.
  clone_database "$local_baseline_database"
  main_row_first_database=$NEW_DATABASE
  main_row_first_project_id=$(query_value "$main_row_first_database" \
    "SELECT id FROM public.project WHERE organization_id = '$local_organization_id' ORDER BY id LIMIT 1;")
  main_row_first_client_id=$(query_value "$main_row_first_database" \
    "SELECT client_id FROM public.project WHERE id = '$main_row_first_project_id';")
  main_row_first_target=$(query_value "$main_row_first_database" \
    "SELECT name || '|' || description FROM public.project WHERE id = '$main_row_first_project_id';")
  main_row_first_client_before=$(query_value "$main_row_first_database" \
    "SELECT pg_catalog.md5(row_to_json(client_row)::TEXT)
       FROM (SELECT * FROM public.client WHERE id = '$main_row_first_client_id') AS client_row;")
  main_row_first_project_audit_before=$(query_value "$main_row_first_database" \
    "SELECT pg_catalog.md5(row_to_json(project_row)::TEXT)
       FROM (
         SELECT id, organization_id, client_id, created_at, created_by, modified_by,
                archived_at, archived_by
           FROM public.project
          WHERE id = '$main_row_first_project_id'
       ) AS project_row;")
  main_row_first_directory=$(mktemp -d "/tmp/epoca_ct532_lock.XXXXXX")
  TEMP_DIRECTORIES="$TEMP_DIRECTORIES $main_row_first_directory"
  mkfifo "$main_row_first_directory/writer.in"
  PGAPPNAME=ct532_cleanup_writer \
    psql_case "$main_row_first_database" --quiet \
    <"$main_row_first_directory/writer.in" >"$main_row_first_directory/writer.out" 2>&1 &
  main_cleanup_writer_pid=$!
  register_current_case_child "$main_cleanup_writer_pid"
  exec 5>"$main_row_first_directory/writer.in"
  PROJECT_CLEANUP_WRITER_CONTROL_OPEN=true
  printf '%s\n' '\set VERBOSITY verbose' 'BEGIN;' \
    "SELECT 1
       FROM public.project
      WHERE organization_id = '$local_organization_id'
        AND client_id = '$main_row_first_client_id'
      ORDER BY id
      FOR UPDATE;" >&5
  wait_for_query_value "$main_row_first_database" 1 \
    "SELECT pg_catalog.count(*)
       FROM pg_catalog.pg_stat_activity AS activity
       JOIN pg_catalog.pg_locks AS lock_row ON lock_row.pid = activity.pid
      WHERE activity.datname = pg_catalog.current_database()
        AND activity.application_name = 'ct532_cleanup_writer'
        AND activity.state = 'idle in transaction'
        AND lock_row.relation = 'public.project'::pg_catalog.regclass
        AND lock_row.mode = 'RowShareLock'
        AND lock_row.granted;" \
    'application-first delete owns project ROW SHARE and tuples before the client'
  PGAPPNAME=ct532_cleanup_blocked_seed \
    run_apply "$main_row_first_database" >"$main_row_first_directory/blocked-seed.out" 2>&1 &
  main_blocked_seed_pid=$!
  register_current_case_child "$main_blocked_seed_pid"
  wait_for_query_value "$main_row_first_database" 1 \
    "SELECT pg_catalog.count(*)
       FROM pg_catalog.pg_stat_activity AS activity
      WHERE activity.datname = pg_catalog.current_database()
        AND activity.application_name = 'ct532_cleanup_blocked_seed'
        AND activity.wait_event_type = 'Lock'
        AND EXISTS (
            SELECT 1 FROM pg_catalog.pg_locks AS lock_row
             WHERE lock_row.pid = activity.pid
               AND lock_row.relation = 'public.project'::pg_catalog.regclass
               AND lock_row.mode = 'ExclusiveLock'
               AND NOT lock_row.granted
        );" \
    'seed waits for EXCLUSIVE while application-first delete owns project tuples'
  printf '%s\n' \
    "SELECT 1
       FROM public.client
      WHERE organization_id = '$local_organization_id'
        AND id = '$main_row_first_client_id'
      FOR UPDATE;" >&5
  wait_for_query_value "$main_row_first_database" 1 \
    "SELECT pg_catalog.count(*)
       FROM pg_catalog.pg_stat_activity AS activity
       JOIN pg_catalog.pg_locks AS lock_row ON lock_row.pid = activity.pid
      WHERE activity.datname = pg_catalog.current_database()
        AND activity.application_name = 'ct532_cleanup_writer'
        AND activity.state = 'idle in transaction'
        AND lock_row.relation = 'public.client'::pg_catalog.regclass
        AND lock_row.mode = 'RowShareLock'
        AND lock_row.granted;" \
    'application-first delete locks its client after its projects'
  printf '%s\n' \
    "UPDATE public.project SET description = 'concurrent main project drift' WHERE id = '$main_row_first_project_id';" \
    'COMMIT;' '\q' >&5
  exec 5>&-
  PROJECT_CLEANUP_WRITER_CONTROL_OPEN=false
  wait_for_background_success "$main_cleanup_writer_pid" "$main_row_first_directory/writer.out" \
    'main-project row-first writer UPDATE and commit ahead of seed EXCLUSIVE'
  wait_for_background_success "$main_blocked_seed_pid" "$main_row_first_directory/blocked-seed.out" \
    'seed reconciliation after main-project row-first writer'
  if grep -Eq '40P01|deadlock detected' "$main_row_first_directory/writer.out" \
      "$main_row_first_directory/blocked-seed.out"; then
    sed -n '1,160p' "$main_row_first_directory/writer.out" >&2
    sed -n '1,160p' "$main_row_first_directory/blocked-seed.out" >&2
    fail 'main-project row-first EXCLUSIVE serialization produced a deadlock'
  fi
  assert_query_eq "$main_row_first_database" "$main_row_first_target" \
    "SELECT name || '|' || description FROM public.project WHERE id = '$main_row_first_project_id';" \
    'seed reconciles the row-first main project to its exact target'
  [ "$main_row_first_client_before" = "$(query_value "$main_row_first_database" \
      "SELECT pg_catalog.md5(row_to_json(client_row)::TEXT)
         FROM (SELECT * FROM public.client WHERE id = '$main_row_first_client_id') AS client_row;")" ] || \
    fail 'application-first project-to-client serialization changed the locked client'
  [ "$main_row_first_project_audit_before" = "$(query_value "$main_row_first_database" \
      "SELECT pg_catalog.md5(row_to_json(project_row)::TEXT)
         FROM (
           SELECT id, organization_id, client_id, created_at, created_by, modified_by,
                  archived_at, archived_by
             FROM public.project
            WHERE id = '$main_row_first_project_id'
         ) AS project_row;")" ] || \
    fail 'application-first project-to-client serialization changed stable project parent or audit fields'
  assert_query_eq "$main_row_first_database" 1 \
    "SELECT pg_catalog.count(*)
       FROM public.project
      WHERE id = '$main_row_first_project_id'
        AND modified_at = '2026-05-18 12:00:00+00'::TIMESTAMPTZ;" \
    'seed records the exact reconciliation modified_at after application-first drift'
  assert_project_seed_state "$main_row_first_database"

  # When seed owns EXCLUSIVE first, application project-to-client locking waits at project ROW SHARE.
  clone_database "$local_baseline_database"
  seed_first_database=$NEW_DATABASE
  seed_first_before=$(snapshot_rows "$seed_first_database")
  seed_first_directory=$(mktemp -d "/tmp/epoca_ct532_lock.XXXXXX")
  TEMP_DIRECTORIES="$TEMP_DIRECTORIES $seed_first_directory"
  mkfifo "$seed_first_directory/relation-holder.in" "$seed_first_directory/blocked-writer.in"
  PGAPPNAME=ct532_cleanup_relation_holder \
    psql_case "$seed_first_database" --quiet \
    <"$seed_first_directory/relation-holder.in" >"$seed_first_directory/relation-holder.out" 2>&1 &
  cleanup_relation_holder_pid=$!
  register_current_case_child "$cleanup_relation_holder_pid"
  exec 4>"$seed_first_directory/relation-holder.in"
  PROJECT_CLEANUP_RELATION_HOLDER_CONTROL_OPEN=true
  printf '%s\n' 'BEGIN; LOCK TABLE public.organization IN ACCESS EXCLUSIVE MODE;' >&4
  wait_for_query_value "$seed_first_database" 1 \
    "SELECT pg_catalog.count(*)
       FROM pg_catalog.pg_stat_activity AS activity
       JOIN pg_catalog.pg_locks AS lock_row ON lock_row.pid = activity.pid
      WHERE activity.datname = pg_catalog.current_database()
        AND activity.application_name = 'ct532_cleanup_relation_holder'
        AND lock_row.relation = 'public.organization'::pg_catalog.regclass
        AND lock_row.mode = 'AccessExclusiveLock'
        AND lock_row.granted;" \
    'cleanup serialization relation holder'
  PGAPPNAME=ct532_cleanup_lock_owner_seed \
    run_apply "$seed_first_database" >"$seed_first_directory/seed.out" 2>&1 &
  lock_owner_seed_pid=$!
  register_current_case_child "$lock_owner_seed_pid"
  wait_for_query_value "$seed_first_database" 1 \
    "SELECT pg_catalog.count(*)
       FROM pg_catalog.pg_stat_activity AS activity
      WHERE activity.datname = pg_catalog.current_database()
        AND activity.application_name = 'ct532_cleanup_lock_owner_seed'
        AND activity.wait_event_type = 'Lock'
        AND EXISTS (
            SELECT 1 FROM pg_catalog.pg_locks AS lock_row
             WHERE lock_row.pid = activity.pid
               AND lock_row.relation = 'public.project'::pg_catalog.regclass
               AND lock_row.mode = 'ExclusiveLock'
               AND lock_row.granted
        );" \
    'seed owns EXCLUSIVE before application SELECT FOR UPDATE starts'
  blocked_writer_client_id=$(query_value "$seed_first_database" \
    "SELECT client_row.id
       FROM public.client AS client_row
      WHERE client_row.organization_id = '$local_organization_id'
        AND EXISTS (
            SELECT 1
              FROM public.project AS project_row
             WHERE project_row.organization_id = client_row.organization_id
               AND project_row.client_id = client_row.id
        )
      ORDER BY client_row.id
      LIMIT 1;")
  PGAPPNAME=ct532_cleanup_blocked_writer \
    psql_case "$seed_first_database" --quiet \
    <"$seed_first_directory/blocked-writer.in" \
    >"$seed_first_directory/blocked-writer.out" 2>&1 &
  blocked_writer_pid=$!
  register_current_case_child "$blocked_writer_pid"
  exec 5>"$seed_first_directory/blocked-writer.in"
  PROJECT_CLEANUP_WRITER_CONTROL_OPEN=true
  printf '%s\n' '\set VERBOSITY verbose' 'BEGIN;' \
    "SELECT 1
       FROM public.project
      WHERE organization_id = '$local_organization_id'
        AND client_id = '$blocked_writer_client_id'
      ORDER BY id
      FOR UPDATE;" >&5
  wait_for_query_value "$seed_first_database" 1 \
    "SELECT pg_catalog.count(*)
       FROM pg_catalog.pg_stat_activity AS activity
      WHERE activity.datname = pg_catalog.current_database()
        AND activity.application_name = 'ct532_cleanup_blocked_writer'
        AND EXISTS (
            SELECT 1 FROM pg_catalog.pg_locks AS lock_row
             WHERE lock_row.pid = activity.pid
               AND lock_row.relation = 'public.project'::pg_catalog.regclass
               AND lock_row.mode = 'RowShareLock'
               AND NOT lock_row.granted
        );" \
    'seed-first application delete waits for project ROW SHARE behind seed EXCLUSIVE'
  kill -0 "$blocked_writer_pid" 2>/dev/null || fail 'seed-first application delete completed while seed held EXCLUSIVE'
  printf '%s\n' 'COMMIT;' '\q' >&4
  exec 4>&-
  PROJECT_CLEANUP_RELATION_HOLDER_CONTROL_OPEN=false
  wait_for_background_success "$cleanup_relation_holder_pid" "$seed_first_directory/relation-holder.out" \
    'cleanup serialization relation holder release'
  wait_for_background_success "$lock_owner_seed_pid" "$seed_first_directory/seed.out" \
    'seed holding EXCLUSIVE'
  wait_for_query_value "$seed_first_database" 1 \
    "SELECT pg_catalog.count(*)
       FROM pg_catalog.pg_stat_activity AS activity
       JOIN pg_catalog.pg_locks AS lock_row ON lock_row.pid = activity.pid
      WHERE activity.datname = pg_catalog.current_database()
        AND activity.application_name = 'ct532_cleanup_blocked_writer'
        AND activity.state = 'idle in transaction'
        AND lock_row.relation = 'public.project'::pg_catalog.regclass
        AND lock_row.mode = 'RowShareLock'
        AND lock_row.granted;" \
    'seed-first application delete acquires project tuples after seed release'
  printf '%s\n' \
    "SELECT 1
       FROM public.client
      WHERE organization_id = '$local_organization_id'
        AND id = '$blocked_writer_client_id'
      FOR UPDATE;" >&5
  wait_for_query_value "$seed_first_database" 1 \
    "SELECT pg_catalog.count(*)
       FROM pg_catalog.pg_stat_activity AS activity
       JOIN pg_catalog.pg_locks AS lock_row ON lock_row.pid = activity.pid
      WHERE activity.datname = pg_catalog.current_database()
        AND activity.application_name = 'ct532_cleanup_blocked_writer'
        AND activity.state = 'idle in transaction'
        AND lock_row.relation = 'public.client'::pg_catalog.regclass
        AND lock_row.mode = 'RowShareLock'
        AND lock_row.granted;" \
    'seed-first application delete locks its client after its projects'
  printf '%s\n' 'ROLLBACK;' '\q' >&5
  exec 5>&-
  PROJECT_CLEANUP_WRITER_CONTROL_OPEN=false
  wait_for_background_success "$blocked_writer_pid" "$seed_first_directory/blocked-writer.out" \
    'seed-first application project-to-client delete rollback'
  if grep -Eq '40P01|deadlock detected' "$seed_first_directory/seed.out" \
      "$seed_first_directory/blocked-writer.out"; then
    sed -n '1,160p' "$seed_first_directory/seed.out" >&2
    sed -n '1,160p' "$seed_first_directory/blocked-writer.out" >&2
    fail 'seed-first EXCLUSIVE serialization produced a deadlock'
  fi
  [ "$seed_first_before" = "$(snapshot_rows "$seed_first_database")" ] || \
    fail 'seed-first project-to-client serialization changed final client, project, or audit state'
  assert_project_seed_state "$seed_first_database"

  # A direct UPDATE also waits when seed owns EXCLUSIVE, using ROW EXCLUSIVE rather than ROW SHARE.
  clone_database "$local_baseline_database"
  direct_update_database=$NEW_DATABASE
  direct_update_before=$(snapshot_rows "$direct_update_database")
  direct_update_directory=$(mktemp -d "/tmp/epoca_ct532_lock.XXXXXX")
  TEMP_DIRECTORIES="$TEMP_DIRECTORIES $direct_update_directory"
  mkfifo "$direct_update_directory/relation-holder.in"
  PGAPPNAME=ct532_cleanup_update_holder \
    psql_case "$direct_update_database" --quiet \
    <"$direct_update_directory/relation-holder.in" >"$direct_update_directory/relation-holder.out" 2>&1 &
  direct_update_relation_holder_pid=$!
  register_current_case_child "$direct_update_relation_holder_pid"
  exec 4>"$direct_update_directory/relation-holder.in"
  PROJECT_CLEANUP_RELATION_HOLDER_CONTROL_OPEN=true
  printf '%s\n' 'BEGIN; LOCK TABLE public.organization IN ACCESS EXCLUSIVE MODE;' >&4
  wait_for_query_value "$direct_update_database" 1 \
    "SELECT pg_catalog.count(*)
       FROM pg_catalog.pg_stat_activity AS activity
       JOIN pg_catalog.pg_locks AS lock_row ON lock_row.pid = activity.pid
      WHERE activity.datname = pg_catalog.current_database()
        AND activity.application_name = 'ct532_cleanup_update_holder'
        AND lock_row.relation = 'public.organization'::pg_catalog.regclass
        AND lock_row.mode = 'AccessExclusiveLock'
        AND lock_row.granted;" \
    'direct-update serialization relation holder'
  PGAPPNAME=ct532_cleanup_update_seed \
    run_apply "$direct_update_database" >"$direct_update_directory/seed.out" 2>&1 &
  direct_update_seed_pid=$!
  register_current_case_child "$direct_update_seed_pid"
  wait_for_query_value "$direct_update_database" 1 \
    "SELECT pg_catalog.count(*)
       FROM pg_catalog.pg_stat_activity AS activity
      WHERE activity.datname = pg_catalog.current_database()
        AND activity.application_name = 'ct532_cleanup_update_seed'
        AND activity.wait_event_type = 'Lock'
        AND EXISTS (
            SELECT 1 FROM pg_catalog.pg_locks AS lock_row
             WHERE lock_row.pid = activity.pid
               AND lock_row.relation = 'public.project'::pg_catalog.regclass
               AND lock_row.mode = 'ExclusiveLock'
               AND lock_row.granted
        );" \
    'seed owns EXCLUSIVE before direct UPDATE starts'
  direct_update_project_id=$(query_value "$direct_update_database" \
    "SELECT id FROM public.project WHERE organization_id = '$local_organization_id' ORDER BY id LIMIT 1;")
  PGAPPNAME=ct532_cleanup_blocked_update \
    psql_case "$direct_update_database" --quiet --command \
    "UPDATE public.project SET description = description WHERE id = '$direct_update_project_id';" \
    >"$direct_update_directory/blocked-update.out" 2>&1 &
  direct_update_writer_pid=$!
  register_current_case_child "$direct_update_writer_pid"
  wait_for_query_value "$direct_update_database" 1 \
    "SELECT pg_catalog.count(*)
       FROM pg_catalog.pg_stat_activity AS activity
      WHERE activity.datname = pg_catalog.current_database()
        AND activity.application_name = 'ct532_cleanup_blocked_update'
        AND activity.wait_event_type = 'Lock'
        AND EXISTS (
            SELECT 1 FROM pg_catalog.pg_locks AS lock_row
             WHERE lock_row.pid = activity.pid
               AND lock_row.relation = 'public.project'::pg_catalog.regclass
               AND lock_row.mode = 'RowExclusiveLock'
               AND NOT lock_row.granted
        );" \
    'direct UPDATE waits for ROW EXCLUSIVE behind seed EXCLUSIVE'
  kill -0 "$direct_update_writer_pid" 2>/dev/null || fail 'direct UPDATE completed while seed held EXCLUSIVE'
  printf '%s\n' 'COMMIT;' '\q' >&4
  exec 4>&-
  PROJECT_CLEANUP_RELATION_HOLDER_CONTROL_OPEN=false
  wait_for_background_success "$direct_update_relation_holder_pid" \
    "$direct_update_directory/relation-holder.out" 'direct-update relation holder release'
  wait_for_background_success "$direct_update_seed_pid" "$direct_update_directory/seed.out" \
    'seed holding EXCLUSIVE ahead of direct UPDATE'
  wait_for_background_success "$direct_update_writer_pid" "$direct_update_directory/blocked-update.out" \
    'direct UPDATE released by seed transaction'
  if grep -Eq '40P01|deadlock detected' "$direct_update_directory/seed.out" \
      "$direct_update_directory/blocked-update.out"; then
    sed -n '1,160p' "$direct_update_directory/seed.out" >&2
    sed -n '1,160p' "$direct_update_directory/blocked-update.out" >&2
    fail 'seed-first direct UPDATE serialization produced a deadlock'
  fi
  [ "$direct_update_before" = "$(snapshot_rows "$direct_update_database")" ] || \
    fail 'seed-first direct no-op UPDATE changed persistent row values'

  printf 'PASS: project-cleanup\n'
}

case_work_units() {
  new_database
  database=$NEW_DATABASE
  apply_migrations "$database"

  dry_run_before=$(snapshot_rows "$database")
  dry_run_output=$(run_dry_run "$database")
  dry_run_after=$(snapshot_rows "$database")
  [ "$dry_run_before" = "$dry_run_after" ] || fail 'work-unit dry-run changed persistent rows'

  apply_output=$(run_apply "$database")
  assert_work_unit_seed_state "$database" 2026-05-18 2026-02-23 2026-05-17

  assert_change_plan_eq "$dry_run_output" work_unit insert 1360 'dry-run work-unit inserts'
  assert_change_plan_eq "$apply_output" work_unit insert 1360 'first apply work-unit inserts'
  assert_change_plan_eq "$dry_run_output" active_worklog insert 1200 'dry-run active Worklog inserts'
  assert_change_plan_eq "$dry_run_output" active_note insert 100 'dry-run active Note inserts'
  assert_change_plan_eq "$dry_run_output" historical_worklog insert 60 'dry-run historical Worklog inserts'
  assert_change_plan_eq "$apply_output" active_worklog insert 1200 'first apply active Worklog inserts'
  assert_change_plan_eq "$apply_output" active_note insert 100 'first apply active Note inserts'
  assert_change_plan_eq "$apply_output" historical_worklog insert 60 'first apply historical Worklog inserts'

  before_rerun=$(snapshot_core "$database")
  assert_core_snapshot_contract "$before_rerun" 'same-day Worklog and Note snapshot'
  rerun_output=$(run_apply "$database")
  after_rerun=$(snapshot_core "$database")
  assert_core_snapshot_unchanged "$before_rerun" "$after_rerun" \
    'same-day Worklog and Note rerun was not byte-stable'
  for operation in insert update activate archive; do
    assert_change_plan_eq "$rerun_output" work_unit "$operation" 0 "same-day work-unit $operation"
    assert_change_plan_eq "$rerun_output" active_worklog "$operation" 0 "same-day active Worklog $operation"
    assert_change_plan_eq "$rerun_output" active_note "$operation" 0 "same-day active Note $operation"
    assert_change_plan_eq "$rerun_output" historical_worklog "$operation" 0 "same-day historical Worklog $operation"
  done
  run_standalone_verify "$database" >/dev/null

  invalid_work_unit_id=$(query_value "$database" \
    "SELECT work_unit_row.id
     FROM work_unit work_unit_row
     JOIN user_identity identity ON identity.user_id = work_unit_row.user_id
     WHERE work_unit_row.state = 'Active'
       AND work_unit_row.archived_at IS NULL
       AND work_unit_row.entry_type = 'Worklog'
       AND identity.value LIKE 'demo+%@epoca.example.invalid'
     ORDER BY work_unit_row.id LIMIT 1;")
  psql_case "$database" --quiet --command \
    "UPDATE work_unit SET duration = interval '14 minutes' WHERE id = '$invalid_work_unit_id';" >/dev/null
  expect_verify_failure_unchanged "$database" \
    'exact generated work-unit target' \
    'invalid active Worklog duration'

  printf 'PASS: work-units\n'
}

case_rolling_window() {
  new_database
  database=$NEW_DATABASE
  apply_migrations "$database"
  run_apply "$database" >/dev/null

  psql_case "$database" --quiet --command "
    CREATE TABLE ct532_old_active_work_unit (id uuid PRIMARY KEY);
    INSERT INTO ct532_old_active_work_unit (id)
    SELECT DISTINCT work_unit_row.id
    FROM work_unit work_unit_row
    JOIN user_identity identity ON identity.user_id = work_unit_row.user_id
    CROSS JOIN LATERAL generate_series(1, 64) AS slot(logical_slot)
    WHERE work_unit_row.state = 'Active'
      AND work_unit_row.archived_at IS NULL
      AND identity.value ~ '^demo\\+.+@epoca\\.example\\.invalid$'
      AND work_unit_row.id = public.uuid_generate_v5(
        '2f2ed0a0-4d42-5f32-8f49-5d36cc3a0532'::uuid,
        'work-unit:v1:' || substring(identity.value FROM '^demo\\+(.+)@epoca\\.example\\.invalid$') || ':' ||
        work_unit_row.date::text || ':' || slot.logical_slot::text
      );

    INSERT INTO work_unit (
      id, organization_id, user_id, project_id, date, duration, description,
      created_at, modified_at, archived_at, task_id, state, client_id, entry_type
    )
    SELECT '79000000-0000-4000-8000-000000000001',
           project_row.organization_id,
           project_membership.user_id,
           project_row.id,
           '2026-05-10',
           interval '45 minutes',
           'Foreign fixture must stay byte-identical',
           '2026-05-10 09:00:00+00',
           '2026-05-10 09:00:00+00',
           NULL,
           'FOREIGN-1',
           'Active',
           project_row.client_id,
           'Worklog'
    FROM project project_row
    JOIN project_user project_membership
      ON project_membership.project_id = project_row.id
     AND project_membership.archived_at IS NULL
    JOIN user_identity identity ON identity.user_id = project_membership.user_id
    WHERE project_row.archived_at IS NULL
      AND identity.value LIKE 'demo+%@epoca.example.invalid'
    ORDER BY project_row.id, project_membership.user_id
    LIMIT 1;" >/dev/null

  foreign_before=$(query_value "$database" \
    "SELECT row_to_json(work_unit_row)::text FROM work_unit work_unit_row WHERE id = '79000000-0000-4000-8000-000000000001';")

  shifted_output=$(run_apply_today "$database" 2026-06-15)
  assert_work_unit_seed_state "$database" 2026-06-15 2026-03-23 2026-06-14
  assert_change_plan_positive "$shifted_output" work_unit insert 'shifted window inserts new deterministic work units'
  assert_change_plan_positive "$shifted_output" work_unit archive 'shifted window archives retired deterministic work units'

  assert_query_eq "$database" 1 \
    "SELECT CASE WHEN count(*) > 0
                      AND bool_and(work_unit_row.state = 'Archived' AND work_unit_row.archived_at IS NOT NULL)
                 THEN 1 ELSE 0 END
     FROM ct532_old_active_work_unit old_target
     JOIN work_unit work_unit_row ON work_unit_row.id = old_target.id
     WHERE work_unit_row.state = 'Archived';" \
    'retired previous-window work units are archived, not deleted'
  assert_query_eq "$database" 0 \
    "SELECT count(*)
     FROM ct532_old_active_work_unit old_target
     JOIN work_unit work_unit_row ON work_unit_row.id = old_target.id
     WHERE work_unit_row.state = 'Active'
       AND (work_unit_row.archived_at IS NOT NULL
            OR work_unit_row.date < '2026-03-23'
            OR work_unit_row.date > '2026-06-14');" \
    'no previous-window owned row remains active outside the shifted window'

  foreign_after=$(query_value "$database" \
    "SELECT row_to_json(work_unit_row)::text FROM work_unit work_unit_row WHERE id = '79000000-0000-4000-8000-000000000001';")
  [ "$foreign_before" = "$foreign_after" ] || fail 'rolling window modified a foreign work unit'
  run_standalone_verify_today "$database" 2026-06-15 >/dev/null

  dry_run_before=$(snapshot_rows "$database")
  future_dry_run=$(run_dry_run_today "$database" 2026-07-13)
  dry_run_after=$(snapshot_rows "$database")
  [ "$dry_run_before" = "$dry_run_after" ] || fail 'shifted dry-run changed persistent work-unit state'
  assert_change_plan_positive "$future_dry_run" work_unit insert 'future dry-run projects work-unit inserts'
  assert_change_plan_positive "$future_dry_run" work_unit archive 'future dry-run projects work-unit archival'
  run_standalone_verify_today "$database" 2026-06-15 >/dev/null

  printf 'PASS: rolling-window\n'
}

case_work_unit_collisions() {
  new_database
  database=$NEW_DATABASE
  apply_migrations "$database"
  run_apply "$database" >/dev/null

  victim_id=$(query_value "$database" \
    "SELECT id FROM work_unit WHERE state = 'Active' AND archived_at IS NULL ORDER BY id LIMIT 1;")
  victim_date=$(query_value "$database" "SELECT date FROM work_unit WHERE id = '$victim_id';")
  victim_user_id=$(query_value "$database" "SELECT user_id FROM work_unit WHERE id = '$victim_id';")
  psql_case "$database" --quiet --command "
    DELETE FROM work_unit WHERE id = '$victim_id';
    INSERT INTO work_unit (
      id, organization_id, user_id, project_id, date, duration, description,
      created_at, modified_at, archived_at, task_id, state, client_id, entry_type
    )
    SELECT '$victim_id', project_row.organization_id, project_membership.user_id,
           project_row.id, '$victim_date', interval '1 hour',
           'Foreign deterministic-id collision', '2026-01-01 00:00:00+00',
           '2026-01-01 00:00:00+00', NULL, 'COLLISION-1', 'Active',
           project_row.client_id, 'Worklog'
    FROM project project_row
    JOIN project_user project_membership
      ON project_membership.project_id = project_row.id
     AND project_membership.archived_at IS NULL
    WHERE project_membership.user_id <> '$victim_user_id'
    ORDER BY project_row.id, project_membership.user_id
    LIMIT 1;" >/dev/null

  expect_apply_failure_unchanged "$database" \
    'deterministic work-unit id is owned by another row' \
    'foreign row under a deterministic work-unit id'

  new_database
  invalid_database=$NEW_DATABASE
  apply_migrations "$invalid_database"
  run_apply "$invalid_database" >/dev/null
  invalid_id=$(query_value "$invalid_database" \
    "SELECT id FROM work_unit WHERE state = 'Active' AND archived_at IS NULL AND entry_type = 'Worklog' ORDER BY id LIMIT 1;")
  psql_case "$invalid_database" --quiet --command \
    "UPDATE work_unit SET duration = interval '14 minutes' WHERE id = '$invalid_id';" >/dev/null
  expect_apply_failure_unchanged "$invalid_database" \
    'owned work-unit has an invalid parent, type, or duration' \
    'owned Worklog with invalid duration granularity'

  printf 'PASS: work-unit-collisions\n'
}

case_work_unit_schema_guards() {
  new_database
  database=$NEW_DATABASE
  apply_migrations "$database"
  psql_case "$database" --quiet --command \
    'ALTER TABLE public.work_unit DROP CONSTRAINT work_unit_project_id_fkey;' >/dev/null
  expect_apply_failure_unchanged "$database" \
    'unexpected work unit constraints' \
    'missing work-unit project foreign key'

  new_database
  verify_database=$NEW_DATABASE
  apply_migrations "$verify_database"
  run_apply "$verify_database" >/dev/null
  psql_case "$verify_database" --quiet --command \
    'ALTER TABLE public.work_unit DROP CONSTRAINT work_unit_entry_type_duration_check;' >/dev/null
  expect_verify_failure_unchanged "$verify_database" \
    'unexpected work unit constraints' \
    'missing work-unit entry-type duration constraint'

  printf 'PASS: work-unit-schema-guards\n'
}

case_exact_work_unit_verification() {
  new_database
  source_database=$NEW_DATABASE
  apply_migrations "$source_database"
  run_apply "$source_database" >/dev/null

  mutation_labels=(
    'valid-domain duration drift'
    'in-window date drift'
    'description drift'
    'task drift'
    'project parent drift'
    'deterministic id drift'
    'created audit drift'
    'modified audit drift'
    'entry-type drift'
    'state drift'
  )
  mutation_sql=(
    "WITH victim AS (
       SELECT id FROM work_unit
       WHERE state = 'Active' AND entry_type = 'Worklog' AND duration = interval '15 minutes'
       ORDER BY id LIMIT 1
     )
     UPDATE work_unit SET duration = interval '30 minutes' FROM victim WHERE work_unit.id = victim.id;"
    "WITH victim AS (
       SELECT id FROM work_unit
       WHERE state = 'Active' AND date < '2026-05-17'
       ORDER BY id LIMIT 1
     )
     UPDATE work_unit SET date = date + 1 FROM victim WHERE work_unit.id = victim.id;"
    "WITH victim AS (
       SELECT id FROM work_unit WHERE state = 'Active' ORDER BY id LIMIT 1
     )
     UPDATE work_unit SET description = description || ' [drift]' FROM victim WHERE work_unit.id = victim.id;"
    "WITH victim AS (
       SELECT id FROM work_unit WHERE state = 'Active' AND entry_type = 'Worklog' ORDER BY id LIMIT 1
     )
     UPDATE work_unit SET task_id = task_id || '9' FROM victim WHERE work_unit.id = victim.id;"
    "WITH victim AS (
       SELECT id, user_id, project_id
       FROM work_unit WHERE state = 'Active' ORDER BY id LIMIT 1
     ), replacement AS (
       SELECT victim.id, project_user.project_id
       FROM victim
       JOIN project_user ON project_user.user_id = victim.user_id
                        AND project_user.project_id <> victim.project_id
                        AND project_user.archived_at IS NULL
       JOIN project ON project.id = project_user.project_id AND project.archived_at IS NULL
       ORDER BY project_user.project_id LIMIT 1
     )
     UPDATE work_unit SET project_id = replacement.project_id
     FROM replacement WHERE work_unit.id = replacement.id;"
    "WITH victim AS (
       SELECT id FROM work_unit WHERE state = 'Active' ORDER BY id LIMIT 1
     )
     UPDATE work_unit SET id = public.uuid_generate_v5(work_unit.id, 'drift')
     FROM victim WHERE work_unit.id = victim.id;"
    "WITH victim AS (
       SELECT id FROM work_unit WHERE state = 'Active' ORDER BY id LIMIT 1
     )
     UPDATE work_unit SET created_at = created_at + interval '1 minute'
     FROM victim WHERE work_unit.id = victim.id;"
    "WITH victim AS (
       SELECT id FROM work_unit WHERE state = 'Active' ORDER BY id LIMIT 1
     )
     UPDATE work_unit SET modified_at = created_at + interval '1 minute'
     FROM victim WHERE work_unit.id = victim.id;"
    "WITH victim AS (
       SELECT id FROM work_unit WHERE state = 'Active' AND entry_type = 'Worklog' ORDER BY id LIMIT 1
     )
     UPDATE work_unit SET entry_type = 'Note', duration = interval '0 seconds', task_id = NULL
     FROM victim WHERE work_unit.id = victim.id;"
    "WITH victim AS (
       SELECT id FROM work_unit WHERE state = 'Active' ORDER BY id LIMIT 1
     )
     UPDATE work_unit SET state = 'Draft' FROM victim WHERE work_unit.id = victim.id;"
  )

  for mutation_index in "${!mutation_labels[@]}"; do
    clone_database "$source_database"
    database=$NEW_DATABASE
    psql_case "$database" --quiet --command "${mutation_sql[$mutation_index]}" >/dev/null
    expect_verify_failure_unchanged "$database" \
      'exact generated work-unit target' \
      "${mutation_labels[$mutation_index]} from deterministic target"
  done

  printf 'PASS: exact-work-unit-verification\n'
}

case_retired_work_unit_lifecycle() {
  new_database
  source_database=$NEW_DATABASE
  apply_migrations "$source_database"
  run_apply "$source_database" >/dev/null
  run_apply_today "$source_database" 2026-06-15 >/dev/null

  retired_labels=(
    'Draft retired row'
    'Active retired row'
    'Archived retired row without archived_at'
  )
  retired_mutations=(
    "SET state = 'Draft'"
    "SET state = 'Active', archived_at = NULL"
    "SET archived_at = NULL"
  )

  for mutation_index in "${!retired_labels[@]}"; do
    clone_database "$source_database"
    database=$NEW_DATABASE
    retired_id=$(query_value "$database" \
      "SELECT id
       FROM work_unit
       WHERE state = 'Archived'
         AND archived_at IS NOT NULL
         AND date < '2026-03-23'
         AND description !~ '^Historical delivery wrap-up'
       ORDER BY id
       LIMIT 1;")
    psql_case "$database" --quiet --command \
      "UPDATE work_unit ${retired_mutations[$mutation_index]} WHERE id = '$retired_id';" >/dev/null
    expect_verify_failure_unchanged_today "$database" 2026-06-15 \
      'retired seed-owned work unit is not archived' \
      "${retired_labels[$mutation_index]} lifecycle"
  done

  printf 'PASS: retired-work-unit-lifecycle\n'
}

case_verify_lock_serialization() {
  CURRENT_CASE_CHILD_PIDS=
  CURRENT_CASE_UNREAPED_CHILD_PIDS=
  new_database
  database=$NEW_DATABASE
  apply_migrations "$database"
  assert_query_eq "$database" 130004 \
    "SELECT current_setting('server_version_num');" \
    'concurrency fixture uses PostgreSQL 13.4'
  run_apply "$database" >/dev/null

  lock_test_directory=$(mktemp -d "/tmp/epoca_ct532_lock.XXXXXX")
  TEMP_DIRECTORIES="$TEMP_DIRECTORIES $lock_test_directory"
  lock_key_sql="pg_catalog.hashtextextended('CT-532:iteratorstesting', 0)"
  before_snapshot=$(snapshot_rows "$database")

  tail -f /dev/null >"$lock_test_directory/non-terminating-child.out" 2>&1 &
  non_terminating_child_pid=$!
  register_current_case_child "$non_terminating_child_pid"
  if wait_for_background_bounded "$non_terminating_child_pid" \
      "$lock_test_directory/non-terminating-child.out" \
      'bounded wait self-check child' 4; then
    bounded_self_check_status=0
  else
    bounded_self_check_status=$?
  fi
  [ "$bounded_self_check_status" -eq 124 ] || fail 'bounded wait self-check did not report its deadline'
  if kill -0 "$non_terminating_child_pid" 2>/dev/null; then
    fail 'bounded wait self-check left its child running'
  fi

  mkfifo "$lock_test_directory/advisory-holder.in"
  PGAPPNAME=ct532_advisory_holder \
    psql_case "$database" --quiet \
    <"$lock_test_directory/advisory-holder.in" \
    >"$lock_test_directory/advisory-holder.out" 2>&1 &
  advisory_holder_pid=$!
  register_current_case_child "$advisory_holder_pid"
  exec 8>"$lock_test_directory/advisory-holder.in"
  ADVISORY_HOLDER_CONTROL_OPEN=true
  printf 'SELECT pg_catalog.pg_advisory_lock(%s);\n' "$lock_key_sql" >&8
  wait_for_query_value "$database" 1 \
    "SELECT count(*)
       FROM pg_catalog.pg_stat_activity activity
       JOIN pg_catalog.pg_locks lock_row ON lock_row.pid = activity.pid
      WHERE activity.datname = current_database()
        AND activity.application_name = 'ct532_advisory_holder'
        AND lock_row.locktype = 'advisory'
        AND lock_row.granted;" \
    'advisory blocker session lock'

  PGAPPNAME=ct532_blocked_verify \
    run_standalone_verify "$database" \
    >"$lock_test_directory/blocked-verify.out" 2>&1 &
  blocked_verify_pid=$!
  register_current_case_child "$blocked_verify_pid"
  wait_for_query_value "$database" 1 \
    "SELECT count(*)
       FROM pg_catalog.pg_stat_activity activity
      WHERE activity.datname = current_database()
        AND activity.application_name = 'ct532_blocked_verify'
        AND EXISTS (
            SELECT 1 FROM pg_catalog.pg_locks lock_row
             WHERE lock_row.pid = activity.pid
               AND lock_row.locktype = 'advisory'
               AND NOT lock_row.granted
        );" \
    'standalone verify waiting for the seed advisory key'
  kill -0 "$blocked_verify_pid" 2>/dev/null || fail 'standalone verify completed while the seed advisory key was held'

  printf 'SELECT pg_catalog.pg_advisory_unlock(%s);\n\\q\n' "$lock_key_sql" >&8
  exec 8>&-
  ADVISORY_HOLDER_CONTROL_OPEN=false
  wait_for_background_success "$advisory_holder_pid" "$lock_test_directory/advisory-holder.out" \
    'advisory blocker session'
  wait_for_background_success "$blocked_verify_pid" "$lock_test_directory/blocked-verify.out" \
    'standalone verify released by the seed advisory key'

  mkfifo "$lock_test_directory/apply-advisory-holder.in"
  PGAPPNAME=ct532_advisory_holder \
    psql_case "$database" --quiet \
    <"$lock_test_directory/apply-advisory-holder.in" \
    >"$lock_test_directory/apply-advisory-holder.out" 2>&1 &
  apply_advisory_holder_pid=$!
  register_current_case_child "$apply_advisory_holder_pid"
  exec 8>"$lock_test_directory/apply-advisory-holder.in"
  ADVISORY_HOLDER_CONTROL_OPEN=true
  printf 'SELECT pg_catalog.pg_advisory_lock(%s);\n' "$lock_key_sql" >&8
  wait_for_query_value "$database" 1 \
    "SELECT count(*)
       FROM pg_catalog.pg_stat_activity activity
       JOIN pg_catalog.pg_locks lock_row ON lock_row.pid = activity.pid
      WHERE activity.datname = current_database()
        AND activity.application_name = 'ct532_advisory_holder'
        AND lock_row.locktype = 'advisory'
        AND lock_row.granted;" \
    'advisory blocker for the real apply runner'

  PGAPPNAME=ct532_blocked_apply \
    run_apply "$database" \
    >"$lock_test_directory/blocked-apply.out" 2>&1 &
  blocked_apply_pid=$!
  register_current_case_child "$blocked_apply_pid"
  wait_for_query_value "$database" 1 \
    "SELECT count(*)
       FROM pg_catalog.pg_stat_activity activity
      WHERE activity.datname = current_database()
        AND activity.application_name = 'ct532_blocked_apply'
        AND EXISTS (
            SELECT 1 FROM pg_catalog.pg_locks lock_row
             WHERE lock_row.pid = activity.pid
               AND lock_row.locktype = 'advisory'
               AND NOT lock_row.granted
        );" \
    'real apply runner waiting for the seed advisory key'
  kill -0 "$blocked_apply_pid" 2>/dev/null || fail 'real apply runner completed while the seed advisory key was held'

  printf 'SELECT pg_catalog.pg_advisory_unlock(%s);\n\\q\n' "$lock_key_sql" >&8
  exec 8>&-
  ADVISORY_HOLDER_CONTROL_OPEN=false
  wait_for_background_success "$apply_advisory_holder_pid" \
    "$lock_test_directory/apply-advisory-holder.out" 'apply advisory blocker session'
  wait_for_background_success "$blocked_apply_pid" "$lock_test_directory/blocked-apply.out" \
    'real apply runner released by the seed advisory key'
  blocked_apply_output=$(cat "$lock_test_directory/blocked-apply.out")
  for operation in insert update activate archive; do
    assert_change_plan_eq "$blocked_apply_output" user "$operation" 0 \
      "serialized same-day apply user $operation"
    assert_change_plan_eq "$blocked_apply_output" work_unit "$operation" 0 \
      "serialized same-day apply work-unit $operation"
  done
  assert_core_distribution "$database" 30
  assert_query_eq "$database" 0 \
    "SELECT count(*) FROM (
       SELECT membership.user_id
       FROM organization_user membership
       JOIN organization organization_row ON organization_row.id = membership.organization_id
       WHERE organization_row.slug = 'iteratorstesting'
         AND membership.archived_at IS NULL
       GROUP BY membership.user_id
       HAVING count(*) > 1
     ) duplicate_memberships;" \
    'serialized apply leaves no dual active membership'

  mkfifo "$lock_test_directory/relation-holder.in"
  PGAPPNAME=ct532_relation_holder \
    psql_case "$database" --quiet \
    <"$lock_test_directory/relation-holder.in" \
    >"$lock_test_directory/relation-holder.out" 2>&1 &
  relation_holder_pid=$!
  register_current_case_child "$relation_holder_pid"
  exec 7>"$lock_test_directory/relation-holder.in"
  RELATION_HOLDER_CONTROL_OPEN=true
  printf '%s\n' 'BEGIN; LOCK TABLE public.organization IN ACCESS EXCLUSIVE MODE;' >&7
  wait_for_query_value "$database" 1 \
    "SELECT count(*)
       FROM pg_catalog.pg_stat_activity activity
      WHERE activity.datname = current_database()
        AND activity.application_name = 'ct532_relation_holder'
        AND EXISTS (
            SELECT 1 FROM pg_catalog.pg_locks lock_row
             WHERE lock_row.pid = activity.pid
               AND lock_row.relation = 'public.organization'::pg_catalog.regclass
               AND lock_row.mode = 'AccessExclusiveLock'
               AND lock_row.granted
        );" \
    'relation blocker for the standalone assertion phase'

  PGAPPNAME=ct532_lock_owner_verify \
    run_standalone_verify "$database" \
    >"$lock_test_directory/lock-owner-verify.out" 2>&1 &
  lock_owner_verify_pid=$!
  register_current_case_child "$lock_owner_verify_pid"
  wait_for_query_value "$database" 1 \
    "SELECT count(*)
       FROM pg_catalog.pg_stat_activity activity
      WHERE activity.datname = current_database()
        AND activity.application_name = 'ct532_lock_owner_verify'
        AND activity.wait_event_type = 'Lock'
        AND EXISTS (
            SELECT 1 FROM pg_catalog.pg_locks lock_row
             WHERE lock_row.pid = activity.pid
               AND lock_row.locktype = 'advisory'
               AND lock_row.granted
        );" \
    'standalone verify holding the seed advisory key during assertions'

  PGAPPNAME=ct532_blocked_dry_run \
    run_dry_run "$database" \
    >"$lock_test_directory/blocked-dry-run.out" 2>&1 &
  blocked_dry_run_pid=$!
  register_current_case_child "$blocked_dry_run_pid"
  wait_for_query_value "$database" 1 \
    "SELECT count(*)
       FROM pg_catalog.pg_stat_activity activity
      WHERE activity.datname = current_database()
        AND activity.application_name = 'ct532_blocked_dry_run'
        AND EXISTS (
            SELECT 1 FROM pg_catalog.pg_locks lock_row
             WHERE lock_row.pid = activity.pid
               AND lock_row.locktype = 'advisory'
               AND NOT lock_row.granted
        );" \
    'dry-run waiting for standalone verification to unlock'
  kill -0 "$blocked_dry_run_pid" 2>/dev/null || fail 'dry-run completed while standalone verify held the seed advisory key'

  printf '%s\n' 'COMMIT;' '\q' >&7
  exec 7>&-
  RELATION_HOLDER_CONTROL_OPEN=false
  wait_for_background_success "$relation_holder_pid" "$lock_test_directory/relation-holder.out" \
    'relation blocker session'
  wait_for_background_success "$lock_owner_verify_pid" "$lock_test_directory/lock-owner-verify.out" \
    'lock-owning standalone verify'
  wait_for_background_success "$blocked_dry_run_pid" "$lock_test_directory/blocked-dry-run.out" \
    'dry-run released by standalone verify'

  after_snapshot=$(snapshot_rows "$database")
  [ "$before_snapshot" = "$after_snapshot" ] || fail 'verify/seed advisory serialization changed persistent rows'
  assert_query_eq "$database" 0 \
    "SELECT count(*)
       FROM pg_catalog.pg_stat_activity activity
       JOIN pg_catalog.pg_locks lock_row ON lock_row.pid = activity.pid
      WHERE activity.datname = current_database()
        AND activity.application_name IN (
            'ct532_blocked_verify', 'ct532_lock_owner_verify', 'ct532_blocked_dry_run'
        )
        AND lock_row.locktype = 'advisory';" \
    'standalone verify explicitly releases the seed advisory key'

  mkfifo "$lock_test_directory/timeout-relation-holder.in"
  PGAPPNAME=ct532_timeout_relation_holder \
    psql_case "$database" --quiet \
    <"$lock_test_directory/timeout-relation-holder.in" \
    >"$lock_test_directory/timeout-relation-holder.out" 2>&1 &
  timeout_relation_holder_pid=$!
  register_current_case_child "$timeout_relation_holder_pid"
  exec 6>"$lock_test_directory/timeout-relation-holder.in"
  TIMEOUT_RELATION_HOLDER_CONTROL_OPEN=true
  printf '%s\n' 'BEGIN; LOCK TABLE public.work_unit_idempotency IN ACCESS EXCLUSIVE MODE;' >&6
  wait_for_query_value "$database" 1 \
    "SELECT count(*)
       FROM pg_catalog.pg_stat_activity activity
      WHERE activity.datname = current_database()
        AND activity.application_name = 'ct532_timeout_relation_holder'
        AND EXISTS (
            SELECT 1 FROM pg_catalog.pg_locks lock_row
             WHERE lock_row.pid = activity.pid
               AND lock_row.relation = 'public.work_unit_idempotency'::pg_catalog.regclass
               AND lock_row.mode = 'AccessExclusiveLock'
               AND lock_row.granted
        );" \
    'application writer relation lock for the assertion timeout'

  PGAPPNAME=ct532_timeout_verify \
    run_standalone_verify "$database" \
    >"$lock_test_directory/timeout-verify.out" 2>&1 &
  timeout_verify_pid=$!
  register_current_case_child "$timeout_verify_pid"
  wait_for_query_value "$database" 1 \
    "SELECT count(*)
       FROM pg_catalog.pg_stat_activity activity
      WHERE activity.datname = current_database()
        AND activity.application_name = 'ct532_timeout_verify'
        AND activity.wait_event_type = 'Lock'
        AND EXISTS (
            SELECT 1 FROM pg_catalog.pg_locks lock_row
             WHERE lock_row.pid = activity.pid
               AND lock_row.locktype = 'advisory'
               AND lock_row.granted
        )
        AND EXISTS (
            SELECT 1 FROM pg_catalog.pg_locks lock_row
             WHERE lock_row.pid = activity.pid
               AND lock_row.relation = 'public.work_unit_idempotency'::pg_catalog.regclass
               AND NOT lock_row.granted
        );" \
    'standalone verify waiting on an application lock during its assertion transaction'

  if wait_for_background_bounded "$timeout_verify_pid" "$lock_test_directory/timeout-verify.out" \
      'assertion lock-timeout standalone verify' 300; then
    timeout_verify_status=0
  else
    timeout_verify_status=$?
  fi
  if [ "$timeout_verify_status" -eq 124 ]; then
    printf '%s\n' 'ROLLBACK;' '\q' >&6
    exec 6>&-
    TIMEOUT_RELATION_HOLDER_CONTROL_OPEN=false
    wait_for_background_success "$timeout_relation_holder_pid" \
      "$lock_test_directory/timeout-relation-holder.out" \
      'application writer relation-lock session after verify deadline'
    fail 'assertion lock-timeout standalone verify exceeded the bounded child deadline'
  fi
  [ "$timeout_verify_status" -ne 0 ] || fail 'assertion lock-timeout standalone verify unexpectedly succeeded'
  grep -q 'canceling statement due to lock timeout' "$lock_test_directory/timeout-verify.out" || {
    sed -n '1,160p' "$lock_test_directory/timeout-verify.out" >&2
    fail 'assertion lock-timeout standalone verify masked the PostgreSQL lock-timeout diagnostic'
  }
  assert_query_eq "$database" 0 \
    "SELECT count(*)
       FROM pg_catalog.pg_stat_activity activity
       JOIN pg_catalog.pg_locks lock_row ON lock_row.pid = activity.pid
      WHERE activity.datname = current_database()
        AND activity.application_name = 'ct532_timeout_verify'
        AND lock_row.locktype = 'advisory';" \
    'assertion SQL error closes the verify connection and releases its session advisory lock'

  printf '%s\n' 'ROLLBACK;' '\q' >&6
  exec 6>&-
  TIMEOUT_RELATION_HOLDER_CONTROL_OPEN=false
  wait_for_background_success "$timeout_relation_holder_pid" \
    "$lock_test_directory/timeout-relation-holder.out" \
    'application writer relation-lock session'
  timeout_after_snapshot=$(snapshot_rows "$database")
  [ "$before_snapshot" = "$timeout_after_snapshot" ] || fail 'assertion lock-timeout verification changed persistent rows'

  printf 'PASS: verify-lock-serialization\n'
}

case_external_project_capacity() {
  new_database
  database=$NEW_DATABASE
  apply_migrations "$database"
  run_apply "$database" >/dev/null

  project_id=$(query_value "$database" "SELECT public.uuid_generate_v5('2f2ed0a0-4d42-5f32-8f49-5d36cc3a0532'::uuid, 'project:patient-portal');")
  lucas_user_id=$(query_value "$database" "SELECT public.uuid_generate_v5('2f2ed0a0-4d42-5f32-8f49-5d36cc3a0532'::uuid, 'user:lucas-martin');")
  nina_user_id=$(query_value "$database" "SELECT public.uuid_generate_v5('2f2ed0a0-4d42-5f32-8f49-5d36cc3a0532'::uuid, 'user:nina-petrova');")
  lucas_project_user_id=$(query_value "$database" "SELECT public.uuid_generate_v5('2f2ed0a0-4d42-5f32-8f49-5d36cc3a0532'::uuid, 'project-user:patient-portal:lucas-martin');")
  nina_project_user_id=$(query_value "$database" "SELECT public.uuid_generate_v5('2f2ed0a0-4d42-5f32-8f49-5d36cc3a0532'::uuid, 'project-user:patient-portal:nina-petrova');")
  psql_case "$database" --quiet --command "
    WITH replaceable AS (
      SELECT pu.id
      FROM project_user pu
      JOIN project p ON p.id = pu.project_id
      JOIN organization_user ou
        ON ou.organization_id = p.organization_id
       AND ou.user_id = pu.user_id
       AND ou.archived_at IS NULL
      WHERE pu.project_id = '$project_id'
        AND pu.archived_at IS NULL
        AND pu.role = 'Member'
        AND ou.roles = ARRAY['Employee']::TEXT[]
        AND (
          SELECT count(*)
          FROM project_user other_membership
          JOIN project other_project ON other_project.id = other_membership.project_id
          WHERE other_membership.user_id = pu.user_id
            AND other_membership.archived_at IS NULL
            AND other_project.archived_at IS NULL
        ) > 1
      ORDER BY pu.id
      LIMIT 2
    )
    UPDATE project_user
    SET archived_at = '2026-04-18 00:00:00+00'
    WHERE id IN (SELECT id FROM replaceable);

    INSERT INTO project_user (id, project_id, user_id, role, created_at, archived_at) VALUES
      ('$lucas_project_user_id', '$project_id', '$lucas_user_id', 'Member', '2025-01-01 00:00:00+00', NULL),
      ('$nina_project_user_id', '$project_id', '$nina_user_id', 'Member', '2025-01-01 00:00:01+00', NULL)
    ON CONFLICT (id) DO UPDATE SET role = EXCLUDED.role, archived_at = NULL;" >/dev/null

  assert_query_eq "$database" 12 \
    "SELECT count(*) FROM project_user WHERE project_id = '$project_id' AND archived_at IS NULL;" \
    'External-capacity fixture preserves catalog team size'
  assert_query_eq "$database" 3 \
    "SELECT count(*)
     FROM project_user pu
     JOIN project p ON p.id = pu.project_id
     JOIN organization_user ou
       ON ou.organization_id = p.organization_id
      AND ou.user_id = pu.user_id
      AND ou.archived_at IS NULL
     WHERE pu.project_id = '$project_id'
       AND pu.archived_at IS NULL
       AND ou.roles = ARRAY['External']::TEXT[];" \
    'External-capacity fixture has three active Externals in one project'
  expect_verify_failure_unchanged "$database" \
    'more than two active External members' \
    'three active Externals in one active project'
  printf 'PASS: external-project-capacity\n'
}

case_foreign_active_project_assignment() {
  new_database
  database=$NEW_DATABASE
  apply_migrations "$database"
  run_apply "$database" >/dev/null

  organization_id=$(query_value "$database" "SELECT id FROM organization WHERE slug = 'iteratorstesting';")
  project_id=$(query_value "$database" "SELECT public.uuid_generate_v5('2f2ed0a0-4d42-5f32-8f49-5d36cc3a0532'::uuid, 'project:delivery-operations-hub');")
  foreign_user_id=5c000000-0000-4000-8000-000000000001
  archived_project_user_id=5c000000-0000-4000-8000-000000000004
  active_project_user_id=5c000000-0000-4000-8000-000000000005

  insert_fixture_user "$database" "$foreign_user_id" NULL
  psql_case "$database" --quiet --command "
    INSERT INTO user_identity (id, user_id, type, value, main, device_id, confirmed, confirmation_code, created_at)
    VALUES ('5c000000-0000-4000-8000-000000000002', '$foreign_user_id', 'MailAddress',
            'foreign.project.member@example.test', true, NULL, true, NULL, '2024-01-01 00:00:00');
    INSERT INTO organization_user (id, organization_id, user_id, roles, archived_at)
    VALUES ('5c000000-0000-4000-8000-000000000003', '$organization_id', '$foreign_user_id',
            ARRAY['Employee'], NULL);
    INSERT INTO project_user (id, project_id, user_id, role, created_at, archived_at)
    VALUES ('$archived_project_user_id', '$project_id', '$foreign_user_id', 'Member',
            '2024-01-01 00:00:00+00', '2025-01-01 00:00:00+00');" >/dev/null

  archived_before=$(query_value "$database" "SELECT row_to_json(project_user_row)::text FROM project_user project_user_row WHERE id = '$archived_project_user_id';")
  run_apply "$database" >/dev/null
  run_standalone_verify "$database" >/dev/null
  archived_after=$(query_value "$database" "SELECT row_to_json(project_user_row)::text FROM project_user project_user_row WHERE id = '$archived_project_user_id';")
  [ "$archived_before" = "$archived_after" ] || fail 'archived foreign project assignment changed during accepted apply or verify'

  psql_case "$database" --quiet --command "
    INSERT INTO project_user (id, project_id, user_id, role, created_at, archived_at)
    VALUES ('$active_project_user_id', '$project_id', '$foreign_user_id', 'Member',
            '2024-01-02 00:00:00+00', NULL);" >/dev/null

  assert_query_eq "$database" 0 \
    "WITH project_key(project_key) AS (
       VALUES
         ('delivery-operations-hub'), ('design-system-evolution'),
         ('time-intelligence'), ('reporting-sandbox'),
         ('patient-portal'), ('care-analytics'),
         ('omnichannel-checkout'), ('retail-insights'),
         ('fleet-companion'), ('mobility-discovery'),
         ('risk-dashboard'), ('open-banking-integration'),
         ('learning-experience'), ('commerce-growth'),
         ('dispatch-optimizer'), ('guest-journey')
     ), seed_user AS (
       SELECT replace(replace(identity.value, 'demo+', ''), '@epoca.example.invalid', '') AS user_key
       FROM user_identity identity
       WHERE identity.value LIKE 'demo+%@epoca.example.invalid'
     )
     SELECT count(*)
     FROM project_key
     CROSS JOIN seed_user
     WHERE public.uuid_generate_v5(
             '2f2ed0a0-4d42-5f32-8f49-5d36cc3a0532'::uuid,
             'project-user:' || project_key.project_key || ':' || seed_user.user_key
           ) = '$active_project_user_id';" \
    'active foreign project assignment id is outside the full project-user catalog'

  assert_query_eq "$database" '8|9|1' \
    "SELECT catalog.team_size || '|' || count(*) FILTER (WHERE project_user_row.archived_at IS NULL) || '|' ||
            count(*) FILTER (WHERE project_user_row.id = '$active_project_user_id' AND project_user_row.archived_at IS NULL)
     FROM project project_row
     JOIN project_user project_user_row ON project_user_row.project_id = project_row.id
     CROSS JOIN (VALUES (8)) catalog(team_size)
     WHERE project_row.id = '$project_id'
     GROUP BY catalog.team_size;" \
    'active foreign assignment makes the real catalog project team exceed team_size'

  apply_before=$(snapshot_rows "$database")
  set +e
  apply_failure_output=$(run_apply "$database" 2>&1)
  apply_failure_status=$?
  set -e
  apply_after=$(snapshot_rows "$database")

  verify_before=$(snapshot_rows "$database")
  set +e
  verify_failure_output=$(run_standalone_verify "$database" 2>&1)
  verify_failure_status=$?
  set -e
  verify_after=$(snapshot_rows "$database")

  unexpected_successes=
  [ "$apply_failure_status" -ne 0 ] || unexpected_successes='rerun apply unexpectedly succeeded'
  if [ "$verify_failure_status" -eq 0 ]; then
    if [ -n "$unexpected_successes" ]; then
      unexpected_successes="$unexpected_successes; standalone verify unexpectedly succeeded"
    else
      unexpected_successes='standalone verify unexpectedly succeeded'
    fi
  fi
  [ -z "$unexpected_successes" ] || fail "$unexpected_successes"

  case "$apply_failure_output" in
    *'active noncatalog project assignment targets a catalog seed project'*) ;;
    *) fail 'foreign active project assignment apply returned the wrong non-secret diagnostic' ;;
  esac
  case "$verify_failure_output" in
    *'active noncatalog project assignment targets a catalog seed project'*) ;;
    *) fail 'foreign active project assignment standalone verify returned the wrong non-secret diagnostic' ;;
  esac
  [ "$apply_before" = "$apply_after" ] || fail 'foreign active project assignment apply left a partial commit'
  [ "$verify_before" = "$verify_after" ] || fail 'foreign active project assignment standalone verify changed persistent rows'

  archived_final=$(query_value "$database" "SELECT row_to_json(project_user_row)::text FROM project_user project_user_row WHERE id = '$archived_project_user_id';")
  [ "$archived_before" = "$archived_final" ] || fail 'archived foreign project assignment was not byte-identical after rejected operations'
  printf 'PASS: foreign-active-project-assignment\n'
}

case_rerun_same_day() {
  new_database
  database=$NEW_DATABASE
  apply_migrations "$database"
  run_apply "$database"
  before_snapshot=$(snapshot_core "$database")
  assert_core_snapshot_contract "$before_snapshot" 'same-day full-core snapshot'
  rerun_output=$(run_apply "$database")
  after_snapshot=$(snapshot_core "$database")

  assert_core_snapshot_unchanged "$before_snapshot" "$after_snapshot" \
    'rerun changed deterministic ids, business fields, created_at, modified_at, or archival state'
  for entity in client project project_user; do
    for operation in insert update activate archive; do
      assert_change_plan_eq "$rerun_output" "$entity" "$operation" 0 \
        "same-day rerun $entity $operation"
    done
  done
  assert_core_distribution "$database" 30
  run_standalone_verify "$database"
  printf 'PASS: rerun-same-day\n'
}

case_reactivate_seed_organization() {
  new_database
  database=$NEW_DATABASE
  apply_migrations "$database"
  deterministic_id=$(query_value "$database" "SELECT uuid_generate_v5('2f2ed0a0-4d42-5f32-8f49-5d36cc3a0532'::uuid, 'organization:iteratorstesting');")
  psql_case "$database" --quiet --command "
    INSERT INTO organization (id, name, slug, created_at, created_by, modified_at, modified_by, archived_at)
    VALUES ('$deterministic_id', 'Preserved Archived Name', 'iteratorstesting', '2024-01-02 03:04:05+00',
            '10000000-0000-4000-8000-000000000001', '2024-02-03 04:05:06+00',
            '10000000-0000-4000-8000-000000000002', '2025-01-01 00:00:00+00');" >/dev/null
  preserved_before=$(query_value "$database" "SELECT id || '|' || name || '|' || created_at || '|' || created_by FROM organization WHERE id = '$deterministic_id';")
  run_apply "$database"
  preserved_after=$(query_value "$database" "SELECT id || '|' || name || '|' || created_at || '|' || created_by FROM organization WHERE id = '$deterministic_id';")
  [ "$preserved_before" = "$preserved_after" ] || fail 'reactivation rewrote organization identity, name, creator, or created_at'
  assert_query_eq "$database" 1 "SELECT count(*) FROM organization WHERE id = '$deterministic_id' AND slug = 'iteratorstesting' AND archived_at IS NULL;" 'deterministic organization reactivated'
  assert_core_distribution "$database" 30
  run_standalone_verify "$database"

  new_database
  active_database=$NEW_DATABASE
  apply_migrations "$active_database"
  psql_case "$active_database" --quiet --command "
    INSERT INTO organization (id, name, slug, created_at, created_by, modified_at, modified_by, archived_at)
    VALUES ('20000000-0000-4000-8000-000000000001', 'Existing Active Name', 'iteratorstesting', '2024-03-04 05:06:07+00',
            '20000000-0000-4000-8000-000000000002', '2024-04-05 06:07:08+00',
            '20000000-0000-4000-8000-000000000003', NULL);" >/dev/null
  active_before=$(query_value "$active_database" "SELECT row_to_json(o)::text FROM organization o WHERE slug = 'iteratorstesting';")
  run_apply "$active_database"
  active_after=$(query_value "$active_database" "SELECT row_to_json(o)::text FROM organization o WHERE slug = 'iteratorstesting';")
  [ "$active_before" = "$active_after" ] || fail 'active existing organization was renamed or re-authored'
  assert_core_distribution "$active_database" 30
  run_standalone_verify "$active_database"
  printf 'PASS: reactivate-seed-organization\n'
}

case_mixed_existing_members() {
  new_database
  database=$NEW_DATABASE
  apply_migrations "$database"
  psql_case "$database" --quiet --command "
    INSERT INTO organization (id, name, slug, created_at, created_by, modified_at, modified_by, archived_at)
    VALUES ('30000000-0000-4000-8000-000000000001', 'Mixed Existing Organization', 'iteratorstesting', '2024-01-01 01:02:03+00',
            '30000000-0000-4000-8000-000000000002', '2024-02-02 02:03:04+00',
            '30000000-0000-4000-8000-000000000003', NULL);
    INSERT INTO \"user\" (id, first_name, last_name, avatar, color, is_super_admin, created_at, modified_at, archived_at) VALUES
      ('31000000-0000-4000-8000-000000000001', 'Existing', 'Owner', 'owner-avatar', '#111111', true,  '2024-01-01 00:00:01+00', '2024-01-02 00:00:01+00', NULL),
      ('31000000-0000-4000-8000-000000000002', 'Existing', 'Employee', NULL, '#222222', false, '2024-01-01 00:00:02+00', '2024-01-02 00:00:02+00', NULL),
      ('31000000-0000-4000-8000-000000000003', 'Existing', 'External', 'external-avatar', '#333333', true, '2024-01-01 00:00:03+00', '2024-01-02 00:00:03+00', NULL);
    INSERT INTO user_identity (id, user_id, type, value, main, device_id, confirmed, confirmation_code, created_at) VALUES
      ('32000000-0000-4000-8000-000000000001', '31000000-0000-4000-8000-000000000001', 'MailAddress', 'existing.owner@example.test', true, NULL, true, NULL, '2024-01-01 00:00:01'),
      ('32000000-0000-4000-8000-000000000002', '31000000-0000-4000-8000-000000000002', 'MailAddress', 'existing.employee@example.test', true, NULL, true, NULL, '2024-01-01 00:00:02'),
      ('32000000-0000-4000-8000-000000000003', '31000000-0000-4000-8000-000000000003', 'MailAddress', 'existing.external@example.test', true, NULL, true, NULL, '2024-01-01 00:00:03');
    INSERT INTO organization_user (id, organization_id, user_id, roles, archived_at) VALUES
      ('33000000-0000-4000-8000-000000000001', '30000000-0000-4000-8000-000000000001', '31000000-0000-4000-8000-000000000001', ARRAY['Owner'], NULL),
      ('33000000-0000-4000-8000-000000000002', '30000000-0000-4000-8000-000000000001', '31000000-0000-4000-8000-000000000002', ARRAY['Employee'], NULL),
      ('33000000-0000-4000-8000-000000000003', '30000000-0000-4000-8000-000000000001', '31000000-0000-4000-8000-000000000003', ARRAY['External'], NULL);" >/dev/null

  organization_before=$(query_value "$database" "SELECT row_to_json(o)::text FROM organization o WHERE slug = 'iteratorstesting';")
  users_before=$(query_value "$database" "SELECT row_to_json(u)::text FROM \"user\" u WHERE id::text LIKE '31000000-%' ORDER BY id;")
  identities_before=$(query_value "$database" "SELECT row_to_json(ui)::text FROM user_identity ui WHERE id::text LIKE '32000000-%' ORDER BY id;")
  memberships_before=$(query_value "$database" "SELECT row_to_json(ou)::text FROM organization_user ou WHERE id::text LIKE '33000000-%' ORDER BY id;")

  run_apply "$database"

  organization_after=$(query_value "$database" "SELECT row_to_json(o)::text FROM organization o WHERE slug = 'iteratorstesting';")
  users_after=$(query_value "$database" "SELECT row_to_json(u)::text FROM \"user\" u WHERE id::text LIKE '31000000-%' ORDER BY id;")
  identities_after=$(query_value "$database" "SELECT row_to_json(ui)::text FROM user_identity ui WHERE id::text LIKE '32000000-%' ORDER BY id;")
  memberships_after=$(query_value "$database" "SELECT row_to_json(ou)::text FROM organization_user ou WHERE id::text LIKE '33000000-%' ORDER BY id;")
  [ "$organization_before" = "$organization_after" ] || fail 'mixed case changed active existing organization bytes'
  [ "$users_before" = "$users_after" ] || fail 'mixed case changed nonseed user bytes or superadmin flags'
  [ "$identities_before" = "$identities_after" ] || fail 'mixed case changed nonseed identity bytes'
  [ "$memberships_before" = "$memberships_after" ] || fail 'mixed case changed nonseed membership bytes or roles'

  assert_core_distribution "$database" 27
  assert_query_eq "$database" 0 \
    "SELECT count(*) FROM organization_user ou JOIN organization o ON o.id = ou.organization_id JOIN \"user\" u ON u.id = ou.user_id JOIN user_identity ui ON ui.user_id = u.id WHERE o.slug = 'iteratorstesting' AND ou.archived_at IS NULL AND u.archived_at IS NULL AND u.is_super_admin AND ui.value LIKE 'demo+%@epoca.example.invalid';" \
    'two existing superadmins leave no seed superadmin deficit'
  run_standalone_verify "$database"
  printf 'PASS: mixed-existing-members\n'
}

case_catalog_parent_collisions() {
  new_database
  database=$NEW_DATABASE
  apply_migrations "$database"
  insert_active_organization "$database" '5b000000-0000-4000-8000-000000000010'
  psql_case "$database" --quiet --command "
    INSERT INTO organization (id, name, slug, created_at, created_by, modified_at, modified_by, archived_at)
    VALUES ('5b000000-0000-4000-8000-000000000011', 'Foreign Client Parent', 'foreign-client-parent', '2024-01-01 00:00:00+00',
            '5b000000-0000-4000-8000-000000000001', '2024-01-01 00:00:00+00',
            '5b000000-0000-4000-8000-000000000001', NULL);" >/dev/null
  catalog_client_id=$(query_value "$database" "SELECT uuid_generate_v5('2f2ed0a0-4d42-5f32-8f49-5d36cc3a0532'::uuid, 'client:atelier-ops');")
  insert_client_fixture "$database" "$catalog_client_id" '5b000000-0000-4000-8000-000000000011'
  expect_apply_failure_unchanged "$database" 'catalog client belongs to another organization' 'catalog client parent organization collision'

  new_database
  database=$NEW_DATABASE
  apply_migrations "$database"
  insert_active_organization "$database" '5b000000-0000-4000-8000-000000000020'
  psql_case "$database" --quiet --command "
    INSERT INTO organization (id, name, slug, created_at, created_by, modified_at, modified_by, archived_at)
    VALUES ('5b000000-0000-4000-8000-000000000021', 'Foreign Project Parent', 'foreign-project-parent', '2024-01-01 00:00:00+00',
            '5b000000-0000-4000-8000-000000000001', '2024-01-01 00:00:00+00',
            '5b000000-0000-4000-8000-000000000001', NULL);" >/dev/null
  catalog_client_id=$(query_value "$database" "SELECT uuid_generate_v5('2f2ed0a0-4d42-5f32-8f49-5d36cc3a0532'::uuid, 'client:atelier-ops');")
  catalog_project_id=$(query_value "$database" "SELECT uuid_generate_v5('2f2ed0a0-4d42-5f32-8f49-5d36cc3a0532'::uuid, 'project:delivery-operations-hub');")
  insert_client_fixture "$database" "$catalog_client_id" '5b000000-0000-4000-8000-000000000020'
  insert_project_fixture "$database" "$catalog_project_id" '5b000000-0000-4000-8000-000000000021' "$catalog_client_id"
  expect_apply_failure_unchanged "$database" 'catalog project belongs to another organization' 'catalog project parent organization collision'

  new_database
  database=$NEW_DATABASE
  apply_migrations "$database"
  insert_active_organization "$database" '5b000000-0000-4000-8000-000000000030'
  catalog_client_id=$(query_value "$database" "SELECT uuid_generate_v5('2f2ed0a0-4d42-5f32-8f49-5d36cc3a0532'::uuid, 'client:atelier-ops');")
  catalog_project_id=$(query_value "$database" "SELECT uuid_generate_v5('2f2ed0a0-4d42-5f32-8f49-5d36cc3a0532'::uuid, 'project:delivery-operations-hub');")
  insert_client_fixture "$database" "$catalog_client_id" '5b000000-0000-4000-8000-000000000030'
  insert_client_fixture "$database" '5b000000-0000-4000-8000-000000000031' '5b000000-0000-4000-8000-000000000030'
  insert_project_fixture "$database" "$catalog_project_id" '5b000000-0000-4000-8000-000000000030' '5b000000-0000-4000-8000-000000000031'
  expect_apply_failure_unchanged "$database" 'catalog project has an unexpected client parent' 'catalog project client parent collision'

  new_database
  database=$NEW_DATABASE
  apply_migrations "$database"
  insert_active_organization "$database" '5b000000-0000-4000-8000-000000000040'
  catalog_client_id=$(query_value "$database" "SELECT uuid_generate_v5('2f2ed0a0-4d42-5f32-8f49-5d36cc3a0532'::uuid, 'client:atelier-ops');")
  catalog_project_id=$(query_value "$database" "SELECT uuid_generate_v5('2f2ed0a0-4d42-5f32-8f49-5d36cc3a0532'::uuid, 'project:delivery-operations-hub');")
  catalog_project_user_id=$(query_value "$database" "SELECT uuid_generate_v5('2f2ed0a0-4d42-5f32-8f49-5d36cc3a0532'::uuid, 'project-user:delivery-operations-hub:amelia-nowak');")
  insert_client_fixture "$database" "$catalog_client_id" '5b000000-0000-4000-8000-000000000040'
  insert_project_fixture "$database" "$catalog_project_id" '5b000000-0000-4000-8000-000000000040' "$catalog_client_id"
  insert_fixture_user "$database" '5b000000-0000-4000-8000-000000000041' NULL
  psql_case "$database" --quiet --command "
    INSERT INTO project_user (id, project_id, user_id, role, created_at, archived_at)
    VALUES ('$catalog_project_user_id', '$catalog_project_id', '5b000000-0000-4000-8000-000000000041',
            'Member', '2024-01-01 00:00:00+00', NULL);" >/dev/null
  expect_apply_failure_unchanged "$database" 'catalog project user has an unexpected project/user parent pair' 'catalog project user parent pair collision'

  new_database
  database=$NEW_DATABASE
  apply_migrations "$database"
  run_apply "$database" >/dev/null
  catalog_project_id=$(query_value "$database" "SELECT uuid_generate_v5('2f2ed0a0-4d42-5f32-8f49-5d36cc3a0532'::uuid, 'project:delivery-operations-hub');")
  catalog_user_id=$(query_value "$database" "SELECT uuid_generate_v5('2f2ed0a0-4d42-5f32-8f49-5d36cc3a0532'::uuid, 'user:amelia-nowak');")
  psql_case "$database" --quiet --command "
    INSERT INTO project_user (id, project_id, user_id, role, created_at, archived_at)
    VALUES ('5b000000-0000-4000-8000-000000000050', '$catalog_project_id', '$catalog_user_id',
            'Member', '2024-01-01 00:00:00+00', NULL);" >/dev/null
  expect_apply_failure_unchanged "$database" \
    'target seed project/user pair is owned by a noncatalog project membership' \
    'target project/user pair collision'

  printf 'PASS: catalog-parent-collisions\n'
}

case_shrink_seed_members() {
  new_database
  database=$NEW_DATABASE
  apply_migrations "$database"
  run_apply "$database" >/dev/null
  organization_id=$(query_value "$database" "SELECT id FROM organization WHERE slug = 'iteratorstesting';")
  insert_three_nonseed_members "$database" "$organization_id"
  nonseed_before=$(query_value "$database" "SELECT row_to_json(row_value)::text FROM (SELECT * FROM \"user\" WHERE id::text LIKE '5a000000-%' ORDER BY id) row_value;")
  shrink_output=$(run_apply "$database")
  nonseed_after=$(query_value "$database" "SELECT row_to_json(row_value)::text FROM (SELECT * FROM \"user\" WHERE id::text LIKE '5a000000-%' ORDER BY id) row_value;")
  [ "$nonseed_before" = "$nonseed_after" ] || fail 'target shrink changed nonseed global users'
  assert_query_eq "$database" 3 \
    "SELECT count(*) FROM \"user\" WHERE id IN (SELECT uuid_generate_v5('2f2ed0a0-4d42-5f32-8f49-5d36cc3a0532'::uuid, 'user:' || logical_key) FROM (VALUES ('lucas-martin'), ('nina-petrova'), ('mateo-costa')) profiles(logical_key)) AND archived_at = '2026-05-18 00:00:00+00'::timestamptz;" \
    'target shrink archives all three extra catalog users at seed_today'
  assert_query_eq "$database" 3 \
    "SELECT count(*) FROM organization_user WHERE id IN (SELECT uuid_generate_v5('2f2ed0a0-4d42-5f32-8f49-5d36cc3a0532'::uuid, 'organization-user:' || logical_key) FROM (VALUES ('lucas-martin'), ('nina-petrova'), ('mateo-costa')) profiles(logical_key)) AND archived_at IS NOT NULL;" \
    'target shrink archives all three extra catalog memberships'
  assert_query_eq "$database" 27 \
    "SELECT count(*) FROM \"user\" users JOIN user_identity identity ON identity.user_id = users.id WHERE identity.value LIKE 'demo+%@epoca.example.invalid' AND users.archived_at IS NULL;" \
    'target shrink leaves exactly twenty-seven global seed users active'
  assert_change_plan_eq "$shrink_output" user archive 3 \
    'target shrink change plan reports three archived users'
  assert_core_distribution "$database" 27
  run_standalone_verify "$database" >/dev/null

  new_database
  conflict_database=$NEW_DATABASE
  apply_migrations "$conflict_database"
  run_apply "$conflict_database" >/dev/null
  organization_id=$(query_value "$conflict_database" "SELECT id FROM organization WHERE slug = 'iteratorstesting';")
  insert_three_nonseed_members "$conflict_database" "$organization_id"
  profile_thirty_id=$(query_value "$conflict_database" "SELECT uuid_generate_v5('2f2ed0a0-4d42-5f32-8f49-5d36cc3a0532'::uuid, 'user:mateo-costa');")
  psql_case "$conflict_database" --quiet --command "
    INSERT INTO organization (id, name, slug, created_at, created_by, modified_at, modified_by, archived_at)
    VALUES ('5c000000-0000-4000-8000-000000000001', 'Other Active Membership', 'other-active-membership', '2024-01-01 00:00:00+00',
            '5c000000-0000-4000-8000-000000000002', '2024-01-01 00:00:00+00',
            '5c000000-0000-4000-8000-000000000002', NULL);
    INSERT INTO organization_user (id, organization_id, user_id, roles, archived_at)
    VALUES ('5c000000-0000-4000-8000-000000000003', '5c000000-0000-4000-8000-000000000001',
            '$profile_thirty_id', ARRAY['Employee'], NULL);" >/dev/null
  expect_apply_failure_unchanged "$conflict_database" 'non-target seed user has another active membership' 'target shrink with another active membership'
  printf 'PASS: shrink-seed-members\n'
}

case_identity_ownership() {
  new_database
  database=$NEW_DATABASE
  apply_migrations "$database"
  run_apply "$database" >/dev/null
  seed_user_id=$(query_value "$database" "SELECT uuid_generate_v5('2f2ed0a0-4d42-5f32-8f49-5d36cc3a0532'::uuid, 'user:amelia-nowak');")
  psql_case "$database" --quiet --command "
    INSERT INTO user_identity (id, user_id, type, value, main, device_id, confirmed, confirmation_code, created_at)
    VALUES ('5d000000-0000-4000-8000-000000000001', '$seed_user_id', 'RecoveryAddress',
            'foreign-extra-identity@example.test', false, NULL, false, NULL, '2024-01-01 00:00:00');" >/dev/null
  expect_verify_failure_unchanged "$database" 'seed user must have exactly one catalog identity' 'additional foreign seed identity'
  expect_apply_failure_unchanged "$database" 'seed user must have exactly one catalog identity' 'additional foreign seed identity preflight'

  new_database
  credential_database=$NEW_DATABASE
  apply_migrations "$credential_database"
  run_apply "$credential_database" >/dev/null
  seed_user_id=$(query_value "$credential_database" "SELECT uuid_generate_v5('2f2ed0a0-4d42-5f32-8f49-5d36cc3a0532'::uuid, 'user:amelia-nowak');")
  psql_case "$credential_database" --quiet --command "
    INSERT INTO user_identity (id, user_id, type, value, main, device_id, confirmed, confirmation_code, created_at)
    VALUES ('5d000000-0000-4000-8000-000000000010', '$seed_user_id', 'RecoveryAddress',
            'foreign-credential-identity@example.test', false, NULL, false, NULL, '2024-01-01 00:00:00');
    INSERT INTO user_password (user_identity_id, password)
    VALUES ('5d000000-0000-4000-8000-000000000010', 'fixture-password-hash');
    INSERT INTO user_login_token (id, user_identity_id, login_token, valid_to)
    VALUES ('5d000000-0000-4000-8000-000000000011', '5d000000-0000-4000-8000-000000000010',
            'fixture-login-token', '2027-01-01 00:00:00');" >/dev/null
  expect_verify_failure_unchanged "$credential_database" 'seed user must have exactly one catalog identity' 'foreign seed identity credentials'
  expect_apply_failure_unchanged "$credential_database" 'seed user must have exactly one catalog identity' 'foreign seed identity credentials preflight'

  new_database
  invitation_database=$NEW_DATABASE
  apply_migrations "$invitation_database"
  run_apply "$invitation_database" >/dev/null
  organization_id=$(query_value "$invitation_database" "SELECT id FROM organization WHERE slug = 'iteratorstesting';")
  psql_case "$invitation_database" --quiet --command "
    INSERT INTO invitation (id, identity_value, identity_type, roles, expire_at, accept_at, reject_at, organization_id, created_at, created_by, modified_at, modified_by, archived_at)
    VALUES ('5d000000-0000-4000-8000-000000000020', 'demo+amelia-nowak@epoca.example.invalid', 'MailAddress', ARRAY['Owner'],
            NULL, NULL, NULL, '$organization_id', '2024-01-01 00:00:00',
            '5d000000-0000-4000-8000-000000000021', '2024-01-01 00:00:00',
            '5d000000-0000-4000-8000-000000000021', NULL);" >/dev/null
  expect_apply_failure_unchanged "$invitation_database" 'seed invitation row is not allowed' 'seed invitation preflight'
  printf 'PASS: identity-ownership\n'
}

case_case_insensitive_identity_ownership() {
  new_database
  database=$NEW_DATABASE
  apply_migrations "$database"
  run_apply "$database" >/dev/null
  insert_fixture_user "$database" '5e000000-0000-4000-8000-000000000001' NULL
  psql_case "$database" --quiet --command "
    INSERT INTO user_identity (id, user_id, type, value, main, device_id, confirmed, confirmation_code, created_at)
    VALUES ('5e000000-0000-4000-8000-000000000002', '5e000000-0000-4000-8000-000000000001', 'MailAddress',
            'DEMO+AMELIA-NOWAK@EPOCA.EXAMPLE.INVALID', true, NULL, true, NULL, '2024-01-01 00:00:00');" >/dev/null
  expect_verify_failure_unchanged "$database" 'case-insensitive seed MailAddress is owned by another relation' 'uppercase foreign seed MailAddress'
  expect_apply_failure_unchanged "$database" 'case-insensitive seed MailAddress is owned by another relation' 'uppercase foreign seed MailAddress preflight'

  new_database
  drift_database=$NEW_DATABASE
  apply_migrations "$drift_database"
  run_apply "$drift_database" >/dev/null
  seed_identity_id=$(query_value "$drift_database" "SELECT public.uuid_generate_v5('2f2ed0a0-4d42-5f32-8f49-5d36cc3a0532'::uuid, 'identity:amelia-nowak');")
  psql_case "$drift_database" --quiet --command "
    UPDATE user_identity
    SET value = upper(value)
    WHERE id = '$seed_identity_id';" >/dev/null
  drift_value_before=$(query_value "$drift_database" "SELECT value FROM user_identity WHERE id = '$seed_identity_id';")
  expect_verify_failure_unchanged "$drift_database" 'seed user must have exactly one catalog identity' 'catalog identity case drift'
  expect_apply_failure_unchanged "$drift_database" 'deterministic seed user id is not accompanied by its exact catalog identity' 'catalog identity case drift preflight'
  drift_value_after=$(query_value "$drift_database" "SELECT value FROM user_identity WHERE id = '$seed_identity_id';")
  [ "$drift_value_before" = "$drift_value_after" ] || fail 'catalog identity case drift was taken over or normalized'
  printf 'PASS: case-insensitive-identity-ownership\n'
}

case_search_path_hardening() {
  new_database
  database=$NEW_DATABASE
  apply_migrations "$database"
  psql_case "$database" --quiet --command "
    CREATE SCHEMA attacker;
    CREATE TABLE attacker.demo_seed_parameters (marker TEXT NOT NULL);
    INSERT INTO attacker.demo_seed_parameters VALUES ('persistent-shadow');
    CREATE TABLE attacker.demo_seed_user_catalog (marker TEXT NOT NULL);
    INSERT INTO attacker.demo_seed_user_catalog VALUES ('persistent-shadow');
    CREATE TABLE attacker.demo_seed_catalog_context (marker TEXT NOT NULL);
    INSERT INTO attacker.demo_seed_catalog_context VALUES ('persistent-shadow');
    CREATE TABLE attacker.demo_seed_verify_catalog_context (marker TEXT NOT NULL);
    INSERT INTO attacker.demo_seed_verify_catalog_context VALUES ('persistent-shadow');
    CREATE TABLE attacker.demo_seed_verify_user_catalog (marker TEXT NOT NULL);
    INSERT INTO attacker.demo_seed_verify_user_catalog VALUES ('persistent-shadow');
    CREATE TABLE attacker.demo_seed_organization_context (marker TEXT NOT NULL);
    INSERT INTO attacker.demo_seed_organization_context VALUES ('persistent-shadow');
    CREATE FUNCTION attacker.current_database()
      RETURNS name
      LANGUAGE plpgsql
    AS \$function\$
    BEGIN
      RAISE EXCEPTION 'attacker fingerprint helper invoked';
    END;
    \$function\$;
    CREATE FUNCTION attacker.hashtextextended(TEXT, BIGINT)
      RETURNS BIGINT
      LANGUAGE plpgsql
    AS \$function\$
    BEGIN
      RAISE EXCEPTION 'attacker fingerprint helper invoked';
    END;
    \$function\$;
    CREATE FUNCTION attacker.pg_advisory_xact_lock(BIGINT)
      RETURNS VOID
      LANGUAGE plpgsql
    AS \$function\$
    BEGIN
      RAISE EXCEPTION 'attacker advisory lock invoked';
    END;
    \$function\$;" >/dev/null
  hostile_search_path='-csearch_path=attacker,pg_catalog,public'
  run_apply_with_pgoptions "$database" "$hostile_search_path" >/dev/null
  run_verify_with_pgoptions "$database" "$hostile_search_path" >/dev/null
  assert_query_eq "$database" 1 \
    "SELECT count(*) FROM attacker.demo_seed_parameters WHERE marker = 'persistent-shadow';" \
    'attacker parameter shadow table remains untouched'
  assert_query_eq "$database" 1 \
    "SELECT count(*) FROM attacker.demo_seed_user_catalog WHERE marker = 'persistent-shadow';" \
    'attacker catalog shadow table remains untouched'
  assert_query_eq "$database" 5 \
    "SELECT count(*) FROM (
       SELECT marker FROM attacker.demo_seed_catalog_context
       UNION ALL SELECT marker FROM attacker.demo_seed_verify_catalog_context
       UNION ALL SELECT marker FROM attacker.demo_seed_verify_user_catalog
       UNION ALL SELECT marker FROM attacker.demo_seed_organization_context
       UNION ALL SELECT marker FROM attacker.demo_seed_user_catalog
     ) shadows WHERE marker = 'persistent-shadow';" \
    'attacker temp-object shadow tables remain untouched'
  assert_core_distribution "$database" 30
  printf 'PASS: search-path-hardening\n'
}

case_trigger_disabled() {
  new_database
  database=$NEW_DATABASE
  apply_migrations "$database"
  psql_case "$database" --quiet --command \
    'ALTER TABLE public.work_unit DISABLE TRIGGER work_unit_client_id_from_project;' >/dev/null
  expect_apply_failure_unchanged "$database" 'unexpected work unit client trigger' 'disabled work unit client trigger'
  printf 'PASS: trigger-disabled\n'
}

case_trigger_wrong_function() {
  new_database
  database=$NEW_DATABASE
  apply_migrations "$database"
  psql_case "$database" --quiet --command "
    CREATE SCHEMA attacker;
    CREATE FUNCTION attacker.set_work_unit_client_id_from_project()
      RETURNS trigger
      LANGUAGE plpgsql
    AS \$function\$
    BEGIN
      RETURN NEW;
    END;
    \$function\$;
    DROP TRIGGER work_unit_client_id_from_project ON public.work_unit;
    CREATE TRIGGER work_unit_client_id_from_project
      BEFORE INSERT OR UPDATE ON public.work_unit
      FOR EACH ROW
      EXECUTE FUNCTION attacker.set_work_unit_client_id_from_project();" >/dev/null
  hostile_search_path='-csearch_path=attacker,pg_catalog,public'
  trigger_definition=$(query_value_with_pgoptions "$database" "$hostile_search_path" "
    SELECT pg_catalog.pg_get_triggerdef(trigger.oid, true)
    FROM pg_catalog.pg_trigger trigger
    JOIN pg_catalog.pg_class relation ON relation.oid = trigger.tgrelid
    JOIN pg_catalog.pg_namespace namespace ON namespace.oid = relation.relnamespace
    WHERE namespace.nspname = 'public'
      AND relation.relname = 'work_unit'
      AND trigger.tgname = 'work_unit_client_id_from_project';")
  [ "$trigger_definition" = 'CREATE TRIGGER work_unit_client_id_from_project BEFORE INSERT OR UPDATE ON work_unit FOR EACH ROW EXECUTE FUNCTION set_work_unit_client_id_from_project()' ] || \
    fail 'wrong-function trigger fixture does not bypass the legacy text-only check'
  expect_apply_failure_unchanged_with_pgoptions "$database" "$hostile_search_path" \
    'unexpected work unit client trigger' 'work unit trigger with wrong function OID'
  printf 'PASS: trigger-wrong-function\n'
}

case_staging_sql() {
  viewer_email='viewer.owner@example.test'

  new_database
  database=$NEW_DATABASE
  apply_migrations "$database"
  organization_id='61000000-0000-4000-8000-000000000001'
  insert_active_organization "$database" "$organization_id"
  insert_staging_viewer "$database" "$organization_id" "$viewer_email" Owner NULL
  insert_complete_staging_project_cleanup_fixture "$database" "$organization_id" \
    '61000000-0000-4000-8000-000000000002' '60000000-0000-4000-8000-000000000001'
  dry_run_before=$(snapshot_rows "$database")
  run_staging_seed "$database" dry-run false "$organization_id" "$viewer_email" iteratorstesting >/dev/null
  dry_run_after=$(snapshot_rows "$database")
  [ "$dry_run_before" = "$dry_run_after" ] || fail 'staging dry-run changed persistent rows'
  run_staging_seed "$database" apply true "$organization_id" "$viewer_email" iteratorstesting >/dev/null
  assert_core_distribution "$database" 29
  run_staging_verify "$database" "$organization_id" "$viewer_email" iteratorstesting >/dev/null

  new_database
  missing_database=$NEW_DATABASE
  apply_migrations "$missing_database"
  missing_organization_id='62000000-0000-4000-8000-000000000001'
  insert_active_organization "$missing_database" "$missing_organization_id"
  insert_complete_staging_project_cleanup_fixture "$missing_database" "$missing_organization_id" \
    '62000000-0000-4000-8000-000000000002' '62000000-0000-4000-8000-000000000003'
  expect_staging_apply_failure_unchanged "$missing_database" \
    'staging viewer must resolve to exactly one active nonseed superadmin, Owner, or Manager' 'missing staging viewer' \
    "$missing_organization_id" missing.viewer@example.test iteratorstesting
  insert_staging_viewer "$missing_database" "$missing_organization_id" real.viewer@example.test Manager NULL
  expect_staging_apply_failure_unchanged "$missing_database" \
    'staging viewer must resolve to exactly one active nonseed superadmin, Owner, or Manager' 'wrong staging viewer email' \
    "$missing_organization_id" wrong.viewer@example.test iteratorstesting

  new_database
  inactive_database=$NEW_DATABASE
  apply_migrations "$inactive_database"
  inactive_organization_id='63000000-0000-4000-8000-000000000001'
  insert_active_organization "$inactive_database" "$inactive_organization_id"
  insert_staging_viewer "$inactive_database" "$inactive_organization_id" inactive.viewer@example.test Owner \
    "'2025-01-01 00:00:00+00'"
  insert_complete_staging_project_cleanup_fixture "$inactive_database" "$inactive_organization_id" \
    '63000000-0000-4000-8000-000000000002' '60000000-0000-4000-8000-000000000001'
  expect_staging_apply_failure_unchanged "$inactive_database" \
    'staging viewer must resolve to exactly one active nonseed superadmin, Owner, or Manager' 'inactive staging viewer' \
    "$inactive_organization_id" inactive.viewer@example.test iteratorstesting

  new_database
  nonprivileged_database=$NEW_DATABASE
  apply_migrations "$nonprivileged_database"
  nonprivileged_organization_id='64000000-0000-4000-8000-000000000001'
  insert_active_organization "$nonprivileged_database" "$nonprivileged_organization_id"
  insert_staging_viewer "$nonprivileged_database" "$nonprivileged_organization_id" employee.viewer@example.test Employee NULL
  insert_complete_staging_project_cleanup_fixture "$nonprivileged_database" "$nonprivileged_organization_id" \
    '64000000-0000-4000-8000-000000000002' '60000000-0000-4000-8000-000000000001'
  expect_staging_apply_failure_unchanged "$nonprivileged_database" \
    'staging viewer must resolve to exactly one active nonseed superadmin, Owner, or Manager' 'non-privileged staging viewer' \
    "$nonprivileged_organization_id" employee.viewer@example.test iteratorstesting

  new_database
  fingerprint_database=$NEW_DATABASE
  apply_migrations "$fingerprint_database"
  fingerprint_organization_id='65000000-0000-4000-8000-000000000001'
  insert_active_organization "$fingerprint_database" "$fingerprint_organization_id"
  insert_staging_viewer "$fingerprint_database" "$fingerprint_organization_id" fingerprint.viewer@example.test Owner NULL
  expect_staging_apply_failure_unchanged "$fingerprint_database" \
    'staging target organization does not match the active fingerprint' 'wrong staging organization id' \
    '65000000-0000-4000-8000-000000000099' fingerprint.viewer@example.test iteratorstesting
  expect_staging_apply_failure_unchanged "$fingerprint_database" \
    'demo seed rejects unexpected organization slug' 'wrong staging organization slug' \
    "$fingerprint_organization_id" fingerprint.viewer@example.test wrongslug

  new_database
  collision_database=$NEW_DATABASE
  apply_migrations "$collision_database"
  collision_organization_id='66000000-0000-4000-8000-000000000001'
  insert_active_organization "$collision_database" "$collision_organization_id"
  insert_staging_viewer "$collision_database" "$collision_organization_id" collision.viewer@example.test Manager NULL
  deterministic_organization_id=$(query_value "$collision_database" \
    "SELECT public.uuid_generate_v5('2f2ed0a0-4d42-5f32-8f49-5d36cc3a0532'::uuid, 'organization:iteratorstesting');")
  psql_case "$collision_database" --quiet --command "
    INSERT INTO organization (id, name, slug, created_at, created_by, modified_at, modified_by, archived_at)
    VALUES ('$deterministic_organization_id', 'Foreign Deterministic Owner', 'foreign-deterministic-owner',
            '2024-01-01 00:00:00+00', '66000000-0000-4000-8000-000000000002',
            '2024-01-01 00:00:00+00', '66000000-0000-4000-8000-000000000002', NULL);" >/dev/null
  expect_staging_apply_failure_unchanged "$collision_database" \
    'deterministic seed organization id is owned by another slug' 'staging deterministic organization collision' \
    "$collision_organization_id" collision.viewer@example.test iteratorstesting
  printf 'PASS: staging-sql\n'
}

case_demo_account_logins() {
  expected_demo_password_hash='$2a$12$YnR4CcAvwX3gnw1qIllM.OgzpgUOPSfftXVJ/5ZiNUfc0tAC6GkVK'

  new_database
  database=$NEW_DATABASE
  apply_migrations "$database"
  organization_id='60500000-0000-4000-8000-000000000001'
  insert_active_organization "$database" "$organization_id"
  insert_staging_baseline_seven "$database" "$organization_id"
  insert_complete_staging_project_cleanup_fixture "$database" "$organization_id" \
    '60500000-0000-4000-8000-000000000002' '60100000-0000-4000-8000-000000000001'
  snapshot_directory=$(mktemp -d "/tmp/epoca_ct532_lock.XXXXXX")
  TEMP_DIRECTORIES="$TEMP_DIRECTORIES $snapshot_directory"
  snapshot_foreign_rows "$database" "$snapshot_directory/before"

  run_staging_seed "$database" apply true "$organization_id" 'user-63a6ddab7c69@example.test' iteratorstesting >/dev/null
  run_staging_verify "$database" "$organization_id" 'user-63a6ddab7c69@example.test' iteratorstesting >/dev/null
  snapshot_foreign_rows "$database" "$snapshot_directory/after-apply"
  assert_foreign_snapshots_equal "$snapshot_directory/before" "$snapshot_directory/after-apply" \
    'seven-account staging baseline apply'

  assert_core_distribution "$database" 23
  assert_query_eq "$database" 2 \
    "SELECT count(*) FROM organization_user ou JOIN \"user\" u ON u.id = ou.user_id WHERE ou.organization_id = '$organization_id' AND ou.archived_at IS NULL AND u.archived_at IS NULL AND u.is_super_admin;" \
    'exactly two active superadmins across the target organization'
  assert_query_eq "$database" 'Employee=1' \
    "SELECT string_agg(ou.roles[1] || '=' || role_count, ',' ORDER BY ou.roles[1]) FROM (SELECT ou.roles, count(*)::text AS role_count FROM organization_user ou JOIN \"user\" u ON u.id = ou.user_id JOIN user_identity ui ON ui.user_id = u.id WHERE ou.organization_id = '$organization_id' AND ou.archived_at IS NULL AND u.archived_at IS NULL AND u.is_super_admin AND ui.value LIKE 'demo+%@epoca.example.invalid' GROUP BY ou.roles) ou;" \
    'one seed superadmin fills an Employee slot for the approved baseline'
  assert_query_eq "$database" 23 \
    "SELECT count(*) FROM user_password password JOIN user_identity identity ON identity.id = password.user_identity_id JOIN \"user\" users ON users.id = identity.user_id WHERE users.archived_at IS NULL AND identity.value LIKE 'demo+%@epoca.example.invalid';" \
    'every active seed profile has one managed password'
  assert_query_eq "$database" 1 \
    "SELECT count(DISTINCT password.password) FROM user_password password JOIN user_identity identity ON identity.id = password.user_identity_id WHERE identity.value LIKE 'demo+%@epoca.example.invalid';" \
    'all seed profiles share one stable password hash'
  assert_query_eq "$database" 23 \
    "SELECT count(*) FROM user_password password JOIN user_identity identity ON identity.id = password.user_identity_id WHERE identity.value LIKE 'demo+%@epoca.example.invalid' AND length(password.password) = 60 AND left(password.password, 7) IN ('\$2a\$12\$', '\$2b\$12\$', '\$2y\$12\$');" \
    'all seed password rows use BCrypt cost 12'
  assert_query_eq "$database" 23 \
    "SELECT count(*) FROM user_password password JOIN user_identity identity ON identity.id = password.user_identity_id WHERE identity.value LIKE 'demo+%@epoca.example.invalid' AND password.password = '$expected_demo_password_hash';" \
    'all seed password rows use the exact backend-verified BCrypt hash'
  assert_query_eq "$database" '0|0' \
    "SELECT (SELECT count(*) FROM user_login_token token JOIN user_identity identity ON identity.id = token.user_identity_id WHERE identity.value LIKE 'demo+%@epoca.example.invalid') || '|' || (SELECT count(*) FROM refresh_token token JOIN user_identity identity ON identity.user_id = token.user_id WHERE identity.value LIKE 'demo+%@epoca.example.invalid');" \
    'first apply creates no login or refresh tokens for seed profiles'

  seed_user_id=$(query_value "$database" "SELECT public.uuid_generate_v5('2f2ed0a0-4d42-5f32-8f49-5d36cc3a0532'::uuid, 'user:amelia-nowak');")
  seed_identity_id=$(query_value "$database" "SELECT public.uuid_generate_v5('2f2ed0a0-4d42-5f32-8f49-5d36cc3a0532'::uuid, 'identity:amelia-nowak');")
  psql_case "$database" --quiet --command "
    INSERT INTO user_login_token (id, user_identity_id, login_token, valid_to)
    VALUES ('60600000-0000-4000-8000-000000000001', '$seed_identity_id', 'simulated-login-token', '2027-01-01 00:00:00');
    INSERT INTO refresh_token (user_id, device_id, user_agent, value, authed_by, last_authed_at, created_at, expired_at)
    VALUES ('$seed_user_id', 'simulated-device', 'integration-agent', 'simulated-refresh-token', ARRAY['$seed_identity_id']::uuid[],
            '2026-01-01 00:00:00', '2026-01-01 00:00:00', NULL);" >/dev/null
  token_before=$(query_value "$database" "SELECT (SELECT row_to_json(t)::text FROM (SELECT * FROM user_login_token WHERE user_identity_id = '$seed_identity_id') t) || '|' || (SELECT row_to_json(t)::text FROM (SELECT * FROM refresh_token WHERE user_id = '$seed_user_id' AND device_id = 'simulated-device') t);")
  run_staging_seed "$database" apply true "$organization_id" 'user-63a6ddab7c69@example.test' iteratorstesting >/dev/null
  run_staging_verify "$database" "$organization_id" 'user-63a6ddab7c69@example.test' iteratorstesting >/dev/null
  token_after=$(query_value "$database" "SELECT (SELECT row_to_json(t)::text FROM (SELECT * FROM user_login_token WHERE user_identity_id = '$seed_identity_id') t) || '|' || (SELECT row_to_json(t)::text FROM (SELECT * FROM refresh_token WHERE user_id = '$seed_user_id' AND device_id = 'simulated-device') t);")
  [ "$token_before" = "$token_after" ] || fail 'seed rerun changed login or refresh tokens created by normal authentication'
  snapshot_foreign_rows "$database" "$snapshot_directory/after-rerun"
  assert_foreign_snapshots_equal "$snapshot_directory/before" "$snapshot_directory/after-rerun" \
    'seven-account staging baseline rerun'

  new_database
  excessive_database=$NEW_DATABASE
  apply_migrations "$excessive_database"
  excessive_organization_id='60700000-0000-4000-8000-000000000001'
  insert_active_organization "$excessive_database" "$excessive_organization_id"
  psql_case "$excessive_database" --quiet --command "
    INSERT INTO \"user\" (id, first_name, last_name, avatar, color, is_super_admin, created_at, modified_at, archived_at) VALUES
      ('60710000-0000-4000-8000-000000000001', 'Existing', 'Admin1', NULL, '#111111', true, '2024-01-01', '2024-01-01', NULL),
      ('60710000-0000-4000-8000-000000000002', 'Existing', 'Admin2', NULL, '#222222', true, '2024-01-01', '2024-01-01', NULL),
      ('60710000-0000-4000-8000-000000000003', 'Existing', 'Admin3', NULL, '#333333', true, '2024-01-01', '2024-01-01', NULL);
    INSERT INTO user_identity (id, user_id, type, value, main, device_id, confirmed, confirmation_code, created_at) VALUES
      ('60720000-0000-4000-8000-000000000001', '60710000-0000-4000-8000-000000000001', 'MailAddress', 'admin1@example.test', true, NULL, true, NULL, '2024-01-01'),
      ('60720000-0000-4000-8000-000000000002', '60710000-0000-4000-8000-000000000002', 'MailAddress', 'admin2@example.test', true, NULL, true, NULL, '2024-01-01'),
      ('60720000-0000-4000-8000-000000000003', '60710000-0000-4000-8000-000000000003', 'MailAddress', 'admin3@example.test', true, NULL, true, NULL, '2024-01-01');
    INSERT INTO organization_user (id, organization_id, user_id, roles, archived_at) VALUES
      ('60730000-0000-4000-8000-000000000001', '$excessive_organization_id', '60710000-0000-4000-8000-000000000001', ARRAY['Employee'], NULL),
      ('60730000-0000-4000-8000-000000000002', '$excessive_organization_id', '60710000-0000-4000-8000-000000000002', ARRAY['Employee'], NULL),
      ('60730000-0000-4000-8000-000000000003', '$excessive_organization_id', '60710000-0000-4000-8000-000000000003', ARRAY['Employee'], NULL);" >/dev/null
  insert_complete_staging_project_cleanup_fixture "$excessive_database" "$excessive_organization_id" \
    '60700000-0000-4000-8000-000000000002' '60710000-0000-4000-8000-000000000001'
  expect_staging_apply_failure_unchanged "$excessive_database" \
    'target organization has more than two active nonseed superadmins' \
    'more than two active nonseed superadmins' \
    "$excessive_organization_id" admin1@example.test iteratorstesting

  printf 'PASS: demo-account-logins\n'
}

case_preserve_foreign_data() {
  new_database
  database=$NEW_DATABASE
  apply_migrations "$database"
  organization_id='71000000-0000-4000-8000-000000000001'
  owner_id='71100000-0000-4000-8000-000000000001'
  identity_id='71100000-0000-4000-8000-000000000002'
  archived_user_id='71100000-0000-4000-8000-000000000004'
  archived_identity_id='71100000-0000-4000-8000-000000000005'
  client_id='71200000-0000-4000-8000-000000000001'
  project_id='71300000-0000-4000-8000-000000000001'
  project_user_id='71400000-0000-4000-8000-000000000001'
  work_unit_id='71500000-0000-4000-8000-000000000001'

  insert_active_organization "$database" "$organization_id"
  psql_case "$database" --quiet --command "
    INSERT INTO \"user\" (id, first_name, last_name, avatar, color, is_super_admin, created_at, modified_at, archived_at)
    VALUES ('$owner_id', 'Real', 'Owner', 'foreign-owner-avatar', '#ABCDEF', true,
            '2024-01-01 01:02:03+00', '2024-02-03 04:05:06+00', NULL);
    INSERT INTO user_identity (id, user_id, type, value, main, device_id, confirmed, confirmation_code, created_at)
    VALUES ('$identity_id', '$owner_id', 'MailAddress', 'real.owner@example.test', true,
            'foreign-device', true, NULL, '2024-01-01 01:02:03');
    INSERT INTO organization_user (id, organization_id, user_id, roles, archived_at)
    VALUES ('71100000-0000-4000-8000-000000000003', '$organization_id', '$owner_id', ARRAY['Owner'], NULL);
    INSERT INTO \"user\" (id, first_name, last_name, avatar, color, is_super_admin, created_at, modified_at, archived_at)
    VALUES ('$archived_user_id', 'Historical', 'Member', NULL, '#654321', false,
            '2024-01-01 01:02:03+00', '2024-02-03 04:05:06+00', '2025-01-01 00:00:00+00');
    INSERT INTO user_identity (id, user_id, type, value, main, device_id, confirmed, confirmation_code, created_at)
    VALUES ('$archived_identity_id', '$archived_user_id', 'MailAddress', 'historical.member@example.test', true,
            NULL, true, NULL, '2024-01-01 01:02:03');
    INSERT INTO organization_user (id, organization_id, user_id, roles, archived_at)
    VALUES ('71100000-0000-4000-8000-000000000006', '$organization_id', '$archived_user_id', ARRAY['Employee'], NULL);
    INSERT INTO client (id, name, color, organization_id, created_at, created_by, modified_at, modified_by,
                        archived_at, archived_by, logo, type, texture)
    VALUES ('$client_id', 'Foreign Client', '#123123', '$organization_id', '2024-01-02 00:00:00+00', '$owner_id',
            '2024-01-03 00:00:00+00', '$owner_id', NULL, NULL, 'foreign-logo', 'External', 'foreign-texture');
    INSERT INTO project (id, name, description, organization_id, client_id, created_at, created_by,
                         modified_at, modified_by, archived_at, archived_by)
    VALUES ('$project_id', 'Foreign Project', 'Real customer data fixture', '$organization_id', '$client_id',
            '2024-01-04 00:00:00+00', '$owner_id', '2024-01-05 00:00:00+00', '$owner_id', NULL, NULL);
    INSERT INTO project_user (id, project_id, user_id, role, created_at, archived_at)
    VALUES ('$project_user_id', '$project_id', '$owner_id', 'Owner', '2024-01-06 00:00:00+00', NULL);
    INSERT INTO work_unit (id, organization_id, user_id, project_id, date, duration, description,
                           created_at, modified_at, archived_at, task_id, state, client_id, entry_type)
    VALUES ('$work_unit_id', '$organization_id', '$owner_id', '$project_id', '2026-05-01', interval '2 hours',
            'Foreign worklog', '2026-05-01 08:00:00+00', '2026-05-01 10:00:00+00', NULL,
            'FOREIGN-100', 'Active', '$client_id', 'Worklog');
    INSERT INTO user_password (user_identity_id, password)
    VALUES ('$identity_id', 'foreign-password-hash');
    INSERT INTO user_login_token (id, user_identity_id, login_token, valid_to)
    VALUES ('71600000-0000-4000-8000-000000000001', '$identity_id', 'foreign-login-token', '2027-01-01 00:00:00');
    INSERT INTO refresh_token (user_id, device_id, user_agent, value, authed_by, last_authed_at, created_at, expired_at)
    VALUES ('$owner_id', 'foreign-device', 'foreign-agent', 'foreign-refresh-token', ARRAY['$identity_id']::uuid[],
            '2026-01-01 00:00:00', '2026-01-01 00:00:00', NULL);
    INSERT INTO invitation (id, identity_value, identity_type, roles, expire_at, accept_at, reject_at,
                            organization_id, created_at, created_by, modified_at, modified_by, archived_at)
    VALUES ('71600000-0000-4000-8000-000000000002', 'foreign.invitee@example.test', 'MailAddress', ARRAY['Employee'],
            '2027-01-01 00:00:00', NULL, NULL, '$organization_id', '2026-01-01 00:00:00', '$owner_id',
            '2026-01-01 00:00:00', '$owner_id', NULL);
    INSERT INTO work_unit_idempotency (
      claim_id, organization_id, actor_id, idempotency_key, operation, target_work_unit_id,
      fingerprint_version, canonical_request, fingerprint, state, outcome_status, outcome_body,
      result_work_unit_id, created_at, response_expires_at
    ) VALUES (
      '71600000-0000-4000-8000-000000000003', '$organization_id', '$owner_id',
      '71600000-0000-4000-8000-000000000004', 'Update', '$work_unit_id', 1,
      '{\"fixture\":true}'::jsonb, 'foreign-fingerprint', 'Completed', 200,
      '{\"ok\":true}'::jsonb, '$work_unit_id', '2026-05-01 10:00:00+00', '2027-01-01 00:00:00+00'
    );" >/dev/null

  snapshot_directory=$(mktemp -d "/tmp/epoca_ct532_lock.XXXXXX")
  TEMP_DIRECTORIES="$TEMP_DIRECTORIES $snapshot_directory"
  snapshot_foreign_rows "$database" "$snapshot_directory/before"

  run_apply_today "$database" 2026-05-18 >/dev/null
  snapshot_foreign_rows "$database" "$snapshot_directory/after-apply"
  assert_foreign_snapshots_equal "$snapshot_directory/before" "$snapshot_directory/after-apply" 'first apply'

  run_apply_today "$database" 2026-05-18 >/dev/null
  snapshot_foreign_rows "$database" "$snapshot_directory/after-rerun"
  assert_foreign_snapshots_equal "$snapshot_directory/before" "$snapshot_directory/after-rerun" 'same-day rerun'

  run_apply_today "$database" 2026-06-15 >/dev/null
  snapshot_foreign_rows "$database" "$snapshot_directory/after-rolling-window"
  assert_foreign_snapshots_equal "$snapshot_directory/before" "$snapshot_directory/after-rolling-window" 'rolling-window apply'

  full_before_dry_run=$(snapshot_rows "$database")
  run_dry_run_today "$database" 2026-06-15 >/dev/null
  full_after_dry_run=$(snapshot_rows "$database")
  [ "$full_before_dry_run" = "$full_after_dry_run" ] || fail '2026-06-15 local dry-run changed full seed-table snapshot'
  snapshot_foreign_rows "$database" "$snapshot_directory/after-dry-run"
  assert_foreign_snapshots_equal "$snapshot_directory/before" "$snapshot_directory/after-dry-run" 'dry-run'
  assert_core_distribution "$database" 29
  run_standalone_verify_today "$database" 2026-06-15 >/dev/null
  printf 'PASS: preserve-foreign-data\n'
}

case_final_verification_rollback() {
  new_database
  database=$NEW_DATABASE
  apply_migrations "$database"
  before_snapshot=$(snapshot_rows "$database")
  set +e
  failure_output=$(run_seed_sql_test "$database" local true true 2>&1)
  failure_status=$?
  set -e
  [ "$failure_status" -ne 0 ] || fail 'forced final verification failure unexpectedly committed'
  case "$failure_output" in
    *'demo seed test-only forced final verification failure'*) ;;
    *) fail 'forced final verification failure returned the wrong exact diagnostic' ;;
  esac
  after_snapshot=$(snapshot_rows "$database")
  [ "$before_snapshot" = "$after_snapshot" ] || fail 'forced final verification failure left a partial commit'

  set +e
  non_test_output=$(run_seed_sql_test "$database" local false true 2>&1)
  non_test_status=$?
  set -e
  [ "$non_test_status" -ne 0 ] || fail 'non-test local SQL accepted the forced verification flag'
  case "$non_test_output" in
    *'test verification failure flag is restricted to disposable local SQL test databases'*) ;;
    *) fail 'non-test local forced verification flag returned the wrong diagnostic' ;;
  esac

  if [ "$INTEGRATION_SQL" = false ]; then
    real_database=epoca_ct532
    real_before_snapshot=$(snapshot_rows "$real_database")
    set +e
    real_database_output=$(run_seed_sql_test "$real_database" local true true 2>&1)
    real_database_status=$?
    set -e
    [ "$real_database_status" -ne 0 ] || fail 'real local database accepted the forced verification flag'
    case "$real_database_output" in
      *'test verification failure flag is restricted to disposable local SQL test databases'*) ;;
      *) fail 'real local database forced verification flag returned the wrong diagnostic' ;;
    esac
    real_after_snapshot=$(snapshot_rows "$real_database")
    [ "$real_before_snapshot" = "$real_after_snapshot" ] || \
      fail 'rejected real local database forced-failure flag attempt changed persistent rows'
  fi

  set +e
  staging_output=$(run_seed_sql_test "$database" staging true true 2>&1)
  staging_status=$?
  set -e
  [ "$staging_status" -ne 0 ] || fail 'staging SQL accepted the forced verification flag'
  case "$staging_output" in
    *'test verification failure flag is restricted to disposable local SQL test databases'*) ;;
    *) fail 'staging forced verification flag returned the wrong diagnostic' ;;
  esac
  final_snapshot=$(snapshot_rows "$database")
  [ "$before_snapshot" = "$final_snapshot" ] || fail 'rejected forced-failure flag attempts changed persistent rows'
  printf 'PASS: final-verification-rollback\n'
}

case_member_conflict_matrix() {
  new_database
  database=$NEW_DATABASE
  apply_migrations "$database"
  organization_id='72000000-0000-4000-8000-000000000001'
  insert_active_organization "$database" "$organization_id"
  insert_nonseed_members "$database" "$organization_id" Employee 31 thirty-one
  expect_apply_failure_unchanged "$database" \
    'target organization has more than eight nonseed active members' 'thirty-one nonseed active members'

  new_database
  database=$NEW_DATABASE
  apply_migrations "$database"
  organization_id='72100000-0000-4000-8000-000000000001'
  insert_active_organization "$database" "$organization_id"
  insert_nonseed_members "$database" "$organization_id" Owner 3 three-owners
  expect_apply_failure_unchanged "$database" \
    'nonseed role capacity cannot reach the required distribution' 'three nonseed Owners'

  new_database
  database=$NEW_DATABASE
  apply_migrations "$database"
  organization_id='72200000-0000-4000-8000-000000000001'
  insert_active_organization "$database" "$organization_id"
  insert_nonseed_members "$database" "$organization_id" Manager 3 three-managers
  expect_apply_failure_unchanged "$database" \
    'nonseed role capacity cannot reach the required distribution' 'three nonseed Managers'

  new_database
  database=$NEW_DATABASE
  apply_migrations "$database"
  organization_id='72300000-0000-4000-8000-000000000001'
  insert_active_organization "$database" "$organization_id"
  insert_nonseed_members "$database" "$organization_id" Employee 9 nine-members
  expect_apply_failure_unchanged "$database" \
    'target organization has more than eight nonseed active members' 'nine nonseed members leave fewer than twenty-two seed slots'

  new_database
  database=$NEW_DATABASE
  apply_migrations "$database"
  organization_id='72400000-0000-4000-8000-000000000001'
  insert_active_organization "$database" "$organization_id"
  insert_nonseed_members "$database" "$organization_id" Employee 23 twenty-three-employees
  expect_apply_failure_unchanged "$database" \
    'target organization has more than eight nonseed active members' 'twenty-three nonseed Employees'

  new_database
  database=$NEW_DATABASE
  apply_migrations "$database"
  organization_id='72500000-0000-4000-8000-000000000001'
  insert_active_organization "$database" "$organization_id"
  insert_nonseed_members "$database" "$organization_id" External 5 five-externals
  expect_apply_failure_unchanged "$database" \
    'nonseed role capacity cannot reach the required distribution' 'five nonseed Externals'

  new_database
  database=$NEW_DATABASE
  apply_migrations "$database"
  psql_case "$database" --quiet --command \
    'ALTER TABLE public.work_unit DROP COLUMN entry_type CASCADE;' >/dev/null
  expect_apply_failure_unchanged "$database" \
    'demo seed schema preflight missing required columns: work_unit.entry_type' \
    'missing V21 work-unit entry_type migration'
  printf 'PASS: member-conflict-matrix\n'
}

case_negative_safety() {
  new_database
  database=$NEW_DATABASE
  apply_migrations "$database"
  psql_case "$database" --quiet --command "
    INSERT INTO organization (id, name, slug, created_at, created_by, modified_at, modified_by, archived_at)
    VALUES ('41000000-0000-4000-8000-000000000001', 'Foreign Archived', 'iteratorstesting', '2024-01-01 00:00:00+00',
            '41000000-0000-4000-8000-000000000002', '2024-01-01 00:00:00+00',
            '41000000-0000-4000-8000-000000000002', '2025-01-01 00:00:00+00');" >/dev/null
  expect_apply_failure_unchanged "$database" 'foreign archived organization owns the target slug' 'foreign archived organization slug collision'

  new_database
  database=$NEW_DATABASE
  apply_migrations "$database"
  deterministic_organization_id=$(query_value "$database" "SELECT uuid_generate_v5('2f2ed0a0-4d42-5f32-8f49-5d36cc3a0532'::uuid, 'organization:iteratorstesting');")
  psql_case "$database" --quiet --command "
    INSERT INTO organization (id, name, slug, created_at, created_by, modified_at, modified_by, archived_at)
    VALUES ('$deterministic_organization_id', 'Other Slug', 'other-slug', '2024-01-01 00:00:00+00',
            '42000000-0000-4000-8000-000000000001', '2024-01-01 00:00:00+00',
            '42000000-0000-4000-8000-000000000001', NULL);" >/dev/null
  expect_apply_failure_unchanged "$database" 'deterministic seed organization id is owned by another slug' 'seed organization id under another slug'

  new_database
  database=$NEW_DATABASE
  apply_migrations "$database"
  insert_active_organization "$database" '43000000-0000-4000-8000-000000000001'
  seed_user_id=$(query_value "$database" "SELECT uuid_generate_v5('2f2ed0a0-4d42-5f32-8f49-5d36cc3a0532'::uuid, 'user:amelia-nowak');")
  seed_identity_id=$(query_value "$database" "SELECT uuid_generate_v5('2f2ed0a0-4d42-5f32-8f49-5d36cc3a0532'::uuid, 'identity:amelia-nowak');")
  seed_membership_id=$(query_value "$database" "SELECT uuid_generate_v5('2f2ed0a0-4d42-5f32-8f49-5d36cc3a0532'::uuid, 'organization-user:amelia-nowak');")
  psql_case "$database" --quiet --command "
    INSERT INTO \"user\" (id, first_name, last_name, avatar, color, is_super_admin, created_at, modified_at, archived_at)
    VALUES ('$seed_user_id', 'Amelia', 'Nowak', NULL, '#000000', false,
            '2024-01-01 00:00:00+00', '2024-01-01 00:00:00+00', '2025-01-01 00:00:00+00');
    INSERT INTO user_identity (id, user_id, type, value, main, device_id, confirmed, confirmation_code, created_at)
    VALUES ('$seed_identity_id', '$seed_user_id', 'MailAddress', 'demo+amelia-nowak@epoca.example.invalid',
            true, NULL, true, NULL, '2024-01-01 00:00:00');
    INSERT INTO organization_user (id, organization_id, user_id, roles, archived_at)
    VALUES ('$seed_membership_id', '43000000-0000-4000-8000-000000000001',
            '$seed_user_id', ARRAY['Employee'], NULL);" >/dev/null
  expect_apply_failure_unchanged "$database" 'active organization membership references an archived seed user' 'active membership to archived seed user'

  new_database
  database=$NEW_DATABASE
  apply_migrations "$database"
  insert_active_organization "$database" '44000000-0000-4000-8000-000000000001'
  insert_fixture_user "$database" '44000000-0000-4000-8000-000000000002' NULL
  psql_case "$database" --quiet --command "
    INSERT INTO organization_user (id, organization_id, user_id, roles, archived_at) VALUES
      ('44000000-0000-4000-8000-000000000003', '44000000-0000-4000-8000-000000000001', '44000000-0000-4000-8000-000000000002', ARRAY['Employee'], NULL),
      ('44000000-0000-4000-8000-000000000004', '44000000-0000-4000-8000-000000000001', '44000000-0000-4000-8000-000000000002', ARRAY['Employee'], NULL);" >/dev/null
  expect_apply_failure_unchanged "$database" 'duplicate active organization membership rows are not supported' 'duplicate active member rows'

  new_database
  database=$NEW_DATABASE
  apply_migrations "$database"
  insert_active_organization "$database" '45000000-0000-4000-8000-000000000001'
  insert_fixture_user "$database" '45000000-0000-4000-8000-000000000002' NULL
  psql_case "$database" --quiet --command "
    INSERT INTO organization_user (id, organization_id, user_id, roles, archived_at)
    VALUES ('45000000-0000-4000-8000-000000000003', '45000000-0000-4000-8000-000000000001',
            '45000000-0000-4000-8000-000000000002', ARRAY['Employee','External'], NULL);" >/dev/null
  expect_apply_failure_unchanged "$database" 'nonseed active members must have exactly one supported role' 'multi-role active membership'

  new_database
  database=$NEW_DATABASE
  apply_migrations "$database"
  seed_user_id=$(query_value "$database" "SELECT uuid_generate_v5('2f2ed0a0-4d42-5f32-8f49-5d36cc3a0532'::uuid, 'user:amelia-nowak');")
  insert_fixture_user "$database" "$seed_user_id" NULL
  psql_case "$database" --quiet --command "
    INSERT INTO user_identity (id, user_id, type, value, main, device_id, confirmed, confirmation_code, created_at)
    VALUES ('45100000-0000-4000-8000-000000000001', '$seed_user_id', 'MailAddress',
            'foreign.confirmed@example.test', true, NULL, true, NULL, '2024-01-01 00:00:00');" >/dev/null
  expect_apply_failure_unchanged "$database" 'deterministic seed user id is not accompanied by its exact catalog identity' 'seed user id ownership collision'

  new_database
  database=$NEW_DATABASE
  apply_migrations "$database"
  insert_fixture_user "$database" '46000000-0000-4000-8000-000000000001' NULL
  psql_case "$database" --quiet --command "
    INSERT INTO user_identity (id, user_id, type, value, main, device_id, confirmed, confirmation_code, created_at)
    VALUES ('46000000-0000-4000-8000-000000000002', '46000000-0000-4000-8000-000000000001', 'MailAddress',
            'demo+amelia-nowak@epoca.example.invalid', true, NULL, true, NULL, '2024-01-01 00:00:00');" >/dev/null
  expect_apply_failure_unchanged "$database" 'deterministic seed identity value is owned by another relation' 'seed email ownership collision'

  new_database
  database=$NEW_DATABASE
  apply_migrations "$database"
  insert_fixture_user "$database" '47000000-0000-4000-8000-000000000001' NULL
  seed_identity_id=$(query_value "$database" "SELECT uuid_generate_v5('2f2ed0a0-4d42-5f32-8f49-5d36cc3a0532'::uuid, 'identity:amelia-nowak');")
  psql_case "$database" --quiet --command "
    INSERT INTO user_identity (id, user_id, type, value, main, device_id, confirmed, confirmation_code, created_at)
    VALUES ('$seed_identity_id', '47000000-0000-4000-8000-000000000001', 'MailAddress',
            'collision@example.test', true, NULL, true, NULL, '2024-01-01 00:00:00');" >/dev/null
  expect_apply_failure_unchanged "$database" 'deterministic seed identity id is owned by another relation' 'seed identity id ownership collision'

  new_database
  database=$NEW_DATABASE
  apply_migrations "$database"
  insert_active_organization "$database" '48000000-0000-4000-8000-000000000001'
  insert_fixture_user "$database" '48000000-0000-4000-8000-000000000002' NULL
  seed_membership_id=$(query_value "$database" "SELECT uuid_generate_v5('2f2ed0a0-4d42-5f32-8f49-5d36cc3a0532'::uuid, 'organization-user:amelia-nowak');")
  psql_case "$database" --quiet --command "
    INSERT INTO organization_user (id, organization_id, user_id, roles, archived_at)
    VALUES ('$seed_membership_id', '48000000-0000-4000-8000-000000000001',
            '48000000-0000-4000-8000-000000000002', ARRAY['Employee'], NULL);" >/dev/null
  expect_apply_failure_unchanged "$database" 'deterministic seed membership id is owned by another relation' 'seed membership id ownership collision'

  new_database
  database=$NEW_DATABASE
  apply_migrations "$database"
  seed_user_id=$(query_value "$database" "SELECT uuid_generate_v5('2f2ed0a0-4d42-5f32-8f49-5d36cc3a0532'::uuid, 'user:amelia-nowak');")
  seed_identity_id=$(query_value "$database" "SELECT uuid_generate_v5('2f2ed0a0-4d42-5f32-8f49-5d36cc3a0532'::uuid, 'identity:amelia-nowak');")
  psql_case "$database" --quiet --command "
    INSERT INTO organization (id, name, slug, created_at, created_by, modified_at, modified_by, archived_at)
    VALUES ('49000000-0000-4000-8000-000000000001', 'Archived Other Organization', 'archived-other', '2024-01-01 00:00:00+00',
            '49000000-0000-4000-8000-000000000002', '2024-01-01 00:00:00+00',
            '49000000-0000-4000-8000-000000000002', '2025-01-01 00:00:00+00');
    INSERT INTO \"user\" (id, first_name, last_name, avatar, color, is_super_admin, created_at, modified_at, archived_at)
    VALUES ('$seed_user_id', 'Archived', 'Seed Fixture', NULL, '#000000', false,
            '2024-01-01 00:00:00+00', '2024-01-01 00:00:00+00', '2025-01-01 00:00:00+00');
    INSERT INTO user_identity (id, user_id, type, value, main, device_id, confirmed, confirmation_code, created_at)
    VALUES ('$seed_identity_id', '$seed_user_id', 'MailAddress', 'demo+amelia-nowak@epoca.example.invalid',
            true, NULL, true, NULL, '2024-01-01 00:00:00');
    INSERT INTO organization_user (id, organization_id, user_id, roles, archived_at)
    VALUES ('49000000-0000-4000-8000-000000000003', '49000000-0000-4000-8000-000000000001',
            '$seed_user_id', ARRAY['Employee'], NULL);" >/dev/null
  expect_apply_failure_unchanged "$database" 'target seed user has an active membership in another organization' 'seed user active membership in another archived organization'

  printf 'PASS: negative-safety\n'
}

run_catalog_contract_on_disposable_database() {
  new_database
  catalog_database=$NEW_DATABASE
  apply_migrations "$catalog_database"
  DEMO_SEED_CATALOG_TEST_BACKEND=psql \
    DEMO_SEED_CATALOG_TEST_DATABASE="$catalog_database" \
    bash "$TEST_DIR/catalog-contract-test.sh"
}

SELECTED_CASE=all
SEEN_INTEGRATION_SQL=false
SEEN_CASE=false
while [ "$#" -gt 0 ]; do
  case "$1" in
    --integration-sql)
      [ "$SEEN_INTEGRATION_SQL" = false ] || fail 'duplicate --integration-sql'
      SEEN_INTEGRATION_SQL=true
      INTEGRATION_SQL=true
      shift
      ;;
    --case)
      [ "$SEEN_CASE" = false ] || fail 'duplicate --case'
      [ "$#" -ge 2 ] || fail 'missing --case value'
      SEEN_CASE=true
      SELECTED_CASE=$2
      shift 2
      ;;
    *)
      printf 'Usage: %s [--integration-sql] [--case empty-local|projects|project-cleanup|work-units|rolling-window|work-unit-collisions|work-unit-schema-guards|exact-work-unit-verification|retired-work-unit-lifecycle|verify-lock-serialization|external-project-capacity|foreign-active-project-assignment|rerun-same-day|reactivate-seed-organization|mixed-existing-members|catalog-parent-collisions|shrink-seed-members|identity-ownership|case-insensitive-identity-ownership|search-path-hardening|trigger-disabled|trigger-wrong-function|staging-sql|demo-account-logins|preserve-foreign-data|final-verification-rollback|member-conflict-matrix|negative-safety]\n' "$0" >&2
      exit 2
      ;;
  esac
done

cleanup_application_names=$(grep -oE 'PGAPPNAME=ct532_[a-z0-9_]+' "$0" | cut -d= -f2 | LC_ALL=C sort -u)
while IFS= read -r cleanup_application_name; do
  if ! [[ "$cleanup_application_name" =~ $CLEANUP_APPLICATION_NAME_REGEX ]]; then
    fail "cleanup application name is outside the closed cleanup regex: $cleanup_application_name"
  fi
done <<EOF
$cleanup_application_names
EOF

if [ "$INTEGRATION_SQL" = true ]; then
  run_catalog_contract_on_disposable_database
fi

case "$SELECTED_CASE" in
  all)
    case_empty_local
    case_projects
    case_project_cleanup
    case_work_units
    case_rolling_window
    case_work_unit_collisions
    case_work_unit_schema_guards
    case_exact_work_unit_verification
    case_retired_work_unit_lifecycle
    case_verify_lock_serialization
    case_external_project_capacity
    case_foreign_active_project_assignment
    case_rerun_same_day
    case_reactivate_seed_organization
    case_mixed_existing_members
    case_catalog_parent_collisions
    case_shrink_seed_members
    case_identity_ownership
    case_case_insensitive_identity_ownership
    case_search_path_hardening
    case_trigger_disabled
    case_trigger_wrong_function
    case_staging_sql
    case_demo_account_logins
    case_preserve_foreign_data
    case_final_verification_rollback
    case_member_conflict_matrix
    case_negative_safety
    ;;
  empty-local) case_empty_local ;;
  projects) case_projects ;;
  project-cleanup) case_project_cleanup ;;
  work-units) case_work_units ;;
  rolling-window) case_rolling_window ;;
  work-unit-collisions) case_work_unit_collisions ;;
  work-unit-schema-guards) case_work_unit_schema_guards ;;
  exact-work-unit-verification) case_exact_work_unit_verification ;;
  retired-work-unit-lifecycle) case_retired_work_unit_lifecycle ;;
  verify-lock-serialization) case_verify_lock_serialization ;;
  external-project-capacity) case_external_project_capacity ;;
  foreign-active-project-assignment) case_foreign_active_project_assignment ;;
  rerun-same-day) case_rerun_same_day ;;
  reactivate-seed-organization) case_reactivate_seed_organization ;;
  mixed-existing-members) case_mixed_existing_members ;;
  catalog-parent-collisions) case_catalog_parent_collisions ;;
  shrink-seed-members) case_shrink_seed_members ;;
  identity-ownership) case_identity_ownership ;;
  case-insensitive-identity-ownership) case_case_insensitive_identity_ownership ;;
  search-path-hardening) case_search_path_hardening ;;
  trigger-disabled) case_trigger_disabled ;;
  trigger-wrong-function) case_trigger_wrong_function ;;
  staging-sql) case_staging_sql ;;
  demo-account-logins) case_demo_account_logins ;;
  preserve-foreign-data) case_preserve_foreign_data ;;
  final-verification-rollback) case_final_verification_rollback ;;
  member-conflict-matrix) case_member_conflict_matrix ;;
  negative-safety) case_negative_safety ;;
  *) fail "unknown integration case: $SELECTED_CASE" ;;
esac
