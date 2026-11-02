#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "$0")/../.." && pwd)
check="$root/scripts/ci/check-production-artifact.sh"
tmp=$(mktemp -d)
trap 'rm -rf -- "$tmp"' EXIT
mkdir -p "$tmp/stage/lib" "$tmp/content"
printf 'ordinary runtime resource\n' > "$tmp/content/README"
jar cf "$tmp/stage/lib/runtime.jar" -C "$tmp/content" .
bash "$check" "$tmp/stage"
for forbidden in pl/iterators/epoca/oauth/fixture/Control.class oauth-e2e.conf application.conf pl/iterators/epoca/invitation/InvitationBrowserTestMain.class pl/iterators/epoca/invitation/InvitationBrowserApplicationLoader.class; do
  mkdir -p "$tmp/content/$(dirname "$forbidden")"
  printf 'oauth.e2eFixture.timeEnabled = false\n' > "$tmp/content/$forbidden"
  jar cf "$tmp/stage/lib/extra-dependency.jar" -C "$tmp/content" .
  if bash "$check" "$tmp/stage" >"$tmp/output" 2>&1; then
    echo "artifact check accepted forbidden entry: $forbidden" >&2
    exit 1
  fi
  grep -F 'fixture tooling must not be packaged' "$tmp/output" >/dev/null
  rm "$tmp/content/$forbidden" "$tmp/stage/lib/extra-dependency.jar"
done
rm "$tmp/stage/lib/runtime.jar"
if bash "$check" "$tmp/stage" >/dev/null 2>&1; then
  echo 'artifact check accepted an empty stage' >&2
  exit 1
fi
echo 'production artifact boundary sensitivity checks passed'
