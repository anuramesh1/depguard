#!/usr/bin/env bash
# Manages DepGuard logins in the Cognito user pool (created by scripts/deploy.sh).
#
#   scripts/users.sh add <username> VIEWER|MAINTAINER   asks for a password (min 14 chars, upper, lower, digit)
#   scripts/users.sh remove <username>
#   scripts/users.sh list
#
# The password is read without echo (or from the DEPGUARD_PASSWORD environment variable, for automation) and is
# never stored by this script. VIEWER: scan, triage, search, propose upgrades. MAINTAINER: also approve upgrade PRs.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TF_DIR="$ROOT/infra/serverless"
REGION=$(sed -n 's/^region = "\(.*\)"/\1/p' "$TF_DIR/terraform.tfvars" 2>/dev/null || true); REGION="${AWS_REGION:-${REGION:-us-east-1}}"
fail() { printf '\033[31mError: %s\033[0m\n' "$*" >&2; exit 1; }

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

POOL=$(terraform -chdir="$TF_DIR" output -raw cognito_user_pool_id 2>/dev/null) || fail "DepGuard isn't deployed (run scripts/deploy.sh)."

case "${1:-}" in
  add)
    user="${2:-}"; group="${3:-}"
    [[ -n "$user" && ( "$group" == "VIEWER" || "$group" == "MAINTAINER" ) ]] || fail "Usage: scripts/users.sh add <username> VIEWER|MAINTAINER"
    password="${DEPGUARD_PASSWORD:-}"
    if [[ -z "$password" ]]; then read -r -s -p "Password for $user: " password; echo; fi
    aws cognito-idp admin-create-user --region "$REGION" --user-pool-id "$POOL" --username "$user" \
      --message-action SUPPRESS >/dev/null
    # Permanent password: no "change it at first login" step for a demo user.
    SECRET_VALUE="$password" python3 -c 'import json,os,sys; print(json.dumps({"UserPoolId": sys.argv[1], "Username": sys.argv[2],
      "Password": os.environ["SECRET_VALUE"], "Permanent": True}))' "$POOL" "$user" \
      | aws_json cognito-idp admin-set-user-password --region "$REGION"
    aws cognito-idp admin-add-user-to-group --region "$REGION" --user-pool-id "$POOL" --username "$user" --group-name "$group"
    echo "Added $user as $group."
    ;;
  remove)
    [[ -n "${2:-}" ]] || fail "Usage: scripts/users.sh remove <username>"
    aws cognito-idp admin-delete-user --region "$REGION" --user-pool-id "$POOL" --username "$2"
    echo "Removed $2."
    ;;
  list)
    for group in VIEWER MAINTAINER; do
      echo "$group: $(aws cognito-idp list-users-in-group --region "$REGION" --user-pool-id "$POOL" --group-name "$group" \
        --query 'Users[].Username' --output text)"
    done
    ;;
  *) fail "Usage: scripts/users.sh add <username> VIEWER|MAINTAINER | remove <username> | list" ;;
esac
