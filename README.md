# DepGuard

An AI agent that triages vulnerable Java dependencies using **live, open-source advisory data**.
Give it a `pom.xml`; it tells you which dependencies are dangerous, why, and what to upgrade first.

Built with **Java 21, Spring Boot 3.5** and **Spring AI** on **Amazon Bedrock**. Each class holds one concept, so the code is easy to read.

> **Status: phases 1–2 of 8.** A scan API (no LLM) and an AI triage agent with tool calling. 37 tests pass.
> Both verified end to end against live data and Bedrock. Deploys to AWS serverless (Lambda + SnapStart, about $0 while idle) with `scripts/deploy.sh`;
> removed with `scripts/teardown.sh`.

---

## Architecture

![DepGuard architecture on AWS, drawn with service icons: a developer sends a pom.xml to Amazon API Gateway, which invokes a Java 21 Lambda with SnapStart running Spring Boot. Inside it, StreamLambdaHandler passes the request to the controllers. /api/scans goes to ScanService and OsvClient, which calls OSV.dev. /api/triage goes to TriageAgent, which calls Claude on Amazon Bedrock and uses three tools: the scan, and EpssClient and KevClient, which call FIRST EPSS and CISA KEV. Logs go to CloudWatch. Deploying: Maven builds the zip, Terraform uploads it through S3 to a new SnapStart version, then a health check. Later phases add Aurora Serverless v2, Langfuse and Spring Security.](docs/architecture/depguard-architecture-aws.png)

1. **Request:** a client posts a `pom.xml` over HTTPS to **API Gateway**, which throttles it to 5 requests/s and invokes the Lambda's `live` alias.
2. **Inside Lambda:** `StreamLambdaHandler` turns the API Gateway event into a normal servlet request, so the same Spring controller runs
   on Lambda and locally. `ScanService` parses the pom with `PomParser`, asks **OSV.dev** about every dependency through `OsvClient`
   (one batched call, then parallel fetches on virtual threads), and returns the report ranked by severity.
   For `/api/triage`, `TriageAgent` instead hands the dependency list to **Claude on Amazon Bedrock** with three tools:
   OSV (the same scan), **FIRST EPSS** (exploit probability) and the **CISA KEV** catalog (known exploited).
3. **Deploy:** `scripts/deploy.sh` runs the tests, builds `target/depguard-lambda.zip` and applies the Terraform in `infra/serverless/`.
   SnapStart snapshots the started app, so cold starts don't pay the Spring Boot startup cost.
4. **Locally:** `./mvnw spring-boot:run` serves the same controller on Tomcat at port 8080. There is no API Gateway or Lambda handler locally.

The dashed panel shows later phases.

<details>
<summary>Simple box version of the same diagram</summary>

![DepGuard architecture as plain boxes: client, API Gateway, Lambda with StreamLambdaHandler, Controllers, TriageAgent, ScanService, EpssClient/KevClient and OsvClient; externals Amazon Bedrock, EPSS/CISA KEV, OSV.dev and CloudWatch; below, the deploy steps and later phases.](docs/architecture/depguard-architecture.svg)

</details>

**Updating the diagrams** (when a phase adds a component):
- Icon version: edit and run `python3 docs/architecture/architecture_diagram_aws.py`. It writes the `.svg` and `.png`
  (the PNG needs `brew install librsvg`). Logos are in `docs/architecture/logos/`; the script's docstring lists where each came from.
- Simple version: edit [depguard-architecture.svg](docs/architecture/depguard-architecture.svg) by hand.

## What works today

### Scan API (phase 1): `POST /api/scans`

```
pom.xml ──► PomParser ──► OsvClient.findVulnerabilityIds ──► OsvClient.getVulnerability (parallel) ──► ScanReport
            (safe XML)     (1 batched HTTP call)              (virtual threads, one per advisory)       (worst first)
```

| Concept | Where |
|---|---|
| REST API with problem-detail errors (RFC 9457) | [api/ScanController.java](src/main/java/dev/depguard/api/ScanController.java), [api/ApiExceptionHandler.java](src/main/java/dev/depguard/api/ApiExceptionHandler.java) |
| Safe parsing of user-uploaded XML (blocks XXE) | [dependency/PomParser.java](src/main/java/dev/depguard/dependency/PomParser.java) |
| Live open data: OSV.dev, free and keyless | [osv/OsvClient.java](src/main/java/dev/depguard/osv/OsvClient.java) |
| Batching: one call for the whole pom | `OsvClient.findVulnerabilityIds` |
| Java 21 virtual threads for parallel I/O | `ScanService.fetchAdvisories`, `spring.threads.virtual.enabled` |
| Explicit timeouts on outbound calls | [osv/OsvConfig.java](src/main/java/dev/depguard/osv/OsvConfig.java), `application.yml` |
| Maven-correct version comparison (`2.9.10.8` < `2.10.0`) | `OsvVulnerability.fixedVersionFor` |
| Honest reports: unresolvable versions are listed as `skipped`, not ignored | `ParsedPom.skipped` |
| Upstream failures return 503 with a safe message; details only in logs | `ApiExceptionHandler` |

### AI triage agent (phase 2): `POST /api/triage`

A Spring AI agent decides **what to upgrade first and why**, using live data through tools:

```
pom.xml ─► PomParser ─► Claude on Bedrock ─┬─► findVulnerabilities   (OSV, the phase 1 scan, compact results)
           (plain Java)   (Spring AI        ├─► getExploitProbability (FIRST EPSS)    ┐ called together
                          ChatClient)       └─► checkKnownExploited   (CISA KEV)      ┘
                               │
                               ▼
                         Markdown answer ─► CveCitationGuard ─► TriageResult (answer, unverified CVEs, tool trace, tokens)
```

| Concept | Where |
|---|---|
| Tool calling with `@Tool` methods; Spring AI runs the call → result → call loop | [agent/tools/](src/main/java/dev/depguard/agent/tools/), [agent/TriageAgent.java](src/main/java/dev/depguard/agent/TriageAgent.java) |
| Prompt templates kept out of code: role, steps, rules, output format | [prompts/triage-system.st](src/main/resources/prompts/triage-system.st), [prompts/triage-user.st](src/main/resources/prompts/triage-user.st) |
| Per-request state passed to shared tools through `ToolContext` | [agent/TriageSession.java](src/main/java/dev/depguard/agent/TriageSession.java) |
| Guardrail: every CVE in the answer must come from a tool result, checked in code | [agent/CveCitationGuard.java](src/main/java/dev/depguard/agent/CveCitationGuard.java) |
| Guardrail: advisory text is treated as data; at most 8 tool calls per request | system prompt, `TriageSession.MAX_TOOL_CALLS` |
| Token-aware tool results: top 5 advisories per dependency, summaries truncated | [agent/tools/VulnerabilityTools.java](src/main/java/dev/depguard/agent/tools/VulnerabilityTools.java) |
| Feeds degrade gracefully: an outage becomes a message to the model, not a failed request | [agent/tools/ExploitTools.java](src/main/java/dev/depguard/agent/tools/ExploitTools.java) |
| Caching a 1.5 MB feed in memory with a TTL | [exploit/KevClient.java](src/main/java/dev/depguard/exploit/KevClient.java) |
| Cost control: API key on the paid endpoint, `max-tokens` cap, low temperature | [api/ApiKeyFilter.java](src/main/java/dev/depguard/api/ApiKeyFilter.java), `application.yml` |
| Tested without a model: a fake `ChatModel` checks prompts and the guardrail | [TriageAgentTest](src/test/java/dev/depguard/agent/TriageAgentTest.java) |

Sample answer for `samples/vulnerable-pom.xml` (live run: 3 tool calls, ~12 s, ~9.6k input + 1.2k output tokens, ~$0.015):

```markdown
## Upgrade first
1. org.apache.logging.log4j:log4j-core 2.14.1 → 2.25.4
   CVE-2021-44228 and CVE-2021-45046 are both in CISA KEV with confirmed ransomware use; 99.99% exploit probability.
2. com.fasterxml.jackson.core:jackson-databind 2.9.8 → 2.18.11
   60 advisories including 5 CRITICAL deserialization RCEs (CVE-2020-8840, …).
3. org.apache.commons:commons-text 1.9 → 1.10.0
   CVE-2022-42889, CRITICAL code execution; EPSS 99.93%.
4. org.springframework:spring-web 5.3.20 → 6.1.14
   … Note: Spring 6 requires Java 17+ and uses jakarta.* packages instead of javax.*.
## Can wait
- junit:junit 4.12 → 4.13.1 (test scope, MODERATE, EPSS 1.7%)
```

The response also lists `citedCves` and `unverifiedCves` (empty here: all 11 CVEs came from tools), the tool trace and token counts.
Known weakness, for phase 4's evals: here the model ranked commons-text (EPSS 99.9%) below jackson-databind (26.6%), against its own rules.

## Local demo (free)

Prerequisite: **JDK 21+** (`brew install --cask temurin@21`). Maven comes with the project (`./mvnw`).

```bash
./mvnw test                     # 37 tests, no network or AWS needed
./mvnw spring-boot:run          # starts on http://localhost:8080

curl -s -X POST localhost:8080/api/scans \
  -H 'Content-Type: application/xml' \
  --data-binary @samples/vulnerable-pom.xml | jq

# AI triage: needs AWS credentials with Bedrock access (aws sso login / AWS_PROFILE). No API key locally.
curl -s -X POST 'localhost:8080/api/triage?question=What+should+I+fix+this+week' \
  -H 'Content-Type: application/xml' \
  --data-binary @samples/vulnerable-pom.xml | jq -r .answer
```

The model is `us.anthropic.claude-haiku-4-5-20251001-v1:0` by default. To use another Bedrock model enabled for your account,
set `SPRING_AI_BEDROCK_CONVERSE_CHAT_OPTIONS_MODEL` (locally) or the Terraform variable `model_id` (on AWS).

In IntelliJ: run `DepGuardApplication`, then open [samples/scans.http](samples/scans.http) and click the green arrows.

To run the same container image that goes to AWS: `docker build -t depguard . && docker run -p 8080:8080 depguard`.

Sample output (live data, so the numbers change over time):

```
CRITICAL  log4j-core 2.14.1        -> upgrade 2.25.4  (8 advisories, incl. CVE-2021-44228 Log4Shell)
CRITICAL  spring-web 5.3.20        -> upgrade 6.1.14  (6 advisories)
CRITICAL  jackson-databind 2.9.8   -> upgrade 2.18.11 (60 advisories)
CRITICAL  commons-text 1.9         -> upgrade 1.10.0  (CVE-2022-42889 Text4Shell)
MODERATE  junit 4.12               -> upgrade 4.13.1
skipped:  org.slf4j:slf4j-api (version managed by a parent pom or BOM)
```

Note `spring-web 5 → 6`: a major upgrade that needs Java 17+ and the `javax` → `jakarta` change.
A plain scanner can't explain that; the triage agent does (see its sample answer above).

`docker compose up -d` starts Postgres+pgvector and Redis for later phases (copy `.env.example`
to `.env` first). Phase 1 does not need them.

## Cloud demo on AWS (serverless, about $0 while idle)

DepGuard deploys as a **Lambda function (Java 21, SnapStart) behind an API Gateway HTTP API**.
Nothing runs between requests, so it can stay deployed for weeks at almost no cost.

```bash
scripts/deploy.sh      # run tests, build the Lambda zip, create/update everything, check health (~3-5 min)
scripts/status.sh      # is it deployed? prints the URL and health
scripts/teardown.sh    # delete everything; asks you to type 'destroy' (or pass --yes)
```

Re-running `scripts/deploy.sh` ships a new version: a new Lambda version is published and the `live` alias moves to it.

**Prerequisites:** JDK 21, AWS CLI v2 with credentials (`aws sso login` or `AWS_PROFILE`), Terraform ≥ 1.6.
Docker isn't needed. Optional: `AWS_REGION` (default `us-east-1`).

**What gets created** ([infra/serverless/](infra/serverless/), 15 resources):

```
anyone ──HTTPS──► API Gateway HTTP API ──► Lambda alias "live" ──► Lambda (Java 21, ARM64, 1 GB, SnapStart) ──HTTPS──► api.osv.dev
                  (throttled: 5 req/s,      (newest published         Spring Boot runs inside it unchanged,
                   burst 10)                  version)                  via StreamLambdaHandler
                                                                        logs → CloudWatch (7-day retention)
                                                                        ──► Amazon Bedrock (Claude), EPSS, CISA KEV
```
Also: a private S3 bucket that holds the Lambda zip (direct uploads are capped at 50 MB; ours is ~45 MB),
an IAM policy for one Bedrock model, and a generated API key for `/api/triage`.

**How Spring Boot runs on Lambda:**
- [StreamLambdaHandler](src/main/java/dev/depguard/lambda/StreamLambdaHandler.java) uses AWS's
  `aws-serverless-java-container` library to turn an API Gateway event into a normal servlet request, so the same
  controllers run locally on Tomcat and on Lambda. [StreamLambdaHandlerTest](src/test/java/dev/depguard/lambda/StreamLambdaHandlerTest.java)
  proves this offline with real-shaped events.
- **SnapStart** boots Spring once when a version is published, snapshots the JVM, and restores the snapshot on cold
  starts. That cuts a multi-second Spring Boot cold start to typically well under a second. It is free for Java.
- `./mvnw -Plambda package` builds `target/depguard-lambda.zip`: classes plus a `lib/` folder of jars. Lambda can't
  run Spring Boot's "jar inside a jar" format, and SnapStart needs a zip rather than a container image.

**Cost** (approximate us-east-1 list prices):

| Situation | ≈ cost |
|---|---|
| Idle | ~$0 (a few cents of log storage at most) |
| One scan (about 2.5 s at 1 GB on ARM64, plus one API request) | ~$0.00003 |
| 1,000 demo scans | ~$0.03, and usually inside Lambda's monthly free allowance |
| One AI triage (3 model calls, ~10k input + ~1.2k output tokens on Haiku 4.5) | ~$0.015 |
| Worst case: someone hammers `/api/scans` at the 5 req/s throttle all day | ~$15/day. This is why the throttle exists; tear down if you see unexpected traffic. |
| Someone hammering `/api/triage` | Blocked: it needs the API key, because unthrottled LLM calls could cost far more |

**Demo security choices** (fine for a demo; see the next section for production):
- The URL is **public** (HTTPS on the default `execute-api` domain), **throttled** to 5 requests/s with a burst of 10.
  HTTP APIs can't restrict callers by IP; see the production checklist.
- `/api/triage` needs an `X-Api-Key` header. Terraform generates the key; read it with
  `terraform -chdir=infra/serverless output -raw api_key`. `/api/scans` stays open because it costs almost nothing.
- The Lambda role can write its own logs and invoke one Bedrock model, nothing else.
- Terraform state is local (`infra/serverless/terraform.tfstate`, gitignored). **Don't delete it while deployed**;
  teardown needs it to find the resources.

## Design alternatives: taking this to production

The cloud demo is deliberately lean. This section explains what to change for real use, and what each option costs.
Prices are approximate us-east-1 list prices; the UAE (me-central-1) and some other regions are roughly 10–20% higher.

### Options at a glance

| Option | ≈ cost | Good for | Trade-offs |
|---|---|---|---|
| **Serverless (this repo's deploy)**: API Gateway + Lambda SnapStart (+ Aurora Serverless v2 in phase 3) | ~$0 idle (~$1–3/month with Aurora storage) + per request | Demos, spiky or low traffic | Public URL (throttled); first request after Aurora pauses waits ~15 s; Lambda limits |
| **Fargate demo stack** ([infra/fargate/](infra/fargate/), not wired to the scripts) | ~$1.40/day while up | Learning ECS; always-warm demo | Fixed cost while up; HTTP only; public subnets |
| **Lean always-on Fargate** + RDS | ~$60/month (~$2/day) | Small internal tool | Single task: brief downtime when it restarts |
| **Production-grade Fargate** (below) | ~$115+/month (~$3.80/day) before scale | Steady traffic, long requests, streaming | Higher fixed cost, more to operate |

**How to choose:**
- **Serverless** when traffic is low or bursty and cost while idle matters.
- **Fargate** when traffic is steady (it gets cheaper than Lambda at sustained load), when requests run longer than
  30 s (API Gateway's limit; relevant for long agent runs), or when you need long-lived connections such as streaming chat.

LLM usage is extra and usage-based on either option: about $0.015 per triage on Claude Haiku 4.5
(measured: ~10k tokens in and ~1.2k out across 3 model calls), so 1,000 triages cost roughly $15. Check current Bedrock prices.

### Phase 3 note: the database on serverless

A Lambda that joins a VPC to reach a database loses internet access unless you add a NAT Gateway (~$33/month),
and DepGuard needs the internet to call OSV. Two ways to avoid that:
- **Aurora Serverless v2 with the RDS Data API** (recommended): the Lambda stays outside the VPC and runs SQL
  (including pgvector queries) over HTTPS with IAM auth. Aurora can pause at 0 capacity when idle.
- **VPC endpoints** for the AWS services you need, if you need a normal JDBC connection. These cost about $7/month each per AZ.

### Production-grade Fargate stack, always on

| Item | Size | ≈ $/month |
|---|---|---|
| ECS Fargate | 2 tasks across 2 AZs, 0.5 vCPU / 1 GB, ARM64 | 29 |
| Application Load Balancer | with HTTPS listener | 17 |
| NAT Gateway | tasks in private subnets | 33 |
| Public IPv4 | ALB × 2 | 7 |
| RDS Postgres + pgvector | db.t4g.micro, 20 GB (Multi-AZ roughly doubles this) | 14 |
| ElastiCache Redis | cache.t4g.micro (optional, see below) | 12 |
| CloudWatch, Secrets Manager, ECR, Route 53 | | 5 |
| **Total** | | **≈ $115 / month** |

### Checklist: what to change for production (serverless)

| Area | Demo (this repo) | Production |
|---|---|---|
| Entry point | Default `execute-api` URL, public, throttled | Custom domain (Route 53 + ACM). For IP allowlists or AWS WAF, use a REST API or put CloudFront + WAF in front (HTTP APIs support neither) |
| Auth (phase 8) | API key on `/api/triage` only | JWT authorizer on API Gateway (Cognito or your IdP) plus Spring Security roles |
| Cold starts | SnapStart | Add SnapStart "priming" (warm up OSV and Jackson classes before the snapshot); provisioned concurrency only if needed |
| Limits | 30 s API Gateway timeout | Long agent runs go async (SQS or Step Functions), or move to Fargate |
| Database (phase 3) | Aurora Serverless v2, min 0 ACU | Min capacity above 0 (no pause), automated backups, Multi-AZ reader |
| Cost protection | Throttle | AWS Budgets alert, per-client usage plans, reserved concurrency as a hard cap |
| Terraform state | Local file | S3 backend with state locking (`use_lockfile = true`), versioning and encryption |
| Deployment | `scripts/deploy.sh` from a laptop | CI/CD (GitHub Actions or Jenkins) assuming an AWS role via OIDC, so no long-lived keys; canary releases by shifting alias weights |
| Monitoring | CloudWatch logs | Alarms on 5xx, latency and throttles; X-Ray tracing; Langfuse for LLM traces (phase 5) |

### Cache: Redis (ElastiCache) or pgvector?

The phase 6 semantic cache answers "have we answered a question like this before?", which is a vector-similarity lookup.
- **pgvector in the existing Postgres:** no extra service or cost, one less thing to operate. Recommended, especially on serverless.
- **ElastiCache (Redis/Valkey):** lower latency at high request rates, and also useful for rate limiting and sessions.
  Adds about $12/month, and a Lambda must join the VPC to reach it (see the phase 3 note).

## Roadmap

| Phase | Adds | Capability |
|---|---|---|
| **1** ✅ | Scan API over OSV | Spring Boot Web, Java 21, API design |
| **2** ✅ | Spring AI agent on Bedrock with tools (OSV, EPSS, CISA KEV), prompt templates, citation guardrail | Agent tool calling, prompt design |
| 3 | Hybrid RAG over advisories and migration guides (pgvector + full-text + RRF) | RAG, Spring Data |
| 4 | Golden-set evals from OSV ground truth + LLM-as-judge, in CI | Evals design |
| 5 | Langfuse traces: tokens, cost, latency, failures | LLM observability |
| 6 | Semantic cache, prompt caching, model routing | Cost and performance optimization |
| 7 | Model benchmark: quality vs latency vs cost vs risk | Model selection |
| 8 | Spring Security (JWT, roles), human-approved upgrade PRs, production hardening of the AWS stack | Security, guardrails, cloud |

## Data sources

All public and free:
- [OSV.dev](https://osv.dev): advisories, including the GitHub Advisory Database (CC-BY 4.0). No key.
- [FIRST EPSS](https://www.first.org/epss/): probability that a CVE is exploited in the next 30 days. No key.
- [CISA KEV](https://www.cisa.gov/known-exploited-vulnerabilities-catalog): CVEs confirmed exploited in the wild. No key.
- Later: the [NVD API](https://nvd.nist.gov/developers) for CVSS details (free key; deferred because OSV already covers severity).
