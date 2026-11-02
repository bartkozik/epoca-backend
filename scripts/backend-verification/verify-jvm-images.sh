#!/usr/bin/env bash

set -euo pipefail

die() {
  printf 'Error: %s\n' "$*" >&2
  exit 1
}

[ -n "${EPOCA_BUILDER_IMAGE:-}" ] || {
  printf '%s\n' 'EPOCA_BUILDER_IMAGE is required' >&2
  exit 1
}
[ -n "${EPOCA_RUNTIME_IMAGE:-}" ] || die 'EPOCA_RUNTIME_IMAGE is required'

DIGEST_PATTERN='^[^[:space:]@]+@sha256:[0-9a-fA-F]{64}$'
[[ "$EPOCA_BUILDER_IMAGE" =~ $DIGEST_PATTERN ]] || die 'EPOCA_BUILDER_IMAGE must be name@sha256:<64hex>'
[[ "$EPOCA_RUNTIME_IMAGE" =~ $DIGEST_PATTERN ]] || die 'EPOCA_RUNTIME_IMAGE must be name@sha256:<64hex>'
command -v docker >/dev/null 2>&1 || die 'docker is required'
IMAGE_PLATFORM='linux/amd64'

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
REPO_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/../.." && pwd -P)
EVIDENCE_DIR="$REPO_ROOT/target/backend-verification/images"
mkdir -p "$EVIDENCE_DIR"

read_java_property() {
  local property_name=$1
  awk -v expected_name="$property_name" '
    {
      separator = index($0, "=")
      if (separator == 0) next
      name = substr($0, 1, separator - 1)
      value = substr($0, separator + 1)
      sub(/^[[:space:]]*/, "", name)
      sub(/[[:space:]]*$/, "", name)
      sub(/^[[:space:]]*/, "", value)
      sub(/[[:space:]]*$/, "", value)
      if (name == expected_name) print value
    }
  '
}

verify_image() {
  local label=$1
  local image=$2
  local image_platform
  local repo_digests
  local java_version
  local java_properties
  local java_runtime_version
  local java_vendor

  docker pull --platform "$IMAGE_PLATFORM" "$image" >/dev/null || die "$label image pull failed"
  image_platform=$(docker image inspect --format '{{.Os}}/{{.Architecture}}' "$image") || \
    die "$label image platform inspection failed"
  [ "$image_platform" = "$IMAGE_PLATFORM" ] || \
    die "$label image platform must equal $IMAGE_PLATFORM (got $image_platform)"
  repo_digests=$(docker image inspect --format '{{range .RepoDigests}}{{println .}}{{end}}' "$image") || \
    die "$label image inspection failed"
  printf '%s\n' "$repo_digests" | grep -Fx -- "$image" >/dev/null || \
    die "$label image RepoDigests do not contain the requested digest"

  java_version=$(docker run --rm --platform "$IMAGE_PLATFORM" --entrypoint java "$image" -version 2>&1) || \
    die "$label image java -version failed"
  java_properties=$(docker run --rm --platform "$IMAGE_PLATFORM" --entrypoint java "$image" -XshowSettings:properties -version 2>&1) || \
    die "$label image Java property inspection failed"

  java_runtime_version=$(printf '%s\n' "$java_properties" | read_java_property java.runtime.version)
  java_vendor=$(printf '%s\n' "$java_properties" | read_java_property java.vendor)

  [ "$java_runtime_version" = '17.0.20+8' ] || \
    die "$label image java.runtime.version must equal 17.0.20+8"
  case "$java_vendor" in
    'Eclipse Adoptium'|Temurin) ;;
    *) die "$label image java.vendor must be Eclipse Adoptium or Temurin" ;;
  esac

  printf '%s\n' "$image" >"$EVIDENCE_DIR/$label.reference.txt"
  printf '%s\n' "$image_platform" >"$EVIDENCE_DIR/$label.platform.txt"
  printf '%s\n' "$repo_digests" >"$EVIDENCE_DIR/$label.repo-digests.txt"
  printf '%s\n%s\n' "$java_version" "$java_properties" >"$EVIDENCE_DIR/$label.java-version.txt"
}

verify_builder_tooling() {
  local image=$1
  local tooling

  tooling=$(docker run --rm --platform "$IMAGE_PLATFORM" --entrypoint bash "$image" -euo pipefail -c '
    test "$(sbt --script-version)" = 1.12.14
    javap -verbose java.lang.String | grep -F "major version: 61"
    javac -version 2>&1 | grep -Fx "javac 17.0.20"
    command -v git
    git --version
    command -v docker
    docker --version
  ') || die 'builder image tooling verification failed'

  printf '%s\n' "$tooling" >"$EVIDENCE_DIR/builder.tooling.txt"
}

verify_runtime_prerequisites() {
  local image=$1
  local prerequisites

  prerequisites=$(docker run --rm --platform "$IMAGE_PLATFORM" --entrypoint bash "$image" -euo pipefail -c '
    command -v java
    command -v bash
    command -v fc-list
    test -r /etc/ssl/certs/ca-certificates.crt
  ') || die 'runtime image prerequisite verification failed'

  printf '%s\n' "$prerequisites" >"$EVIDENCE_DIR/runtime.prerequisites.txt"
}

verify_image builder "$EPOCA_BUILDER_IMAGE"
verify_image runtime "$EPOCA_RUNTIME_IMAGE"
verify_builder_tooling "$EPOCA_BUILDER_IMAGE"
verify_runtime_prerequisites "$EPOCA_RUNTIME_IMAGE"
