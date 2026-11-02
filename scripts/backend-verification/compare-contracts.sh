#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
REPO_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/../.." && pwd -P)
TOOLS_DIR="$REPO_ROOT/target/backend-verification-tools"
CONTRACT_NORMALIZER="$SCRIPT_DIR/ContractNormalizer.java"
OASDIFF_VERSION=1.28.0
OASDIFF_ROOT="$TOOLS_DIR/oasdiff/$OASDIFF_VERSION"
INSTALL_ROOT=
LOCK_ACQUIRED=false
LOCK_DIR=
WORK_DIR=
CONTROLLED_OASDIFF_CONFIG=

die() {
  printf 'Error: %s\n' "$*" >&2
  exit 1
}

sha256_file() {
  local file=$1
  if command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$file" | awk '{print $1}'
  elif command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$file" | awk '{print $1}'
  else
    die 'A SHA-256 checksum tool is required'
  fi
}

require_option_value() {
  if [ "$#" -lt 2 ] || [ -z "$2" ]; then
    die "Missing value for $1"
  fi
  case "$2" in
    --*) die "Missing value for $1" ;;
  esac
}

cleanup() {
  set +e
  if [ -n "$WORK_DIR" ]; then
    rm -rf -- "$WORK_DIR"
  fi
  if [ -n "$INSTALL_ROOT" ]; then
    rm -rf -- "$INSTALL_ROOT"
  fi
  if [ "$LOCK_ACQUIRED" = true ]; then
    rmdir -- "$LOCK_DIR"
  fi
}

trap cleanup EXIT

EXPECTED_DIR=
ACTUAL_DIR=
SEEN_EXPECTED=false
SEEN_ACTUAL=false

while [ "$#" -gt 0 ]; do
  case "$1" in
    --expected-dir)
      require_option_value "$@"
      [ "$SEEN_EXPECTED" = false ] || die 'Duplicate option: --expected-dir'
      SEEN_EXPECTED=true
      EXPECTED_DIR=$2
      shift 2
      ;;
    --actual-dir)
      require_option_value "$@"
      [ "$SEEN_ACTUAL" = false ] || die 'Duplicate option: --actual-dir'
      SEEN_ACTUAL=true
      ACTUAL_DIR=$2
      shift 2
      ;;
    *) die "Unknown option: $1" ;;
  esac
done

[ -n "$EXPECTED_DIR" ] || die '--expected-dir is required'
[ -n "$ACTUAL_DIR" ] || die '--actual-dir is required'
[ -f "$EXPECTED_DIR/openapi/openapi.yml" ] || die 'Expected OpenAPI document is missing'
[ -d "$EXPECTED_DIR/simple" ] || die 'Expected SimpleDocs directory is missing'
[ -f "$ACTUAL_DIR/openapi/openapi.yml" ] || die 'Actual OpenAPI document is missing'
[ -d "$ACTUAL_DIR/simple" ] || die 'Actual SimpleDocs directory is missing'
[ -f "$CONTRACT_NORMALIZER" ] || die 'Contract normalizer source is missing'
command -v java >/dev/null 2>&1 || die 'Java 17 is required for contract normalization'
command -v javac >/dev/null 2>&1 || die 'Javac 17 is required for contract normalization'

case "$(uname -s):$(uname -m)" in
  Linux:x86_64|Linux:amd64)
    PLATFORM=linux_amd64
    EXPECTED_ARCHIVE_SHA=e0ef076f2cf953d922addc04be9c3851cf3ec18f7678d2b94d44cea23dca51b5
    EXPECTED_EXECUTABLE_SHA=69f23d7d1899ba1bd27af6c1dd3db18c425937f7b85c0a3ad72efd01670957d2
    ;;
  Linux:aarch64|Linux:arm64)
    PLATFORM=linux_arm64
    EXPECTED_ARCHIVE_SHA=cb15a381472321ac602cc252e65018d03feba7e6449a0854e1181680444d4051
    EXPECTED_EXECUTABLE_SHA=2ac47b3efc8ba716afd52839596513ff0e7e0d273ec58842a41b779f2435decd
    ;;
  Darwin:arm64|Darwin:x86_64)
    PLATFORM=darwin_all
    EXPECTED_ARCHIVE_SHA=ff76474bf47bfb806d1711aa3e962b8e55570badcd462fa487b80aa532a823db
    EXPECTED_EXECUTABLE_SHA=1e3c6aacd8ae95f04beb904363be13b73b2c8c732f39d5a3db8b3e40aa936cc3
    ;;
  *) die 'oasdiff v1.28.0 is unsupported on this platform' ;;
esac

CACHE_DIR="$OASDIFF_ROOT/$PLATFORM"
OASDIFF="$CACHE_DIR/oasdiff"
LOCK_DIR="$OASDIFF_ROOT/.$PLATFORM.lock"

if [ -e "$TOOLS_DIR/oasdiff" ] && [ ! -d "$TOOLS_DIR/oasdiff" ]; then
  die 'Unversioned oasdiff cache is unsupported'
fi
mkdir -p "$OASDIFF_ROOT"

LOCK_ATTEMPTS=0
until mkdir "$LOCK_DIR" 2>/dev/null; do
  LOCK_ATTEMPTS=$((LOCK_ATTEMPTS + 1))
  [ "$LOCK_ATTEMPTS" -lt 30 ] || die 'Timed out waiting for the oasdiff cache lock'
  sleep 1
done
LOCK_ACQUIRED=true

authenticate_executable() {
  local executable=$1
  local source_label=$2
  local executable_sha
  local version_output

  [ -f "$executable" ] && [ -x "$executable" ] || die "$source_label oasdiff executable is missing or not executable"
  executable_sha=$(sha256_file "$executable")
  [ "$executable_sha" = "$EXPECTED_EXECUTABLE_SHA" ] || \
    die "$source_label oasdiff executable checksum mismatch"
  version_output=$("$executable" --version) || die "$source_label oasdiff version check failed"
  [ "$version_output" = 'oasdiff version 1.28.0' ] || die "$source_label oasdiff version mismatch"
}

if [ -e "$CACHE_DIR" ]; then
  [ -d "$CACHE_DIR" ] || die 'Cached oasdiff path is not a directory'
  authenticate_executable "$OASDIFF" Cached
else
  INSTALL_ROOT=$(mktemp -d "$TOOLS_DIR/oasdiff-install.XXXXXX")
  ARCHIVE="$INSTALL_ROOT/oasdiff_1.28.0_${PLATFORM}.tar.gz"
  EXTRACT_DIR="$INSTALL_ROOT/extracted"
  STAGED_DIR="$INSTALL_ROOT/staged"
  URL="https://github.com/oasdiff/oasdiff/releases/download/v1.28.0/oasdiff_1.28.0_${PLATFORM}.tar.gz"

  curl --fail --location --silent --show-error --output "$ARCHIVE" "$URL" || die 'Failed to download oasdiff v1.28.0'
  ACTUAL_ARCHIVE_SHA=$(sha256_file "$ARCHIVE")
  [ "$ACTUAL_ARCHIVE_SHA" = "$EXPECTED_ARCHIVE_SHA" ] || die 'oasdiff v1.28.0 archive checksum mismatch'
  mkdir -p "$EXTRACT_DIR" "$STAGED_DIR"
  tar -xzf "$ARCHIVE" -C "$EXTRACT_DIR"
  [ -f "$EXTRACT_DIR/oasdiff" ] || die 'oasdiff v1.28.0 archive is missing its executable'
  mv -- "$EXTRACT_DIR/oasdiff" "$STAGED_DIR/oasdiff"
  chmod +x "$STAGED_DIR/oasdiff"
  authenticate_executable "$STAGED_DIR/oasdiff" Downloaded
  mv -- "$STAGED_DIR" "$CACHE_DIR"
  authenticate_executable "$OASDIFF" Cached
fi

EXPECTED_OPENAPI="$EXPECTED_DIR/openapi/openapi.yml"
ACTUAL_OPENAPI="$ACTUAL_DIR/openapi/openapi.yml"
WORK_DIR=$(mktemp -d "${TMPDIR:-/tmp}/stack-contracts.XXXXXX")
CONTROLLED_OASDIFF_CONFIG="$WORK_DIR/oasdiff-config.yaml"
(
  umask 077
  printf '{}\n' >"$CONTROLLED_OASDIFF_CONFIG.tmp"
)
mv -- "$CONTROLLED_OASDIFF_CONFIG.tmp" "$CONTROLLED_OASDIFF_CONFIG"

if ! env -u OASDIFF_CONFIG "$OASDIFF" breaking \
  "$EXPECTED_OPENAPI" "$ACTUAL_OPENAPI" --fail-on ERR \
  --config "$CONTROLLED_OASDIFF_CONFIG"; then
  die 'OpenAPI breaking comparison failed'
fi

NORMALIZER_CLASSES="$WORK_DIR/normalizer-classes"
mkdir -p "$NORMALIZER_CLASSES"
javac -d "$NORMALIZER_CLASSES" "$CONTRACT_NORMALIZER" || die 'Contract normalizer compilation failed'

OPENAPI_DIFF="$WORK_DIR/openapi-diff.json"
env -u OASDIFF_CONFIG "$OASDIFF" diff \
  "$EXPECTED_OPENAPI" "$ACTUAL_OPENAPI" --format json \
  --config "$CONTROLLED_OASDIFF_CONFIG" >"$OPENAPI_DIFF" || \
  die 'OpenAPI semantic comparison failed'
java -cp "$NORMALIZER_CLASSES" ContractNormalizer validate-openapi-diff "$OPENAPI_DIFF" || \
  die 'OpenAPI diff contains unsupported changes'

write_simpledocs_manifest() {
  local root=$1
  local output=$2
  local normalized_root=$3
  local normalized_file
  local relative
  : >"$output"
  while IFS= read -r -d '' relative; do
    relative=${relative#"$root/"}
    normalized_file="$normalized_root/$relative"
    mkdir -p "$(dirname -- "$normalized_file")"
    java -cp "$NORMALIZER_CLASSES" ContractNormalizer normalize-simpledocs "$root/$relative" "$normalized_file" || \
      die "SimpleDocs normalization failed: $relative"
    printf '%s\t%s\n' "$(sha256_file "$normalized_file")" "$relative" >>"$output"
  done < <(find "$root" -type f -print0 | LC_ALL=C sort -z)
}

write_simpledocs_manifest "$EXPECTED_DIR/simple" "$WORK_DIR/expected.tsv" "$WORK_DIR/expected"
write_simpledocs_manifest "$ACTUAL_DIR/simple" "$WORK_DIR/actual.tsv" "$WORK_DIR/actual"
cmp -s "$WORK_DIR/expected.tsv" "$WORK_DIR/actual.tsv" || die 'SimpleDocs manifest differs'
