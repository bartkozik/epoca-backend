#!/usr/bin/env bash
set -euo pipefail

script_directory=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
work=$(mktemp -d)
trap 'rm -rf -- "$work"' EXIT
mkdir -p "$work/bin"

cat >"$work/bin/docker" <<'DOCKER'
#!/usr/bin/env bash
set -euo pipefail
case "$1" in
  network)
    printf 'network %s\n' "$2" >>"$TEST_LOG"
    ;;
  rm) printf 'remove postgres\n' >>"$TEST_LOG" ;;
  run)
    if [[ "$2" != -d ]]; then
      printf 'migration reached\n' >>"$TEST_LOG"
      exit 73 # Stop at the first migration, after database initialization.
    fi
    ;;
  exec)
    # Model the temporary socket-only startup server reporting ready.
    if [[ " $* " == *' pg_isready '* ]]; then
      printf 'temporary socket ready\n' >>"$TEST_LOG"
      exit 0
    fi
    [[ " $* " == *' --host 127.0.0.1 '* ]] || {
      echo 'temporary PostgreSQL socket has disappeared' >&2
      exit 2
    }
    [[ " $* " == *' PGPASSWORD='* && " $* " == *' PGCONNECT_TIMEOUT=2 '* && " $* " == *' --no-password '* ]] || exit 74
    if [[ "${*: -1}" == 'select 1' ]]; then
      attempt=$(cat "$TEST_ATTEMPT")
      attempt=$((attempt + 1))
      printf '%s\n' "$attempt" >"$TEST_ATTEMPT"
      printf 'readiness %s\n' "$attempt" >>"$TEST_LOG"
      [[ "$TEST_MODE" != unavailable && "$attempt" -gt 1 ]] || exit 2
      if [[ "$attempt" == 2 ]]; then printf '0\n'; else
        touch "$TEST_READY"
        printf '1\n'
      fi
    else
      [[ -f "$TEST_READY" ]] || { echo 'SQL ran before readiness' >&2; exit 75; }
      printf 'sql\n' >>"$TEST_LOG"
      cat >/dev/null
    fi
    ;;
  *) exit 76 ;;
esac
DOCKER
cat >"$work/bin/sleep" <<'SLEEP'
#!/usr/bin/env bash
exit 0
SLEEP
chmod +x "$work/bin/docker" "$work/bin/sleep"

run_case() {
  local mode=$1
  : >"$work/$mode.log"
  printf '0\n' >"$work/$mode.attempt"
  local status=0
  env PATH="$work/bin:$PATH" TEST_MODE="$mode" \
    TEST_LOG="$work/$mode.log" TEST_ATTEMPT="$work/$mode.attempt" TEST_READY="$work/$mode.ready" \
    bash "$script_directory/test-migration-image.sh" candidate-image </dev/null >"$work/$mode.output" 2>&1 || status=$?
  if [[ "$mode" == delayed ]]; then
    [[ "$status" == 73 ]] || { cat "$work/$mode.output" >&2; echo 'did not reach migrations after TCP readiness' >&2; return 1; }
    [[ "$(cat "$work/$mode.attempt")" == 3 ]]
    grep -qx 'migration reached' "$work/$mode.log"
    ! grep -q 'temporary socket ready' "$work/$mode.log"
  else
    [[ "$status" == 1 ]]
    [[ "$(cat "$work/$mode.attempt")" == 60 ]]
    ! grep -Eq '^(sql|migration reached)$' "$work/$mode.log"
    grep -q 'test PostgreSQL did not start' "$work/$mode.output"
  fi
  grep -qx 'remove postgres' "$work/$mode.log"
  grep -qx 'network rm' "$work/$mode.log"
}

run_case delayed
run_case unavailable
echo 'PASS: migration-image setup waits for authenticated TCP SQL and fails closed on timeout'
