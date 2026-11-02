#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
fixture_root=$(mktemp -d)
trap 'rm -rf "$fixture_root"' EXIT

mkdir -p "$fixture_root/scripts/ci" "$fixture_root/deploy" "$fixture_root/src/main/resources/db"
cp "$repo_root/scripts/ci/test-staging-release-ci.sh" "$fixture_root/scripts/ci/"
cp "$repo_root/deploy/staging-release.env" "$fixture_root/deploy/"
cp "$repo_root/src/main/resources/db/Dockerfile" "$fixture_root/src/main/resources/db/"
cp "$repo_root/.gitlab-ci.yml" "$fixture_root/"

# Make grep produce more output than the pipe buffer. With `set -o pipefail`,
# `grep | head -1` can then fail with SIGPIPE even though a match exists.
for ((i = 0; i < 100000; i++)); do
  printf '%s\n' '    - docker push "$BACKEND_IMAGE"' >> "$fixture_root/.gitlab-ci.yml"
done

output=$(bash "$fixture_root/scripts/ci/test-staging-release-ci.sh" 2>&1)
grep -Fq 'PASS: staging release CI contract' <<< "$output"

echo 'PASS: staging release CI contract is pipefail-safe'
