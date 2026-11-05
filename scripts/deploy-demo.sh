#!/bin/bash
set -euo pipefail
umask 077
[[ ${API_DIGEST-} =~ ^sha256:[a-f0-9]{64}$ && ${GATEWAY_DIGEST-} =~ ^sha256:[a-f0-9]{64}$ ]]
[[ ${DEPLOY_HOST-} =~ ^[a-zA-Z0-9.-]+$ ]]
[[ -n ${DEPLOY_SSH_KEY-} && -n ${DEPLOY_KNOWN_HOSTS-} ]]
task_dir=$(mktemp -d)
trap 'rm -rf "$task_dir"' EXIT
printf '%s\n' "$DEPLOY_SSH_KEY" > "$task_dir/key"
printf '%s\n' "$DEPLOY_KNOWN_HOSTS" > "$task_dir/known_hosts"
unset DEPLOY_SSH_KEY DEPLOY_KNOWN_HOSTS
ssh -i "$task_dir/key" -o BatchMode=yes -o IdentitiesOnly=yes -o StrictHostKeyChecking=yes \
  -o "UserKnownHostsFile=$task_dir/known_hosts" -o ConnectTimeout=15 \
  "epoca-deploy@$DEPLOY_HOST" "deploy backend $API_DIGEST $GATEWAY_DIGEST"
curl --fail --silent --show-error --retry 3 https://epoca.kozikowski.it/api/demo/health
