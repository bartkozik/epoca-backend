#!/usr/bin/env bash
set -euo pipefail
repo=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
cd "$repo"
work=$(mktemp -d)
cleanup() {
  local exit_code=$?
  if [[ "$exit_code" != 0 ]]; then
    for log in "$work/valid" "$work/invalid"; do
      if [[ -f "$log" ]]; then tail -60 "$log" >&2; fi
    done
  fi
  rm -rf -- "$work"
  exit "$exit_code"
}
trap cleanup EXIT

export DB_URL='jdbc:postgresql:epoca?currentSchema=settings_probe'
export TEST_DB_URL="$DB_URL"
export DB_MIGRATION_BOOTSTRAP=true TEST_DB_MIGRATION_BOOTSTRAP=true
unset FLYWAY_URL FLYWAY_SCHEMAS FLYWAY_DEFAULT_SCHEMA FLYWAY_LOCATIONS
sbt 'show flywaySchemas' 'show flywayLocations' >"$work/valid" 2>&1
grep -F 'settings_probe' "$work/valid" >/dev/null
grep -F '/db/configured' "$work/valid" >/dev/null
grep -F '/db/bootstrap' "$work/valid" >/dev/null

for override in '-Dflyway.defaultSchema=public' '-Dflyway.schemas=public' '-Dflyway.locations=filesystem:/sql' '-Dflyway.url=jdbc:postgresql:other'; do
  if sbt "$override" 'show flywaySchemas' >"$work/invalid" 2>&1; then
    echo 'sbt accepted an override that redirects migrations' >&2; exit 1
  fi
  grep -F 'override conflicts with the configured application schema/lineage' "$work/invalid" >/dev/null
done
for query in 'currentSchema=a&currentSchema=b' 'current%53chema=a' 'currentSchema=%61'; do
  export DB_URL="jdbc:postgresql:epoca?$query" TEST_DB_URL="jdbc:postgresql:epoca?$query"
  if sbt 'show flywaySchemas' >"$work/invalid" 2>&1; then
    echo 'sbt accepted an ambiguous JDBC schema selection' >&2; exit 1
  fi
  grep -E 'JDBC (currentSchema|parameter names)' "$work/invalid" >/dev/null
done
echo 'PASS: sbt schema selection, configured locations and conflicting overrides'
