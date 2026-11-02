#!/usr/bin/env bash

set -euo pipefail

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

DATABASE_URL=
OUTPUT=
SEEN_URL=false
SEEN_OUTPUT=false

while [ "$#" -gt 0 ]; do
  case "$1" in
    --url)
      require_option_value "$@"
      [ "$SEEN_URL" = false ] || die 'Duplicate option: --url'
      SEEN_URL=true
      DATABASE_URL=$2
      shift 2
      ;;
    --output)
      require_option_value "$@"
      [ "$SEEN_OUTPUT" = false ] || die 'Duplicate option: --output'
      SEEN_OUTPUT=true
      OUTPUT=$2
      shift 2
      ;;
    *) die "Unknown option: $1" ;;
  esac
done

[ -n "$DATABASE_URL" ] || die '--url is required'
[ -n "$OUTPUT" ] || die '--output is required'
DATABASE_URL_PATTERN='^postgresql://([A-Za-z0-9._~-]+(:[A-Za-z0-9._~-]+)?@)?(localhost|127[.]0[.]0[.]1|\[::1\])(:([0-9]{1,5}))?/([A-Za-z0-9_][A-Za-z0-9_.-]*)$'
[[ "$DATABASE_URL" =~ $DATABASE_URL_PATTERN ]] || \
  die 'Database URL must be an unambiguous loopback URL'
PORT=${BASH_REMATCH[5]:-5432}
[ "$PORT" -ge 1 ] && [ "$PORT" -le 65535 ] || die 'Database URL has an invalid port'

command -v psql >/dev/null 2>&1 || die 'psql is required'
mkdir -p "$(dirname -- "$OUTPUT")"
TEMP_OUTPUT=$(mktemp "${OUTPUT}.tmp.XXXXXX")
trap 'rm -f -- "$TEMP_OUTPUT"' EXIT

read -r -d '' SNAPSHOT_SQL <<'SQL' || true
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
SELECT pg_catalog.format(
  $stack$
SELECT %L,
       count(*)::text,
       coalesce(md5(string_agg(row_json, E'\n' ORDER BY row_json)), md5(''))
FROM (
  SELECT row_to_json(source_row)::text AS row_json
  FROM %I.%I AS source_row
) AS canonical_rows;
$stack$,
  n.nspname || '.' || c.relname,
  n.nspname,
  c.relname
)
FROM pg_catalog.pg_class AS c
JOIN pg_catalog.pg_namespace AS n ON n.oid = c.relnamespace
WHERE c.relkind = 'r'
  AND n.nspname IN ('public', 'quartz')
  AND NOT (n.nspname = 'public' AND c.relname = 'flyway_schema_history')
ORDER BY n.nspname, c.relname
\gexec
COMMIT;
SQL

SNAPSHOT_ROWS=$(env -u PGHOSTADDR -u PGHOST -u PGPORT -u PGDATABASE -u PGSERVICE -u PGSERVICEFILE \
  psql -X --set ON_ERROR_STOP=1 --no-align --tuples-only --quiet \
  --field-separator $'\t' --dbname "$DATABASE_URL" <<<"$SNAPSHOT_SQL")
[ -n "$SNAPSHOT_ROWS" ] || die 'Database snapshot returned no table rows'

printf 'schema.table\tcount\tmd5\n' >"$TEMP_OUTPUT"
VALID_ROW_COUNT=0
while IFS=$'\t' read -r table_name row_count content_hash result_extra; do
  case "$table_name" in
    ?*.?*) ;;
    *) die 'Unexpected table-name result' ;;
  esac
  case "$row_count" in
    ''|*[!0-9]*) die 'Unexpected row-count result' ;;
  esac
  case "$content_hash" in
    [0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f]) ;;
    *) die 'Unexpected content-hash result' ;;
  esac
  [ -z "${result_extra:-}" ] || die 'Unexpected snapshot result'
  printf '%s\t%s\t%s\n' "$table_name" "$row_count" "$content_hash" >>"$TEMP_OUTPUT"
  VALID_ROW_COUNT=$((VALID_ROW_COUNT + 1))
done <<<"$SNAPSHOT_ROWS"
[ "$VALID_ROW_COUNT" -gt 0 ] || die 'Database snapshot returned no valid table rows'

{ head -n 1 "$TEMP_OUTPUT"; tail -n +2 "$TEMP_OUTPUT" | LC_ALL=C sort; } >"${TEMP_OUTPUT}.sorted"
mv -- "${TEMP_OUTPUT}.sorted" "$TEMP_OUTPUT"
mv -- "$TEMP_OUTPUT" "$OUTPUT"
