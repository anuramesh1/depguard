#!/usr/bin/env bash
# Deploys DepGuard to AWS as a serverless app: API Gateway (HTTP API) -> Lambda (Java 21, SnapStart).
# Re-run it any time to ship a new version of the code.
#
# Cost: about $0.50/month while idle (Aurora storage + secret); ~$0.00003 per scan, ~$0.015 per AI triage. Run scripts/teardown.sh to remove it all.
#
# Usage:   scripts/deploy.sh
# Options (environment variables):
#   AWS_PROFILE   which AWS credentials to use (default: your default profile)
#   AWS_REGION    region to deploy to (default: us-east-1)
#   SKIP_TESTS=1  skip the test suite during the build (not recommended)
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TF_DIR="$ROOT/infra/serverless"
PACKAGE="$ROOT/target/depguard-lambda.zip"
REGION="${AWS_REGION:-us-east-1}"

step() { printf '\n\033[1m==> %s\033[0m\n' "$*"; }
fail() { printf '\033[31mError: %s\033[0m\n' "$*" >&2; exit 1; }

# ---------------------------------------------------------------------------
step "1/4 Checking prerequisites"
for tool in aws terraform curl java; do
  command -v "$tool" >/dev/null || fail "$tool is not installed"
done
java -version >/dev/null 2>&1 || fail "No working JDK. Install one: brew install --cask temurin@21"
ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text 2>/dev/null) \
  || fail "No AWS credentials. Run 'aws configure' (access keys) or 'aws sso login' (SSO), or set AWS_PROFILE."
echo "AWS account ****${ACCOUNT_ID: -4}, region $REGION"   # masked: don't print full account IDs

# Secrets go from the gitignored .env straight into SSM Parameter Store (SecureString, free): never into Terraform
# files, Terraform state, or the Lambda configuration. The app reads them at startup (SsmParametersPostProcessor).
# Only the lines named below are read from .env.
env_value() { [[ -f "$ROOT/.env" ]] && sed -n "s/^$1=//p" "$ROOT/.env" | tail -1 | sed -E "s/^[\"'](.*)[\"']$/\\1/"; }
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

put_secret() {  # put_secret <parameter name under /depguard/> <value>; prints the parameter's version
  [[ -z "$2" ]] && return 0
  # The value goes in as JSON through a private temp file, never as a command-line argument (visible in the process list).
  SECRET_VALUE="$2" python3 -c 'import json,os,sys; print(json.dumps({"Name": sys.argv[1], "Value": os.environ["SECRET_VALUE"],
    "Type": "SecureString", "Overwrite": True}))' "/depguard/$1" \
    | aws_json ssm put-parameter --region "$REGION" --query Version --output text >/dev/null
  aws ssm get-parameter --region "$REGION" --name "/depguard/$1" --query Parameter.Version --output text
}
v1=$(put_secret langfuse/public-key "$(env_value LANGFUSE_PUBLIC_KEY)")
v2=$(put_secret langfuse/secret-key "$(env_value LANGFUSE_SECRET_KEY)")
v3=$(put_secret github/token "$(env_value GITHUB_TOKEN)")
# Version numbers only (no secrets): a changed secret changes this, which publishes a new SnapStart version.
export TF_VAR_secrets_version="lfpk:${v1:-0},lfsk:${v2:-0},gh:${v3:-0}"
host="$(env_value LANGFUSE_HOST)"; [[ -z "$host" ]] && host="$(env_value LANGFUSE_BASE_URL)"
[[ -n "$host" ]] && export TF_VAR_langfuse_host="$host"
email="$(env_value ALERT_EMAIL)"; [[ -n "$email" ]] && export TF_VAR_alert_email="$email"
repo="$(env_value DEPGUARD_GITHUB_REPOSITORY)"; [[ -n "$repo" ]] && export TF_VAR_github_repository="$repo"
echo "Secrets in SSM: Langfuse ${v1:+on}${v1:-off}, GitHub token ${v3:+on}${v3:-off} · alerts email: ${email:+set}${email:-none}"

# Terraform reads terraform.tfvars automatically, so teardown.sh reuses the same region.
# The file is gitignored (*.tfvars).
echo "region = \"$REGION\"" > "$TF_DIR/terraform.tfvars"

# ---------------------------------------------------------------------------
step "2/4 Building the Lambda package (runs the tests first)"
# clean: target/lib would otherwise keep jars from older builds and bloat the zip
MVN_ARGS=(-B -q -Plambda clean package)
[[ "${SKIP_TESTS:-}" == "1" ]] && MVN_ARGS+=(-DskipTests)
(cd "$ROOT" && ./mvnw "${MVN_ARGS[@]}")
[[ -f "$PACKAGE" ]] || fail "Build did not produce $PACKAGE"
echo "$(basename "$PACKAGE") ($(du -h "$PACKAGE" | cut -f1))"

# ---------------------------------------------------------------------------
step "3/4 Creating/updating AWS resources (SnapStart adds 1-2 minutes per new version)"
terraform -chdir="$TF_DIR" init -input=false >/dev/null
terraform -chdir="$TF_DIR" apply -input=false -auto-approve -var "package_path=$PACKAGE"

URL=$(terraform -chdir="$TF_DIR" output -raw url)
VERSION=$(terraform -chdir="$TF_DIR" output -raw live_version)

# ---------------------------------------------------------------------------
step "4/4 Checking the deployed app"
for _ in $(seq 1 24); do
  if curl -fsS --max-time 30 "$URL/actuator/health" >/dev/null 2>&1; then break; fi
  sleep 5
done
curl -fsS --max-time 30 "$URL/actuator/health" >/dev/null \
  || fail "Health check failed at $URL/actuator/health. Logs: aws logs tail /aws/lambda/depguard --since 15m --region $REGION"

cat <<EOF

DepGuard is live: $URL   (Lambda version $VERSION)

Every endpoint except /actuator/health needs a login. Create users first (below), then:
  TOKEN=\$(scripts/token.sh alice)
Scan (no model, almost free):
  curl -s -X POST $URL/api/scans -H "Authorization: Bearer \$TOKEN" -H 'Content-Type: application/xml' --data-binary @samples/vulnerable-pom.xml

Knowledge base (first deploy only, ~4-10 min, ~\$0.05): fill Aurora with advisories + migration guides:
  scripts/ingest.sh aws

Users (first deploy): a VIEWER and a MAINTAINER (asks for each password, never stored):
  scripts/users.sh add alice VIEWER
  scripts/users.sh add bob MAINTAINER

AI triage (needs a login; about \$0.006 of Bedrock tokens per call):
  TOKEN=\$(scripts/token.sh alice)
  curl -s -X POST $URL/api/triage -H "Authorization: Bearer \$TOKEN" -H 'Content-Type: application/xml' --data-binary @samples/vulnerable-pom.xml

Upgrade pull request (alice proposes, bob approves: "four eyes"):
  curl -s -X POST $URL/api/upgrades -H "Authorization: Bearer \$TOKEN"
  curl -s -X POST $URL/api/upgrades/<id>/approve -H "Authorization: Bearer \$(scripts/token.sh bob)"

Logs:      aws logs tail /aws/lambda/depguard --follow --region $REGION
Cost:      about \$0.50/month while idle (Aurora storage + its secret; compute pauses). Throttled (5 req/s), at most 5 Lambdas at once.
Alarms:    if ALERT_EMAIL is set, confirm the subscription email AWS sends you.
Tear down: scripts/teardown.sh
EOF
