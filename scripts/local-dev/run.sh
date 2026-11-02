#!/usr/bin/env bash
# Local Época stack without Docker: a native PostgreSQL, the backend through sbt and the
# frontend through Vite. See README.md in this directory.
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
API=$(cd "$HERE/../.." && pwd)
[ -f "$HERE/local.env" ] && . "$HERE/local.env"

FE="${EPOCA_FRONTEND_DIR:-$API/../epoca-frontend}"
STATE="${EPOCA_LOCAL_STATE:-${TMPDIR:-/tmp}/epoca-local}"
DB="${EPOCA_LOCAL_DB:-epoca}"
BACKEND_PORT="${BACKEND_PORT:-9000}"
FRONTEND_PORT="${FRONTEND_PORT:-5173}"
SBT_MEM="${SBT_MEM:-4096}"
DEMO_LOGINS=(marta-zielinska piotr-wisniewski adam-jankowski lucas-martin amelia-nowak)

die() { printf 'local-dev: %s\n' "$1" >&2; exit 1; }

java17() {
  if [ -n "${JAVA_HOME:-}" ] && "$JAVA_HOME/bin/java" -version 2>&1 | grep -q '"17\.'; then return; fi
  local candidate
  for candidate in "$(/usr/libexec/java_home -v 17 2>/dev/null || true)" "$HOME"/.sdkman/candidates/java/17*; do
    if [ -x "$candidate/bin/java" ]; then export JAVA_HOME=$candidate; return; fi
  done
  die 'JDK 17 not found; set JAVA_HOME'
}

java17
export PATH="$JAVA_HOME/bin:$PATH"
export DB_URL="jdbc:postgresql://127.0.0.1:5432/$DB" DB_USER="${DB_USER:-$USER}" DB_PASSWORD="${DB_PASSWORD:-local}"
export TEST_DB_URL="$DB_URL" TEST_DB_USER="$DB_USER" TEST_DB_PASSWORD="$DB_PASSWORD"
# Local-only values: application.conf has no defaults for these secrets.
export JWT_SECRET="${JWT_SECRET:-local-only-jwt-secret}" SECRET_KEY="${SECRET_KEY:-local-only-health-secret}"
export KAMON_ENABLED=false PORT="$BACKEND_PORT"
mkdir -p "$STATE"

probe() {
  local code
  code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 2 "$2" || true)
  if [ "$code" = 000 ]; then echo "$1 down"; else echo "$1 $code"; fi
}

psql_db() { PGPASSWORD="$DB_PASSWORD" psql -X -h 127.0.0.1 -U "$DB_USER" -d "$DB" "$@"; }

seed() {
  (cd "$API" && scripts/demo-seed/run.sh local --apply --confirm-local-database "$DB" | tail -3)
  if [ -z "${LOCAL_DEMO_PASSWORD:-}" ]; then
    echo 'LOCAL_DEMO_PASSWORD is not set; seeded accounts keep the shared demo password.'
    return
  fi
  command -v htpasswd >/dev/null || die 'htpasswd is required to set LOCAL_DEMO_PASSWORD'
  local hash logins
  hash=$(htpasswd -nbBC 12 x "$LOCAL_DEMO_PASSWORD" | cut -d: -f2 | sed 's/^\$2y\$/$2a$/')
  logins=$(printf "'demo+%s@epoca.example.invalid'," "${DEMO_LOGINS[@]}")
  psql_db -qc "UPDATE user_password p SET password='$hash' FROM user_identity ui
               WHERE ui.id = p.user_identity_id AND ui.main AND ui.value IN (${logins%,})"
  echo "LOCAL_DEMO_PASSWORD set for: ${DEMO_LOGINS[*]}"
}

up() {
  if ! curl -s -o /dev/null --max-time 2 "http://localhost:$BACKEND_PORT/api/v1/health-check"; then
    (cd "$API" && nohup sbt -mem "$SBT_MEM" -batch run > "$STATE/backend.log" 2>&1 &)
    printf 'backend starting on :%s' "$BACKEND_PORT"
    until curl -s -o /dev/null --max-time 2 "http://localhost:$BACKEND_PORT/api/v1/health-check"; do
      if grep -q '\[error\]' "$STATE/backend.log"; then
        echo; grep '\[error\]' "$STATE/backend.log" | head -5; die "backend failed; see $STATE/backend.log"
      fi
      printf .; sleep 5
    done
    echo ' up'
  fi
  [ -d "$FE" ] || { echo "frontend not found at $FE; set EPOCA_FRONTEND_DIR"; return; }
  if ! curl -s -o /dev/null --max-time 2 "http://127.0.0.1:$FRONTEND_PORT/"; then
    # A stale CRA-era .env (REACT_APP_*) is ignored by Vite and leaves the API base URL empty.
    [ -f "$FE/.env.local" ] || printf 'VITE_API_BASE_URL=/api/v1\nVITE_PUBLIC_URL=\n' > "$FE/.env.local"
    (
      cd "$FE"
      if [ -s "$HOME/.nvm/nvm.sh" ]; then . "$HOME/.nvm/nvm.sh"; nvm use >/dev/null; fi
      EPOCA_LOCAL_PROXY_TARGET="http://localhost:$BACKEND_PORT" \
        nohup node .yarn/releases/yarn-*.cjs dev --port "$FRONTEND_PORT" --strictPort > "$STATE/frontend.log" 2>&1 &
    )
    until grep -q 'Local:' "$STATE/frontend.log" 2>/dev/null; do sleep 1; done
  fi
  echo "frontend: http://127.0.0.1:$FRONTEND_PORT"
}

case "${1:-}" in
  reset)
    dropdb --if-exists -h 127.0.0.1 -U "$DB_USER" "$DB"
    createdb -h 127.0.0.1 -U "$DB_USER" "$DB"
    (cd "$API" && sbt -mem "$SBT_MEM" -batch flywayMigrate | grep -E 'Successfully|ERROR|error\]')
    seed ;;
  reseed) seed ;;
  up) up ;;
  down)
    for port in "$BACKEND_PORT" "$FRONTEND_PORT"; do
      for pid in $(lsof -t -nP -iTCP:"$port" -sTCP:LISTEN); do kill "$pid"; done
    done
    echo stopped ;;
  status)
    probe "backend  :$BACKEND_PORT" "http://localhost:$BACKEND_PORT/api/v1/health-check"
    probe "frontend :$FRONTEND_PORT" "http://127.0.0.1:$FRONTEND_PORT/" ;;
  users)
    psql_db -Atc "SELECT ou.roles::text, ui.value FROM organization_user ou
                  JOIN user_identity ui ON ui.user_id = ou.user_id AND ui.main
                  WHERE ou.archived_at IS NULL AND ui.value LIKE 'demo+%'
                  ORDER BY ou.roles::text, ui.value" | column -t -s'|' ;;
  *) echo "usage: $0 {reset|reseed|up|down|status|users}" >&2; exit 2 ;;
esac
