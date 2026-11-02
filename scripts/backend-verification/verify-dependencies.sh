#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
REPO_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/../.." && pwd -P)

die() {
  printf 'Error: %s\n' "$*" >&2
  exit 1
}

require_option_value() {
  if [ "$#" -lt 2 ] || [ -z "$2" ]; then
    die "Missing value for $1"
  fi
  case "$2" in
    --*) die "Missing value for $1" ;;
  esac
}

ACTUAL=
ACCEPTED=
POLICY=
SEEN_ACTUAL=false
SEEN_ACCEPTED=false
SEEN_POLICY=false

while [ "$#" -gt 0 ]; do
  case "$1" in
    --actual)
      require_option_value "$@"
      [ "$SEEN_ACTUAL" = false ] || die 'Duplicate option: --actual'
      SEEN_ACTUAL=true
      ACTUAL=$2
      shift 2
      ;;
    --accepted)
      require_option_value "$@"
      [ "$SEEN_ACCEPTED" = false ] || die 'Duplicate option: --accepted'
      SEEN_ACCEPTED=true
      ACCEPTED=$2
      shift 2
      ;;
    --policy)
      require_option_value "$@"
      [ "$SEEN_POLICY" = false ] || die 'Duplicate option: --policy'
      SEEN_POLICY=true
      POLICY=$2
      shift 2
      ;;
    *) die "Unknown option: $1" ;;
  esac
done

[ -n "$ACTUAL" ] || die '--actual is required'
[ -n "$ACCEPTED" ] || die '--accepted is required'
[ -n "$POLICY" ] || die '--policy is required'
[ -f "$ACTUAL" ] || die 'Actual dependency manifest is missing'
[ -f "$ACCEPTED" ] || die 'Accepted dependency manifest is missing'
[ -f "$POLICY" ] || die 'Dependency policy is missing'

MANIFEST_HEADER='configuration	organization	name	revision	evicted'
POLICY_HEADER='family	organization	name_pattern	expected_revision'
[ "$(head -n 1 "$ACTUAL")" = "$(printf '%b' "$MANIFEST_HEADER")" ] || \
  die 'Actual dependency manifest header mismatch'
[ "$(head -n 1 "$ACCEPTED")" = "$(printf '%b' "$MANIFEST_HEADER")" ] || \
  die 'Accepted dependency manifest header mismatch'
[ "$(head -n 1 "$POLICY")" = "$(printf '%b' "$POLICY_HEADER")" ] || \
  die 'Dependency policy header mismatch'

if [ -f "$REPO_ROOT/project/build.properties" ] && \
  grep -Eq '^[[:blank:]]*sbt[.]version[[:blank:]]*=[[:blank:]]*2([.]|[[:blank:]]*$)' "$REPO_ROOT/project/build.properties"; then
  die 'sbt 2 is forbidden'
fi

awk -F '\t' '
  NR == FNR {
    if (FNR == 1) next
    if (NF != 4 || $1 == "" || $2 == "" || $3 == "" || $4 == "") {
      print "Error: malformed dependency policy row" > "/dev/stderr"
      exit 1
    }
    policy_count++
    policy_org[policy_count] = $2
    policy_pattern[policy_count] = $3
    policy_revision[policy_count] = $4
    next
  }

  FNR == 1 { next }

  {
    if (NF != 5 || $1 == "" || $2 == "" || $3 == "" || $4 == "" || ($5 != "true" && $5 != "false")) {
      print "Error: malformed actual dependency row" > "/dev/stderr"
      exit 1
    }

    configuration = $1
    organization = $2
    name = $3
    revision = $4
    evicted = $5

    if (organization == "com.typesafe.akka") {
      printf "Error: com.typesafe.akka is forbidden: %s:%s:%s\n", organization, name, revision > "/dev/stderr"
      exit 1
    }

    if (name ~ /_3$/) {
      printf "Error: Scala 3 artifact is forbidden: %s:%s:%s\n", organization, name, revision > "/dev/stderr"
      exit 1
    }

    if (evicted == "false") {
      selected_key = configuration SUBSEP organization SUBSEP name
      if (selected_key in selected_revision && selected_revision[selected_key] != revision) {
        printf "Error: multiple non-evicted revisions in %s for %s:%s (%s, %s)\n", configuration, organization, name, selected_revision[selected_key], revision > "/dev/stderr"
        exit 1
      }
      selected_revision[selected_key] = revision
    }

    if (evicted == "false" && organization == "org.apache.pekko") {
      matched = 0
      for (i = 1; i <= policy_count; i++) {
        if (organization == policy_org[i] && name ~ policy_pattern[i]) {
          matched = 1
          if (revision != policy_revision[i]) {
            printf "Error: Pekko policy violation: %s:%s:%s, expected %s\n", organization, name, revision, policy_revision[i] > "/dev/stderr"
            exit 1
          }
        }
      }
      if (!matched) {
        printf "Error: Pekko policy violation: unapproved coordinate %s:%s:%s\n", organization, name, revision > "/dev/stderr"
        exit 1
      }
    }
  }

  END {
    if (policy_count == 0) {
      print "Error: dependency policy has no rows" > "/dev/stderr"
      exit 1
    }
  }
' "$POLICY" "$ACTUAL" || exit 1

cmp -s "$ACTUAL" "$ACCEPTED" || die 'resolved manifest differs from accepted manifest'
