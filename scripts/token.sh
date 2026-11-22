#!/usr/bin/env bash
# Prints a Cognito access token for a DepGuard user (valid 60 minutes), for curl's Authorization header:
#
#   TOKEN=$(scripts/token.sh alice)
#   curl -H "Authorization: Bearer $TOKEN" ...
#
# Asks for the password without echo (or reads DEPGUARD_PASSWORD). Treat the token like a password until it expires.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TF_DIR="$ROOT/infra/serverless"
REGION=$(sed -n 's/^region = "\(.*\)"/\1/p' "$TF_DIR/terraform.tfvars" 2>/dev/null || true); REGION="${AWS_REGION:-${REGION:-us-east-1}}"

# Runs an AWS CLI command with its JSON input read from stdin. The AWS CLI can't read stdin as a file on macOS, so the
# JSON goes into a temp file only you can read, deleted right after. Secrets never appear on a command line.
aws_json() {
  local file rc=0
  file=$(umask 077; mktemp)
  cat > "$file"
  aws "$@" --cli-input-json "file://$file" || rc=$?
  rm -f "$file"
  return $rc
}

user="${1:?Usage: scripts/token.sh <username>}"
CLIENT=$(terraform -chdir="$TF_DIR" output -raw cognito_client_id)
password="${DEPGUARD_PASSWORD:-}"
if [[ -z "$password" ]]; then read -r -s -p "Password for $user: " password; echo >&2; fi

SECRET_VALUE="$password" python3 -c 'import json,os,sys; print(json.dumps({"ClientId": sys.argv[1], "AuthFlow": "USER_PASSWORD_AUTH",
  "AuthParameters": {"USERNAME": sys.argv[2], "PASSWORD": os.environ["SECRET_VALUE"]}}))' "$CLIENT" "$user" \
  | aws_json cognito-idp initiate-auth --region "$REGION" \
      --query AuthenticationResult.AccessToken --output text
