#!/bin/sh
set -eu

repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
pipeline=${1:-$repo_root/.gitlab-ci.yml}
maximum_bytes=$((64 * 1024))

[ -f "$pipeline" ] && [ ! -L "$pipeline" ] || {
  echo "deploy pipeline must be a regular non-symlink file" >&2
  exit 1
}
[ "$(wc -c <"$pipeline")" -le "$maximum_bytes" ] || {
  echo "deploy pipeline exceeds the verifier size limit" >&2
  exit 1
}

trigger_count=$(grep -c '^trigger_deploy:$' "$pipeline" || true)
[ "$trigger_count" -eq 1 ] || {
  echo "pipeline must contain exactly one trigger_deploy job" >&2
  exit 1
}

trigger_block=$(awk '
  /^trigger_deploy:$/ { capture = 1 }
  capture && seen && /^[A-Za-z0-9_.-]+:$/ { exit }
  capture { print; seen = 1 }
' "$pipeline")
[ -n "$trigger_block" ] || {
  echo "trigger_deploy job could not be read" >&2
  exit 1
}

require_once() {
  literal=$1
  description=$2
  count=$(printf '%s\n' "$trigger_block" | grep -F -c -- "$literal" || true)
  [ "$count" -eq 1 ] || {
    echo "trigger_deploy must contain exactly one $description" >&2
    exit 1
  }
}

require_once 'name: curlimages/curl:8.14.1@sha256:9a1ed35addb45476afa911696297f8e115993df459278ed036182dd2cd22b67b' 'reviewed immutable curl image'
require_once 'entrypoint: [""]' 'empty image entrypoint'
require_once 'dependencies: []' 'empty artifact dependency list'
require_once '. deploy/staging-release.env' 'POSIX release-contract load'

image_declarations=$(printf '%s\n' "$trigger_block" | grep -c '^  image:$' || true)
[ "$image_declarations" -eq 1 ] || {
  echo "trigger_deploy must contain exactly one explicit image block" >&2
  exit 1
}
dependency_declarations=$(printf '%s\n' "$trigger_block" | grep -c '^  dependencies:' || true)
[ "$dependency_declarations" -eq 1 ] || {
  echo "trigger_deploy must disable implicit artifact downloads" >&2
  exit 1
}

if printf '%s\n' "$trigger_block" | grep -Eq '(^|[[:space:]-])(bash|source)([[:space:]]|$)'; then
  echo "trigger_deploy must use only the portable job shell" >&2
  exit 1
fi
if printf '%s\n' "$trigger_block" | grep -Eq 'before_script:|(^|[[:space:]-])(apk|apt-get|apt|dnf|yum)[[:space:]]'; then
  echo "trigger_deploy must not install packages at runtime" >&2
  exit 1
fi

endpoint='https://gitlab.company-5897fee414.example.invalid/api/v4/projects/717/trigger/pipeline'
require_once "$endpoint" 'reviewed project-717 endpoint'
endpoint_count=$(printf '%s\n' "$trigger_block" | grep -E -c 'api/v4/projects/[0-9]+/trigger/pipeline' || true)
[ "$endpoint_count" -eq 1 ] || {
  echo "trigger_deploy contains an ambiguous downstream project" >&2
  exit 1
}

for required in \
  'token=$TRIGGER_DEPLOY_TOKEN' \
  'ref=main' \
  'variables[BACKEND_SHA]=$CI_COMMIT_SHA' \
  'variables[BACKEND_IMAGE]=$CI_REGISTRY_IMAGE:$CI_COMMIT_SHA' \
  'variables[MIGRATION_IMAGE]=$CI_REGISTRY_IMAGE/migrations:$CI_COMMIT_SHA' \
  'variables[MIGRATION_MODE]=$MIGRATION_MODE' \
  'variables[EXPECTED_SCHEMA_VERSION]=$EXPECTED_SCHEMA_VERSION' \
  'variables[DEPLOY_PHASE]=$DEPLOY_PHASE'
do
  require_once "--form-string \"$required\"" 'reviewed form field'
done

allowed_variables='BACKEND_IMAGE
BACKEND_SHA
DEPLOY_PHASE
EXPECTED_SCHEMA_VERSION
MIGRATION_IMAGE
MIGRATION_MODE'
actual_variables=$(printf '%s\n' "$trigger_block" | grep -oE 'variables\[[A-Z0-9_]+\]' | sed -E 's/^variables\[//; s/\]$//' | sort -u || true)
[ "$actual_variables" = "$allowed_variables" ] || {
  echo "trigger_deploy forwards an unreviewed variable set" >&2
  exit 1
}

if printf '%s\n' "$trigger_block" | grep -Eiq '(CT-?522|OAUTH_E2E_FIXTURE_|EPOCA_DEPLOYMENT_TIER|CONTROL[_-]?PORT|disposable|fixture[^[:space:]]*(time|listener)|time[^[:space:]]*enabled|listener[^[:space:]]*enabled)'; then
  echo "trigger_deploy forwards a feature-scoped fixture capability" >&2
  exit 1
fi

echo "PASS: backend deploy trigger is portable and closed to project 717"
