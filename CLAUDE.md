# DepGuard: project context and session status

Read this first in every session. Update the **Status** and **Next steps** sections at the end of each working session.

## What this is
DepGuard is an AI agent that triages vulnerable Java
dependencies using live open-source advisory data (OSV.dev, EPSS, CISA KEV; NVD later).
The owner is learning: keep code simple, one concept per class, short comments that explain *why*.

The product spec and the 8-phase plan are in `docs/spec/DepGuard-Use-Case.md`. The roadmap table is also in README.md.
This is a public repo: keep personal notes and context out of committed files. They belong only in the gitignored
`docs/interview/` folder.

## Decisions already made (don't re-open without asking)
- **Java 21, Spring Boot 4.1.3** (upgraded from 3.5.16 on 2026-11-20: 3.5's free support ended 2026-06-30; 4.1's runs to 2027-07-31).
  Boot 4 notes: Jackson 3 (`tools.jackson.*`; annotations stay `com.fasterxml.jackson.annotation`), `spring-boot-starter-webmvc`,
  `spring-boot-starter-restclient` for `RestClient.Builder`, and `spring-boot-starter-webmvc-test` for `@WebMvcTest`.
  The springboot4 Lambda adapter pulls in Spring Cloud Function's `ServerlessAutoConfiguration`, which replaces Tomcat
  (nothing listens on :8080). It is excluded in `application.yml`; the Lambda handler works without it.
- **Spring AI 2.1.0** (not LangChain4j): Spring-native, and its Micrometer observability makes the Langfuse phase easier.
- **Amazon Bedrock** for models (owner's choice): IAM role on Lambda, no API key to store; Converse API via `spring-ai-starter-model-bedrock-converse`.
- Single Spring Boot app, no microservices. Packages: `dependency`, `osv`, `scan`, `exploit`, `agent`, `rag`, `api`, `lambda` (later `cache`, `security`, `evals`).
- Plain blocking code on virtual threads (`spring.threads.virtual.enabled=true`), no reactive stack.
- Errors are RFC 9457 problem details. Upstream failures return 503 with a generic message; details go only to logs.
- The pom parser rejects DOCTYPE (XXE protection). Dependencies with unknown versions are reported as `skipped`, never guessed.
- Langfuse is deferred to phase 5 (its self-hosted stack needs ClickHouse and MinIO). docker-compose has Postgres+pgvector (RAG, local) and Redis (phase 6).
- **RAG (phase 3):** own `AdvisoryStore` with hand-written hybrid SQL (`HybridSearchSql`: pgvector cosine + full-text OR-query, RRF k=60),
  not Spring AI's PgVectorStore (needs JDBC). `JdbcAdvisoryStore` locally, `DataApiAdvisoryStore` on AWS (RDS Data API: Lambda stays out of the VPC).
  Embeddings: Titan Text v2 via Spring AI `EmbeddingModel`, 1024 dims (Spring AI's Titan client sends no `dimensions`).
  Ingest = `ingest` profile (`IngestCommand`, `scripts/ingest.sh local|aws [max]`); content hashes make re-runs incremental.
  `spring-webflux` is excluded from `spring-ai-starter-model-bedrock`: on the classpath it makes the Lambda adapter start a reactive app.
- **Deploy: serverless**, chosen by the owner. API Gateway HTTP API (throttled 5 req/s, burst 10) → Lambda alias
  `live` → Lambda (java21, arm64, 1 GB, SnapStart). Terraform is in `infra/serverless/` with local state; the scripts
  are `scripts/deploy.sh`, `status.sh` and `teardown.sh`. Costs about $0 while idle.
  - Spring runs on Lambda via `aws-serverless-java-container-springboot4` 3.0.5 (`dev.depguard.lambda.StreamLambdaHandler`,
    HTTP API v2 events). Package: `./mvnw -Plambda package` → `target/depguard-lambda.zip`. SnapStart needs a zip, not an image.
  - Database: Aurora Serverless v2 (PG 16.9, min 0 ACU, auto-pause 10 min) via the **RDS Data API**, in its own private VPC (no IGW/NAT).
  - The Fargate version lives on in `infra/fargate/` as a documented alternative, not wired to the scripts.
    Production options and costs are in README "Design alternatives". The semantic cache will use pgvector, not ElastiCache.

## Status (updated 2026-11-20)
- **Phase 1 done:** `POST /api/scans` parses a pom, batch-queries OSV, fetches advisories in parallel, returns findings ranked by severity with a recommended upgrade version.
- **Phase 2 done:** `POST /api/triage` runs a Spring AI agent on Bedrock with tools `findVulnerabilities` (OSV via ScanService),
  `getExploitProbability` (FIRST EPSS) and `checkKnownExploited` (CISA KEV feed, cached 6 h). Prompts are in `resources/prompts/`.
  Guardrails: `CveCitationGuard` (answer CVEs must come from tools), max 8 tool calls (`TriageSession`), and an API key on paid endpoints when deployed.
  - Known flaw for phase 4 evals: it ranked commons-text (EPSS 99.9%) below jackson-databind (26.6%), against the prompt's own rule.
  - The first local triage attempt (phase 2) hung with no log output and never reproduced. If it recurs, take a virtual-thread dump with `jcmd <pid> Thread.dump_to_file`.
- **Upgrade done (`baaed67`):** Spring Boot 4.1.3 + Spring AI 2.1.0.
- **Phase 3 done (verified locally 2026-11-21):** knowledge base (`rag/`), tool `searchAdvisories`, `GET /api/knowledge/search`
  (vectorRank/keywordRank/score), `scripts/ingest.sh`, Aurora in `infra/serverless/aurora.tf`.
  - Full local ingest: 7,352 chunks (7,143 advisories + 209 guide sections), ~4 min, 0 failures; re-run skips unchanged chunks.
  - Live triage: 5 tool calls (2 searches), 7 guide sources, ~11.6 s, ~18.5k in + 1.4k out tokens ≈ $0.025, all CVEs verified.
  - Eval cases for phase 4: commons-text ranked below jackson-databind (intermittent; correct in the latest run), and
    after reading the Jackson 3 guide the model claimed jackson-databind "requires" Jackson 3 (2.18.11 already fixes the CVEs).
  - Retrieval note: keyword half ORs words, so generic words ("new", "moving") add noise; specific queries work well.
- **Tests:** 55 (`./mvnw test`), all pass; the 3 Testcontainers ones need Docker running. No network or AWS needed; the agent tests use a fake `ChatModel`.
- **Bedrock access:** this account can use Claude **Haiku 4.5** (`us.anthropic.claude-haiku-4-5-20251001-v1:0`) and Titan Text Embeddings v2.
  Haiku 5.5 and Sonnet 5.5 return AccessDenied ("not available for this account"). The model is configurable (`model_id` in Terraform).
- **Deploy:** never applied. Lambda zip ~45 MB, uploaded through S3. The account has no default VPC in us-east-1 (hence aurora.tf's own VPC).
- Public repo: https://github.com/anuramesh1/depguard. Phases 1–3 and the upgrade pushed (2026-11-20/21).
  The owner wants **no Claude co-author or attribution lines** in commits. Note: the old `CLAUDE.md` wording with job references is still in `b6cdc00`'s history.
- Local dev: `.env` (gitignored) holds a random POSTGRES_PASSWORD; `docker compose up -d postgres`. Docker Desktop may start minimised to the menu bar.
- Local JDK for this machine: `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home` (no JDK on the default PATH).

## Next steps
1. Optionally deploy (`scripts/deploy.sh`, then `scripts/ingest.sh aws`) and try `/api/triage` on Lambda with the key.
   Verify SnapStart + Bedrock credentials after restore, Aurora resume behaviour, and that a triage finishes inside API Gateway's 30 s.
2. **Phase 4: evals** — golden set from OSV ground truth + LLM judge, in CI (should catch the commons-text ranking flaw).

## Running in IntelliJ
- Open the `depguard` folder; IntelliJ imports the Maven project from `pom.xml`.
- Run `dev.depguard.DepGuardApplication` (port 8080).
- `samples/scans.http` sends the sample poms from IntelliJ's HTTP client (click the green arrows). Triage and knowledge search need AWS credentials (e.g. `aws sso login`); search also needs `docker compose up -d postgres` and an ingest.
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
