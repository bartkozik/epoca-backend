#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
script="$repo_root/scripts/ci/test-backend-runtime-image.sh"
pipeline="$repo_root/.gitlab-ci.yml"

require_literal() {
  local value=$1
  grep -F -- "$value" "$script" >/dev/null || {
    echo "runtime-image test is missing required contract: $value" >&2
    exit 1
  }
}

require_literal 'org.opencontainers.image.revision'
require_literal 'CI_COMMIT_SHA'
require_literal '.Config.ExposedPorts'
require_literal 'fixture control port must not be exposed'
require_literal '.Config.Env'
require_literal 'fixture configuration must not be baked into the backend image'
require_literal 'EPOCA_DEPLOYMENT_TIER=disposable'
require_literal 'migration_image=${2:?migration image is required}'
require_literal 'postgres:13.4'
require_literal 'docker network create "$runtime_network"'
require_literal 'docker run --detach --name "$postgres_container"'
require_literal 'exec flyway-10.20.0/flyway migrate'
require_literal '--network "$runtime_network"'
require_literal 'runtime_jwt_secret="backend-runtime-image-jwt-synthetic-'
require_literal 'runtime_operational_secret="backend-runtime-image-operational-synthetic-'
require_literal 'OAUTH_E2E_FIXTURE_TIME_ENABLED'
require_literal 'OAUTH_E2E_FIXTURE_CONTROL_LISTENER_ENABLED'
require_literal 'test "${EPOCA_DEPLOYMENT_TIER:-normal}" = "disposable"'
require_literal 'EPOCA_DEPLOYMENT_TIER=disposable'
require_literal 'EPOCA_DEPLOYMENT_TIER=normal'
require_literal 'read -r slot local_address remote_address state rest'
require_literal 'fixture control listener must remain closed'
require_literal 'FixtureControlListenerNegativeProbe'
require_literal 'runtime listener sensitivity probe did not detect the fixture port'
require_literal 'oauth_e2e_fixture_run'
require_literal 'oauth_e2e_fixture_audit'
require_literal 'oauth_e2e_fixture_ownership'
require_literal 'javac --release 17 -d "$runtime_probe_classes"'
require_literal 'docker cp "$runtime_probe_classes/." "$uid_probe_container:/runtime-probes"'
require_literal 'docker cp "$runtime_probe_classes/." "$runtime_container:/runtime-probes"'

postgres_readiness_block=$(sed -n '/^postgres_ready=false$/,/^\[\[ "$postgres_ready" == true \]\]/p' "$script")
for required_readiness_contract in \
  '--env PGPASSWORD="$runtime_db_password"' \
  '--env PGCONNECT_TIMEOUT=2' \
  'psql --host 127.0.0.1 --no-psqlrc --no-password' \
  '--username "$runtime_db_user" --dbname "$runtime_database"' \
  "--command 'select 1'"; do
  grep -F -- "$required_readiness_contract" <<<"$postgres_readiness_block" >/dev/null || {
    echo "PostgreSQL readiness must execute authenticated SQL over TCP against the requested database: $required_readiness_contract" >&2
    exit 1
  }
done

postgres_version_block=$(sed -n '/^postgres_version_number=/,/^\[\[ "$postgres_version_number" == 130004 \]\]/p' "$script")
for required_version_contract in \
  '--env PGPASSWORD="$runtime_db_password"' \
  '--env PGCONNECT_TIMEOUT=2' \
  'psql --host 127.0.0.1 --no-psqlrc --no-password' \
  '--username "$runtime_db_user" --dbname "$runtime_database"'; do
  grep -F -- "$required_version_contract" <<<"$postgres_version_block" >/dev/null || {
    echo "PostgreSQL version check must use authenticated TCP against the requested database: $required_version_contract" >&2
    exit 1
  }
done

if grep -F -- 'pg_isready' <<<"$postgres_readiness_block" >/dev/null; then
  echo 'pg_isready does not prove that the requested database exists' >&2
  exit 1
fi

if grep -F -- 'java "$listener_source"' "$script" >/dev/null ||
    grep -F -- 'java --class-path "/opt/docker/lib/*" "$source"' "$script" >/dev/null; then
  echo 'runtime probes must be precompiled on the CI runner because the runtime JRE omits jdk.compiler' >&2
  exit 1
fi

isolated_invocation='bash scripts/ci/test-backend-runtime-image.sh "$BACKEND_IMAGE" "$MIGRATION_IMAGE"'
[[ $(grep -Fc -- "$isolated_invocation" "$pipeline") -eq 3 ]] || {
  echo 'every backend image job must use an isolated disposable database for the runtime probe' >&2
  exit 1
}

if grep -F -- '--network container:' "$script" "$pipeline" >/dev/null; then
  echo 'the runtime-image probe must not depend on the GitLab job container network namespace' >&2
  exit 1
fi

if grep -Eq -- '^(JWT_SECRET|SECRET_KEY)=\$\{' "$script"; then
  echo 'the runtime-image probe must not forward ambient backend secrets' >&2
  exit 1
fi

[[ $(grep -Fc -- 'bash scripts/ci/test-backend-runtime-image-contract.sh' "$pipeline") -eq 1 ]] || {
  echo 'the runtime-image contract test must run in exactly one backend CI gate' >&2
  exit 1
}

echo 'backend runtime-image fixture-absence contract is complete'
