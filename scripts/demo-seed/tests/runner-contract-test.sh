#!/usr/bin/env bash

set -euo pipefail

TEST_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
BUNDLE_DIR=$(CDPATH= cd -- "$TEST_DIR/.." && pwd -P)
# shellcheck source=assertions.sh
. "$TEST_DIR/assertions.sh"

TMP_DIR=$(mktemp -d "${TMPDIR:-/tmp}/demo-seed-runner.XXXXXX")
trap 'rm -rf "$TMP_DIR"' EXIT HUP INT TERM

FAKE_BIN="$TMP_DIR/bin"
mkdir -p "$FAKE_BIN"

cat >"$FAKE_BIN/psql" <<'FAKE_PSQL'
#!/usr/bin/env bash
set -euo pipefail

: "${FAKE_PSQL_CALL_DIR:?}"
: "${FAKE_EXPECTED_PGPASSWORD:?}"
if [ "${PGPASSWORD+x}" != x ] || [ "$PGPASSWORD" != "$FAKE_EXPECTED_PGPASSWORD" ]; then
  printf 'fake psql authentication environment mismatch\n' >&2
  exit 91
fi
call_number=0
while [ -e "$FAKE_PSQL_CALL_DIR/call-$call_number.args" ]; do
  call_number=$((call_number + 1))
done
call_path="$FAKE_PSQL_CALL_DIR/call-$call_number.args"
for arg in "$@"; do
  printf '%s\n' "$arg" >>"$call_path"
done

all_args=$(printf '%s\n' "$@")

case "$all_args" in
  *--file=-*) cat >"$call_path.stdin" ;;
esac

case "$all_args" in
  *date_to_validate=*)
    date_value=
    for arg in "$@"; do
      case "$arg" in
        date_to_validate=*) date_value=${arg#date_to_validate=} ;;
      esac
    done
    if [ "$date_value" = "${FAKE_REJECT_DATE:-2026-02-30}" ]; then
      printf 'ERROR: date/time field value out of range\n' >&2
      exit "${FAKE_DATE_EXIT:-1}"
    fi
    exit 0
    ;;
esac

case "$all_args" in
  *pg_control_system*)
    if [ "${FAKE_IDENTITY_EXIT:-0}" -ne 0 ]; then
      printf 'fake psql identity command failure\n' >&2
      exit "$FAKE_IDENTITY_EXIT"
    fi
    separator=$(printf '\034')
    printf '%s%s%s%s%s%s%s%s%s' \
      "${FAKE_DATABASE_NAME-epoca_ct532}" "$separator" \
      "${FAKE_SYSTEM_IDENTIFIER-7524217734000000001}" "$separator" \
      "${FAKE_SERVER_ADDRESS-10.20.30.40}" "$separator" \
      "${FAKE_SERVER_PORT-55432}" "$separator" \
      "${FAKE_SEED_TODAY-2026-05-19}"
    if [ "${FAKE_ADD_TRAILING_FIELD:-false}" = true ]; then
      printf '%s' "$separator"
    fi
    printf '\n'
    exit 0
    ;;
esac

case "$all_args" in
  *--file*) exit "${FAKE_FINAL_EXIT:-0}" ;;
esac

exit 0
FAKE_PSQL
chmod +x "$FAKE_BIN/psql"

ACTIVE_RUNNER="$BUNDLE_DIR/run.sh"
DEFAULT_DB_URL='jdbc:postgresql://127.0.0.1:55432/epoca_ct532'
DB_USER_VALUE=postgres
DB_PASSWORD_VALUE='do-not-print-me'
SCENARIO_NUMBER=0
LAST_OUTPUT=
LAST_STATUS=0
LAST_ARGV_LOG=
LAST_FINAL_ARGV=
LAST_CALL_COUNT=0

run_runner() {
  local active_jdbc_url=${TEST_DB_URL:-$DEFAULT_DB_URL}
  local call_number
  local call_path
  local call_argv
  local -a runner_command

  runner_command=("$ACTIVE_RUNNER")
  if [ "${TRACE_RUNNER:-false}" = true ]; then
    runner_command=(/bin/bash -x "$ACTIVE_RUNNER")
  fi

  SCENARIO_NUMBER=$((SCENARIO_NUMBER + 1))
  LAST_CALL_DIR="$TMP_DIR/calls-$SCENARIO_NUMBER"
  mkdir -p "$LAST_CALL_DIR"

  set +e
  LAST_OUTPUT=$(
    PATH="$FAKE_BIN:$PATH" \
      FAKE_PSQL_CALL_DIR="$LAST_CALL_DIR" \
      FAKE_DATABASE_NAME="${FAKE_DATABASE_NAME-epoca_ct532}" \
      FAKE_SYSTEM_IDENTIFIER="${FAKE_SYSTEM_IDENTIFIER-7524217734000000001}" \
      FAKE_SERVER_ADDRESS="${FAKE_SERVER_ADDRESS-10.20.30.40}" \
      FAKE_SERVER_PORT="${FAKE_SERVER_PORT-55432}" \
      FAKE_SEED_TODAY="${FAKE_SEED_TODAY-2026-05-19}" \
      FAKE_IDENTITY_EXIT="${FAKE_IDENTITY_EXIT:-0}" \
      FAKE_ADD_TRAILING_FIELD="${FAKE_ADD_TRAILING_FIELD:-false}" \
      FAKE_FINAL_EXIT="${FAKE_FINAL_EXIT:-0}" \
      FAKE_REJECT_DATE="${FAKE_REJECT_DATE:-2026-02-30}" \
      FAKE_DATE_EXIT="${FAKE_DATE_EXIT:-1}" \
      FAKE_EXPECTED_PGPASSWORD="${FAKE_EXPECTED_PASSWORD_OVERRIDE:-$DB_PASSWORD_VALUE}" \
      DB_URL="$active_jdbc_url" \
      DB_USER="$DB_USER_VALUE" \
      DB_PASSWORD="$DB_PASSWORD_VALUE" \
      "${runner_command[@]}" "$@" 2>&1
  )
  LAST_STATUS=$?
  set -e

  LAST_ARGV_LOG=
  LAST_FINAL_ARGV=
  call_number=0
  while [ -f "$LAST_CALL_DIR/call-$call_number.args" ]; do
    call_path="$LAST_CALL_DIR/call-$call_number.args"
    call_argv=$(cat "$call_path")
    LAST_ARGV_LOG="${LAST_ARGV_LOG}${call_argv}"
    LAST_FINAL_ARGV=$call_argv
    call_number=$((call_number + 1))
  done
  LAST_CALL_COUNT=$call_number

  assert_not_contains "$LAST_OUTPUT" "$DB_PASSWORD_VALUE" 'password must not appear in stdout or stderr'
  assert_not_contains "$LAST_ARGV_LOG" "$DB_PASSWORD_VALUE" 'password must not appear in psql argv'
  assert_not_contains "$LAST_OUTPUT" "$active_jdbc_url" 'active JDBC URL must not appear in stdout or stderr'
  assert_not_contains "$LAST_ARGV_LOG" "$active_jdbc_url" 'active JDBC URL must not appear in psql argv'
}

assert_failed_with() {
  local expected_message=$1
  shift
  run_runner "$@"
  if [ "$LAST_STATUS" -eq 0 ]; then
    fail "expected command to fail: $*"
  fi
  assert_contains "$LAST_OUTPUT" "$expected_message" "failure message for: $*"
}

assert_final_call() {
  local label=$1
  local target=$2
  local mode=$3
  local seed_today=$4
  local viewer_email=$5
  local organization_id=$6
  local database_name=$7
  local system_identifier=$8
  local server_address=$9
  shift 9
  local server_port=$1
  local postgresql_url=$2
  local sql_file=$3
  local expected

  expected=$(printf '%s\n' \
    '-X' \
    '--set' 'ON_ERROR_STOP=1' \
    '--set' "target=$target" \
    '--set' "operation=$mode" \
    '--set' 'organization_slug=iteratorstesting' \
    '--set' "seed_today=$seed_today" \
    '--set' "demo_viewer_email=$viewer_email" \
    '--set' "expected_organization_id=$organization_id" \
    '--set' "expected_database_name=$database_name" \
    '--set' "expected_system_identifier=$system_identifier" \
    '--set' "expected_server_address=$server_address" \
    '--set' "expected_server_port=$server_port" \
    '--set' 'sql_test_mode=false')

  if [ "$mode" = verify ]; then
    expected=$(printf '%s\n%s\n' "$expected" "$(printf '%s\n' '--set' 'standalone_verify=true')")
  else
    apply=false
    [ "$mode" != apply ] || apply=true
    expected=$(printf '%s\n%s\n' "$expected" \
      "$(printf '%s\n' '--set' 'standalone_verify=false' '--set' "apply=$apply")")
  fi

  expected=$(printf '%s\n%s' "$expected" \
    "$(printf '%s\n' '--username' "$DB_USER_VALUE" '--dbname' "$postgresql_url" '--file' "$sql_file")")
  assert_eq "$expected" "$LAST_FINAL_ARGV" "$label exact final psql argv"
}

assert_date_cast_call() {
  local date_value=$1
  local expected
  local actual
  local date_call_path="$LAST_CALL_DIR/call-1.args"
  local date_stdin_path="$LAST_CALL_DIR/call-1.args.stdin"

  [ -f "$date_call_path" ] || fail "missing separate date-cast psql call for $date_value"
  actual=$(cat "$date_call_path")
  expected=$(printf '%s\n' \
    '-X' \
    '--set' 'ON_ERROR_STOP=1' \
    '--set' "date_to_validate=$date_value" \
    '--no-align' \
    '--tuples-only' \
    '--quiet' \
    '--username' "$DB_USER_VALUE" \
    '--dbname' 'postgresql://127.0.0.1:55432/epoca_ct532' \
    '--file=-')
  assert_eq "$expected" "$actual" "exact safe date-cast argv for $date_value"
  [ -f "$date_stdin_path" ] || fail "missing date-cast stdin for $date_value"
  actual=$(cat "$date_stdin_path")
  assert_eq "SELECT :'date_to_validate'::date;" "$actual" "exact safe date-cast stdin for $date_value"
}

assert_identity_call() {
  local postgresql_url=$1
  local identity_call_path="$LAST_CALL_DIR/call-0.args"
  local field_separator
  local expected
  local actual

  [ -f "$identity_call_path" ] || fail 'missing identity preflight psql call'
  field_separator=$(printf '\034')
  actual=$(cat "$identity_call_path")
  expected=$(printf '%s\n' \
    '-X' \
    '--set' 'ON_ERROR_STOP=1' \
    '--no-align' \
    '--tuples-only' \
    '--quiet' \
    '--field-separator' "$field_separator" \
    '--username' "$DB_USER_VALUE" \
    '--dbname' "$postgresql_url" \
    '--command' \
    "SELECT pg_catalog.current_database(), (pg_catalog.pg_control_system()).system_identifier, pg_catalog.inet_server_addr(), pg_catalog.inet_server_port(), (CURRENT_TIMESTAMP AT TIME ZONE 'Europe/Warsaw')::pg_catalog.date;")
  assert_eq "$expected" "$actual" 'exact identity preflight psql argv'
}

assert_identity_rejected() {
  local expected_message=$1
  local label=$2

  IDENTITY_CONTRACT_CASES=$((IDENTITY_CONTRACT_CASES + 1))
  run_runner local --dry-run
  if [ "$LAST_STATUS" -eq 0 ]; then
    fail "$label unexpectedly succeeded"
  fi
  assert_contains "$LAST_OUTPUT" "$expected_message" "$label failure message"
  assert_status 1 "$LAST_CALL_COUNT" "$label must stop after identity preflight"
  assert_not_contains "$LAST_ARGV_LOG" '--file' "$label must not invoke final SQL"
}

assert_staging_failure() {
  local expected_message=$1
  shift
  TEST_DB_URL='jdbc:postgresql://staging.db.internal:55432/epoca_ct532'
  assert_failed_with "$expected_message" "$@"
  unset TEST_DB_URL
}

IDENTITY_CONTRACT_CASES=0

run_runner local --dry-run
assert_status 0 "$LAST_STATUS" 'identity preflight baseline should succeed'
IDENTITY_CONTRACT_CASES=$((IDENTITY_CONTRACT_CASES + 1))
assert_identity_call 'postgresql://127.0.0.1:55432/epoca_ct532'

FAKE_IDENTITY_EXIT=74
run_runner local --dry-run
unset FAKE_IDENTITY_EXIT
IDENTITY_CONTRACT_CASES=$((IDENTITY_CONTRACT_CASES + 1))
assert_status 74 "$LAST_STATUS" 'identity command failure status must propagate'
assert_contains "$LAST_OUTPUT" 'Database identity preflight failed' 'identity command failure diagnostic'
assert_status 1 "$LAST_CALL_COUNT" 'identity command failure must stop before final SQL'
assert_not_contains "$LAST_ARGV_LOG" '--file' 'identity command failure must not invoke final SQL'

FAKE_DATABASE_NAME=''
assert_identity_rejected 'exactly five non-empty fields' 'empty identity database'
unset FAKE_DATABASE_NAME
FAKE_SYSTEM_IDENTIFIER=''
assert_identity_rejected 'exactly five non-empty fields' 'empty identity system identifier'
unset FAKE_SYSTEM_IDENTIFIER
FAKE_SERVER_ADDRESS=''
assert_identity_rejected 'exactly five non-empty fields' 'empty identity server address'
unset FAKE_SERVER_ADDRESS
FAKE_SERVER_PORT=''
assert_identity_rejected 'exactly five non-empty fields' 'empty identity server port'
unset FAKE_SERVER_PORT
FAKE_SEED_TODAY=''
assert_identity_rejected 'exactly five non-empty fields' 'empty identity seed date'
unset FAKE_SEED_TODAY

FAKE_DATABASE_NAME='bad/name'
assert_identity_rejected 'invalid database name' 'malformed identity database'
unset FAKE_DATABASE_NAME
FAKE_SYSTEM_IDENTIFIER='not-numeric'
assert_identity_rejected 'invalid system identifier' 'malformed identity system identifier'
unset FAKE_SYSTEM_IDENTIFIER
FAKE_SERVER_ADDRESS='bad/address'
assert_identity_rejected 'invalid server address' 'malformed identity server address'
unset FAKE_SERVER_ADDRESS
FAKE_SERVER_PORT='not-numeric'
assert_identity_rejected 'invalid server port' 'malformed identity server port'
unset FAKE_SERVER_PORT
FAKE_SEED_TODAY='2026-5-19'
assert_identity_rejected 'invalid seed date' 'malformed identity seed date'
unset FAKE_SEED_TODAY

FAKE_ADD_TRAILING_FIELD=true
assert_identity_rejected 'exactly five fields' 'wrong identity field count'
unset FAKE_ADD_TRAILING_FIELD

assert_eq 13 "$IDENTITY_CONTRACT_CASES" 'identity preflight contract coverage'

# Core CLI rejection table.
assert_failed_with 'Usage'
assert_failed_with 'Unsupported target' production --dry-run
assert_failed_with 'Unsupported target' verify production
assert_failed_with 'Unsupported mode' local verify
assert_failed_with 'Unknown option' local --dry-run --wat value
assert_failed_with 'organization slug is fixed' local --dry-run --organization-slug changed

TRACE_RUNNER=true
run_runner local --dry-run
unset TRACE_RUNNER
assert_status 0 "$LAST_STATUS" 'local dry-run should remain successful under Bash xtrace'

FAKE_EXPECTED_PASSWORD_OVERRIDE='intentionally-wrong-test-expectation'
run_runner local --dry-run
unset FAKE_EXPECTED_PASSWORD_OVERRIDE
if [ "$LAST_STATUS" -eq 0 ]; then
  fail 'fake psql must reject an incorrect PGPASSWORD environment'
fi
assert_contains "$LAST_OUTPUT" 'authentication environment mismatch' \
  'fake psql wrong-password diagnostic'

# Every known option rejects duplicate and missing values deterministically.
assert_failed_with 'Duplicate option' local --apply \
  --confirm-local-database epoca_ct532 --confirm-local-database epoca_ct532
assert_staging_failure 'Duplicate option' staging --dry-run \
  --confirm-organization iteratorstesting --confirm-organization iteratorstesting
assert_staging_failure 'Duplicate option' staging --dry-run \
  --demo-viewer-email one@example.com --demo-viewer-email two@example.com
assert_failed_with 'Duplicate option' local --dry-run \
  --today 2026-05-18 --today 2026-05-18

assert_failed_with 'Missing value' local --apply --confirm-local-database
assert_staging_failure 'Missing value' staging --dry-run --confirm-organization
assert_staging_failure 'Missing value' staging --dry-run --demo-viewer-email
assert_failed_with 'Missing value' local --dry-run --today

# The mode/option matrix must cover four known options across six base modes.
OPTION_MATRIX_CASES=0

OPTION_BUNDLE="$TMP_DIR/option-matrix-demo-seed"
cp -R "$BUNDLE_DIR" "$OPTION_BUNDLE"
OPTION_BUNDLE=$(CDPATH= cd -- "$OPTION_BUNDLE" && pwd -P)
cat >"$OPTION_BUNDLE/staging-fingerprint.conf" <<'EOF'
DATABASE_NAME=epoca_ct532
SYSTEM_IDENTIFIER=7524217734000000001
SERVER_ADDRESS=10.20.30.40
SERVER_PORT=55432
ORGANIZATION_ID=11111111-2222-4333-8444-555555555555
EOF

assert_option_case() {
  local expected=$1
  local label=$2
  shift 2

  OPTION_MATRIX_CASES=$((OPTION_MATRIX_CASES + 1))
  run_runner "$@"
  if [ "$expected" = SUCCESS ]; then
    assert_status 0 "$LAST_STATUS" "$label"
  else
    if [ "$LAST_STATUS" -eq 0 ]; then
      fail "$label unexpectedly succeeded"
    fi
    assert_contains "$LAST_OUTPUT" "$expected" "$label failure message"
  fi
}

# local dry-run: local confirmation invalid; staging options invalid; today valid.
assert_option_case 'not valid for dry-run' 'local dry-run / local confirmation' \
  local --dry-run --confirm-local-database epoca_ct532
assert_option_case 'not valid for local' 'local dry-run / organization confirmation' \
  local --dry-run --confirm-organization iteratorstesting
assert_option_case 'not valid for local' 'local dry-run / demo viewer' \
  local --dry-run --demo-viewer-email viewer@example.com
assert_option_case SUCCESS 'local dry-run / today' \
  local --dry-run --today 2026-05-18

# local apply: local confirmation and today valid; staging options invalid.
assert_option_case SUCCESS 'local apply / local confirmation' \
  local --apply --confirm-local-database epoca_ct532
assert_option_case 'not valid for local' 'local apply / organization confirmation' \
  local --apply --confirm-local-database epoca_ct532 --confirm-organization iteratorstesting
assert_option_case 'not valid for local' 'local apply / demo viewer' \
  local --apply --confirm-local-database epoca_ct532 --demo-viewer-email viewer@example.com
assert_option_case SUCCESS 'local apply / today' \
  local --apply --confirm-local-database epoca_ct532 --today 2026-05-18

# local verify: local confirmation and staging options invalid; today valid.
assert_option_case 'not valid for verify' 'local verify / local confirmation' \
  verify local --confirm-local-database epoca_ct532
assert_option_case 'not valid for local' 'local verify / organization confirmation' \
  verify local --confirm-organization iteratorstesting
assert_option_case 'not valid for local' 'local verify / demo viewer' \
  verify local --demo-viewer-email viewer@example.com
assert_option_case SUCCESS 'local verify / today' \
  verify local --today 2026-05-18

# Local apply and verify also reject both staging-only options together.
run_runner local --apply --confirm-local-database epoca_ct532 \
  --confirm-organization iteratorstesting --demo-viewer-email viewer@example.com
assert_status 2 "$LAST_STATUS" 'local apply rejects both staging-only options'
assert_contains "$LAST_OUTPUT" 'not valid for local' 'local apply staging-only option failure'
run_runner verify local \
  --confirm-organization iteratorstesting --demo-viewer-email viewer@example.com
assert_status 2 "$LAST_STATUS" 'local verify rejects both staging-only options'
assert_contains "$LAST_OUTPUT" 'not valid for local' 'local verify staging-only option failure'

ACTIVE_RUNNER="$OPTION_BUNDLE/run.sh"
TEST_DB_URL='jdbc:postgresql://staging.db.internal:55432/epoca_ct532'

# staging dry-run: local confirmation and today invalid; staging options valid.
assert_option_case 'not valid for staging' 'staging dry-run / local confirmation' \
  staging --dry-run --confirm-local-database epoca_ct532 \
  --confirm-organization iteratorstesting --demo-viewer-email viewer@example.com
assert_option_case SUCCESS 'staging dry-run / organization confirmation' \
  staging --dry-run --confirm-organization iteratorstesting --demo-viewer-email viewer@example.com
assert_option_case SUCCESS 'staging dry-run / demo viewer' \
  staging --dry-run --confirm-organization iteratorstesting --demo-viewer-email viewer@example.com
assert_option_case 'staging rejects today' 'staging dry-run / today' \
  staging --dry-run --confirm-organization iteratorstesting \
  --demo-viewer-email viewer@example.com --today 2026-05-18

# staging apply: local confirmation and today invalid; staging options valid.
assert_option_case 'not valid for staging' 'staging apply / local confirmation' \
  staging --apply --confirm-local-database epoca_ct532 \
  --confirm-organization iteratorstesting --demo-viewer-email viewer@example.com
assert_option_case SUCCESS 'staging apply / organization confirmation' \
  staging --apply --confirm-organization iteratorstesting --demo-viewer-email viewer@example.com
assert_option_case SUCCESS 'staging apply / demo viewer' \
  staging --apply --confirm-organization iteratorstesting --demo-viewer-email viewer@example.com
assert_option_case 'staging rejects today' 'staging apply / today' \
  staging --apply --confirm-organization iteratorstesting \
  --demo-viewer-email viewer@example.com --today 2026-05-18

# staging verify: local confirmation and today invalid; staging options valid.
assert_option_case 'not valid for staging' 'staging verify / local confirmation' \
  verify staging --confirm-local-database epoca_ct532 \
  --confirm-organization iteratorstesting --demo-viewer-email viewer@example.com
assert_option_case SUCCESS 'staging verify / organization confirmation' \
  verify staging --confirm-organization iteratorstesting --demo-viewer-email viewer@example.com
assert_option_case SUCCESS 'staging verify / demo viewer' \
  verify staging --confirm-organization iteratorstesting --demo-viewer-email viewer@example.com
assert_option_case 'staging rejects today' 'staging verify / today' \
  verify staging --confirm-organization iteratorstesting \
  --demo-viewer-email viewer@example.com --today 2026-05-18

unset TEST_DB_URL
ACTIVE_RUNNER="$BUNDLE_DIR/run.sh"
assert_eq 24 "$OPTION_MATRIX_CASES" 'mode/option matrix coverage'

# Date validation must distinguish strict-shape rejection from safe SQL casting.
DATE_VALIDATION_CASES=0

# Local confirmation and loopback guard failures.
assert_failed_with '--confirm-local-database' local --apply
assert_failed_with 'confirmation mismatch' local --apply --confirm-local-database wrong
TEST_DB_URL='jdbc:postgresql://db.example.internal:55432/epoca_ct532'
assert_failed_with 'loopback' local --dry-run
unset TEST_DB_URL

# Successful local dry-run, apply, verify, and verify with a validated date override.
run_runner local --dry-run --today 2026-05-18
assert_status 0 "$LAST_STATUS" 'local dry-run should succeed'
assert_contains "$LAST_OUTPUT" 'seed_today=2026-05-18' 'local dry-run should report override date'
assert_contains "$LAST_OUTPUT" 'tunnel/proxy' 'local warning should be printed'
DATE_VALIDATION_CASES=$((DATE_VALIDATION_CASES + 1))
assert_status 3 "$LAST_CALL_COUNT" 'valid date should run identity, date cast, and final SQL'
assert_date_cast_call 2026-05-18
assert_final_call 'local dry-run' local dry-run 2026-05-18 '' '' \
  epoca_ct532 7524217734000000001 10.20.30.40 55432 \
  'postgresql://127.0.0.1:55432/epoca_ct532' "$BUNDLE_DIR/seed.sql"

assert_failed_with 'Invalid --today date' local --dry-run --today 2026-5-18
DATE_VALIDATION_CASES=$((DATE_VALIDATION_CASES + 1))
assert_status 1 "$LAST_CALL_COUNT" 'non-padded date must fail before a date-cast call'
assert_not_contains "$LAST_ARGV_LOG" 'date_to_validate=' 'non-padded date must not reach date cast'

INJECTION_DATE="2026-05-18'; SELECT pg_sleep(10); --"
assert_failed_with 'Invalid --today date' local --dry-run --today "$INJECTION_DATE"
DATE_VALIDATION_CASES=$((DATE_VALIDATION_CASES + 1))
assert_status 1 "$LAST_CALL_COUNT" 'injection-shaped date must fail before a date-cast call'
assert_not_contains "$LAST_ARGV_LOG" 'date_to_validate=' 'injection-shaped date must not reach date cast'

run_runner local --apply --confirm-local-database epoca_ct532
assert_status 0 "$LAST_STATUS" 'confirmed local apply should succeed'
assert_final_call 'local apply' local apply 2026-05-19 '' '' \
  epoca_ct532 7524217734000000001 10.20.30.40 55432 \
  'postgresql://127.0.0.1:55432/epoca_ct532' "$BUNDLE_DIR/seed.sql"

run_runner verify local
assert_status 0 "$LAST_STATUS" 'local standalone verify should succeed'
assert_final_call 'local verify' local verify 2026-05-19 '' '' \
  epoca_ct532 7524217734000000001 10.20.30.40 55432 \
  'postgresql://127.0.0.1:55432/epoca_ct532' "$BUNDLE_DIR/verify.sql"

run_runner verify local --today 2026-05-18
assert_status 0 "$LAST_STATUS" 'local standalone verify should accept --today'
assert_contains "$LAST_OUTPUT" 'seed_today=2026-05-18' 'local verify should report override date'
assert_final_call 'local verify with today' local verify 2026-05-18 '' '' \
  epoca_ct532 7524217734000000001 10.20.30.40 55432 \
  'postgresql://127.0.0.1:55432/epoca_ct532' "$BUNDLE_DIR/verify.sql"

assert_failed_with 'Invalid --today date' verify local --today 2026-02-30
DATE_VALIDATION_CASES=$((DATE_VALIDATION_CASES + 1))
assert_status 2 "$LAST_CALL_COUNT" 'calendar-invalid date must reach only identity and date cast'
assert_date_cast_call 2026-02-30

FAKE_DATE_EXIT=74
run_runner verify local --today 2026-02-30
unset FAKE_DATE_EXIT
assert_status 74 "$LAST_STATUS" 'runner must propagate date-cast psql status exactly'
assert_status 2 "$LAST_CALL_COUNT" 'date-cast failure must stop before final SQL'
assert_eq 4 "$DATE_VALIDATION_CASES" 'date validation contract coverage'

# Every staging mode requires exact confirmation and a viewer, and rejects today.
assert_staging_failure '--confirm-organization' staging --dry-run --demo-viewer-email viewer@example.com
assert_staging_failure '--confirm-organization' staging --dry-run \
  --confirm-organization wrong --demo-viewer-email viewer@example.com
assert_staging_failure '--demo-viewer-email' staging --dry-run --confirm-organization iteratorstesting
assert_staging_failure 'staging rejects today' staging --dry-run \
  --confirm-organization iteratorstesting --demo-viewer-email viewer@example.com --today 2026-05-18

assert_staging_failure '--confirm-organization' staging --apply --demo-viewer-email viewer@example.com
assert_staging_failure '--confirm-organization' staging --apply \
  --confirm-organization wrong --demo-viewer-email viewer@example.com
assert_staging_failure '--demo-viewer-email' staging --apply --confirm-organization iteratorstesting
assert_staging_failure 'staging rejects today' staging --apply \
  --confirm-organization iteratorstesting --demo-viewer-email viewer@example.com --today 2026-05-18

assert_staging_failure '--confirm-organization' verify staging --demo-viewer-email viewer@example.com
assert_staging_failure '--confirm-organization' verify staging \
  --confirm-organization wrong --demo-viewer-email viewer@example.com
assert_staging_failure '--demo-viewer-email' verify staging --confirm-organization iteratorstesting
assert_staging_failure 'staging rejects today' verify staging \
  --confirm-organization iteratorstesting --demo-viewer-email viewer@example.com --today 2026-05-18

# Final SQL status propagation.
FAKE_FINAL_EXIT=73
run_runner local --dry-run
assert_status 73 "$LAST_STATUS" 'runner must propagate final psql status'
unset FAKE_FINAL_EXIT

# Narrow JDBC grammar, numeric port safety, and local database allow-list.
assert_bad_url() {
  TEST_DB_URL=$1
  assert_failed_with "$2" local --dry-run
  unset TEST_DB_URL
}

assert_bad_url 'not-a-jdbc-url' 'Malformed DB_URL'
assert_bad_url 'jdbc:postgresql://user@127.0.0.1:55432/epoca_ct532' 'Malformed DB_URL'
assert_bad_url 'jdbc:postgresql://127.0.0.1:55432/epoca_ct532#fragment' 'Malformed DB_URL'
assert_bad_url 'jdbc:postgresql://127.0.0.1:55432/one/two' 'Malformed DB_URL'
assert_bad_url 'jdbc:postgresql://127.0.0.1:/epoca_ct532' 'Malformed DB_URL'
assert_bad_url 'jdbc:postgresql://127.0.0.1:123456/epoca_ct532' 'Malformed DB_URL'
assert_bad_url 'jdbc:postgresql://127.0.0.1:18446744073709557048/epoca_ct532' 'Malformed DB_URL'
assert_bad_url 'jdbc:postgresql://127.0.0.1:0/epoca_ct532' 'Malformed DB_URL port'
assert_bad_url 'jdbc:postgresql://127.0.0.1:65536/epoca_ct532' 'Malformed DB_URL port'
assert_bad_url 'jdbc:postgresql://[::1:55432/epoca_ct532' 'Malformed DB_URL'
assert_bad_url 'jdbc:postgresql://[not-ipv6]:55432/epoca_ct532' 'Malformed DB_URL host'
assert_bad_url 'jdbc:postgresql://[::1/path]:55432/epoca_ct532' 'Malformed DB_URL host'
assert_bad_url 'jdbc:postgresql://::1:55432/epoca_ct532' 'Malformed DB_URL'
assert_bad_url 'jdbc:postgresql://127.0.0.1:55432/epoca_ct532?sslmode=require' 'Malformed DB_URL'

TEST_DB_URL='jdbc:postgresql://127.0.0.1/epoca_ct532'
FAKE_SERVER_PORT=5432
run_runner local --dry-run
assert_status 0 "$LAST_STATUS" 'omitted JDBC port should use PostgreSQL default'
assert_final_call 'local omitted port' local dry-run 2026-05-19 '' '' \
  epoca_ct532 7524217734000000001 10.20.30.40 5432 \
  'postgresql://127.0.0.1:5432/epoca_ct532' "$BUNDLE_DIR/seed.sql"
unset FAKE_SERVER_PORT TEST_DB_URL

TEST_DB_URL='jdbc:postgresql://[::1]:55432/epoca_ct532'
run_runner local --dry-run
assert_status 0 "$LAST_STATUS" 'bracketed IPv6 loopback should succeed'
assert_final_call 'local IPv6' local dry-run 2026-05-19 '' '' \
  epoca_ct532 7524217734000000001 10.20.30.40 55432 \
  'postgresql://[::1]:55432/epoca_ct532' "$BUNDLE_DIR/seed.sql"
unset TEST_DB_URL

TEST_DB_URL='jdbc:postgresql://localhost:55432/epoca'
FAKE_DATABASE_NAME=epoca
run_runner local --dry-run
assert_status 0 "$LAST_STATUS" 'epoca database should be allowed locally'
unset FAKE_DATABASE_NAME TEST_DB_URL

TEST_DB_URL='jdbc:postgresql://127.0.0.1:55432/epoca_ct532_case_12_34'
FAKE_DATABASE_NAME=epoca_ct532_case_12_34
run_runner local --dry-run
assert_status 0 "$LAST_STATUS" 'numbered CT-532 case database should be allowed locally'
unset FAKE_DATABASE_NAME TEST_DB_URL

assert_bad_url 'jdbc:postgresql://127.0.0.1:55432/epoca_ct532_case_1' 'not approved for local seeding'
assert_bad_url 'jdbc:postgresql://127.0.0.1:55432/epoca_ct532_case_1_x' 'not approved for local seeding'
assert_bad_url 'jdbc:postgresql://127.0.0.1:55432/epoca_ct532_case_1_2_extra' 'not approved for local seeding'
assert_bad_url 'jdbc:postgresql://127.0.0.1:55432/epoca_unapproved' 'not approved for local seeding'

# Work only against a copied bundle when replacing the staging fingerprint.
COPIED_BUNDLE="$TMP_DIR/copied-demo-seed"
cp -R "$BUNDLE_DIR" "$COPIED_BUNDLE"
COPIED_BUNDLE=$(CDPATH= cd -- "$COPIED_BUNDLE" && pwd -P)
ACTIVE_RUNNER="$COPIED_BUNDLE/run.sh"
TEST_DB_URL='jdbc:postgresql://staging.db.internal:55432/epoca_ct532'
ORGANIZATION_ID='11111111-2222-4333-8444-555555555555'

write_fingerprint() {
  cat >"$COPIED_BUNDLE/staging-fingerprint.conf" <<EOF
DATABASE_NAME=$1
SYSTEM_IDENTIFIER=$2
SERVER_ADDRESS=$3
SERVER_PORT=$4
ORGANIZATION_ID=$5
EOF
}

run_staging_seed() {
  local mode=$1
  if [ "$mode" = verify ]; then
    run_runner verify staging \
      --confirm-organization iteratorstesting --demo-viewer-email viewer@example.com
  else
    run_runner staging "--$mode" \
      --confirm-organization iteratorstesting --demo-viewer-email viewer@example.com
  fi
}

assert_staging_fingerprint_failure() {
  local expected_message=$1
  run_staging_seed dry-run
  if [ "$LAST_STATUS" -eq 0 ]; then
    fail "expected staging fingerprint failure: $expected_message"
  fi
  assert_contains "$LAST_OUTPUT" "$expected_message" "staging fingerprint failure message"
}

write_fingerprint epoca_ct532 7524217734000000001 10.20.30.40 55432 "$ORGANIZATION_ID"
run_staging_seed dry-run
assert_status 0 "$LAST_STATUS" 'staging dry-run should succeed with matching fingerprint'
assert_final_call 'staging dry-run' staging dry-run 2026-05-19 viewer@example.com "$ORGANIZATION_ID" \
  epoca_ct532 7524217734000000001 10.20.30.40 55432 \
  'postgresql://staging.db.internal:55432/epoca_ct532' "$COPIED_BUNDLE/seed.sql"

run_staging_seed apply
assert_status 0 "$LAST_STATUS" 'staging apply should succeed with matching fingerprint'
assert_final_call 'staging apply' staging apply 2026-05-19 viewer@example.com "$ORGANIZATION_ID" \
  epoca_ct532 7524217734000000001 10.20.30.40 55432 \
  'postgresql://staging.db.internal:55432/epoca_ct532' "$COPIED_BUNDLE/seed.sql"

run_staging_seed verify
assert_status 0 "$LAST_STATUS" 'staging verify should succeed with matching fingerprint'
assert_final_call 'staging verify' staging verify 2026-05-19 viewer@example.com "$ORGANIZATION_ID" \
  epoca_ct532 7524217734000000001 10.20.30.40 55432 \
  'postgresql://staging.db.internal:55432/epoca_ct532' "$COPIED_BUNDLE/verify.sql"

cat >"$COPIED_BUNDLE/staging-fingerprint.conf" <<'EOF'
MALFORMED
EOF
assert_staging_fingerprint_failure 'Malformed staging fingerprint line'

write_fingerprint epoca_ct532 7524217734000000001 10.20.30.40 55432 "$ORGANIZATION_ID"
printf 'UNKNOWN=value\n' >>"$COPIED_BUNDLE/staging-fingerprint.conf"
assert_staging_fingerprint_failure 'Unknown staging fingerprint key'

write_fingerprint epoca_ct532 7524217734000000001 10.20.30.40 55432 "$ORGANIZATION_ID"
printf 'DATABASE_NAME=epoca_ct532\n' >>"$COPIED_BUNDLE/staging-fingerprint.conf"
assert_staging_fingerprint_failure 'Duplicate staging fingerprint key'

cat >"$COPIED_BUNDLE/staging-fingerprint.conf" <<EOF
DATABASE_NAME=epoca_ct532
SYSTEM_IDENTIFIER=7524217734000000001
SERVER_ADDRESS=10.20.30.40
SERVER_PORT=55432
EOF
assert_staging_fingerprint_failure 'Missing staging fingerprint key: ORGANIZATION_ID'

write_fingerprint UNCONFIGURED 7524217734000000001 10.20.30.40 55432 "$ORGANIZATION_ID"
assert_staging_fingerprint_failure 'UNCONFIGURED'
write_fingerprint epoca_ct532 UNCONFIGURED 10.20.30.40 55432 "$ORGANIZATION_ID"
assert_staging_fingerprint_failure 'UNCONFIGURED'
write_fingerprint epoca_ct532 7524217734000000001 UNCONFIGURED 55432 "$ORGANIZATION_ID"
assert_staging_fingerprint_failure 'UNCONFIGURED'
write_fingerprint epoca_ct532 7524217734000000001 10.20.30.40 UNCONFIGURED "$ORGANIZATION_ID"
assert_staging_fingerprint_failure 'UNCONFIGURED'
write_fingerprint epoca_ct532 7524217734000000001 10.20.30.40 55432 UNCONFIGURED
assert_staging_fingerprint_failure 'UNCONFIGURED'

write_fingerprint 'bad/name' 7524217734000000001 10.20.30.40 55432 "$ORGANIZATION_ID"
assert_staging_fingerprint_failure 'Invalid staging DATABASE_NAME'
write_fingerprint epoca_ct532 not_numeric 10.20.30.40 55432 "$ORGANIZATION_ID"
assert_staging_fingerprint_failure 'Invalid staging SYSTEM_IDENTIFIER'
write_fingerprint epoca_ct532 7524217734000000001 'bad/address' 55432 "$ORGANIZATION_ID"
assert_staging_fingerprint_failure 'Invalid staging SERVER_ADDRESS'
write_fingerprint epoca_ct532 7524217734000000001 10.20.30.40 not_numeric "$ORGANIZATION_ID"
assert_staging_fingerprint_failure 'Invalid staging SERVER_PORT'
write_fingerprint epoca_ct532 7524217734000000001 10.20.30.40 55432 not-a-uuid
assert_staging_fingerprint_failure 'Invalid staging ORGANIZATION_ID'

write_fingerprint wrong_database 7524217734000000001 10.20.30.40 55432 "$ORGANIZATION_ID"
assert_staging_fingerprint_failure 'fingerprint mismatch: DATABASE_NAME'
write_fingerprint epoca_ct532 7524217734000000002 10.20.30.40 55432 "$ORGANIZATION_ID"
assert_staging_fingerprint_failure 'fingerprint mismatch: SYSTEM_IDENTIFIER'
write_fingerprint epoca_ct532 7524217734000000001 10.20.30.41 55432 "$ORGANIZATION_ID"
assert_staging_fingerprint_failure 'fingerprint mismatch: SERVER_ADDRESS'
write_fingerprint epoca_ct532 7524217734000000001 10.20.30.40 55433 "$ORGANIZATION_ID"
assert_staging_fingerprint_failure 'fingerprint mismatch: SERVER_PORT'

unset TEST_DB_URL
ACTIVE_RUNNER="$BUNDLE_DIR/run.sh"

# Standalone verification owns the seed advisory key across its committed target-materialization
# boundary and its locked repeatable-read assertion transaction, then treats a failed explicit unlock as an error.
VERIFY_SQL=$(cat "$BUNDLE_DIR/verify.sql")
assert_contains "$VERIFY_SQL" \
  "pg_catalog.pg_advisory_lock(" \
  'standalone verify must acquire a session advisory lock'
assert_contains "$VERIFY_SQL" \
  'BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ;' \
  'standalone verify assertions must use one repeatable-read snapshot'
assert_contains "$VERIFY_SQL" \
  'LOCK TABLE public.project IN EXCLUSIVE MODE;' \
  'standalone verify must serialize the exact cleanup target assertion against project writers'
assert_contains "$VERIFY_SQL" \
  "pg_catalog.pg_advisory_unlock(" \
  'standalone verify must explicitly release its session advisory lock'
assert_contains "$VERIFY_SQL" \
  'IF lock_released IS DISTINCT FROM true THEN' \
  'standalone verify must reject a failed explicit advisory unlock'

# The aggregate dispatcher keeps the two integration modes independent.
DISPATCH_ROOT="$TMP_DIR/dispatch-repo"
DISPATCH_TEST_DIR="$DISPATCH_ROOT/scripts/demo-seed/tests"
DISPATCH_LOG="$TMP_DIR/dispatch.log"
mkdir -p "$DISPATCH_TEST_DIR"
cp "$TEST_DIR/run.sh" "$DISPATCH_TEST_DIR/run.sh"
cat >"$DISPATCH_TEST_DIR/docs-ci-contract-test.sh" <<'EOF'
printf 'docs-ci\n' >>"$DISPATCH_LOG"
EOF
cat >"$DISPATCH_TEST_DIR/capture-staging-fingerprint-contract-test.sh" <<'EOF'
printf 'capture-fingerprint\n' >>"$DISPATCH_LOG"
EOF
cat >"$DISPATCH_TEST_DIR/runner-contract-test.sh" <<'EOF'
printf 'runner\n' >>"$DISPATCH_LOG"
EOF
cat >"$DISPATCH_TEST_DIR/catalog-contract-test.sh" <<'EOF'
printf 'catalog\n' >>"$DISPATCH_LOG"
EOF
cat >"$DISPATCH_TEST_DIR/integration-test.sh" <<'EOF'
printf 'integration:%s\n' "$*" >>"$DISPATCH_LOG"
EOF

: >"$DISPATCH_LOG"
DISPATCH_LOG="$DISPATCH_LOG" bash "$DISPATCH_TEST_DIR/run.sh"
assert_eq $'docs-ci\ncapture-fingerprint\nrunner\ncatalog' "$(cat "$DISPATCH_LOG")" 'default dispatch order'

: >"$DISPATCH_LOG"
DISPATCH_LOG="$DISPATCH_LOG" bash "$DISPATCH_TEST_DIR/run.sh" --integration
assert_eq $'docs-ci\ncapture-fingerprint\nrunner\ncatalog\nintegration:' "$(cat "$DISPATCH_LOG")" 'integration dispatch order'

: >"$DISPATCH_LOG"
DISPATCH_LOG="$DISPATCH_LOG" bash "$DISPATCH_TEST_DIR/run.sh" --integration-sql
assert_eq $'docs-ci\ncapture-fingerprint\nrunner\nintegration:--integration-sql' "$(cat "$DISPATCH_LOG")" \
  'integration SQL dispatch order'

pass 'runner contract'
