#!/usr/bin/env bash

set +x
set -euo pipefail

ORGANIZATION_SLUG=iteratorstesting

die() {
  printf 'Error: %s\n' "$*" >&2
  exit 2
}

if [ "$#" -ne 2 ] || [ "$1" != --confirm-organization ]; then
  die 'Usage: capture-staging-fingerprint.sh --confirm-organization iteratorstesting'
fi
[ "$2" = "$ORGANIZATION_SLUG" ] || \
  die "--confirm-organization must equal $ORGANIZATION_SLUG"

: "${DB_URL:?DB_URL is required}"
: "${DB_USER:?DB_USER is required}"
: "${DB_PASSWORD:?DB_PASSWORD is required}"

JDBC_PATTERN='^jdbc:postgresql://(\[[^]]+\]|[^/:?#@]+)(:([0-9]{1,5}))?/([^/?#]+)$'
if [[ ! "$DB_URL" =~ $JDBC_PATTERN ]]; then
  die 'Malformed DB_URL; expected jdbc:postgresql://host[:port]/database'
fi

DB_HOST=${BASH_REMATCH[1]}
DB_PORT=${BASH_REMATCH[3]:-5432}
DB_NAME=${BASH_REMATCH[4]}

case "$DB_HOST" in
  \[*\])
    IPV6_HOST=${DB_HOST#\[}
    IPV6_HOST=${IPV6_HOST%\]}
    case "$IPV6_HOST" in
      ''|*[!0-9A-Fa-f:.%]*) die 'Malformed DB_URL host' ;;
    esac
    ;;
  *)
    case "$DB_HOST" in
      ''|*[!A-Za-z0-9.-]*) die 'Malformed DB_URL host' ;;
    esac
    ;;
esac
case "$DB_PORT" in
  ''|*[!0-9]*) die 'Malformed DB_URL port' ;;
esac
if [ "$DB_PORT" -lt 1 ] || [ "$DB_PORT" -gt 65535 ]; then
  die 'Malformed DB_URL port'
fi
case "$DB_NAME" in
  ''|*[!A-Za-z0-9_-]*) die 'Malformed DB_URL database name' ;;
esac

LIBPQ_URL="postgresql://$DB_HOST:$DB_PORT/$DB_NAME"
FIELD_SEPARATOR=$(printf '\034')
FINGERPRINT_SQL="BEGIN TRANSACTION READ ONLY;
SELECT pg_catalog.current_database(),
       (pg_catalog.pg_control_system()).system_identifier,
       pg_catalog.inet_server_addr(),
       pg_catalog.inet_server_port(),
       organization.id
  FROM public.organization
 WHERE slug = :'organization_slug'
   AND archived_at IS NULL;
ROLLBACK;"

set +e
FINGERPRINT_OUTPUT=$(
  printf '%s\n' "$FINGERPRINT_SQL" | \
    env -u PGHOSTADDR -u PGSERVICE -u PGSERVICEFILE -u PGPASSFILE -u PGOPTIONS \
      PGPASSWORD="$DB_PASSWORD" \
      psql -X \
        --set ON_ERROR_STOP=1 \
        --set "organization_slug=$ORGANIZATION_SLUG" \
        --no-align \
        --tuples-only \
        --quiet \
        --field-separator "$FIELD_SEPARATOR" \
        --username "$DB_USER" \
        --dbname "$LIBPQ_URL" \
        --file=-
)
FINGERPRINT_STATUS=$?
set -e
if [ "$FINGERPRINT_STATUS" -ne 0 ]; then
  printf '%s\n' 'Error: Fingerprint capture failed' >&2
  exit "$FINGERPRINT_STATUS"
fi

case "$FINGERPRINT_OUTPUT" in
  ''|*$'\n'*) die 'Fingerprint capture must return exactly one active organization' ;;
esac

FINGERPRINT_REMAINDER=$FINGERPRINT_OUTPUT
FINGERPRINT_SEPARATOR_COUNT=0
while :; do
  case "$FINGERPRINT_REMAINDER" in
    *"$FIELD_SEPARATOR"*)
      FINGERPRINT_SEPARATOR_COUNT=$((FINGERPRINT_SEPARATOR_COUNT + 1))
      FINGERPRINT_REMAINDER=${FINGERPRINT_REMAINDER#*"$FIELD_SEPARATOR"}
      ;;
    *) break ;;
  esac
done
[ "$FINGERPRINT_SEPARATOR_COUNT" -eq 4 ] || \
  die 'Fingerprint capture must return exactly five fields'

FINGERPRINT_DATABASE=
FINGERPRINT_SYSTEM_IDENTIFIER=
FINGERPRINT_SERVER_ADDRESS=
FINGERPRINT_SERVER_PORT=
FINGERPRINT_ORGANIZATION_ID=
FINGERPRINT_EXTRA=
IFS="$FIELD_SEPARATOR" read -r \
  FINGERPRINT_DATABASE \
  FINGERPRINT_SYSTEM_IDENTIFIER \
  FINGERPRINT_SERVER_ADDRESS \
  FINGERPRINT_SERVER_PORT \
  FINGERPRINT_ORGANIZATION_ID \
  FINGERPRINT_EXTRA <<<"$FINGERPRINT_OUTPUT"

if [ -z "$FINGERPRINT_DATABASE" ] || \
  [ -z "$FINGERPRINT_SYSTEM_IDENTIFIER" ] || \
  [ -z "$FINGERPRINT_SERVER_ADDRESS" ] || \
  [ -z "$FINGERPRINT_SERVER_PORT" ] || \
  [ -z "$FINGERPRINT_ORGANIZATION_ID" ] || \
  [ -n "$FINGERPRINT_EXTRA" ]; then
  die 'Fingerprint capture must return exactly five non-empty fields'
fi

case "$FINGERPRINT_DATABASE" in
  *[!A-Za-z0-9_-]*) die 'Fingerprint returned an invalid database name' ;;
esac
case "$FINGERPRINT_SYSTEM_IDENTIFIER" in
  *[!0-9]*) die 'Fingerprint returned an invalid system identifier' ;;
esac
case "$FINGERPRINT_SERVER_ADDRESS" in
  *[!A-Za-z0-9:.%-]*) die 'Fingerprint returned an invalid server address' ;;
esac
case "$FINGERPRINT_SERVER_PORT" in
  ''|*[!0-9]*) die 'Fingerprint returned an invalid server port' ;;
esac
if [[ ! "$FINGERPRINT_ORGANIZATION_ID" =~ ^[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}$ ]]; then
  die 'Fingerprint returned an invalid organization identifier'
fi
[ "$FINGERPRINT_DATABASE" = "$DB_NAME" ] || \
  die 'Fingerprint database does not match DB_URL'

printf 'DATABASE_NAME=%s\n' "$FINGERPRINT_DATABASE"
printf 'SYSTEM_IDENTIFIER=%s\n' "$FINGERPRINT_SYSTEM_IDENTIFIER"
printf 'SERVER_ADDRESS=%s\n' "$FINGERPRINT_SERVER_ADDRESS"
printf 'SERVER_PORT=%s\n' "$FINGERPRINT_SERVER_PORT"
printf 'ORGANIZATION_ID=%s\n' "$FINGERPRINT_ORGANIZATION_ID"
