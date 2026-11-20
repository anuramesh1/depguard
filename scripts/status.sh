#!/usr/bin/env bash
# Shows whether DepGuard is currently deployed to AWS, and whether it's healthy.
# Usage: scripts/status.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TF_DIR="$ROOT/infra/serverless"

if [[ ! -f "$TF_DIR/terraform.tfstate" ]] || [[ -z "$(terraform -chdir="$TF_DIR" state list 2>/dev/null)" ]]; then
  echo "Not deployed."
  exit 0
fi

COUNT=$(terraform -chdir="$TF_DIR" state list | wc -l | tr -d ' ')
URL=$(terraform -chdir="$TF_DIR" output -raw url 2>/dev/null || echo "(no URL yet)")
VERSION=$(terraform -chdir="$TF_DIR" output -raw live_version 2>/dev/null || echo "?")

echo "DEPLOYED: $COUNT resources, Lambda version $VERSION. About \$0 while idle; public URL, throttled."
echo "URL:      $URL"
if [[ "$URL" == http* ]] && curl -fsS --max-time 30 "$URL/actuator/health" >/dev/null 2>&1; then
  echo "Health:   UP"
else
  echo "Health:   not reachable. Logs: aws logs tail /aws/lambda/depguard --since 15m"
fi
echo "Tear down with: scripts/teardown.sh"
