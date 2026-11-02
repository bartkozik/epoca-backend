#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
ci_file="$repo_root/.gitlab-ci.yml"
release_file="$repo_root/deploy/staging-release.env"
migration_dockerfile="$repo_root/src/main/resources/db/Dockerfile"

require_literal() {
  local literal=$1
  local file=${2:-$ci_file}
  grep -Fq -- "$literal" "$file" || {
    printf 'missing required release contract: %s\n' "$literal" >&2
    return 1
  }
}

require_literal 'MIGRATION_MODE=backward-compatible-expand' "$release_file"
require_literal 'DEPLOY_PHASE=maintenance-switch' "$release_file"
require_literal 'FROM iterators/flyway-docker@sha256:7a31476f42775c576b14e155cb4243654d2962e45ab9ddf88cd00df337edeae1' "$migration_dockerfile"

require_literal 'bash scripts/ci/test-staging-release-ci.sh'
require_literal 'sh scripts/ci/test-backend-deploy-trigger.sh'
require_literal 'BACKEND_IMAGE="$CI_REGISTRY_IMAGE:$CI_COMMIT_SHA"'
require_literal 'MIGRATION_IMAGE="$CI_REGISTRY_IMAGE/migrations:$CI_COMMIT_SHA"'
require_literal 'org.opencontainers.image.revision=$CI_COMMIT_SHA'
require_literal 'bash scripts/ci/test-backend-runtime-image.sh "$BACKEND_IMAGE"'
require_literal 'docker push "$BACKEND_IMAGE"'
require_literal 'docker push "$MIGRATION_IMAGE"'
require_literal 'docker login -u "$CI_REGISTRY_USER" --password-stdin "$CI_REGISTRY"'
require_literal 'name: curlimages/curl:8.14.1@sha256:9a1ed35addb45476afa911696297f8e115993df459278ed036182dd2cd22b67b'
require_literal 'entrypoint: [""]'
require_literal 'dependencies: []'
require_literal '. deploy/staging-release.env'
require_literal 'variables[BACKEND_SHA]=$CI_COMMIT_SHA'
require_literal 'variables[BACKEND_IMAGE]=$CI_REGISTRY_IMAGE:$CI_COMMIT_SHA'
require_literal 'variables[MIGRATION_IMAGE]=$CI_REGISTRY_IMAGE/migrations:$CI_COMMIT_SHA'
require_literal 'variables[MIGRATION_MODE]=$MIGRATION_MODE'
require_literal 'variables[EXPECTED_SCHEMA_VERSION]=$EXPECTED_SCHEMA_VERSION'
require_literal 'variables[DEPLOY_PHASE]=$DEPLOY_PHASE'

if grep -Eq '[Cc][Tt]-?[0-9]{3}' "$ci_file"; then
  echo 'default CI must not contain feature-ticket-specific jobs or commands' >&2
  exit 1
fi

if grep -Eq '^oauth_.*fixture.*contract:' "$ci_file"; then
  echo 'default CI must not run the retired disposable OAuth fixture contract' >&2
  exit 1
fi

if grep -Eq 'docker (build|pull)[^#\n]*:latest' "$ci_file"; then
  echo 'release jobs must not build or pull mutable latest images' >&2
  exit 1
fi

backend_sha_push=$(grep -m1 -nF 'docker push "$BACKEND_IMAGE"' "$ci_file" | cut -d: -f1)
backend_latest_push=$(grep -m1 -nF 'docker push "$CI_REGISTRY_IMAGE:latest"' "$ci_file" | cut -d: -f1)
migration_sha_push=$(grep -m1 -nF 'docker push "$MIGRATION_IMAGE"' "$ci_file" | cut -d: -f1)
migration_latest_push=$(grep -m1 -nF 'docker push "$CI_REGISTRY_IMAGE/migrations:latest"' "$ci_file" | cut -d: -f1)

((backend_sha_push < backend_latest_push)) || {
  echo 'backend SHA image must be pushed before latest is updated' >&2
  exit 1
}
((migration_sha_push < migration_latest_push)) || {
  echo 'migration SHA image must be pushed before latest is updated' >&2
  exit 1
}

echo 'PASS: staging release CI contract'
