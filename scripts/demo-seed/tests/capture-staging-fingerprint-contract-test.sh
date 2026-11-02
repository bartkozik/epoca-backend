#!/usr/bin/env bash

set -euo pipefail

TEST_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
BUNDLE_DIR=$(CDPATH= cd -- "$TEST_DIR/.." && pwd -P)
# shellcheck source=assertions.sh
. "$TEST_DIR/assertions.sh"

TMP_DIR=$(mktemp -d "${TMPDIR:-/tmp}/demo-seed-fingerprint.XXXXXX")
trap 'rm -rf "$TMP_DIR"' EXIT HUP INT TERM

FAKE_BIN="$TMP_DIR/bin"
mkdir -p "$FAKE_BIN"

cat >"$FAKE_BIN/psql" <<'FAKE_PSQL'
#!/usr/bin/env bash
set -euo pipefail

: "${FAKE_ARGS_PATH:?}"
: "${FAKE_STDIN_PATH:?}"
: "${FAKE_EXPECTED_PASSWORD:?}"

if [ "${PGPASSWORD+x}" != x ] || [ "$PGPASSWORD" != "$FAKE_EXPECTED_PASSWORD" ]; then
  printf '%s\n' 'fake psql authentication environment mismatch' >&2
  exit 91
fi

for arg in "$@"; do
  printf '%s\n' "$arg" >>"$FAKE_ARGS_PATH"
done
cat >"$FAKE_STDIN_PATH"

if [ "${FAKE_PSQL_STATUS:-0}" -ne 0 ]; then
  printf '%s\n' 'fake psql read failure' >&2
  exit "$FAKE_PSQL_STATUS"
fi

separator=$(printf '\034')
case "${FAKE_RESULT_KIND:-valid}" in
  valid)
    printf '%s%s%s%s%s%s%s%s%s\n' \
      "${FAKE_DATABASE_NAME:-epoca}" "$separator" \
      "${FAKE_SYSTEM_IDENTIFIER:-7524217734000000001}" "$separator" \
      "${FAKE_SERVER_ADDRESS:-10.20.30.40}" "$separator" \
      "${FAKE_SERVER_PORT:-5432}" "$separator" \
      "${FAKE_ORGANIZATION_ID:-11111111-2222-4333-8444-555555555555}"
    ;;
  empty) ;;
  multiple)
    printf '%s%s%s%s%s%s%s%s%s\n' \
      epoca "$separator" 7524217734000000001 "$separator" \
      10.20.30.40 "$separator" 5432 "$separator" \
      11111111-2222-4333-8444-555555555555
    printf '%s%s%s%s%s%s%s%s%s\n' \
      epoca "$separator" 7524217734000000001 "$separator" \
      10.20.30.40 "$separator" 5432 "$separator" \
      22222222-3333-4444-8555-666666666666
    ;;
esac
FAKE_PSQL
chmod +x "$FAKE_BIN/psql"

SCRIPT="$BUNDLE_DIR/capture-staging-fingerprint.sh"
DB_URL_VALUE='jdbc:postgresql://db.internal:5432/epoca'
DB_USER_VALUE='epoca_user'
DB_PASSWORD_VALUE='do-not-print-me'
ARGS_PATH="$TMP_DIR/psql.args"
STDIN_PATH="$TMP_DIR/psql.stdin"
LAST_OUTPUT=
LAST_STATUS=0

run_capture() {
  : >"$ARGS_PATH"
  : >"$STDIN_PATH"
  set +e
  LAST_OUTPUT=$(
    PATH="$FAKE_BIN:$PATH" \
      FAKE_ARGS_PATH="$ARGS_PATH" \
      FAKE_STDIN_PATH="$STDIN_PATH" \
      FAKE_EXPECTED_PASSWORD="$DB_PASSWORD_VALUE" \
      FAKE_PSQL_STATUS="${FAKE_PSQL_STATUS:-0}" \
      FAKE_RESULT_KIND="${FAKE_RESULT_KIND:-valid}" \
      FAKE_DATABASE_NAME="${FAKE_DATABASE_NAME:-epoca}" \
      FAKE_SYSTEM_IDENTIFIER="${FAKE_SYSTEM_IDENTIFIER:-7524217734000000001}" \
      FAKE_SERVER_ADDRESS="${FAKE_SERVER_ADDRESS:-10.20.30.40}" \
      FAKE_SERVER_PORT="${FAKE_SERVER_PORT:-5432}" \
      FAKE_ORGANIZATION_ID="${FAKE_ORGANIZATION_ID:-11111111-2222-4333-8444-555555555555}" \
      DB_URL="${TEST_DB_URL:-$DB_URL_VALUE}" \
      DB_USER="$DB_USER_VALUE" \
      DB_PASSWORD="$DB_PASSWORD_VALUE" \
      bash "$SCRIPT" "$@" 2>&1
  )
  LAST_STATUS=$?
  set -e

  assert_not_contains "$LAST_OUTPUT" "$DB_PASSWORD_VALUE" 'password must not appear in output'
  assert_not_contains "$(cat "$ARGS_PATH")" "$DB_PASSWORD_VALUE" 'password must not appear in psql argv'
  assert_not_contains "$LAST_OUTPUT" "${TEST_DB_URL:-$DB_URL_VALUE}" 'JDBC URL must not appear in output'
  assert_not_contains "$(cat "$ARGS_PATH")" "${TEST_DB_URL:-$DB_URL_VALUE}" 'JDBC URL must not appear in psql argv'
}

run_capture --confirm-organization iteratorstesting
assert_status 0 "$LAST_STATUS" 'valid fingerprint capture should succeed'
expected_output=$(printf '%s\n' \
  'DATABASE_NAME=epoca' \
  'SYSTEM_IDENTIFIER=7524217734000000001' \
  'SERVER_ADDRESS=10.20.30.40' \
  'SERVER_PORT=5432' \
  'ORGANIZATION_ID=11111111-2222-4333-8444-555555555555')
assert_eq "$expected_output" "$LAST_OUTPUT" 'fingerprint output must be copy-ready and exact'

expected_args=$(printf '%s\n' \
  '-X' \
  '--set' 'ON_ERROR_STOP=1' \
  '--set' 'organization_slug=iteratorstesting' \
  '--no-align' \
  '--tuples-only' \
  '--quiet' \
  '--field-separator' "$(printf '\034')" \
  '--username' 'epoca_user' \
  '--dbname' 'postgresql://db.internal:5432/epoca' \
  '--file=-')
assert_eq "$expected_args" "$(cat "$ARGS_PATH")" 'fingerprint psql argv must be exact'

captured_sql=$(cat "$STDIN_PATH")
assert_contains "$captured_sql" 'BEGIN TRANSACTION READ ONLY;' 'capture must open a read-only transaction'
assert_contains "$captured_sql" 'ROLLBACK;' 'capture must always roll back'
assert_contains "$captured_sql" "slug = :'organization_slug'" 'organization slug must use psql literal quoting'
assert_contains "$captured_sql" 'archived_at IS NULL' 'capture must require an active organization'
for forbidden_sql in INSERT UPDATE DELETE MERGE TRUNCATE ALTER DROP CREATE GRANT REVOKE; do
  assert_not_contains "$captured_sql" "$forbidden_sql" "capture SQL must not contain $forbidden_sql"
done

run_capture --confirm-organization wrong
assert_status 2 "$LAST_STATUS" 'wrong organization confirmation must fail'
assert_contains "$LAST_OUTPUT" 'must equal iteratorstesting' 'wrong organization diagnostic'
[ ! -s "$ARGS_PATH" ] || fail 'wrong organization confirmation must stop before psql'

run_capture
assert_status 2 "$LAST_STATUS" 'missing organization confirmation must fail'
[ ! -s "$ARGS_PATH" ] || fail 'missing organization confirmation must stop before psql'

TEST_DB_URL='jdbc:postgresql://user-992b6f927b91@example.test:5432/epoca'
run_capture --confirm-organization iteratorstesting
unset TEST_DB_URL
assert_status 2 "$LAST_STATUS" 'unsafe JDBC URL must fail'
assert_contains "$LAST_OUTPUT" 'Malformed DB_URL' 'unsafe JDBC URL diagnostic'
[ ! -s "$ARGS_PATH" ] || fail 'unsafe JDBC URL must stop before psql'

FAKE_RESULT_KIND=empty
run_capture --confirm-organization iteratorstesting
unset FAKE_RESULT_KIND
assert_status 2 "$LAST_STATUS" 'missing organization row must fail'
assert_contains "$LAST_OUTPUT" 'exactly one active organization' 'missing organization diagnostic'

FAKE_RESULT_KIND=multiple
run_capture --confirm-organization iteratorstesting
unset FAKE_RESULT_KIND
assert_status 2 "$LAST_STATUS" 'multiple fingerprint rows must fail'
assert_contains "$LAST_OUTPUT" 'exactly one active organization' 'multiple row diagnostic'

FAKE_DATABASE_NAME=other_database
run_capture --confirm-organization iteratorstesting
unset FAKE_DATABASE_NAME
assert_status 2 "$LAST_STATUS" 'database identity mismatch must fail'
assert_contains "$LAST_OUTPUT" 'does not match DB_URL' 'database mismatch diagnostic'

FAKE_ORGANIZATION_ID=not-a-uuid
run_capture --confirm-organization iteratorstesting
unset FAKE_ORGANIZATION_ID
assert_status 2 "$LAST_STATUS" 'malformed organization id must fail'
assert_contains "$LAST_OUTPUT" 'invalid organization identifier' 'organization id diagnostic'

FAKE_PSQL_STATUS=74
run_capture --confirm-organization iteratorstesting
unset FAKE_PSQL_STATUS
assert_status 74 "$LAST_STATUS" 'psql failure status must propagate'
assert_contains "$LAST_OUTPUT" 'Fingerprint capture failed' 'psql failure diagnostic'

pass 'read-only staging fingerprint capture contract'
