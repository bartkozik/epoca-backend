#!/usr/bin/env bash

set -euo pipefail

fail() {
  printf 'FAIL: %s\n' "$*" >&2
  exit 1
}

assert_eq() {
  local expected=$1
  local actual=$2
  local label=${3:-values differ}

  if [ "$expected" != "$actual" ]; then
    fail "$label (expected '$expected', got '$actual')"
  fi
}

assert_contains() {
  local haystack=$1
  local needle=$2
  local label=${3:-expected text was not found}

  case "$haystack" in
    *"$needle"*) ;;
    *) fail "$label (missing '$needle')" ;;
  esac
}

assert_not_contains() {
  local haystack=$1
  local needle=$2
  local label=${3:-unexpected text was found}

  case "$haystack" in
    *"$needle"*) fail "$label (found '$needle')" ;;
    *) ;;
  esac
}

assert_status() {
  local expected=$1
  local actual=$2
  local label=${3:-unexpected exit status}

  assert_eq "$expected" "$actual" "$label"
}

pass() {
  printf 'PASS: %s\n' "$*"
}
