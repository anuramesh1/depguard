#!/usr/bin/env bash
# Deletes everything scripts/deploy.sh created in AWS.
# Safe to run more than once. Your code and local files are not touched.
#
# Usage:  scripts/teardown.sh          (asks for confirmation)
#         scripts/teardown.sh --yes    (no prompt)
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TF_DIR="$ROOT/infra/serverless"

step() { printf '\n\033[1m==> %s\033[0m\n' "$*"; }
fail() { printf '\033[31mError: %s\033[0m\n' "$*" >&2; exit 1; }

for tool in aws terraform; do
  command -v "$tool" >/dev/null || fail "$tool is not installed"
done

if [[ ! -f "$TF_DIR/terraform.tfstate" ]] || [[ -z "$(terraform -chdir="$TF_DIR" state list 2>/dev/null)" ]]; then
  echo "Nothing is deployed (Terraform state is empty). Nothing to do."
  exit 0
fi
[[ -f "$TF_DIR/terraform.tfvars" ]] || fail "infra/serverless/terraform.tfvars is missing; it records the region deploy.sh used."

aws sts get-caller-identity >/dev/null 2>&1 \
  || fail "No AWS credentials. Run 'aws configure' (access keys) or 'aws sso login' (SSO), or set AWS_PROFILE."

step "These resources will be DELETED"
terraform -chdir="$TF_DIR" state list | sed 's/^/  /'

if [[ "${1:-}" != "--yes" ]]; then
  echo
  read -r -p "Type 'destroy' to delete them: " answer
  [[ "$answer" == "destroy" ]] || { echo "Cancelled. Nothing was deleted."; exit 1; }
fi

step "Destroying (usually under a minute)"
terraform -chdir="$TF_DIR" init -input=false >/dev/null
# The package path is only needed to plan an update; any existing file satisfies it during destroy.
terraform -chdir="$TF_DIR" destroy -input=false -auto-approve -var "package_path=$TF_DIR/versions.tf"

if [[ -n "$(terraform -chdir="$TF_DIR" state list 2>/dev/null)" ]]; then
  fail "Some resources are still in the state. Re-run scripts/teardown.sh."
fi

# The secrets live outside Terraform (deploy.sh writes them, so their values never enter Terraform state).
step "Deleting the /depguard/ secrets from SSM Parameter Store"
REGION=$(sed -n 's/^region = "\(.*\)"/\1/p' "$TF_DIR/terraform.tfvars")
names=$(aws ssm get-parameters-by-path --region "$REGION" --path /depguard/ --recursive \
  --query 'Parameters[].Name' --output text)
if [[ -n "$names" && "$names" != "None" ]]; then
  # shellcheck disable=SC2086  # word splitting is intended: one argument per parameter name
  aws ssm delete-parameters --region "$REGION" --names $names --query 'DeletedParameters' --output text
else
  echo "  none found"
fi

echo
echo "All DepGuard resources are deleted."
