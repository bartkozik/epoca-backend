#!/bin/sh
set -eu

repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
verifier="$repo_root/scripts/ci/verify-backend-deploy-trigger.sh"
test_root=$(mktemp -d)
trap 'rm -rf -- "$test_root"' 0 HUP INT TERM

[ -f "$verifier" ] || {
  echo "RED: generic backend deploy trigger verifier is missing" >&2
  exit 1
}

expect_failure() {
  description=$1
  file=$2
  if sh "$verifier" "$file" >"$test_root/failure.out" 2>&1; then
    echo "verifier unexpectedly accepted $description" >&2
    exit 1
  fi
}

cp "$repo_root/.gitlab-ci.yml" "$test_root/valid.yml"
sh "$verifier" "$test_root/valid.yml" >/dev/null

sed 's|projects/717/trigger/pipeline|projects/718/trigger/pipeline|' "$test_root/valid.yml" >"$test_root/wrong-project.yml"
expect_failure "a different downstream project" "$test_root/wrong-project.yml"

awk '{ print } /variables\[DEPLOY_PHASE\]/ { print "      --form-string \"variables[OAUTH_E2E_FIXTURE_TIME_ENABLED]=true\"" }' \
  "$test_root/valid.yml" >"$test_root/fixture-variable.yml"
expect_failure "a fixture capability" "$test_root/fixture-variable.yml"

sed 's/variables\[DEPLOY_PHASE\]/variables[UNREVIEWED]/' "$test_root/valid.yml" >"$test_root/unknown-variable.yml"
expect_failure "an unreviewed downstream variable" "$test_root/unknown-variable.yml"

sed 's|curlimages/curl:8.14.1@sha256:[0-9a-f]*|curlimages/curl:8.14.1|' \
  "$test_root/valid.yml" >"$test_root/mutable-image.yml"
expect_failure "a mutable deploy image" "$test_root/mutable-image.yml"

sed '/^  dependencies: \[\]$/d' "$test_root/valid.yml" >"$test_root/artifact-dependencies.yml"
expect_failure "implicit artifact downloads" "$test_root/artifact-dependencies.yml"

sed 's|\. deploy/staging-release.env|source deploy/staging-release.env|' \
  "$test_root/valid.yml" >"$test_root/bash-source.yml"
expect_failure "the non-POSIX source builtin" "$test_root/bash-source.yml"

awk '{ print } /https:\/\/gitlab\.iterato\.rs\/api\/v4\/projects\/717\/trigger\/pipeline/ { print "    - bash scripts/ci/runtime-check.sh" }' \
  "$test_root/valid.yml" >"$test_root/bash-runtime.yml"
expect_failure "a Bash runtime dependency" "$test_root/bash-runtime.yml"

awk '{ print } /https:\/\/gitlab\.iterato\.rs\/api\/v4\/projects\/717\/trigger\/pipeline/ { print "    - apk add curl --upgrade" }' \
  "$test_root/valid.yml" >"$test_root/package-install.yml"
expect_failure "runtime package installation" "$test_root/package-install.yml"

cp "$test_root/valid.yml" "$test_root/duplicate-trigger.yml"
printf '%s\n' '      https://gitlab.company-5897fee414.example.invalid/api/v4/projects/717/trigger/pipeline' >>"$test_root/duplicate-trigger.yml"
expect_failure "a duplicate downstream trigger" "$test_root/duplicate-trigger.yml"

ln -s "$test_root/valid.yml" "$test_root/symlink.yml"
expect_failure "a symlinked pipeline" "$test_root/symlink.yml"

echo "PASS: backend deploy trigger is portable and closed to project 717"
