#!/usr/bin/env bash
set -euo pipefail

: "${RUNNER_TEMP:?GitHub runner temporary directory is required}"
: "${GITHUB_PATH:?GitHub path file is required}"

version=2.0.12
checksum=73f9c4a10584cde3d37d773e2b38d2fc4348c6cef8d88da8b7dd41e7d5f614d7
fingerprint=2EE0EA64E40A89B84B2DF73499E82A75642AC823
repository=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
staging=$(mktemp -d "$RUNNER_TEMP/sbt-download.XXXXXX")
installation=$(mktemp -d "$RUNNER_TEMP/sbt-verified.XXXXXX")
trap 'rm -rf -- "$staging"' EXIT
mkdir -m 700 "$staging/gnupg"

for suffix in zip zip.asc; do
  curl --fail --silent --show-error --location --retry 3 --connect-timeout 20 --max-time 120 \
    --output "$staging/sbt.$suffix" "https://github.com/sbt/sbt/releases/download/v$version/sbt-$version.$suffix"
done
actual_checksum=$(shasum -a 256 "$staging/sbt.zip" | awk '{print $1}')
[[ "$actual_checksum" == "$checksum" ]]
gpg --homedir "$staging/gnupg" --batch --import "$repository/.github/keys/sbt-release.asc"
actual_fingerprint=$(gpg --homedir "$staging/gnupg" --batch --with-colons --list-keys | awk -F: '$1 == "fpr" {print $10; exit}')
[[ "$actual_fingerprint" == "$fingerprint" ]]
gpg --homedir "$staging/gnupg" --batch --no-auto-key-retrieve --verify "$staging/sbt.zip.asc" "$staging/sbt.zip"
unzip -q "$staging/sbt.zip" -d "$installation"
test -x "$installation/sbt/bin/sbt"
printf '%s\n' "$installation/sbt/bin" >> "$GITHUB_PATH"
