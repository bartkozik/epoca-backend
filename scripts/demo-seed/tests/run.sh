#!/usr/bin/env bash

set -euo pipefail

TEST_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
REPO_ROOT=$(CDPATH= cd -- "$TEST_DIR/../../.." && pwd -P)
RUN_INTEGRATION=false
INTEGRATION_SQL=false

for arg in "$@"; do
  case "$arg" in
    --integration) RUN_INTEGRATION=true ;;
    --integration-sql) INTEGRATION_SQL=true ;;
    *)
      printf 'Usage: %s [--integration] [--integration-sql]\n' "$0" >&2
      exit 2
      ;;
  esac
done

bash "$REPO_ROOT/scripts/demo-seed/tests/docs-ci-contract-test.sh"
bash "$REPO_ROOT/scripts/demo-seed/tests/capture-staging-fingerprint-contract-test.sh"
bash "$REPO_ROOT/scripts/demo-seed/tests/runner-contract-test.sh"

if [ "$INTEGRATION_SQL" = true ]; then
  bash "$REPO_ROOT/scripts/demo-seed/tests/integration-test.sh" --integration-sql
else
  bash "$REPO_ROOT/scripts/demo-seed/tests/catalog-contract-test.sh"
  if [ "$RUN_INTEGRATION" = true ]; then
    bash "$REPO_ROOT/scripts/demo-seed/tests/integration-test.sh"
  fi
fi
