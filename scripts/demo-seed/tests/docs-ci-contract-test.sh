#!/usr/bin/env bash

set -euo pipefail

TEST_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
REPO_ROOT=$(CDPATH= cd -- "$TEST_DIR/../../.." && pwd -P)
RUNBOOK="$REPO_ROOT/scripts/demo-seed/README.md"
ROOT_README="$REPO_ROOT/README.md"
CI_CONFIG="$REPO_ROOT/.gitlab-ci.yml"
INTEGRATION_TEST="$REPO_ROOT/scripts/demo-seed/tests/integration-test.sh"
STAGING_FINGERPRINT_CI="$REPO_ROOT/scripts/demo-seed/capture-staging-fingerprint-ci.sh"

fail() {
  printf 'FAIL: %s\n' "$*" >&2
  exit 1
}

assert_contains() {
  haystack=$1
  needle=$2
  label=$3
  case "$haystack" in
    *"$needle"*) ;;
    *) fail "$label" ;;
  esac
}

assert_not_contains() {
  haystack=$1
  needle=$2
  label=$3
  case "$haystack" in
    *"$needle"*) fail "$label" ;;
  esac
}

assert_count() {
  expected=$1
  actual=$2
  label=$3
  [ "$actual" -eq "$expected" ] || fail "$label (expected $expected, got $actual)"
}

[ -f "$RUNBOOK" ] || fail 'missing scripts/demo-seed/README.md runbook'

runbook=$(cat "$RUNBOOK")
integration_test=$(cat "$INTEGRATION_TEST")
assert_contains "$integration_test" 'psql_case "$database" --single-transaction --file -' \
  'integration harness must apply every Flyway migration in its own transaction'
assert_contains "$runbook" 'psql 13+' 'runbook must require psql 13+'
assert_contains "$runbook" 'Flyway V21' 'runbook must require the full Flyway V21 schema'
assert_contains "$runbook" "export DB_URL='jdbc:postgresql://127.0.0.1:5432/epoca'" 'runbook must show the approved local JDBC URL'
assert_contains "$runbook" "export DB_USER='postgres'" 'runbook must show the local DB user export'
assert_contains "$runbook" "export DB_PASSWORD='postgres'" 'runbook must show the local DB password export'
assert_contains "$runbook" 'scripts/demo-seed/run.sh local --dry-run' 'runbook must show local dry-run'
assert_contains "$runbook" 'scripts/demo-seed/run.sh local --apply --confirm-local-database epoca' 'runbook must show confirmed local apply'
assert_contains "$runbook" 'scripts/demo-seed/run.sh verify local' 'runbook must show standalone local verify'

warning_line=$(grep -n -F 'WARNING: Do not run local apply through a tunnel or proxy.' "$RUNBOOK" | cut -d: -f1 || true)
apply_line=$(grep -n -F 'scripts/demo-seed/run.sh local --apply --confirm-local-database epoca' "$RUNBOOK" | cut -d: -f1 || true)
[ -n "$warning_line" ] || fail 'local apply must have an explicit tunnel/proxy warning'
[ -n "$apply_line" ] || fail 'local apply command is missing'
[ "$warning_line" -lt "$apply_line" ] || fail 'tunnel/proxy warning must precede local apply'
[ $((apply_line - warning_line)) -le 3 ] || fail 'tunnel/proxy warning must be directly above local apply'

for required_text in \
  'exactly 30 active people' \
  '2 Owner, 2 Manager, 22 Employee, and 4 External' \
  'exactly two active superadmins in total' \
  'same shared demo password' \
  'Existing nonseed accounts, passwords, roles, superadmin flags, and sessions are never changed' \
  'Project Owner is the Project Manager' \
  '10 clients' \
  '16 projects' \
  '1,200 active Worklogs' \
  '100 active Notes' \
  '60 archived historical Worklogs' \
  '12-week rolling window' \
  'reports, calendar, pivot, and export'; do
  assert_contains "$runbook" "$required_text" "runbook is missing: $required_text"
done

for required_stage in \
  '1. Capture and review the fingerprint read-only' \
  '2. Confirm a real login' \
  '3. Run the staging dry-run' \
  '4. Review the projected counts' \
  '5. Apply the reviewed plan' \
  '6. Run standalone verification' \
  '7. Smoke-test the API and UI' \
  '8. Record Jira evidence'; do
  assert_contains "$runbook" "$required_stage" "staging sequence is missing: $required_stage"
done

assert_count 3 "$(grep -F -c -- '--confirm-organization iteratorstesting' "$RUNBOOK")" 'all three staging commands must confirm iteratorstesting'
assert_count 3 "$(grep -F -c -- '--demo-viewer-email "$DEMO_VIEWER_EMAIL"' "$RUNBOOK")" 'all three staging commands must use DEMO_VIEWER_EMAIL'
assert_contains "$runbook" 'fail closed' 'runbook must explain fail-closed staging behavior'
assert_contains "$runbook" 'Do not take over or manually rewrite conflicting rows' 'runbook must forbid manual takeover'
assert_contains "$runbook" 'Production is not a supported target' 'runbook must explicitly exclude production'

staging_section=$(awk '/^## Staging runbook$/{capture=1; next} /^## /{if (capture) exit} capture' "$RUNBOOK")
assert_not_contains "$staging_section" 'jdbc:postgresql://' 'staging runbook must not contain a DSN'
assert_not_contains "$staging_section" 'DB_USER=' 'staging runbook must not contain an operator user'
assert_not_contains "$staging_section" 'DB_PASSWORD=' 'staging runbook must not contain an operator password'
assert_not_contains "$staging_section" '@' 'staging runbook must not contain an operator email'

root_database_section=$(awk '/^## Database migrations$/{capture=1; next} /^## /{if (capture) exit} capture' "$ROOT_README")
assert_contains "$root_database_section" '### Demo data seed' 'root Database migrations section must contain Demo data seed'
assert_contains "$root_database_section" '[`scripts/demo-seed/README.md`](scripts/demo-seed/README.md)' 'root README must link to the demo seed runbook'
assert_contains "$root_database_section" 'not a Flyway migration' 'root README must distinguish the seed from Flyway'
assert_contains "$root_database_section" 'not run at application start or during deployment' 'root README must exclude start/deploy automation'

ci_job=$(awk '/^demo_seed_contracts:/{capture=1} capture && NR > 1 && /^[^[:space:]#][^:]*:/{if ($0 !~ /^demo_seed_contracts:/) exit} capture' "$CI_CONFIG")
[ -n "$ci_job" ] || fail 'missing demo_seed_contracts CI job'
for required_ci_text in \
  'image: postgres:13.4' \
  'stage: test' \
  '    - privileged' \
  '    - bydle-server' \
  '    PGHOST: postgres' \
  '    PGPORT: "5432"' \
  '    PGUSER: postgres' \
  '    PGPASSWORD: postgres' \
  '    PGDATABASE: postgres' \
  '  retry:' \
  '    max: 1' \
  '    when: script_failure' \
  '  rules:' \
  "    - if: '\$CI_PIPELINE_SOURCE == \"schedule\"'" \
  "    - if: '\$CI_PIPELINE_SOURCE == \"push\" && \$CI_COMMIT_BRANCH'" \
  '      changes:' \
  '        - .gitlab-ci.yml' \
  '        - README.md' \
  '        - scripts/demo-seed/**/*' \
  '        - src/main/resources/db/migration/**/*' \
  '    - when: never' \
  '    - bash scripts/demo-seed/tests/run.sh --integration-sql'; do
  assert_contains "$ci_job" "$required_ci_text" "demo_seed_contracts is missing: $required_ci_text"
done
assert_count 1 "$(printf '%s\n' "$ci_job" | grep -F -c -- '    - bash scripts/demo-seed/tests/run.sh --integration-sql')" 'demo seed CI job must run the single approved script'
assert_not_contains "$ci_job" 'staging' 'demo seed CI job must never target staging'
assert_not_contains "$ci_job" 'fingerprint' 'demo seed CI job must not use a staging fingerprint'
assert_not_contains "$ci_job" 'DB_URL' 'demo seed CI job must not use external JDBC configuration'

[ -f "$STAGING_FINGERPRINT_CI" ] || fail 'missing staging fingerprint CI wrapper'
ci_config=$(cat "$CI_CONFIG")
assert_not_contains "$ci_config" 'epoca-staging' \
  'backend CI must not target the project-scoped staging runner'
assert_not_contains "$ci_config" 'capture_staging_demo_seed_fingerprint:' \
  'backend CI must leave staging execution to epoca-devops'

staging_fingerprint_ci=$(cat "$STAGING_FINGERPRINT_CI")
for required_wrapper_text in \
  'set +x' \
  'APP_DIR=/app/epoca' \
  'ENV_FILE="$APP_DIR/.env.sh"' \
  'docker-compose ps -q db' \
  'docker inspect' \
  'docker cp' \
  'docker exec' \
  'POSTGRES_DB' \
  'DB_URL="jdbc:postgresql://127.0.0.1:5432/$POSTGRES_DB"' \
  'capture-staging-fingerprint.sh' \
  '--confirm-organization iteratorstesting'; do
  assert_contains "$staging_fingerprint_ci" "$required_wrapper_text" \
    "staging fingerprint CI wrapper is missing: $required_wrapper_text"
done
for forbidden_wrapper_text in \
  'cat /app/epoca/.env.sh' \
  'echo $DB_' \
  'printenv' \
  '--env DB_PASSWORD' \
  'run.sh staging' \
  '--apply'; do
  assert_not_contains "$staging_fingerprint_ci" "$forbidden_wrapper_text" \
    "staging fingerprint CI wrapper contains forbidden text: $forbidden_wrapper_text"
done

safety_tmp=$(mktemp -d "/tmp/epoca_ct532_ci_contract.XXXXXX")
trap 'rm -rf "$safety_tmp"' EXIT HUP INT TERM
safety_bin="$safety_tmp/bin"
safety_log="$safety_tmp/database-tools.log"
mkdir -p "$safety_bin"
cat >"$safety_bin/psql" <<'EOF'
#!/usr/bin/env bash
printf '%s|%s\n' "${0##*/}" "$*" >>"$DEMO_SEED_DB_TOOL_LOG"
exit 99
EOF
chmod +x "$safety_bin/psql"
cp "$safety_bin/psql" "$safety_bin/createdb"
cp "$safety_bin/psql" "$safety_bin/dropdb"

for hostile_selector in PGHOSTADDR PGSERVICE PGSERVICEFILE PGPASSFILE PGOPTIONS; do
  : >"$safety_log"
  set +e
  hostile_output=$(env \
    -u PGHOSTADDR -u PGSERVICE -u PGSERVICEFILE -u PGPASSFILE -u PGOPTIONS \
    PGHOST=postgres PGPORT=5432 PGUSER=postgres PGPASSWORD=postgres PGDATABASE=postgres \
    "$hostile_selector=hostile-contract-value" \
    DEMO_SEED_DB_TOOL_LOG="$safety_log" PATH="$safety_bin:$PATH" \
    bash "$INTEGRATION_TEST" --integration-sql --case empty-local 2>&1)
  hostile_status=$?
  set -e
  [ "$hostile_status" -ne 0 ] || fail "$hostile_selector unexpectedly passed the integration SQL environment guard"
  assert_contains "$hostile_output" "$hostile_selector" "$hostile_selector rejection must name the hostile selector"
  [ ! -s "$safety_log" ] || fail "$hostile_selector reached a database tool before rejection"
done

for invalid_contract in \
  'PGHOST=not-the-ci-service' \
  'PGPORT=6543' \
  'PGUSER=not-postgres' \
  'PGPASSWORD=not-the-ci-password' \
  'PGDATABASE=not-the-maintenance-database'; do
  invalid_variable=${invalid_contract%%=*}
  : >"$safety_log"
  set +e
  invalid_output=$(env \
    -u PGHOSTADDR -u PGSERVICE -u PGSERVICEFILE -u PGPASSFILE -u PGOPTIONS \
    PGHOST=postgres PGPORT=5432 PGUSER=postgres PGPASSWORD=postgres PGDATABASE=postgres \
    "$invalid_contract" \
    DEMO_SEED_DB_TOOL_LOG="$safety_log" PATH="$safety_bin:$PATH" \
    bash "$INTEGRATION_TEST" --integration-sql --case empty-local 2>&1)
  invalid_status=$?
  set -e
  [ "$invalid_status" -ne 0 ] || fail "$invalid_variable mismatch unexpectedly passed the integration SQL environment guard"
  assert_contains "$invalid_output" "$invalid_variable" "$invalid_variable rejection must name the mismatched contract variable"
  [ ! -s "$safety_log" ] || fail "$invalid_variable mismatch reached a database tool before rejection"
done

: >"$safety_log"
set +e
approved_output=$(env \
  -u PGHOSTADDR -u PGSERVICE -u PGSERVICEFILE -u PGPASSFILE -u PGOPTIONS \
  PGHOST=postgres PGPORT=5432 PGUSER=postgres PGPASSWORD=postgres PGDATABASE=postgres \
  DEMO_SEED_DB_TOOL_LOG="$safety_log" PATH="$safety_bin:$PATH" \
  bash "$INTEGRATION_TEST" --integration-sql --case empty-local 2>&1)
approved_status=$?
set -e
[ "$approved_status" -eq 99 ] || fail "approved integration SQL fixture returned unexpected status $approved_status: $approved_output"
approved_first_command=$(sed -n '1p' "$safety_log")
assert_contains "$approved_first_command" 'createdb|' 'approved integration SQL must begin with disposable database creation'
for canonical_option in \
  '--host=postgres' \
  '--port=5432' \
  '--username=postgres' \
  '--maintenance-db=postgres'; do
  assert_contains "$approved_first_command" "$canonical_option" "approved integration SQL command is missing $canonical_option"
done

migration_fixture="$safety_tmp/migration-fixture"
fixture_test_dir="$migration_fixture/scripts/demo-seed/tests"
fixture_migration_dir="$migration_fixture/src/main/resources/db/migration"
mkdir -p "$fixture_test_dir" "$fixture_migration_dir"
cp "$INTEGRATION_TEST" "$fixture_test_dir/integration-test.sh"
: >"$fixture_migration_dir/V1__supported.sql"
: >"$fixture_migration_dir/R__supported.sql"
: >"$fixture_migration_dir/V22_1__unsupported.sql"
: >"$safety_log"
set +e
migration_output=$(env \
  -u PGHOSTADDR -u PGSERVICE -u PGSERVICEFILE -u PGPASSFILE -u PGOPTIONS \
  PGHOST=postgres PGPORT=5432 PGUSER=postgres PGPASSWORD=postgres PGDATABASE=postgres \
  DEMO_SEED_DB_TOOL_LOG="$safety_log" PATH="$safety_bin:$PATH" \
  bash "$fixture_test_dir/integration-test.sh" --integration-sql --case empty-local 2>&1)
migration_status=$?
set -e
[ "$migration_status" -ne 0 ] || fail 'unsupported Flyway migration filename unexpectedly passed validation'
assert_contains "$migration_output" 'unsupported Flyway migration filename: V22_1__unsupported.sql' \
  'unsupported Flyway migration filename must fail with the exact filename'
[ ! -s "$safety_log" ] || fail 'unsupported Flyway migration filename reached a database tool before rejection'

printf '%s\n' 'PASS: demo seed documentation and CI contracts'
