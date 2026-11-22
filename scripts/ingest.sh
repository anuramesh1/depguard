#!/usr/bin/env bash
# Fills or refreshes DepGuard's RAG knowledge base: every OSV Maven advisory plus the configured
# migration guides, embedded with Amazon Titan Text Embeddings v2.
#
# Re-runs are cheap: only new or changed chunks are embedded. First full run: ~7,350 chunks,
# about 4-10 minutes and ~$0.05 of Bedrock embeddings.
#
# Usage:
#   scripts/ingest.sh local [max]   into the docker-compose Postgres (start it: docker compose up -d postgres)
#   scripts/ingest.sh aws [max]     into the deployed Aurora cluster, through the RDS Data API
#                                   (run scripts/deploy.sh first)
#   max = how many advisories to index (default: all). e.g. 300 for a quick, nearly free trial.
#
# Needs AWS credentials with Bedrock access in both modes (embeddings are always computed by Bedrock).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TF_DIR="$ROOT/infra/serverless"
TARGET="${1:-}"
MAX="${2:-0}"

step() { printf '\n\033[1m==> %s\033[0m\n' "$*"; }
fail() { printf '\033[31mError: %s\033[0m\n' "$*" >&2; exit 1; }

[[ "$TARGET" == "local" || "$TARGET" == "aws" ]] || fail "Usage: scripts/ingest.sh local|aws [max-advisories]"
[[ "$MAX" =~ ^[0-9]+$ ]] || fail "max-advisories must be a number"
aws sts get-caller-identity >/dev/null 2>&1 \
  || fail "No AWS credentials (needed for Bedrock embeddings). Run 'aws configure' (access keys) or 'aws sso login' (SSO), or set AWS_PROFILE."

if [[ "$TARGET" == "local" ]]; then
  export DEPGUARD_RAG_STORE=jdbc
  (cd "$ROOT" && docker compose ps --status running postgres 2>/dev/null | grep -q postgres) \
    || fail "Local Postgres isn't running. Start it with: docker compose up -d postgres"
else
  export DEPGUARD_RAG_STORE=data-api
  DEPGUARD_RAG_CLUSTER_ARN=$(terraform -chdir="$TF_DIR" output -raw rag_cluster_arn 2>/dev/null) \
    || fail "No deployed Aurora cluster found. Run scripts/deploy.sh first."
  DEPGUARD_RAG_SECRET_ARN=$(terraform -chdir="$TF_DIR" output -raw rag_secret_arn)
  export DEPGUARD_RAG_CLUSTER_ARN DEPGUARD_RAG_SECRET_ARN
  REGION_FROM_TF=$(sed -n 's/^region = "\(.*\)"/\1/p' "$TF_DIR/terraform.tfvars" 2>/dev/null || true)
  export AWS_REGION="${AWS_REGION:-${REGION_FROM_TF:-us-east-1}}"
fi

step "Building the app"
(cd "$ROOT" && ./mvnw -B -q -DskipTests package)
JAR=$(ls "$ROOT"/target/depguard-*.jar | grep -v plain | head -1)

step "Ingesting into $TARGET (advisories: ${MAX/#0/all})"
# The "ingest" profile runs IngestCommand once, without a web server, and exits.
(cd "$ROOT" && java -jar "$JAR" --spring.profiles.active=ingest --depguard.rag.ingest.max-advisories="$MAX")

echo
echo "Done. Try a search (shows semantic rank, keyword rank and the fused RRF score):"
if [[ "$TARGET" == "local" ]]; then
  echo "  curl -s 'localhost:8080/api/knowledge/search?q=Text4Shell+StringSubstitutor'"
else
  # On AWS the search needs a login (phase 8).
  echo "  TOKEN=\$(scripts/token.sh alice)"
  echo "  curl -s -H \"Authorization: Bearer \$TOKEN\" '$(terraform -chdir="$ROOT/infra/serverless" output -raw url)/api/knowledge/search?q=Text4Shell+StringSubstitutor'"
fi
