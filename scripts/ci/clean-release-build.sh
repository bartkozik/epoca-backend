#!/usr/bin/env bash
case "${EPOCA_CLEAN_RELEASE_BUILD:-false}" in
  false) export RELEASE_DOCKER_BUILD_FLAGS= ;;
  true)
    release_cache_root=$(mktemp -d "${CI_PROJECT_DIR:-$PWD}/.release-clean.XXXXXX") || return 1
    export COURSIER_CACHE="$release_cache_root/coursier"
    export XDG_CACHE_HOME="$release_cache_root/xdg"
    mkdir -p "$COURSIER_CACHE" "$XDG_CACHE_HOME"
    export SBT_OPTS="${SBT_OPTS:-} -Dsbt.boot.directory=$release_cache_root/boot -Dsbt.global.base=$release_cache_root/global -Dsbt.ivy.home=$release_cache_root/ivy"
    export RELEASE_DOCKER_BUILD_FLAGS='--no-cache --pull'
    ;;
  *) echo 'EPOCA_CLEAN_RELEASE_BUILD must be true or false' >&2; return 1 ;;
esac
