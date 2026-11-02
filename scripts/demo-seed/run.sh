#!/usr/bin/env bash

set +x
set -euo pipefail

ORGANIZATION_SLUG=iteratorstesting
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
FINGERPRINT_PATH="$SCRIPT_DIR/staging-fingerprint.conf"

die() {
  printf 'Error: %s\n' "$*" >&2
  exit 2
}

usage() {
  printf '%s\n' \
    'Usage:' \
    '  run.sh <local|staging> <--dry-run|--apply> [options]' \
    '  run.sh verify <local|staging> [options]' >&2
  exit 2
}

require_option_value() {
  if [ "$#" -lt 2 ] || [ -z "$2" ]; then
    die "Missing value for $1"
  fi
  case "$2" in
    --*) die "Missing value for $1" ;;
  esac
}

if [ "$#" -eq 0 ]; then
  usage
fi

TARGET=
MODE=

if [ "$1" = verify ]; then
  MODE=verify
  shift
  if [ "$#" -eq 0 ]; then
    usage
  fi
  TARGET=$1
  shift
else
  TARGET=$1
  shift
  case "$TARGET" in
    local|staging) ;;
    *) die "Unsupported target: $TARGET" ;;
  esac
  if [ "$#" -eq 0 ]; then
    usage
  fi
  case "$1" in
    --dry-run) MODE=dry-run ;;
    --apply) MODE=apply ;;
    *) die "Unsupported mode: $1" ;;
  esac
  shift
fi

case "$TARGET" in
  local|staging) ;;
  *) die "Unsupported target: $TARGET" ;;
esac

CONFIRM_LOCAL_DATABASE=
CONFIRM_ORGANIZATION=
DEMO_VIEWER_EMAIL=
TODAY_OVERRIDE=
SEEN_CONFIRM_LOCAL=false
SEEN_CONFIRM_ORGANIZATION=false
SEEN_DEMO_VIEWER=false
SEEN_TODAY=false

while [ "$#" -gt 0 ]; do
  option=$1
  case "$option" in
    --confirm-local-database)
      require_option_value "$@"
      [ "$SEEN_CONFIRM_LOCAL" = false ] || die "Duplicate option: $option"
      SEEN_CONFIRM_LOCAL=true
      CONFIRM_LOCAL_DATABASE=$2
      shift 2
      ;;
    --confirm-organization)
      require_option_value "$@"
      [ "$SEEN_CONFIRM_ORGANIZATION" = false ] || die "Duplicate option: $option"
      SEEN_CONFIRM_ORGANIZATION=true
      CONFIRM_ORGANIZATION=$2
      shift 2
      ;;
    --demo-viewer-email)
      require_option_value "$@"
      [ "$SEEN_DEMO_VIEWER" = false ] || die "Duplicate option: $option"
      SEEN_DEMO_VIEWER=true
      DEMO_VIEWER_EMAIL=$2
      shift 2
      ;;
    --today)
      require_option_value "$@"
      [ "$SEEN_TODAY" = false ] || die "Duplicate option: $option"
      SEEN_TODAY=true
      TODAY_OVERRIDE=$2
      shift 2
      ;;
    --organization-slug|--organization-slug=*)
      die "organization slug is fixed to $ORGANIZATION_SLUG"
      ;;
    *) die "Unknown option: $option" ;;
  esac
done

if [ "$TARGET" = staging ]; then
  [ "$SEEN_CONFIRM_LOCAL" = false ] || die '--confirm-local-database is not valid for staging'
  [ "$CONFIRM_ORGANIZATION" = "$ORGANIZATION_SLUG" ] || \
    die "--confirm-organization must equal $ORGANIZATION_SLUG"
  [ -n "$DEMO_VIEWER_EMAIL" ] || die '--demo-viewer-email is required for staging'
  [ "$SEEN_TODAY" = false ] || die 'staging rejects today overrides'
else
  [ "$SEEN_CONFIRM_ORGANIZATION" = false ] || die '--confirm-organization is not valid for local'
  [ "$SEEN_DEMO_VIEWER" = false ] || die '--demo-viewer-email is not valid for local'
  if [ "$MODE" != apply ] && [ "$SEEN_CONFIRM_LOCAL" = true ]; then
    die "--confirm-local-database is not valid for $MODE"
  fi
fi

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

if [ "$TARGET" = local ]; then
  case "$DB_HOST" in
    localhost|127.0.0.1|'[::1]') ;;
    *) die 'Local target requires a loopback DB_URL host' ;;
  esac
  case "$DB_NAME" in
    epoca|epoca_ct532) ;;
    epoca_ct532_case_*)
      if [[ ! "$DB_NAME" =~ ^epoca_ct532_case_[0-9]+_[0-9]+$ ]]; then
        die "Database '$DB_NAME' is not approved for local seeding"
      fi
      ;;
    *) die "Database '$DB_NAME' is not approved for local seeding" ;;
  esac
fi

LIBPQ_URL="postgresql://$DB_HOST:$DB_PORT/$DB_NAME"
FIELD_SEPARATOR=$(printf '\034')
IDENTITY_SQL="SELECT pg_catalog.current_database(), (pg_catalog.pg_control_system()).system_identifier, pg_catalog.inet_server_addr(), pg_catalog.inet_server_port(), (CURRENT_TIMESTAMP AT TIME ZONE 'Europe/Warsaw')::pg_catalog.date;"

set +e
IDENTITY_OUTPUT=$(
  PGPASSWORD="$DB_PASSWORD" psql -X \
    --set ON_ERROR_STOP=1 \
    --no-align \
    --tuples-only \
    --quiet \
    --field-separator "$FIELD_SEPARATOR" \
    --username "$DB_USER" \
    --dbname "$LIBPQ_URL" \
    --command "$IDENTITY_SQL"
)
IDENTITY_STATUS=$?
set -e
if [ "$IDENTITY_STATUS" -ne 0 ]; then
  printf '%s\n' 'Error: Database identity preflight failed' >&2
  exit "$IDENTITY_STATUS"
fi

case "$IDENTITY_OUTPUT" in
  *$'\n'*) die 'Database identity preflight returned more than one row' ;;
esac

IDENTITY_REMAINDER=$IDENTITY_OUTPUT
IDENTITY_SEPARATOR_COUNT=0
while :; do
  case "$IDENTITY_REMAINDER" in
    *"$FIELD_SEPARATOR"*)
      IDENTITY_SEPARATOR_COUNT=$((IDENTITY_SEPARATOR_COUNT + 1))
      IDENTITY_REMAINDER=${IDENTITY_REMAINDER#*"$FIELD_SEPARATOR"}
      ;;
    *) break ;;
  esac
done
if [ "$IDENTITY_SEPARATOR_COUNT" -ne 4 ]; then
  die 'Database identity preflight must return exactly five fields'
fi

IDENTITY_DATABASE=
IDENTITY_SYSTEM_IDENTIFIER=
IDENTITY_SERVER_ADDRESS=
IDENTITY_SERVER_PORT=
IDENTITY_SEED_TODAY=
IDENTITY_EXTRA=
IFS="$FIELD_SEPARATOR" read -r \
  IDENTITY_DATABASE \
  IDENTITY_SYSTEM_IDENTIFIER \
  IDENTITY_SERVER_ADDRESS \
  IDENTITY_SERVER_PORT \
  IDENTITY_SEED_TODAY \
  IDENTITY_EXTRA <<<"$IDENTITY_OUTPUT"

if [ -z "$IDENTITY_DATABASE" ] || \
  [ -z "$IDENTITY_SYSTEM_IDENTIFIER" ] || \
  [ -z "$IDENTITY_SERVER_ADDRESS" ] || \
  [ -z "$IDENTITY_SERVER_PORT" ] || \
  [ -z "$IDENTITY_SEED_TODAY" ] || \
  [ -n "$IDENTITY_EXTRA" ]; then
  die 'Database identity preflight must return exactly five non-empty fields'
fi

case "$IDENTITY_DATABASE" in
  *[!A-Za-z0-9_-]*) die 'Database identity returned an invalid database name' ;;
esac
case "$IDENTITY_SYSTEM_IDENTIFIER" in
  *[!0-9]*) die 'Database identity returned an invalid system identifier' ;;
esac
case "$IDENTITY_SERVER_ADDRESS" in
  *[!A-Za-z0-9:.%-]*) die 'Database identity returned an invalid server address' ;;
esac
case "$IDENTITY_SERVER_PORT" in
  ''|*[!0-9]*) die 'Database identity returned an invalid server port' ;;
esac
if [[ ! "$IDENTITY_SEED_TODAY" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]]; then
  die 'Database identity returned an invalid seed date'
fi
if [ "$IDENTITY_DATABASE" != "$DB_NAME" ]; then
  die 'Database identity does not match the DB_URL database'
fi

SEED_TODAY=$IDENTITY_SEED_TODAY
if [ -n "$TODAY_OVERRIDE" ]; then
  if [[ ! "$TODAY_OVERRIDE" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]]; then
    die 'Invalid --today date'
  fi
  set +e
  printf '%s\n' "SELECT :'date_to_validate'::date;" | \
    PGPASSWORD="$DB_PASSWORD" psql -X \
    --set ON_ERROR_STOP=1 \
    --set "date_to_validate=$TODAY_OVERRIDE" \
    --no-align \
    --tuples-only \
    --quiet \
    --username "$DB_USER" \
    --dbname "$LIBPQ_URL" \
    --file=- >/dev/null
  DATE_VALIDATION_STATUS=$?
  set -e
  if [ "$DATE_VALIDATION_STATUS" -ne 0 ]; then
    printf '%s\n' 'Error: Invalid --today date' >&2
    exit "$DATE_VALIDATION_STATUS"
  fi
  SEED_TODAY=$TODAY_OVERRIDE
fi

EXPECTED_DATABASE_NAME=$IDENTITY_DATABASE
EXPECTED_SYSTEM_IDENTIFIER=$IDENTITY_SYSTEM_IDENTIFIER
EXPECTED_SERVER_ADDRESS=$IDENTITY_SERVER_ADDRESS
EXPECTED_SERVER_PORT=$IDENTITY_SERVER_PORT
EXPECTED_ORGANIZATION_ID=

if [ "$TARGET" = local ]; then
  if [ "$MODE" = apply ]; then
    [ -n "$CONFIRM_LOCAL_DATABASE" ] || die '--confirm-local-database is required for local apply'
    [ "$CONFIRM_LOCAL_DATABASE" = "$IDENTITY_DATABASE" ] || die 'Local database confirmation mismatch'
  fi
else
  [ -f "$FINGERPRINT_PATH" ] || die 'Staging fingerprint file is missing'

  FP_DATABASE_NAME=
  FP_SYSTEM_IDENTIFIER=
  FP_SERVER_ADDRESS=
  FP_SERVER_PORT=
  FP_ORGANIZATION_ID=
  SEEN_FP_DATABASE_NAME=false
  SEEN_FP_SYSTEM_IDENTIFIER=false
  SEEN_FP_SERVER_ADDRESS=false
  SEEN_FP_SERVER_PORT=false
  SEEN_FP_ORGANIZATION_ID=false

  while IFS= read -r fingerprint_line || [ -n "$fingerprint_line" ]; do
    case "$fingerprint_line" in
      *=*) ;;
      *) die 'Malformed staging fingerprint line' ;;
    esac
    fingerprint_key=${fingerprint_line%%=*}
    fingerprint_value=${fingerprint_line#*=}
    [ -n "$fingerprint_value" ] || die "Missing staging fingerprint value: $fingerprint_key"
    case "$fingerprint_key" in
      DATABASE_NAME)
        [ "$SEEN_FP_DATABASE_NAME" = false ] || die 'Duplicate staging fingerprint key: DATABASE_NAME'
        SEEN_FP_DATABASE_NAME=true
        FP_DATABASE_NAME=$fingerprint_value
        ;;
      SYSTEM_IDENTIFIER)
        [ "$SEEN_FP_SYSTEM_IDENTIFIER" = false ] || die 'Duplicate staging fingerprint key: SYSTEM_IDENTIFIER'
        SEEN_FP_SYSTEM_IDENTIFIER=true
        FP_SYSTEM_IDENTIFIER=$fingerprint_value
        ;;
      SERVER_ADDRESS)
        [ "$SEEN_FP_SERVER_ADDRESS" = false ] || die 'Duplicate staging fingerprint key: SERVER_ADDRESS'
        SEEN_FP_SERVER_ADDRESS=true
        FP_SERVER_ADDRESS=$fingerprint_value
        ;;
      SERVER_PORT)
        [ "$SEEN_FP_SERVER_PORT" = false ] || die 'Duplicate staging fingerprint key: SERVER_PORT'
        SEEN_FP_SERVER_PORT=true
        FP_SERVER_PORT=$fingerprint_value
        ;;
      ORGANIZATION_ID)
        [ "$SEEN_FP_ORGANIZATION_ID" = false ] || die 'Duplicate staging fingerprint key: ORGANIZATION_ID'
        SEEN_FP_ORGANIZATION_ID=true
        FP_ORGANIZATION_ID=$fingerprint_value
        ;;
      *) die "Unknown staging fingerprint key: $fingerprint_key" ;;
    esac
  done <"$FINGERPRINT_PATH"

  [ "$SEEN_FP_DATABASE_NAME" = true ] || die 'Missing staging fingerprint key: DATABASE_NAME'
  [ "$SEEN_FP_SYSTEM_IDENTIFIER" = true ] || die 'Missing staging fingerprint key: SYSTEM_IDENTIFIER'
  [ "$SEEN_FP_SERVER_ADDRESS" = true ] || die 'Missing staging fingerprint key: SERVER_ADDRESS'
  [ "$SEEN_FP_SERVER_PORT" = true ] || die 'Missing staging fingerprint key: SERVER_PORT'
  [ "$SEEN_FP_ORGANIZATION_ID" = true ] || die 'Missing staging fingerprint key: ORGANIZATION_ID'

  for fingerprint_value in \
    "$FP_DATABASE_NAME" \
    "$FP_SYSTEM_IDENTIFIER" \
    "$FP_SERVER_ADDRESS" \
    "$FP_SERVER_PORT" \
    "$FP_ORGANIZATION_ID"; do
    [ "$fingerprint_value" != UNCONFIGURED ] || die 'Staging fingerprint is UNCONFIGURED'
  done

  case "$FP_DATABASE_NAME" in *[!A-Za-z0-9_-]*) die 'Invalid staging DATABASE_NAME' ;; esac
  case "$FP_SYSTEM_IDENTIFIER" in ''|*[!0-9]*) die 'Invalid staging SYSTEM_IDENTIFIER' ;; esac
  case "$FP_SERVER_ADDRESS" in *[!A-Za-z0-9:.%-]*) die 'Invalid staging SERVER_ADDRESS' ;; esac
  case "$FP_SERVER_PORT" in ''|*[!0-9]*) die 'Invalid staging SERVER_PORT' ;; esac
  if [[ ! "$FP_ORGANIZATION_ID" =~ ^[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}$ ]]; then
    die 'Invalid staging ORGANIZATION_ID'
  fi

  [ "$FP_DATABASE_NAME" = "$IDENTITY_DATABASE" ] || die 'Staging fingerprint mismatch: DATABASE_NAME'
  [ "$FP_SYSTEM_IDENTIFIER" = "$IDENTITY_SYSTEM_IDENTIFIER" ] || die 'Staging fingerprint mismatch: SYSTEM_IDENTIFIER'
  [ "$FP_SERVER_ADDRESS" = "$IDENTITY_SERVER_ADDRESS" ] || die 'Staging fingerprint mismatch: SERVER_ADDRESS'
  [ "$FP_SERVER_PORT" = "$IDENTITY_SERVER_PORT" ] || die 'Staging fingerprint mismatch: SERVER_PORT'

  EXPECTED_DATABASE_NAME=$FP_DATABASE_NAME
  EXPECTED_SYSTEM_IDENTIFIER=$FP_SYSTEM_IDENTIFIER
  EXPECTED_SERVER_ADDRESS=$FP_SERVER_ADDRESS
  EXPECTED_SERVER_PORT=$FP_SERVER_PORT
  EXPECTED_ORGANIZATION_ID=$FP_ORGANIZATION_ID
fi

APPLY=false
SQL_FILE="$SCRIPT_DIR/seed.sql"
if [ "$MODE" = apply ]; then
  APPLY=true
fi
if [ "$MODE" = verify ]; then
  SQL_FILE="$SCRIPT_DIR/verify.sql"
  MODE_SPECIFIC_ARGS=(--set 'standalone_verify=true')
else
  MODE_SPECIFIC_ARGS=(--set 'standalone_verify=false' --set "apply=$APPLY")
fi

if [ "$TARGET" = local ]; then
  printf '%s\n' 'WARNING: local mode must not be used through a tunnel/proxy.'
fi
printf 'target=%s\n' "$TARGET"
printf 'host=%s\n' "$DB_HOST"
printf 'port=%s\n' "$DB_PORT"
printf 'database=%s\n' "$IDENTITY_DATABASE"
printf 'organization_slug=%s\n' "$ORGANIZATION_SLUG"
printf 'system_identifier=%s\n' "$IDENTITY_SYSTEM_IDENTIFIER"
printf 'seed_today=%s\n' "$SEED_TODAY"

set +e
PGPASSWORD="$DB_PASSWORD" psql -X \
  --set ON_ERROR_STOP=1 \
  --set "target=$TARGET" \
  --set "operation=$MODE" \
  --set "organization_slug=$ORGANIZATION_SLUG" \
  --set "seed_today=$SEED_TODAY" \
  --set "demo_viewer_email=$DEMO_VIEWER_EMAIL" \
  --set "expected_organization_id=$EXPECTED_ORGANIZATION_ID" \
  --set "expected_database_name=$EXPECTED_DATABASE_NAME" \
  --set "expected_system_identifier=$EXPECTED_SYSTEM_IDENTIFIER" \
  --set "expected_server_address=$EXPECTED_SERVER_ADDRESS" \
  --set "expected_server_port=$EXPECTED_SERVER_PORT" \
  --set 'sql_test_mode=false' \
  "${MODE_SPECIFIC_ARGS[@]}" \
  --username "$DB_USER" \
  --dbname "$LIBPQ_URL" \
  --file "$SQL_FILE"
FINAL_STATUS=$?
set -e
exit "$FINAL_STATUS"
