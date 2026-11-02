#!/usr/bin/env bash
set -euo pipefail

image=${1:?candidate migration image is required}
script_directory=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
suffix="$(date +%s)-$$-${RANDOM}"
network="epoca-schema-$suffix"
postgres="epoca-schema-postgres-$suffix"
password="schema-synthetic-$suffix"
work=$(mktemp -d)
created_network=false
created_postgres=false
cleanup() {
  if [[ "$created_postgres" == true ]]; then docker rm -f "$postgres" >/dev/null; fi
  if [[ "$created_network" == true ]]; then docker network rm "$network" >/dev/null; fi
  rm -rf -- "$work"
}
trap cleanup EXIT
docker network create "$network" >/dev/null
created_network=true
docker run -d --name "$postgres" --network "$network" \
  --label pl.iterators.epoca.test=migration-schema \
  -e POSTGRES_PASSWORD="$password" postgres:13.4 >/dev/null
created_postgres=true

sql() {
  local database=$1
  shift
  docker exec -i --env PGPASSWORD="$password" --env PGCONNECT_TIMEOUT=2 "$postgres" \
    psql --host 127.0.0.1 --no-password -X -v ON_ERROR_STOP=1 -At -U postgres -d "$database" "$@"
}

# The image's temporary initialization server accepts socket connections before
# shutting down. Wait for authenticated SQL over TCP on the final server.
ready=false
for _ in $(seq 1 60); do
  if result=$(sql postgres -c 'select 1' 2>/dev/null) && [[ "$result" == 1 ]]; then ready=true; break; fi
  sleep 1
done
[[ "$ready" == true ]] || { echo 'test PostgreSQL did not start' >&2; exit 1; }

run() {
  local database=$1 schema=$2 command=$3
  shift 3
  docker run --rm --network "$network" \
    -e DB_URL="jdbc:postgresql://$postgres:5432/$database?currentSchema=$schema" \
    -e DB_USER=postgres -e DB_PASSWORD="$password" -e FLYWAY_CMD="$command" \
    "$@" "$image"
}
assert_sql() {
  local database=$1 statement=$2 expected=$3
  local actual
  actual=$(sql "$database" -c "$statement")
  [[ "$actual" == "$expected" ]] || {
    printf 'Assertion failed: %s; expected %s, got %s\n' "$statement" "$expected" "$actual" >&2
    exit 1
  }
}

sql postgres -c 'CREATE DATABASE schema_fresh' >/dev/null
sql schema_fresh <<'SQL' >/dev/null
CREATE ROLE schema_restricted LOGIN PASSWORD 'test-only';
GRANT CREATE ON DATABASE schema_fresh TO schema_restricted;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
SQL
# Exercise the real image CMD, not a test-only direct Flyway invocation.
run schema_fresh application migrate -e DB_USER=schema_restricted -e DB_PASSWORD=test-only
run schema_fresh application validate -e DB_USER=schema_restricted -e DB_PASSWORD=test-only
run schema_fresh application migrate -e DB_USER=schema_restricted -e DB_PASSWORD=test-only
assert_sql schema_fresh "SELECT count(*) FROM application.flyway_schema_history WHERE type='SQL_BASELINE' AND version='25'" 1
assert_sql schema_fresh "SELECT count(*) FROM pg_tables WHERE schemaname='public'" 0
assert_sql schema_fresh "SELECT count(*) FROM application.oauth_client" 0
echo 'PASS: packaged baseline and restricted fresh nonpublic install'

sql postgres -c 'CREATE DATABASE schema_fresh_public' >/dev/null
run schema_fresh_public public migrate -e DB_MIGRATION_BOOTSTRAP=true
run schema_fresh_public public validate -e DB_MIGRATION_BOOTSTRAP=true
run schema_fresh_public public migrate -e DB_MIGRATION_BOOTSTRAP=true
assert_sql schema_fresh_public "SELECT count(*) FROM public.flyway_schema_history WHERE type='SQL_BASELINE' AND version='25'" 1
echo 'PASS: explicit public baseline remains valid on subsequent runs'

for schema in public application; do
  database="schema_upgrade_$schema"
  sql postgres -c "CREATE DATABASE $database" >/dev/null
  # Reproduce the actual historical source SQL through V17 using the pinned
  # engine shipped with the candidate, before selecting the new upgrade path.
  docker run --rm --network "$network" \
    -e DB_URL="jdbc:postgresql://$postgres:5432/$database?currentSchema=$schema" \
    -e DB_USER=postgres -e DB_PASSWORD="$password" \
    --entrypoint /flyway-10.20.0/flyway "$image" \
    "-url=jdbc:postgresql://$postgres:5432/$database?currentSchema=$schema" \
    -user=postgres "-password=$password" "-schemas=$schema" "-defaultSchema=$schema" \
    -locations=filesystem:/sql -target=17 migrate
  sql "$database" -v schema="$schema" <"$script_directory/migration-upgrade-seed.sql" >/dev/null
  preserved_columns='id,organization_id,user_id,project_id,date,duration,description,created_at,modified_at,client_id,state'
  sql "$database" -c "SELECT $preserved_columns FROM $schema.work_unit ORDER BY id" >"$work/$schema-data-before"
  sql "$database" -c "SELECT version,checksum FROM $schema.flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank" >"$work/$schema-before"
  run "$database" "$schema" migrate
  run "$database" "$schema" validate
  run "$database" "$schema" migrate
  sql "$database" -c "SELECT version,checksum FROM $schema.flyway_schema_history WHERE version::integer <= 17 ORDER BY installed_rank" >"$work/$schema-after"
  diff -u "$work/$schema-before" "$work/$schema-after"
  sql "$database" -c "SELECT $preserved_columns FROM $schema.work_unit ORDER BY id" >"$work/$schema-data-after"
  diff -u "$work/$schema-data-before" "$work/$schema-data-after"
  assert_sql "$database" "SELECT first_name||':'||last_name FROM $schema.\"user\" WHERE id='00000000-0000-0000-0000-000000000099'" Existing:Person
  assert_sql "$database" "SELECT max(version::integer) FROM $schema.flyway_schema_history WHERE success" "$(sh "$script_directory/latest-schema-version.sh")"
  assert_sql "$database" "SELECT count(*) FROM $schema.flyway_schema_history WHERE type='SQL_BASELINE'" 0
  assert_sql "$database" "SELECT entry_type FROM $schema.work_unit WHERE duration=INTERVAL '0 seconds'" Note
  assert_sql "$database" "SELECT entry_type FROM $schema.work_unit WHERE duration=INTERVAL '1 hour'" Worklog
  sql "$database" -v schema="$schema" <"$script_directory/migration-upgrade-isolation.sql" >"$work/$schema-isolation"
  [[ $(grep -c '^t$' "$work/$schema-isolation") -eq 2 ]] || { cat "$work/$schema-isolation"; exit 1; }
  assert_sql "$database" "SELECT count(*) FROM pg_constraint k JOIN pg_class t ON t.oid=k.conrelid JOIN pg_namespace n ON n.oid=t.relnamespace JOIN pg_class r ON r.oid=k.confrelid JOIN pg_namespace rn ON rn.oid=r.relnamespace WHERE k.contype='f' AND n.nspname='$schema' AND rn.nspname<>'$schema'" 0
  if [[ "$schema" != public ]]; then
    assert_sql "$database" "SELECT count(*) FROM pg_tables WHERE schemaname='public'" 0
  fi
  echo "PASS: $schema V17 upgrade retains data and checksums"
done

# An unknown existing nonpublic V18 checksum must never be silently repaired.
legacy_checksum=$(sql schema_upgrade_public -c "SELECT checksum FROM public.flyway_schema_history WHERE version='18'")
sql schema_upgrade_application -c "UPDATE application.flyway_schema_history SET checksum=$legacy_checksum WHERE version='18'" >/dev/null
sql schema_upgrade_application -c 'TABLE application.flyway_schema_history' >"$work/incompatible-before"
if run schema_upgrade_application application migrate -e DB_CONNECTION_CHECK=true >"$work/incompatible.log" 2>&1; then
  echo 'Incompatible legacy history was accepted' >&2; exit 1
fi
grep -qi 'checksum mismatch' "$work/incompatible.log"
sql schema_upgrade_application -c 'TABLE application.flyway_schema_history' >"$work/incompatible-after"
diff -u "$work/incompatible-before" "$work/incompatible-after"
assert_sql schema_upgrade_application 'SELECT count(*) FROM application.work_unit' 2
echo 'PASS: incompatible history fails validation without repair or connection-check loop'

# The existing deployments may use schema_version instead of Flyway's default.
sql postgres -c 'CREATE DATABASE schema_history_name' >/dev/null
run schema_history_name public migrate -e FLYWAY_TABLE=schema_version
run schema_history_name public validate -e FLYWAY_TABLE=schema_version
assert_sql schema_history_name "SELECT count(*) FROM public.schema_version WHERE version='25' AND success" 1
assert_sql schema_history_name "SELECT to_regclass('public.flyway_schema_history') IS NULL" t
echo 'PASS: explicit history-table selection'

if run schema_fresh application migrate -e FLYWAY_DEFAULT_SCHEMA=public >"$work/conflict.log" 2>&1; then
  echo 'Conflicting schema settings were accepted' >&2; exit 1
fi
grep -qi 'schema' "$work/conflict.log"
assert_sql schema_fresh "SELECT count(*) FROM pg_tables WHERE schemaname='public'" 0
echo 'PASS: conflicting schema selection fails before migration'

for query in 'currentSchema=application&currentSchema=other' 'current%53chema=application' 'currentSchema=%61pplication' 'currentSchema=public,application'; do
  if run schema_fresh application migrate \
      -e DB_URL="jdbc:postgresql://$postgres:5432/schema_fresh?$query" >"$work/invalid.log" 2>&1; then
    echo 'Ambiguous schema configuration was accepted' >&2; exit 1
  fi
  grep -q 'Migration configuration error' "$work/invalid.log"
done
echo 'PASS: ambiguous JDBC schema configuration rejected before connection'
