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
- Single Spring Boot app, no microservices. Packages: `dependency`, `osv`, `scan`, `exploit`, `agent`, `rag`, `cache`, `observability`, `security`, `upgrade`, `api`, `lambda`; evals live in test sources (`evals`).
- Plain blocking code on virtual threads (`spring.threads.virtual.enabled=true`), no reactive stack.
- Errors are RFC 9457 problem details. Upstream failures return 503 with a generic message; details go only to logs.
- The pom parser rejects DOCTYPE (XXE protection). Dependencies with unknown versions are reported as `skipped`, never guessed.
- **Langfuse Cloud** (free Hobby plan; owner's choice over self-hosting) for traces. docker-compose has Postgres+pgvector (RAG, local) and Redis (unused: the cache is pgvector).
- **Tracing (phase 5):** `observability/` package. Boot 4 `spring-boot-starter-opentelemetry` + `opentelemetry-exporter-otlp`;
  Boot's own OTLP exporter disabled; `LangfuseConfig` adds an `OtlpHttpSpanExporter` only when both keys are set.
  Sampling 1.0; `http.server/client.requests` observations disabled (were ~77 spans/triage). Spring AI 2.x log-prompt only
  logs, so `ChatContentObservationFilter` puts gen_ai.prompt/completion/usage.cost on model spans and langfuse input/output on
  tool spans. Content capture is switchable (`LANGFUSE_CAPTURE_CONTENT`). `TraceFlushFilter` flushes per request on Lambda.
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
  Guardrails: `CveCitationGuard` (answer CVEs must come from tools), max 8 tool calls (`TriageSession`), and (until phase 8 replaced it with Cognito JWTs) an API key on paid endpoints.
  - Known flaw for phase 4 evals: it ranked commons-text (EPSS 99.9%) below jackson-databind (26.6%), against the prompt's own rule.
  - The first local triage attempt (phase 2) hung with no log output and never reproduced. If it recurs, take a virtual-thread dump with `jcmd <pid> Thread.dump_to_file`.
- **Upgrade done (`baaed67`):** Spring Boot 4.1.3 + Spring AI 2.1.0.
- **Phase 3 done (verified locally 2026-11-21):** knowledge base (`rag/`), tool `searchAdvisories`, `GET /api/knowledge/search`
  (vectorRank/keywordRank/score), `scripts/ingest.sh`, Aurora in `infra/serverless/aurora.tf`.
  - Full local ingest: 7,352 chunks (7,143 advisories + 209 guide sections), ~4 min, 0 failures; re-run skips unchanged chunks.
  - Live triage: 5 tool calls (2 searches), 7 guide sources, ~11.6 s, ~18.5k in + 1.4k out tokens ≈ $0.025, all CVEs verified.
  - Retrieval note: keyword half ORs words, so generic words ("new", "moving") add noise; specific queries work well.
- **Phase 4 done (2026-11-21):** evals in `src/test/java/dev/depguard/evals/` (tag `eval`, classes `*Eval`), run with
  `./mvnw test -Pevals`; golden sets in `src/test/resources/evals/`. TriageOracle derives expectations from live data;
  TriageChecks = gates (fail build) + tracked; TriageJudge = Amazon Nova Pro (`us.amazon.nova-pro-v1:0`; the account also
  has Pixtral Large and Claude Opus 4.1, not Sonnet). Scorecards → `target/evals/`. Final: gates 100%, top-pick 100%,
  judge factual 4.66 / notes 4.81, retrieval hybrid hit@5 0.87 (vector 0.70, keyword 0.57). ~$0.42 per triage eval run.
  - Fixes from evals: CVE-ID tokenization (`HybridSearchSql.normalizeIds`; local table migrated in place), prompt rules
    (don't rank by advisory count, align framework module versions, report unchecked deps inside the "Can wait" template).
  - Open, tracked: "Java 17" often missing from spring-web notes; occasional irrelevant Jackson 3 notes; agent sometimes
    quotes EPSS percentile as probability. Nova Pro over-flags rounding.
  - Cost tiers: `-Devals.tier=smoke` (5 cases × 1, ~$0.08), `-Devals.replay=<scorecard.json>` re-grades saved answers
    without calling the agent ($0 with `-Devals.judge=false`). Use replay when only graders change.
  - CI: `.github/workflows/ci.yml` (unit tests on push; evals manual + needs repo variable AWS_EVAL_ROLE_ARN from
    `infra/ci/` Terraform, not applied).
- **Phase 5 done (2026-11-21), verified on Langfuse Cloud (US region; keys in `.env` as LANGFUSE_PUBLIC_KEY/SECRET_KEY/BASE_URL,
  quoted, which LangfuseProperties strips).** Trace = 15-20 observations with model name, tokens, cost. Eval run = session
  (`AiTracing.inSession`, not baggage: OTel bridge doesn't support local baggage fields) + 7 scores per trace (`LangfuseScores`).
  Reading data back: Langfuse removed v1 reads on 2026-11-16; use `/api/public/v2/observations` (needs fromStartTime/toStartTime)
  and `/api/public/v3/scores`. OTLP metrics/log export disabled (starter defaulted to localhost:4318).
  Prompt rule added after a gate failure: no major-version migration notes when the fix is same-major (Jackson 3 case).
- **Phase 6 done (2026-11-21):** triage = workflow + agent. `TriageFactsService` gathers OSV + EPSS + KEV in code, ranks
  (non-test, KEV, EPSS, severity) with explicit `rank`, aligns release trains (org.springframework, jackson-core, log4j,
  netty), rounds EPSS to 4 decimals, computes majorUpgrade. Model gets facts + one tool (searchAdvisories), offered only when
  a major upgrade exists. No-model route for clean poms. `AnswerCache` (cache/): exact + similar (0.55, measured) tiers,
  6 h TTL, key = deps + model + prompts; Postgres via `SqlRunner` (JDBC / Data API); off in evals. Prompt caching measured
  and removed (Haiku 4.5 min 4,096 tokens; first call ~2.2k); cost accounting includes cached tokens. `OsvClient` caps 16
  concurrent requests (OSV "too many concurrent streams"). Final eval: all checks 100%, judge 4.94/5/5/5, $0.0047/triage
  (was $0.0116), p50 2.6 s (was 4.9 s). Old VulnerabilityTools/ExploitTools removed.
- **Phase 7 done (2026-11-21):** `evals/ModelBenchmark` (run with `-Dtest=ModelBenchmark`; not part of `-Pevals`): 8 models,
  same facts/prompts/checks, Nova Pro judge only (owner's cost choice; Nova scores flagged self-graded). Only Claude Haiku 4.5 and
  Sonnet 4.5 pass all gates; Nova models fail the prompt-injection case (stop triaging). Decision: keep Haiku 4.5, no routing.
  Hard tool cap via `spring.ai.tools.limits` (4 calls, throw) + facts-only fallback in TriageAgent (also for Bedrock SdkException).
  Price table in application.yml from the AWS Price List API; `us.` Claude = +10% vs `global.` (earlier costs ~10% low).
  Llama 4 Scout partly "Legacy, not activated"; Pixtral Large throttled (low quota). Account can now call Claude Sonnet 4.5.
- **Phase 8 done (2026-11-21):** security + production hardening, all kept free.
  - Auth: Cognito user pool (Lite tier, admin-created users, groups VIEWER/MAINTAINER, public client, USER_PASSWORD_AUTH,
    60-min access tokens) → API Gateway JWT authorizer on paid routes → Spring Security resource server (`security/SecurityConfig`:
    JWKS, issuer, client_id, token_use=access; `cognito:groups` → ROLE_*). Auth is off when `DEPGUARD_AUTH_ISSUER_URI` is
    blank (local, evals). Only `GET /actuator/health` is public: API Gateway's `$default` route needs a JWT (unknown paths never start the Lambda) and Spring requires VIEWER/MAINTAINER on scans too. `ApiKeyFilter` and the Terraform random API key were removed.
  - Upgrades (`upgrade/`): `POST /api/upgrades` (any role) proposes from the GitHub pom; `/{id}/approve` (MAINTAINER,
    four-eyes, pom sha unchanged) opens a PR on `depguard/upgrade-<id8>`; GitHub failure rolls back to PENDING.
    `PomUpgrader` edits only `<version>` text; `${property}` versions are skipped. Store: `upgrade_proposal` table via `SqlRunner`.
    Demo target: public repo anuramesh1/depguard-demo-app (intentionally vulnerable pom). Token: fine-grained PAT, that repo only.
  - Secrets: SSM Parameter Store SecureString under `/depguard/` (free; chosen over Secrets Manager on cost), written by
    deploy.sh via stdin JSON, loaded by `SsmParametersPostProcessor` (EnvironmentPostProcessor, `DEPGUARD_SSM_PATH`).
    Terraform gets only `secrets_version` so new secrets roll the Lambda. teardown.sh deletes them.
  - `monitoring.tf`: 4 alarms → SNS email (`ALERT_EMAIL`), AWS Budget ($25, 80% actual / 100% forecast), reserved concurrency 5.
  - Scripts: `users.sh add|remove|list`, `token.sh <user>` (passwords via no-echo prompt or `DEPGUARD_PASSWORD`).
- **Tests:** 91 (`./mvnw test`), all pass; the Testcontainers ones need Docker running. No network or AWS needed; the agent tests use a fake `ChatModel`.
- **Bedrock access (2026-11-21):** Claude **Haiku 4.5**, **Sonnet 4.5**, Opus 4.1, Nova family, Pixtral Large, Writer Palmyra; earlier only (`us.anthropic.claude-haiku-4-5-20251001-v1:0`) and Titan Text Embeddings v2.
  Haiku 5.5 and Sonnet 5.5 return AccessDenied ("not available for this account"). The model is configurable (`model_id` in Terraform).
- **Deploy:** first applied 2026-11-22 (39 resources; Aurora took ~7 min). Fixes found on the first deploy: Boot's disk-space health check is always DOWN on Lambda (disabled); SecurityConfig must be `@ConditionalOnWebApplication` (ingest runs without a web server); GitHub URLs must be built from path segments (`{repo}` with a "/" became %2F → 404). The AWS CLI can't read `file:///dev/stdin` on macOS: scripts pass secret JSON through a private temp file (`aws_json`). The owner's AWS CLI uses access keys, not SSO. Lambda zip ~45 MB, uploaded through S3. The account has no default VPC in us-east-1 (hence aurora.tf's own VPC).
- Public repo: https://github.com/anuramesh1/depguard. Phases 1–7 and the upgrade pushed (2026-11-20/21).
  The owner wants **no Claude co-author or attribution lines** in commits. Note: the old `CLAUDE.md` wording with job references is still in `b6cdc00`'s history.
- Local dev: `.env` (gitignored) holds a random POSTGRES_PASSWORD; `docker compose up -d postgres`. Docker Desktop may start minimised to the menu bar.
- Local JDK for this machine: `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home` (no JDK on the default PATH).

## Next steps
1. Verified on Lambda (2026-11-22): 401 without/forged token, VIEWER 403 on approve, four-eyes 409, PR opened on the demo
   repo (only `<version>` lines changed), triage 9 s / ~$0.015 with 7 guide sources, cache repeat 24 ms, Langfuse trace with
   20 observations (keys from SSM; Lambda env holds no secrets), 4 alarms OK, reserved concurrency 5.
   The account-wide budget was already over $10 from other projects on first deploy, so it was raised to $25 (variable `monthly_budget_usd`).
2. All 8 phases are built. Further ideas are in the README's production checklist.

## Running in IntelliJ
- Open the `depguard` folder; IntelliJ imports the Maven project from `pom.xml`.
- Run `dev.depguard.DepGuardApplication` (port 8080).
- `samples/scans.http` sends the sample poms from IntelliJ's HTTP client (click the green arrows). Triage and knowledge search need AWS credentials (`aws configure` or `aws sso login`); search also needs `docker compose up -d postgres` and an ingest.
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
