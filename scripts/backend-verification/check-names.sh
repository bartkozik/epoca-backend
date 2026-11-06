#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
REPO_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/../.." && pwd -P)
PATTERN='(^|[^[:alnum:]])ct[-_]?[0-9]+'
failed=false

while IFS= read -r -d '' file; do
  if printf '%s\n' "${file#"$REPO_ROOT/"}" | grep -Eiq "$PATTERN"; then
    printf 'Ticket-derived tooling filename: %s\n' "$file" >&2
    failed=true
  fi
  if grep -Eni "$PATTERN" "$file"; then
    printf 'Use purpose-based names in %s\n' "$file" >&2
    failed=true
  else
    status=$?
    [ "$status" -eq 1 ] || exit "$status"
  fi
done < <(
  find "$SCRIPT_DIR" -path "$SCRIPT_DIR/accepted" -prune -o -type f \
    \( -name '*.sh' -o -name '*.java' \) -print0
  find "$REPO_ROOT/oauth-e2e/src" -type f \( -name '*.scala' -o -name '*.conf' \) -print0
  printf '%s\0' "$REPO_ROOT/project/BackendVerification.scala" "$REPO_ROOT/scripts/ci/verify-jvm-images.sh"
)

[ "$failed" = false ] || exit 1
printf 'PASS: backend verification uses purpose-based names\n'
