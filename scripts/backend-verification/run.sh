#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
REPO_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/../.." && pwd -P)
ACCEPTED_DIR="$REPO_ROOT/scripts/backend-verification/accepted"
ACTUAL_DEPENDENCIES="$REPO_ROOT/target/backend-verification/dependencies/resolved.tsv"

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

[ "$#" -gt 0 ] || die 'Usage: run.sh <checkpoint|final> [--focus <sbt expression>]'
MODE=$1
shift
case "$MODE" in
  checkpoint|final) ;;
  *) die "Unsupported mode: $MODE" ;;
esac

FOCUS=
SEEN_FOCUS=false
while [ "$#" -gt 0 ]; do
  case "$1" in
    --focus)
      require_option_value "$@"
      [ "$SEEN_FOCUS" = false ] || die 'Duplicate option: --focus'
      SEEN_FOCUS=true
      FOCUS=$2
      shift 2
      ;;
    *) die "Unknown option: $1" ;;
  esac
done

if [ "$MODE" = checkpoint ] && [ -z "$FOCUS" ]; then
  die 'checkpoint mode requires --focus'
fi

[ -f "$ACCEPTED_DIR/dependencies.tsv" ] || die 'Accepted dependency manifest is required'
[ -f "$ACCEPTED_DIR/dependency-policy.tsv" ] || die 'Accepted dependency policy is required'

if [ "$MODE" = final ]; then
  [ -x "$REPO_ROOT/scripts/backend-verification/verify-migrations.sh" ] || \
    die 'Final gate requires scripts/backend-verification/verify-migrations.sh'
  [ -f "$ACCEPTED_DIR/contracts/openapi/openapi.yml" ] || \
    die 'Final gate requires the accepted OpenAPI contract'
  [ -d "$ACCEPTED_DIR/contracts/simple" ] || \
    die 'Final gate requires the accepted SimpleDocs contracts'
  : "${EPOCA_BUILDER_IMAGE:?EPOCA_BUILDER_IMAGE is required for the final gate}"
  : "${EPOCA_RUNTIME_IMAGE:?EPOCA_RUNTIME_IMAGE is required for the final gate}"
  : "${EPOCA_REGRESSION_REPOSITORY:?EPOCA_REGRESSION_REPOSITORY is required for the final gate}"
  : "${EPOCA_REGRESSION_COMMIT_SHA:?EPOCA_REGRESSION_COMMIT_SHA is required for the final gate}"
  : "${EPOCA_REGRESSION_COMMAND:?EPOCA_REGRESSION_COMMAND is required for the final gate}"
  [[ "$EPOCA_REGRESSION_COMMIT_SHA" =~ ^[0-9a-fA-F]{40}$ ]] || \
    die 'EPOCA_REGRESSION_COMMIT_SHA must be an exact 40-character commit SHA'
fi

command -v sbt >/dev/null 2>&1 || die 'sbt is required'
export SBT_OPTS='-Xmx4G -Duser.timezone=GMT'

cd "$REPO_ROOT"
sbt 'writeBackendDependencyManifest; writeBackendToolchainManifest'
bash "$REPO_ROOT/scripts/backend-verification/verify-dependencies.sh" \
  --actual "$ACTUAL_DEPENDENCIES" \
  --accepted "$ACCEPTED_DIR/dependencies.tsv" \
  --policy "$ACCEPTED_DIR/dependency-policy.tsv"

if [ -n "$FOCUS" ]; then
  sbt "$FOCUS"
fi
sbt 'scalafmtCheckAll; scalafmtSbtCheck; compile; Test/compile; test'

if [ "$MODE" = final ]; then
  sbt 'scalafix --check; Test/scalafix --check'
  bash "$REPO_ROOT/scripts/backend-verification/verify-migrations.sh"
  sbt 'Test/baklavaGenerate; Docker/stage'
  bash "$REPO_ROOT/scripts/backend-verification/compare-contracts.sh" \
    --expected-dir "$ACCEPTED_DIR/contracts" \
    --actual-dir "$REPO_ROOT/target/baklava"
  bash "$REPO_ROOT/scripts/backend-verification/verify-jvm-images.sh"

  FOUND_CLASS=false
  while IFS= read -r -d '' class_file; do
    FOUND_CLASS=true
    javap -verbose "$class_file" | grep -F 'major version: 61' >/dev/null || \
      die "Bytecode major version is not 61: ${class_file#"$REPO_ROOT/"}"
  done < <(find "$REPO_ROOT/target" -type f \
    \( -path '*/scala-*/classes/*.class' -o -path '*/scala-*/test-classes/*.class' \) -print0)
  [ "$FOUND_CLASS" = true ] || die 'No compiled class files were found for bytecode verification'

  REGRESSION_WORK_DIR=$(mktemp -d "${TMPDIR:-/tmp}/epoca-regression-harness.XXXXXX")
  trap 'rm -rf -- "$REGRESSION_WORK_DIR"' EXIT
  git clone --no-checkout "$EPOCA_REGRESSION_REPOSITORY" "$REGRESSION_WORK_DIR/repository"
  git -C "$REGRESSION_WORK_DIR/repository" checkout --detach "$EPOCA_REGRESSION_COMMIT_SHA"
  ACTUAL_REGRESSION_SHA=$(git -C "$REGRESSION_WORK_DIR/repository" rev-parse HEAD)
  [ "$ACTUAL_REGRESSION_SHA" = "$EPOCA_REGRESSION_COMMIT_SHA" ] || die 'regression harness checkout does not match EPOCA_REGRESSION_COMMIT_SHA'
  (
    cd "$REGRESSION_WORK_DIR/repository"
    /bin/bash -lc "$EPOCA_REGRESSION_COMMAND"
  )
fi
