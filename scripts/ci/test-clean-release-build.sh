#!/usr/bin/env bash
set -euo pipefail
root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
(
  EPOCA_CLEAN_RELEASE_BUILD=true CI_PROJECT_DIR="$work" source "$root/clean-release-build.sh"
  [[ "$RELEASE_DOCKER_BUILD_FLAGS" == '--no-cache --pull' ]]
  [[ "$COURSIER_CACHE" == "$work"/.release-clean.*/coursier ]]
  [[ "$SBT_OPTS" == *'-Dsbt.ivy.home='* ]]
  [[ -d "$COURSIER_CACHE" ]]
)
(
  unset EPOCA_CLEAN_RELEASE_BUILD RELEASE_DOCKER_BUILD_FLAGS
  source "$root/clean-release-build.sh"
  [[ -z "${RELEASE_DOCKER_BUILD_FLAGS:-}" ]]
)
if (EPOCA_CLEAN_RELEASE_BUILD=invalid source "$root/clean-release-build.sh"); then
  echo 'Invalid clean-build mode accepted' >&2; exit 1
fi
echo 'PASS: clean release dependency and Docker cache contracts'

# Execute the real branch image-job script against a fresh Docker CLI boundary.
# Builds model private FROM resolution; no daemon, registry or real credentials.
awk '
  /^test_backend_images:/ { job = 1; next }
  job && /^[^ ]/ { exit }
  job && /^  script:/ { script = 1; next }
  script && /^    - \|$/ { next }
  script && /^    - / { sub(/^    - /, ""); print; next }
  script && /^      / { sub(/^      /, ""); print }
' "$root/../../.gitlab-ci.yml" >"$work/image-job.sh"
[[ -s "$work/image-job.sh" ]]
for mode in false true; do
  (
    export EPOCA_CLEAN_RELEASE_BUILD="$mode" CI_PROJECT_DIR="$work"
    source "$root/clean-release-build.sh"
    export CI_REGISTRY=registry.example.invalid CI_REGISTRY_IMAGE=registry.example.invalid/project
    export CI_REGISTRY_USER=regression CI_REGISTRY_PASSWORD=synthetic-password
    export CI_COMMIT_SHA=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
    export AUTH_STATE="$work/auth-$mode" BUILD_EVENTS="$work/build-$mode" PUSH_EVENTS="$work/push-$mode"
    sbt() { :; }
    bash() { :; }
    cp() { :; }
    docker() {
      case "$1" in
        login)
          [[ "$*" == *'--password-stdin'* ]]
          [[ "$(cat)" == "$CI_REGISTRY_PASSWORD" ]]
          touch "$AUTH_STATE"
          ;;
        build)
          [[ -f "$AUTH_STATE" ]] || { echo 'private base image requires prior registry login' >&2; return 1; }
          if [[ "$EPOCA_CLEAN_RELEASE_BUILD" == true ]]; then
            [[ "$*" == *'--no-cache --pull'* ]]
          fi
          echo build >>"$BUILD_EVENTS"
          ;;
        inspect) printf '%s\n' "$CI_COMMIT_SHA" ;;
        tag) : ;;
        push) echo push >>"$PUSH_EVENTS" ;;
        *) echo 'unexpected Docker operation' >&2; return 1 ;;
      esac
    }
    source "$work/image-job.sh"
    [[ $(wc -l <"$BUILD_EVENTS") -eq 2 ]]
    if [[ "$mode" == true ]]; then
      [[ $(wc -l <"$PUSH_EVENTS") -eq 2 ]]
    else
      [[ ! -e "$PUSH_EVENTS" ]]
    fi
  )
done
echo 'PASS: branch image jobs authenticate before private builds in normal and clean modes'
