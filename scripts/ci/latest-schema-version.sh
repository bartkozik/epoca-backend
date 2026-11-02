#!/bin/sh
# Prints the highest versioned migration number, e.g. 37 for V37__*.sql.
set -eu

repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
ls "$repo_root/src/main/resources/db/migration" | sed -n 's/^V\([0-9][0-9]*\)__.*/\1/p' | sort -n | tail -n 1
