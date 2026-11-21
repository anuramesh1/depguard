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
  || fail "No AWS credentials. Run 'aws sso login' (or 'aws configure'), or set AWS_PROFILE."
echo "AWS account ****${ACCOUNT_ID: -4}, region $REGION"   # masked: don't print full account IDs

# Langfuse tracing (optional): pass the keys from the gitignored .env to Terraform as TF_VAR_* environment
# variables, so they are never typed on a command line or written to a .tf file. Only these 3 lines are read.
if [[ -f "$ROOT/.env" ]]; then
  # Last value for a key, without surrounding quotes (Langfuse's setup snippet quotes them).
  env_value() { sed -n "s/^$1=//p" "$ROOT/.env" | tail -1 | sed -E "s/^[\"'](.*)[\"']$/\\1/"; }
  export TF_VAR_langfuse_public_key="$(env_value LANGFUSE_PUBLIC_KEY)"
  export TF_VAR_langfuse_secret_key="$(env_value LANGFUSE_SECRET_KEY)"
  host="$(env_value LANGFUSE_HOST)"; [[ -z "$host" ]] && host="$(env_value LANGFUSE_BASE_URL)"
  [[ -n "$host" ]] && export TF_VAR_langfuse_host="$host"
fi
[[ -n "${TF_VAR_langfuse_public_key:-}" ]] && echo "Langfuse tracing: on" || echo "Langfuse tracing: off (no keys in .env)"

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

Scan (free, no key):
  curl -s -X POST $URL/api/scans -H 'Content-Type: application/xml' --data-binary @samples/vulnerable-pom.xml

Knowledge base (first deploy only, ~4-10 min, ~\$0.05): fill Aurora with advisories + migration guides:
  scripts/ingest.sh aws

AI triage (needs the API key; each call costs about \$0.015 in Bedrock tokens):
  KEY=\$(terraform -chdir=infra/serverless output -raw api_key)
  curl -s -X POST $URL/api/triage -H "X-Api-Key: \$KEY" -H 'Content-Type: application/xml' --data-binary @samples/vulnerable-pom.xml

Logs:      aws logs tail /aws/lambda/depguard --follow --region $REGION
Cost:      about \$0.50/month while idle (Aurora storage + its secret; compute pauses). The URL is public but throttled (5 req/s); /api/triage needs the key.
Tear down: scripts/teardown.sh
EOF
