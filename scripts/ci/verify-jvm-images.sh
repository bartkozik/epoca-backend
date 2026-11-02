#!/usr/bin/env bash

set -euo pipefail

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
exec bash "$script_dir/../backend-verification/verify-jvm-images.sh" "$@"
