#!/usr/bin/env bash
set -euo pipefail

fail() { printf 'Migration configuration error: %s\n' "$1" >&2; exit 2; }
[[ "${DB_URL:-}" == jdbc:postgresql:* ]] || fail 'DB_URL must be a PostgreSQL JDBC URL'
: "${DB_USER:?DB_USER is required}" "${DB_PASSWORD:?DB_PASSWORD is required}"

schema=public
schema_seen=false
if [[ "$DB_URL" == *\?* ]]; then
  IFS='&' read -r -a parameters <<<"${DB_URL#*\?}"
  for parameter in "${parameters[@]}"; do
    key=${parameter%%=*}
    [[ "$key" != *%* ]] || fail 'JDBC parameter names must not be percent encoded'
    if [[ "$key" == currentSchema ]]; then
      [[ "$schema_seen" == false ]] || fail 'JDBC currentSchema must occur at most once'
      schema_seen=true
      [[ "$parameter" == *=* ]] || fail 'JDBC currentSchema requires a value'
      schema=${parameter#*=}
    fi
  done
fi
[[ "$schema" =~ ^[a-z][a-z0-9_]{0,62}$ ]] || fail 'JDBC currentSchema must be one lowercase schema identifier'
case "$schema" in pg_*|information_schema|quartz) fail 'Reserved application schema';; esac
[[ -z "${FLYWAY_DEFAULT_SCHEMA:-}" || "$FLYWAY_DEFAULT_SCHEMA" == "$schema" ]] || fail 'FLYWAY_DEFAULT_SCHEMA conflicts with JDBC currentSchema'
[[ -z "${FLYWAY_SCHEMAS:-}" || "$FLYWAY_SCHEMAS" == "$schema" || "$FLYWAY_SCHEMAS" == "$schema,quartz" ]] || fail 'FLYWAY_SCHEMAS conflicts with JDBC currentSchema'
[[ -z "${FLYWAY_URL:-}" || "$FLYWAY_URL" == "$DB_URL" ]] || fail 'FLYWAY_URL conflicts with DB_URL'

location=/sql
bootstrap=${DB_MIGRATION_BOOTSTRAP:-false}
if [[ "$schema" != public ]]; then
  location=/sql-configured
  bootstrap=${DB_MIGRATION_BOOTSTRAP:-true}
fi
case "$bootstrap" in true|false) ;; *) fail 'DB_MIGRATION_BOOTSTRAP must be true or false';; esac
locations="filesystem:$location"
if [[ "$bootstrap" == true ]]; then locations+=,filesystem:/sql-bootstrap; fi
[[ -z "${FLYWAY_LOCATIONS:-}" || "$FLYWAY_LOCATIONS" == "$locations" ]] || fail 'FLYWAY_LOCATIONS conflicts with the selected schema lineage'

read -r -a command <<<"${FLYWAY_CMD:-migrate}"
for argument in "${command[@]}"; do
  case "$argument" in
    -url=*|-user=*|-password=*|-schemas=*|-defaultSchema=*|-locations=*|-configFiles=*)
      fail 'Connection, schema and locations must be configured through environment variables';;
  esac
done
retry=()
if [[ -n "${DB_CONNECTION_CHECK+x}" ]]; then retry=(-connectRetries=60); fi
exec /flyway-10.20.0/flyway "${command[@]}" \
  "-url=$DB_URL" "-user=$DB_USER" "-password=$DB_PASSWORD" \
  "-defaultSchema=$schema" "-schemas=$schema,quartz" "-locations=$locations" "${retry[@]}"
