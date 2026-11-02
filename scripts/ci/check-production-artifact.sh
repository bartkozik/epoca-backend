#!/usr/bin/env bash
# Check the complete staged runtime classpath, not only the application JAR.
set -euo pipefail
stage=${1:?usage: check-production-artifact.sh STAGE_DIRECTORY}
[[ -d "$stage" ]] || { echo 'stage directory is missing' >&2; exit 1; }
stage=$(cd "$stage" && pwd)
tmp=$(mktemp -d)
trap 'rm -rf -- "$tmp"' EXIT
count=0
while IFS= read -r -d '' archive; do
  count=$((count + 1))
  entries=$(jar tf "$archive")
  if printf '%s\n' "$entries" | grep -E '(^|/)pl/iterators/epoca/oauth/fixture/|(^|/)OAuthE2E(Main|ApplicationLoader)|(^|/)oauth-e2e\.conf$|(^|/)pl/iterators/epoca/invitation/InvitationBrowser' >/dev/null; then
    echo "fixture tooling must not be packaged in production: $archive" >&2
    exit 1
  fi
  for config in application.conf reference.conf; do
    if printf '%s\n' "$entries" | grep -Fx "$config" >/dev/null; then
      (cd "$tmp" && jar xf "$archive" "$config")
      if grep -Eq 'e2eFixture|OAUTH_E2E_FIXTURE_' "$tmp/$config"; then
        echo "fixture tooling must not be packaged in production: $archive ($config)" >&2
        exit 1
      fi
      rm "$tmp/$config"
    fi
  done
done < <(find "$stage" -type f -name '*.jar' -print0)
[[ $count -gt 0 ]] || { echo 'no staged runtime JARs found' >&2; exit 1; }
echo "production artifact boundary passed ($count JARs)"
