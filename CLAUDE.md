# DepGuard: project context and session status

Read this first in every session. Update the **Status** and **Next steps** sections at the end of each working session.

## What this is
DepGuard is an AI agent that triages vulnerable Java
dependencies using live open-source advisory data (OSV.dev, later EPSS, CISA KEV, NVD).
The owner is learning: keep code simple, one concept per class, short comments that explain *why*.

The product spec and the 8-phase plan are in `docs/spec/DepGuard-Use-Case.md`. The roadmap table is also in README.md.
This is a public repo: keep personal notes and context out of committed files. They belong only in the gitignored
`docs/interview/` folder.

## Decisions already made (don't re-open without asking)
- **Java 21, Spring Boot 3.5.16** (stay on 3.x). start.spring.io only offers 4.x now, so the parent version is pinned by hand in pom.xml.
- **Spring AI 1.1.8** (not LangChain4j; 2.x needs Boot 4): Spring-native, and its Micrometer observability makes the Langfuse phase easier.
- **Amazon Bedrock** for models (owner's choice): IAM role on Lambda, no API key to store; Converse API via `spring-ai-starter-model-bedrock-converse`.
- Single Spring Boot app, no microservices. Packages: `dependency`, `osv`, `scan`, `api` (later `agent`, `rag`, `cache`, `security`, `evals`).
- Plain blocking code on virtual threads (`spring.threads.virtual.enabled=true`), no reactive stack.
- Errors are RFC 9457 problem details. Upstream failures return 503 with a generic message; details go only to logs.
- The pom parser rejects DOCTYPE (XXE protection). Dependencies with unknown versions are reported as `skipped`, never guessed.
- Langfuse is deferred to phase 5 (its self-hosted stack needs ClickHouse and MinIO). docker-compose currently has Postgres+pgvector and Redis only.
- **Deploy: serverless**, chosen by the owner. API Gateway HTTP API (throttled 5 req/s, burst 10) → Lambda alias
  `live` → Lambda (java21, arm64, 1 GB, SnapStart). Terraform is in `infra/serverless/` with local state; the scripts
  are `scripts/deploy.sh`, `status.sh` and `teardown.sh`. Costs about $0 while idle.
  - Spring runs on Lambda via `aws-serverless-java-container-springboot3` (`dev.depguard.lambda.StreamLambdaHandler`,
    HTTP API v2 events). Package: `./mvnw -Plambda package` → `target/depguard-lambda.zip`. SnapStart needs a zip, not an image.
  - Phase 3 database: Aurora Serverless v2 via the **RDS Data API**, so the Lambda stays out of the VPC (no NAT needed to reach OSV).
  - The Fargate version lives on in `infra/fargate/` as a documented alternative, not wired to the scripts.
    Production options and costs are in README "Design alternatives". The semantic cache will use pgvector, not ElastiCache.

## Status (updated 2026-11-20)
- **Phase 1 done:** `POST /api/scans` parses a pom, batch-queries OSV, fetches advisories in parallel, returns findings ranked by severity with a recommended upgrade version.
- **Phase 2 done:** `POST /api/triage` runs a Spring AI 1.1.8 agent on Bedrock with tools `findVulnerabilities` (OSV via ScanService),
  `getExploitProbability` (FIRST EPSS) and `checkKnownExploited` (CISA KEV feed, cached 6 h). Prompts are in `resources/prompts/`.
  Guardrails: `CveCitationGuard` (answer CVEs must come from tools), max 8 tool calls (`TriageSession`), and an API key on `/api/triage` when deployed.
  - Live run: 3 tool calls, ~12 s, ~9.6k input + 1.2k output tokens (~$0.015), all 11 cited CVEs verified.
  - Known flaw for phase 4 evals: it ranked commons-text (EPSS 99.9%) below jackson-databind (26.6%), against the prompt's own rule.
  - The first local triage attempt hung with no log output and never reproduced. If it recurs, take a virtual-thread dump with `jcmd <pid> Thread.dump_to_file`.
- **Tests:** 37 pass (`./mvnw test`). No network or AWS needed; the agent tests use a fake `ChatModel`.
- **Bedrock access:** this account can use Claude **Haiku 4.5** (`us.anthropic.claude-haiku-4-5-20251001-v1:0`). Haiku 5.5 and Sonnet 5.5 are
  listed but return AccessDenied ("not available for this account"). The model is configurable (`model_id` in Terraform).
- **Deploy:** the Terraform now has 15 resources (adds Bedrock IAM, a random API key, and an S3 bucket for the ~45 MB zip, because direct
  upload is capped at 50 MB). `validate` and a read-only `plan` pass. **Never applied yet.**
- Public repo: https://github.com/anuramesh1/depguard. Phase 1 (`b6cdc00`) and phase 2 pushed 2026-11-20.
  The owner wants **no Claude co-author or attribution lines** in commits. Note: the old `CLAUDE.md` wording with job references is still in `b6cdc00`'s history.

## Next steps
1. Optionally deploy (`scripts/deploy.sh`) and try `/api/triage` on Lambda with the key (`terraform -chdir=infra/serverless output -raw api_key`).
   Verify SnapStart + Bedrock credentials work after restore, and that a triage finishes inside API Gateway's 30 s limit.
2. **Phase 3: hybrid RAG** over advisory text and migration guides: Aurora Serverless v2 (pgvector + full-text + RRF) via the RDS Data API.

## Running in IntelliJ
- Open the `depguard` folder; IntelliJ imports the Maven project from `pom.xml`.
- Run `dev.depguard.DepGuardApplication` (port 8080).
- `samples/scans.http` sends the sample poms from IntelliJ's HTTP client (click the green arrows). The triage request needs AWS credentials (e.g. `aws sso login`).
- `.idea/` is gitignored.

## Project rules
- Architecture diagrams are in `docs/architecture/`:
  - **Icon version (main, in the README):** generated by `architecture_diagram_aws.py`, which writes `depguard-architecture-aws.svg` and `.png`. Logos are in `logos/`.
  - **Simple box version:** `depguard-architecture.svg`, hand-written.
  Update both, and the README's Architecture section, when a phase adds a component. The diagrams don't mention the
  current phase number; only future components carry phase labels.
- `docs/architecture/` is approved for the repo (owner confirmed 2026-11-20).
- `docs/spec/` is approved for the repo (generic product spec only).
- `docs/interview/` holds private notes and stays gitignored. Ask before adding other folders under `docs/` to the repo.
- No secrets in code; `.env` is gitignored and `.env.example` documents the variables.
