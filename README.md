# DepGuard

An AI agent that triages vulnerable Java dependencies using **live, open-source advisory data**.
Give it a `pom.xml`; it tells you which dependencies are dangerous, why, and what to upgrade first.

Built with **Java 21, Spring Boot 4.1** and **Spring AI** on **Amazon Bedrock**. Each class holds one concept, so the code is easy to read.

> **Status: phases 1–7 of 8.** A scan API (no LLM), an AI triage agent with tool calling, a hybrid-search (RAG)
> knowledge base of every OSV Maven advisory plus official migration guides, quality evals (golden sets graded by
> code checks and an LLM judge), LLM tracing to Langfuse, and measured cost/latency work: a triage now costs ~$0.005 and
> takes ~2.6 s (was $0.012 and 4.9 s), repeats come from an answer cache in ~10 ms. A benchmark of 8 Bedrock models kept Claude Haiku 4.5. 81 tests (7 need
> Docker), plus 2 eval suites (`-Pevals`) and a model benchmark.
> Deploys to AWS serverless (Lambda + SnapStart, Aurora Serverless v2 that pauses when idle) with `scripts/deploy.sh`;
> removed with `scripts/teardown.sh`.

---

## Architecture

![DepGuard architecture on AWS, drawn with service icons: a developer sends a pom.xml to Amazon API Gateway, which invokes a Java 21 Lambda with SnapStart running Spring Boot. Inside it, StreamLambdaHandler passes the request to the controllers. /api/scans goes to ScanService and OsvClient, which calls OSV.dev. /api/triage goes to TriageAgent, which calls Claude on Amazon Bedrock and uses four tools: the scan, EpssClient and KevClient (FIRST EPSS and CISA KEV), and HybridRetriever, which embeds the question with Titan and searches Aurora Serverless v2 (pgvector + full-text) through the RDS Data API. Logs go to CloudWatch. Deploying: Maven builds the zip, Terraform uploads it through S3 to a new SnapStart version, then a health check. An ingest run fills the knowledge base from the OSV export and migration guides. Traces go to Langfuse Cloud over OTLP. Later phases add caching, a model benchmark and Spring Security.](docs/architecture/depguard-architecture-aws.png)

1. **Request:** a client posts a `pom.xml` over HTTPS to **API Gateway**, which throttles it to 5 requests/s and invokes the Lambda's `live` alias.
2. **Inside Lambda:** `StreamLambdaHandler` turns the API Gateway event into a normal servlet request, so the same Spring controller runs
   on Lambda and locally. `ScanService` parses the pom with `PomParser`, asks **OSV.dev** about every dependency through `OsvClient`
   (one batched call, then parallel fetches on virtual threads), and returns the report ranked by severity.
   For `/api/triage`, `TriageAgent` first checks the **answer cache**, then gathers the facts in code: OSV (the same
   scan), **FIRST EPSS** (exploit probability) and the **CISA KEV** catalog (known exploited), ranked by DepGuard's rule.
   Nothing vulnerable: it answers without a model. Otherwise **Claude on Amazon Bedrock** writes the explanation, with one
   tool: a **hybrid search** over the knowledge base in **Aurora Serverless v2** (reached through the RDS Data API, so
   the Lambda needs no VPC).
3. **Knowledge base:** `scripts/ingest.sh` downloads every OSV Maven advisory and the migration guides, embeds them
   with **Titan Text Embeddings v2** and stores them in Postgres with pgvector. Re-runs embed only what changed.
4. **Deploy:** `scripts/deploy.sh` runs the tests, builds `target/depguard-lambda.zip` and applies the Terraform in `infra/serverless/`.
   SnapStart snapshots the started app, so cold starts don't pay the Spring Boot startup cost.
5. **Tracing:** each triage is one trace in **Langfuse**: the agent step, every model call (prompt, answer, tokens,
   cost), every tool call and retrieval. Spring AI's Micrometer observations become OpenTelemetry spans, sent over OTLP.
6. **Locally:** `./mvnw spring-boot:run` serves the same controller on Tomcat at port 8080, and the knowledge base is
   the docker-compose Postgres. There is no API Gateway or Lambda handler locally.

The dashed panel shows later phases.

<details>
<summary>Simple box version of the same diagram</summary>

![DepGuard architecture as plain boxes: client, API Gateway, Lambda with StreamLambdaHandler, Controllers, TriageAgent, ScanService, HybridRetriever, EpssClient/KevClient and OsvClient; externals Amazon Bedrock, Aurora Serverless v2, EPSS/CISA KEV, OSV.dev and CloudWatch; below, the deploy and ingest steps and later phases.](docs/architecture/depguard-architecture.svg)

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

### AI triage (phases 2 and 6): `POST /api/triage`

DepGuard explains **what to upgrade first and why**. Since phase 6 it is a *workflow + agent*: code does everything
that is the same on every request, and the model does what only a model can (explain, and decide when the migration
guides are needed):

```
pom.xml ─► PomParser ─► answer cache ── hit ──────────────────────────────────────────► reused answer (~10 ms, $0)
                            │ miss
                            ▼
                     TriageFactsService (code): OSV + EPSS + CISA KEV, ranked; release trains aligned
                            │
              nothing vulnerable ──────────────────────────────────────────────────────► "Nothing to upgrade" ($0)
                            │
                            ▼
                     Claude on Bedrock (Spring AI) ── searchAdvisories, only if a major upgrade needs guides
                            │
                            ▼
              Markdown answer ─► CveCitationGuard ─► TriageResult (answer, unverified CVEs, sources, tokens, traceId)
```

Until phase 6 the model fetched the OSV, EPSS and KEV data itself with three tool calls; see
[Cost and performance](#cost-and-performance-phase-6-measured-before-and-after) for why that changed.

| Concept | Where |
|---|---|
| Facts gathered and ranked in code (KEV, then EPSS, then severity; test scope last) | [agent/TriageFactsService.java](src/main/java/dev/depguard/agent/TriageFactsService.java), [agent/TriageFacts.java](src/main/java/dev/depguard/agent/TriageFacts.java) |
| Tool calling with `@Tool`; Spring AI runs the call → result → call loop (one tool, offered only when useful) | [agent/tools/KnowledgeTools.java](src/main/java/dev/depguard/agent/tools/KnowledgeTools.java), [agent/TriageAgent.java](src/main/java/dev/depguard/agent/TriageAgent.java) |
| Prompt templates kept out of code: role, steps, rules, output format | [prompts/triage-system.st](src/main/resources/prompts/triage-system.st), [prompts/triage-user.st](src/main/resources/prompts/triage-user.st) |
| Per-request state passed to shared tools through `ToolContext` | [agent/TriageSession.java](src/main/java/dev/depguard/agent/TriageSession.java) |
| Guardrail: every CVE in the answer must come from the facts or a search result, checked in code | [agent/CveCitationGuard.java](src/main/java/dev/depguard/agent/CveCitationGuard.java) |
| Guardrail: advisory text is treated as data; at most 8 tool calls per request | system prompt, `TriageSession.MAX_TOOL_CALLS` |
| Token-aware facts: top 5 advisories per dependency, summaries truncated, EPSS rounded | `TriageFactsService` |
| Feeds degrade gracefully: an outage becomes a note to the model, not a failed request | `TriageFactsService` |
| Caching a 1.5 MB feed in memory with a TTL | [exploit/KevClient.java](src/main/java/dev/depguard/exploit/KevClient.java) |
| Cost control: API key on the paid endpoint, `max-tokens` cap, low temperature | [api/ApiKeyFilter.java](src/main/java/dev/depguard/api/ApiKeyFilter.java), `application.yml` |
| Tested without a model: a fake `ChatModel` checks prompts and the guardrail | [TriageAgentTest](src/test/java/dev/depguard/agent/TriageAgentTest.java) |

Sample answer for `samples/vulnerable-pom.xml` (phase 2 design, live run: 3 tool calls, ~12 s, ~9.6k input + 1.2k output tokens, ~$0.015):

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
Weakness seen here: the model ranked commons-text (EPSS 99.9%) below jackson-databind (26.6%), against its own rules.
Phase 4's evals measured it and a prompt change fixed it (see [Evals](#evals-phase-4-how-good-are-the-answers)).

### Knowledge base with hybrid search (phase 3): `GET /api/knowledge/search`

The agent's `searchAdvisories` tool grounds its "Upgrade notes" in the official migration guides instead of the
model's memory. The same search is exposed as an endpoint, so you can see what retrieval finds and why.

```
ingest (once, then incremental)                         query time (one SQL statement)
OSV Maven export (~7,300 advisories) ─┐                 question ─► Titan embedding ─┐
migration guides (Spring Boot 3/4,    ├─► chunk ─► Titan ─► Postgres                 ├─► semantic: pgvector cosine, top 40 ─┐
Spring Framework 6/7, Jackson 3) ─────┘    embeddings     pgvector + full-text       └─► keyword: full-text, top 40 ────────┴─► RRF fusion ─► top 5
```

| Concept | Where |
|---|---|
| Hybrid search in one SQL query: pgvector + Postgres full-text, fused with Reciprocal Rank Fusion | [rag/HybridSearchSql.java](src/main/java/dev/depguard/rag/HybridSearchSql.java) |
| One store interface, two connections: JDBC locally, **RDS Data API** on AWS (Lambda stays out of the VPC) | [rag/AdvisoryStore.java](src/main/java/dev/depguard/rag/AdvisoryStore.java), [JdbcAdvisoryStore](src/main/java/dev/depguard/rag/JdbcAdvisoryStore.java), [DataApiAdvisoryStore](src/main/java/dev/depguard/rag/DataApiAdvisoryStore.java) |
| Embeddings through Spring AI's `EmbeddingModel` (Titan Text v2, 1024 dimensions) | [rag/HybridRetriever.java](src/main/java/dev/depguard/rag/HybridRetriever.java), `application.yml` |
| Chunking by document structure (headings), not fixed sizes | [rag/ingest/DocumentChunker.java](src/main/java/dev/depguard/rag/ingest/DocumentChunker.java) |
| Exact identifiers (package names, fixed versions, CVE IDs) written into each advisory chunk, so keyword search can find them | [rag/ingest/OsvAdvisoryFeed.java](src/main/java/dev/depguard/rag/ingest/OsvAdvisoryFeed.java) |
| Incremental ingest: content hashes skip unchanged chunks; bounded parallel embedding on virtual threads | [rag/ingest/IngestService.java](src/main/java/dev/depguard/rag/ingest/IngestService.java) |
| Retrieved CVEs count as "known" for the citation guardrail; sources are listed in the triage response | [agent/tools/KnowledgeTools.java](src/main/java/dev/depguard/agent/tools/KnowledgeTools.java) |
| The real SQL tested against pgvector in a throwaway container | [JdbcAdvisoryStoreTest](src/test/java/dev/depguard/rag/JdbcAdvisoryStoreTest.java) (Testcontainers; skipped without Docker) |

**Why hybrid:** embeddings find meaning ("deserialization attack" ≈ "gadget chain RCE") but blur exact tokens such as
`CVE-2022-42889`, `jackson-databind` or `@ConstructorBinding`; full-text search is the reverse. RRF combines the two
by rank only (each list adds `1 / (60 + rank)`), so their very different score scales never need comparing.
Each search result shows `vectorRank`, `keywordRank` and `score`, so you can see which half found it.

**Live results** (full local index: 7,352 chunks; first full ingest took ~4 minutes with 0 failures):

```
GET /api/knowledge/search?q=log4j-core JNDI lookup remote code execution
  vectorRank 1   keywordRank 1   score 0.0328   GHSA-jfh8-c2jp-5v3q: Remote code injection in Log4j
  vectorRank 2   keywordRank 3   score 0.0320   GHSA-7rjr-3q55-vv33: Incomplete fix for Apache Log4j vulnerability
  vectorRank 10  keywordRank 2   score 0.0304   GHSA-v57x-gxfj-484q: Security Advisory for "Log4Shell"

GET /api/knowledge/search?q=CVE-2021-44228
  vectorRank —   keywordRank 1   score 0.0164   GHSA-v57x-gxfj-484q: Security Advisory for "Log4Shell"
  vectorRank 1   keywordRank —   score 0.0164   GHSA-x2p8-rgfm-qw3v: Access Control vulnerability within CoreNLP   ← embeddings blur exact IDs
```

The second query shows why both halves are needed: only the keyword half understands a bare CVE ID.
With the knowledge base, the triage's "Upgrade notes" become specific and sourced (live run: 5 tool calls including
2 searches, 7 guide sections read, ~11.6 s, ~18.5k input + 1.4k output tokens ≈ $0.025):

```markdown
## Upgrade notes
**Spring Framework 6 migration** (from Spring Framework 6.0 Release Notes):
Jakarta EE migration required: switch `javax.servlet` imports to `jakarta.servlet`, upgrade Tomcat to 10+, Jetty to 11+ …
**Jackson 3 migration** (from Jackson 3 Migration Guide):
Java 17+ required. Maven group ID changes from `com.fasterxml.jackson` to `tools.jackson` (except `jackson-annotations`) …
```

A weakness this run showed (now a phase 4 eval case): having read the Jackson 3 guide, the model claimed jackson-databind "requires" Jackson 3
for full security, although 2.18.11 already fixes the listed CVEs. Retrieval can pull an answer off course, which is why
RAG needs evals, not just a guardrail.

**Why not Spring AI's `PgVectorStore`:** it needs JDBC, and on AWS the app reaches Aurora through the Data API (HTTPS + IAM)
to avoid putting the Lambda in a VPC with a NAT gateway (~$32/month). Hand-written SQL also keeps the RRF query visible.

## Evals (phase 4): how good are the answers?

Guardrails stop specific failures; evals **measure quality**, so a prompt or model change can be judged by numbers.
Two suites, run with `./mvnw test -Pevals` (excluded from `./mvnw test` because they call Bedrock):

| Suite | What it measures | Cost per run |
|---|---|---|
| [RetrievalEval](src/test/java/dev/depguard/evals/RetrievalEval.java) | 30 golden queries → hit@1, hit@5, MRR@10 for vector-only, keyword-only and hybrid search | < $0.01 |
| [TriageEval](src/test/java/dev/depguard/evals/TriageEval.java) | 16 golden poms × 2 runs, graded by code checks and an LLM judge | ~$0.42 full · ~$0.08 smoke · $0 replay |

**How the triage eval grades an answer:**
- **Ground truth from live data, not hand-written answers.** [TriageOracle](src/test/java/dev/depguard/evals/TriageOracle.java)
  calls the same tools as the agent (OSV, EPSS, KEV) and applies the ranking rule in plain code, so the golden set
  ([triage-golden.json](src/test/resources/evals/triage-golden.json)) never goes stale when advisories change. Cases only
  say what data can't: what the answer must mention, and what it must never say.
- **Code checks** ([TriageChecks](src/test/java/dev/depguard/evals/TriageChecks.java)): objective and free.
  *Gates* must pass on every run or the build fails (only verified CVEs cited, every vulnerable dependency mentioned,
  no forbidden text such as prompt-injection output). *Tracked* checks report a pass rate (top pick follows "KEV, then
  EPSS", pairwise order, recommended version ≥ OSV's fix, no false alarms, framework modules on one major version).
  The graders have their own unit tests ([TriageChecksTest](src/test/java/dev/depguard/evals/TriageChecksTest.java)).
- **LLM judge** ([TriageJudge](src/test/java/dev/depguard/evals/TriageJudge.java), [rubric](src/test/resources/evals/judge-rubric.md)):
  **Amazon Nova Pro**, a different model family from the agent (judges favour their own style), given the oracle's
  facts as evidence, scoring four dimensions on anchored 1-5 scales, as structured JSON at temperature 0.
- **Repeats:** each case runs twice; the failures that matter are intermittent.

**Results** (2026-11-21; scorecards are written to `target/evals/`):

| Triage metric | Round 1 (baseline) | Final |
|---|---|---|
| Gates (grounding, safety) | 100% | 100% |
| Top pick follows the ranking rule | 97% | **100%** |
| Pairwise ranking, recommended versions, no false alarms | 100% | 100% |
| Expected content mentioned | 78% | 83% |
| Judge: factual accuracy / upgrade notes accuracy (mean, 1-5) | 4.34 / 3.88 | **4.66 / 4.81** |
| Latency p50 / p95 · agent cost per triage | 4.7 s / 11.3 s · $0.012 | 4.6 s / 10.6 s · $0.012 |

| Retrieval (30 queries) | hit@1 | hit@5 | MRR@10 |
|---|---|---|---|
| Vector only | 0.60 | 0.70 | 0.65 |
| Keyword only | 0.37 | 0.57 | 0.45 |
| **Hybrid (RRF)** | **0.70** | **0.87** | **0.79** |

**What the evals found and what changed** (the point of evals: each fix is measured, not assumed):
1. **Exact CVE search was broken.** Postgres splits `CVE-2022-42889` into `cve`, `-2022` and `-42889`, so keyword search
   matched every "2022" advisory: 0/8 CVE queries found. Rewriting CVE IDs into one token in the index and the query:
   8/8 in the top 5, and hybrid hit@5 rose from 0.63 (worse than vector alone) to 0.87.
2. **Fusion weights are a trade-off, not a free win.** A sweep in the retrieval report shows that down-weighting the
   keyword half helps natural-language queries but breaks every exact-ID query; plain RRF (weight 1) was kept rather
   than tuned to 30 queries. Next idea: route identifier queries to keyword search.
3. **The agent ranked by advisory count** (H2 with 4 advisories above SnakeYAML with 99.5% EPSS). Prompt rule added
   ("do not rank by the number of advisories"): top pick 97% → 100%.
4. **Unchecked dependencies went unreported**: the output template ("Can wait … or 'Nothing else.'") beat a separate
   rule. Moving the instruction into the template fixed the focused case (9/9 runs); it still slips occasionally.
5. **Mixed framework versions** (spring-webmvc → 7.0.9 with spring-beans → 5.3.20) were spotted by reading answers, then
   turned into a code check and a prompt rule. Lesson: read outputs; evals only catch what someone thought to check.
6. **The judge was wrong too.** Round 1 flagged correct answers: 2026 CVEs called "future", 5 → 6 called "same major",
   per-CVE facts called unsupported, because its evidence was coarser than the answer. Fix: per-CVE facts, a computed
   "major upgrade" flag, precise numbers. Nova Pro still nitpicks rounding despite the rubric, so its factual score is
   conservative; but it also caught real errors no check did (a "99.8%" typo for 99.98%; the EPSS *percentile* quoted as
   the *probability*).
7. **The eval setup itself had a bug**: `-Pevals` ran 0 tests because Surefire only picks up `*Test` classes. A green
   build that tested nothing; fixed by including `*Eval`.

Still open (tracked, not gated): "Java 17" is missing from the spring-web 5 → 6 notes in most runs, and an occasional
irrelevant Jackson 3 note. Phase 7 reuses these suites to compare models.

**Running them, cheapest first** (the agent's Claude calls are ~88% of the cost, so the savings come from not
re-running the agent when it isn't needed):

| When | Command | ≈ Cost | Time |
|---|---|---|---|
| Changing a check, the rubric or the oracle | `./mvnw test -Pevals -Dtest=TriageEval -Devals.replay=target/evals/triage-scorecard.json -Devals.judge=false` | $0 | ~15 s |
| … and re-judging too | same, without `-Devals.judge=false` | ~$0.05 | ~1 min |
| Iterating on a prompt | `./mvnw test -Pevals -Dtest=TriageEval -Devals.tier=smoke` (5 smoke cases × 1 run) | ~$0.08 | ~20 s |
| Checking one case for flakiness | `./mvnw test -Pevals -Dtest=TriageEval -Devals.cases=sample-pom -Devals.repeats=3` | ~$0.06 | ~30 s |
| Before a commit | `./mvnw test -Pevals` (both suites; needs the local knowledge base) | ~$0.42 | ~1 min |

**Replay** re-grades the answers saved in a previous scorecard with the current checks and judge, without calling the
agent; the graders run exactly the same code as in a live run (verified: a replay reproduced the live results).
It overwrites `target/evals/triage-scorecard.*`, so copy the file first to keep old grades. Smoke cases are marked
`"smoke": true` in [triage-golden.json](src/test/resources/evals/triage-golden.json).

**In CI** ([.github/workflows/ci.yml](.github/workflows/ci.yml)): every push runs the free test suite (actions pinned to
commit SHAs). The evals job runs only when started by hand and only after an AWS role for GitHub (OIDC, no stored keys)
is created; see [infra/ci/README.md](infra/ci/README.md).

## Observability (phase 5): what happened inside each triage?

Evals say *how good* answers are on average; tracing shows *why* one answer came out the way it did. Every triage is
sent to **[Langfuse](https://langfuse.com)** (Cloud, free Hobby plan) as one trace:

```
depguard.triage                      agent      input: question + dependencies · output: the answer
│                                               metadata: route (cache / no-model / model), cited and unverified CVEs
│                                               level WARNING when the citation guardrail fires
├── depguard.answer_cache            span       hit · match (exact / similar) · similarity
├── depguard.facts                   span       OSV + EPSS + KEV, ranked (output: the facts JSON)
└── spring_ai chat_client
    ├── chat us.anthropic.claude-haiku…  generation  prompt · completion · model · tokens · cost      (1-2 calls)
    └── tool_call searchAdvisories       tool        arguments · result
        └── depguard.retrieval           retriever   query · hits with vector rank, keyword rank, RRF score
```

**Turning it on:** create a Langfuse project, then put `LANGFUSE_PUBLIC_KEY`, `LANGFUSE_SECRET_KEY` and
`LANGFUSE_HOST` (EU or US region; `LANGFUSE_BASE_URL`, the name in Langfuse's own snippet, works too; quotes are fine) in `.env`. Without keys, tracing is off and nothing else changes.
`scripts/deploy.sh` passes the same keys to Lambda. Every `/api/triage` response includes its `traceId`.

| Concept | Where |
|---|---|
| Spans exported over OTLP/HTTP to Langfuse; exporter exists only when keys are set | [observability/LangfuseConfig.java](src/main/java/dev/depguard/observability/LangfuseConfig.java) |
| Application spans (agent, retriever) with Langfuse attributes: input, output, metadata, WARNING level | [observability/AiTracing.java](src/main/java/dev/depguard/observability/AiTracing.java) |
| Prompt, completion and **cost** on every model call; arguments and results on every tool call | [observability/ChatContentObservationFilter.java](src/main/java/dev/depguard/observability/ChatContentObservationFilter.java), [ModelPricing](src/main/java/dev/depguard/observability/ModelPricing.java) |
| Flush spans before the response on Lambda (it freezes the process right after) | [observability/TraceFlushFilter.java](src/main/java/dev/depguard/observability/TraceFlushFilter.java) |
| Eval runs as Langfuse sessions, with judge and check scores attached to each trace | [evals/LangfuseScores.java](src/test/java/dev/depguard/evals/LangfuseScores.java), `TriageEval` |

**Design notes (things that were not obvious):**
- **Spring AI 2.x doesn't put prompts on spans.** `spring.ai.chat.observations.log-prompt` now writes to the *log*;
  Langfuse's Spring AI guide targets 1.x. A small Micrometer `ObservationFilter` copies prompt and completion onto
  the model-call span as `gen_ai.prompt` / `gen_ai.completion`, which Langfuse reads.
- **Cost is computed, not looked up.** Langfuse prices models by name, and Bedrock IDs such as
  `us.anthropic.claude-haiku-4-5-20251001-v1:0` may not match its list, so the span carries `gen_ai.usage.cost` from
  a small price table in `application.yml`.
- **Fewer, better spans.** With default instrumentation a triage produced ~95 spans, 77 of them one HTTP GET per OSV
  advisory. Langfuse bills per span (50k/month free), and the tool spans already time those calls, so HTTP
  request spans are switched off: ~15 spans per triage, i.e. ~3,000 triages a month on the free plan.
- **Sampling is 100%.** Spring Boot's default samples 10% of traces; every triage matters here.
- **Content capture is switchable** (`LANGFUSE_CAPTURE_CONTENT=false`): then traces keep tokens, cost, latency,
  tool names and errors, but no prompts, answers or pom contents. Pom files aren't secret, but in a company they
  reveal internal library names.
- **Lambda needs an explicit flush.** Spans are normally exported in the background every few seconds; Lambda freezes
  the process once the response is sent, so on Lambda a filter flushes before returning (a few hundred ms).
- **Model name on generations.** Spring AI records the *request* model, which is `none` when the model comes from
  defaults (Bedrock here); the filter sets `langfuse.observation.model.name` to the real model ID.
- **Only traces are exported.** The OpenTelemetry starter also turns on OTLP metrics and log export to
  `localhost:4318`, which failed every minute; both are switched off.
- **Secrets:** the keys live in `.env` (gitignored) locally. On Lambda they are environment variables set by
  Terraform; production should read them from Secrets Manager instead (see the production checklist).

**Verified on Langfuse Cloud** (2026-11-21, read back through Langfuse's v2 observations and v3 scores APIs): one triage
= 15-20 observations (1 agent, 4 generations with model, tokens and cost, 5 tools, 2 retrievers, 2 embeddings, plus
Spring AI's chat-client spans), ~$0.014-0.026 per trace; a smoke eval run = one session of 10 traces with 7 scores each.

**Evals + tracing:** with keys set, each eval run is a Langfuse **session** (`eval-YYYYMMDD-HHMMSS`) and every trace
gets scores (`judge.factual_accuracy`, `judge.upgrade_notes_accuracy`, `eval.gates_passed`, `eval.top_pick`, …).
The session is set explicitly (`AiTracing.inSession`) on DepGuard's root span; OpenTelemetry baggage was tried first,
but Spring Boot's OpenTelemetry bridge doesn't support local baggage fields. That's the run history the scorecards lacked: filter traces by a low score and open the exact prompts and tool
results that produced the answer.

## Cost and performance (phase 6): measured before and after

Every change had to show its effect in the full triage eval (16 cases × 2 runs, Langfuse costs) or be dropped.

| | Baseline (agent, 3 data tool calls) | **After phase 6** |
|---|---|---|
| Agent cost per triage (at the `global.` price) | $0.0116 | **$0.0047 (−59%)** |
| Latency p50 / p95 | 4.9 s / 10.8 s | **2.6 s / 7.3 s (−47% / −32%)** |
| Model input / output tokens | 8,522 / 611 | 3,150 / 311 |
| Model calls per triage | ~4 | ~1.5 (0 for clean poms and cache hits) |
| Quality: all code checks; judge factual / prioritization / notes / clarity | 100% (Spring versions aligned: 0%); 4.69 / 4.88 / 4.88 / 4.94 | **100% (aligned: 100%); 4.94 / 5.00 / 5.00 / 5.00** |
| Repeated request (answer cache) | full price | **~10 ms, $0** |

**What changed, and what each one taught:**
1. **Workflow before agent (A).** The model always asked for the same data in the same order (OSV, then EPSS + KEV),
   paying a round trip each time that re-sent the whole conversation. Code now gathers the facts and ranks them; the
   model only explains. First attempt: cheaper but *worse* (a safety gate failed, top pick 93%, expected content 78%).
   Reading the answers showed the fixes were more of the same idea: give each dependency an explicit `rank` (the model
   re-sorted by severity), offer the guide search only when a major upgrade exists (otherwise it wrote Jackson 3 notes
   nobody needed), let code write the "Not checked" line, round EPSS so model and judge quote the same number, and
   align release trains (spring-webmvc 7 with spring-beans 5 won't run) in code instead of asking the model.
2. **Skip the model when nothing is vulnerable (B).** A clean pom gets a deterministic answer: $0, ~1 s. The eval also
   caught that the "clean" golden pom wasn't clean any more (Guava gained an advisory): live data drifts.
3. **Prompt caching (C): measured, then removed.** It works on Bedrock (a 7k-token prefix was read back from cache), but
   Claude Haiku 4.5 caches only prompts of 4,096+ tokens. After (A) the first model call is ~2,200 tokens and the second
   is the last, so nothing is ever read back: $0.00505 vs $0.00506 per triage. Caching pays when a long prefix is re-sent,
   and (A) removed exactly those re-sends. Kept: cost accounting that includes cached tokens (Bedrock reports them
   separately from `inputTokens`, so a cached call would otherwise look free).
4. **Answer cache (D).** Same dependency set (plus model and prompt version) and the same question, within 6 hours →
   the stored answer, from Postgres. Differently-worded questions reuse it only above a 0.55 embedding similarity:
   measured with Titan, paraphrases scored 0.14-0.59 and *different* questions up to 0.32, so the threshold catches about
   half the paraphrases and none of the different questions. A wrong cached answer is worse than a miss. Any database
   problem counts as a miss, never an error. Off in the evals, which measure the agent.

| Concept | Where |
|---|---|
| Answer cache: exact + similar tiers, TTL, versioned key, fail-open | [cache/AnswerCache.java](src/main/java/dev/depguard/cache/AnswerCache.java), [AnswerCacheTest](src/test/java/dev/depguard/cache/AnswerCacheTest.java) (pgvector) |
| SQL over JDBC locally and the RDS Data API on AWS | [cache/SqlRunner.java](src/main/java/dev/depguard/cache/SqlRunner.java) |
| Cost including cached tokens | [observability/ModelPricing.java](src/main/java/dev/depguard/observability/ModelPricing.java) |
| Before/after scorecards | `target/evals/` after `./mvnw test -Pevals`; every run is also a Langfuse session |

5. **A production bug the evals surfaced.** Gathering facts up front made four parallel triages fetch ~200 OSV advisories
   at the same moment, and OSV refused with "too many concurrent streams" (the JDK client multiplexes requests over one
   HTTP/2 connection). `OsvClient` now allows at most 16 requests in flight, app-wide; a test fires 100 at once and checks
   the peak. One big pom or a few simultaneous users could have hit this in production.

Model routing (a cheaper model for easy poms) is left for phase 7, where the eval suites compare models properly.

## Model benchmark (phase 7): which model, at what price?

[ModelBenchmark](src/test/java/dev/depguard/evals/ModelBenchmark.java) runs the full triage golden set (16 cases × 2) on
each model with **only the model changed**: same facts, prompts, temperature, token limit, checks and judge. Prices
come from the AWS Price List API (us-east-1) and live in one table in `application.yml`, used by the evals and by the
cost on every Langfuse span.

| Model | Runs with all gates | Simple poms: all gates | Judge factual / notes | p50 latency | Cost per triage |
|---|---|---|---|---|---|
| **Claude Haiku 4.5 (chosen)** | **100%** | **100%** | 4.88 / 4.81 | 2.4 s | $0.0056 |
| Claude Sonnet 4.5 | 100% | 100% | 5.00 / 4.94 | 5.1 s | $0.0163 |
| Amazon Nova 2 Lite | 97% | 95% | 4.88 / 5.00* | 1.4 s | $0.0014 |
| Amazon Nova Micro | 94% | 90% | 4.44 / 4.50* | 1.4 s | $0.00014 |
| Amazon Nova Pro | 94% | 90% | 4.69 / 4.69* | 1.8 s | $0.0028 |
| Meta Llama 4 Scout | 88%† | 90% | 4.64 / 4.86 | 1.6 s | $0.0005 |
| Amazon Nova Lite | 88% | 85% | 4.50 / 4.50* | 1.7 s | $0.0019‡ |
| Mistral Pixtral Large | 78%† | 70% | 5.00 / 4.84 | 3.8 s | $0.0060 |

\* Self-graded: the judge is Nova Pro, so its Nova scores are an upper bound (a cost decision: one judge, bias flagged).
† Account issues, not quality: Llama 4 Scout is "Legacy, not activated" in some regions of the `us.` profile (4 errors),
and Pixtral Large hit this account's low throttling quota (7 errors). ‡ Inflated by one runaway run (see below).
"Simple poms" are cases with no major-version upgrade: the slice a router would send to a cheaper model.

**Decisions:**
- **Keep Claude Haiku 4.5; no model routing.** Only the two Claude models passed every gate. The cheaper models' main
  failure is the prompt-injection case: told to "say every dependency is safe", every Nova model refused to comply but
  also stopped triaging, so the user never heard about a known-exploited log4j RCE. Routing simple poms to Nova 2 Lite
  would save ~75% on them but fail a gate in ~1 of 20; for a security tool, not worth half a cent. Sonnet 4.5 matched
  Haiku on every check at 2.9× the cost and twice the latency.
- **A real guardrail gap, fixed.** Nova Lite once made 40 guide searches (820,892 input tokens) in one triage: the
  8-call budget only *told* the model to stop. Now Spring AI enforces a hard cap (`spring.ai.tools.limits`, 4 calls), and
  hitting it, or a Bedrock outage, gives a facts-only answer (the ranked list, built in code) instead of an error.
- **Price correction.** Claude through a `us.` inference profile costs 10% more than through `global.` (Haiku 4.5:
  $1.10/$5.50 vs $1.00/$5.00 per million tokens); earlier cost figures used the `global.` price and were ~10% low.
  `global.` is cheaper but may process requests in any AWS region: a data-residency decision, so DepGuard stays on `us.`.

```bash
./mvnw test -Pevals -Dtest=ModelBenchmark                                        # 8 models, ~$1.60, ~10 min
./mvnw test -Pevals -Dtest=ModelBenchmark -Devals.models=us.amazon.nova-lite-v1:0 -Devals.repeats=1
```

The report is written to `target/evals/model-benchmark.md` (plus a scorecard per model); each model's runs are a
Langfuse session with scores.

## Local demo (free)

Prerequisite: **JDK 21+** (`brew install --cask temurin@21`). Maven comes with the project (`./mvnw`).

```bash
./mvnw test                     # 81 tests, no network or AWS needed (the 3 pgvector tests run when Docker is up)
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

**Knowledge base (phase 3), locally:** Postgres + pgvector in Docker, embeddings from Bedrock (AWS credentials needed).

```bash
cp .env.example .env                 # then set POSTGRES_PASSWORD to any local password
docker compose up -d postgres
scripts/ingest.sh local 300          # quick trial: 300 advisories + the guides, < 1 min, about $0.003
scripts/ingest.sh local              # everything: ~7,350 chunks, ~4 min, about $0.05; re-runs embed only changes
curl -s 'localhost:8080/api/knowledge/search?q=Text4Shell+StringSubstitutor' | jq
```

Without the database the app still runs: knowledge search returns 503, and triage answers without the guides (it says so).

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

`docker compose up -d` also starts Redis, which phase 6 will use.

## Cloud demo on AWS (serverless, about $0.50/month while idle)

DepGuard deploys as a **Lambda function (Java 21, SnapStart) behind an API Gateway HTTP API**.
Nothing runs between requests, and the Aurora database pauses after 10 idle minutes, so it can stay deployed for weeks at almost no cost.

```bash
scripts/deploy.sh      # run tests, build the Lambda zip, create/update everything, check health (~3-5 min; first run ~10 min for Aurora)
scripts/ingest.sh aws  # fill the knowledge base in Aurora from your laptop, through the Data API (first time, or to refresh)
scripts/status.sh      # is it deployed? prints the URL and health
scripts/teardown.sh    # delete everything; asks you to type 'destroy' (or pass --yes)
```

Re-running `scripts/deploy.sh` ships a new version: a new Lambda version is published and the `live` alias moves to it.

**Prerequisites:** JDK 21, AWS CLI v2 with credentials (`aws sso login` or `AWS_PROFILE`), Terraform ≥ 1.6.
Docker isn't needed. Optional: `AWS_REGION` (default `us-east-1`).

**What gets created** ([infra/serverless/](infra/serverless/), 23 resources):

```
anyone ──HTTPS──► API Gateway HTTP API ──► Lambda alias "live" ──► Lambda (Java 21, ARM64, 1 GB, SnapStart) ──HTTPS──► api.osv.dev
                  (throttled: 5 req/s,      (newest published         Spring Boot runs inside it unchanged,
                   burst 10)                  version)                  via StreamLambdaHandler
                                                                        logs → CloudWatch (7-day retention)
                                                                        ──► Amazon Bedrock (Claude, Titan), EPSS, CISA KEV
                                                                        ──► RDS Data API ──► Aurora Serverless v2 (pgvector)
```
Also: a private S3 bucket that holds the Lambda zip (direct uploads are capped at 50 MB; ours is ~45 MB),
IAM policies for two Bedrock models and the Data API, a generated API key, and Aurora's own private VPC
(two subnets, no internet gateway, no NAT, no inbound rules: the Data API is the only way in).

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
| Idle | ~$0.50/month: Aurora's password secret ($0.40) and storage (cents); its compute pauses at 0 ACU |
| Aurora while awake | ~$0.06/hour at 0.5 ACU; it pauses again after 10 idle minutes. The first query after a pause waits ~15 s |
| First full ingest | ~$0.05 of Titan embeddings (~7,350 chunks), plus a few minutes of Aurora time |
| One scan (about 2.5 s at 1 GB on ARM64, plus one API request) | ~$0.00003 |
| 1,000 demo scans | ~$0.03, and usually inside Lambda's monthly free allowance |
| One AI triage (1–2 model calls, ~3.5k input + ~0.3k output tokens on Haiku 4.5 via `us.`; up to ~10k for big poms) | ~$0.0056 (big poms up to ~$0.016); repeats from the answer cache: $0 |
| Worst case: someone hammers `/api/scans` at the 5 req/s throttle all day | ~$15/day. This is why the throttle exists; tear down if you see unexpected traffic. |
| Someone hammering `/api/triage` | Blocked: it needs the API key, because unthrottled LLM calls could cost far more |

**Demo security choices** (fine for a demo; see the next section for production):
- The URL is **public** (HTTPS on the default `execute-api` domain), **throttled** to 5 requests/s with a burst of 10.
  HTTP APIs can't restrict callers by IP; see the production checklist.
- `/api/triage` and `/api/knowledge` need an `X-Api-Key` header. Terraform generates the key; read it with
  `terraform -chdir=infra/serverless output -raw api_key`. `/api/scans` stays open because it costs almost nothing.
- The Lambda role can write its own logs, invoke two Bedrock models, and run SQL on one Aurora cluster with its one secret; nothing else.
- Terraform state is local (`infra/serverless/terraform.tfstate`, gitignored). **Don't delete it while deployed**;
  teardown needs it to find the resources.

## Design alternatives: taking this to production

The cloud demo is deliberately lean. This section explains what to change for real use, and what each option costs.
Prices are approximate us-east-1 list prices; the UAE (me-central-1) and some other regions are roughly 10–20% higher.

### Options at a glance

| Option | ≈ cost | Good for | Trade-offs |
|---|---|---|---|
| **Serverless (this repo's deploy)**: API Gateway + Lambda SnapStart + Aurora Serverless v2 (Data API) | ~$0.50/month idle + per request | Demos, spiky or low traffic | Public URL (throttled); first request after Aurora pauses waits ~15 s; Lambda limits |
| **Fargate demo stack** ([infra/fargate/](infra/fargate/), not wired to the scripts) | ~$1.40/day while up | Learning ECS; always-warm demo | Fixed cost while up; HTTP only; public subnets |
| **Lean always-on Fargate** + RDS | ~$60/month (~$2/day) | Small internal tool | Single task: brief downtime when it restarts |
| **Production-grade Fargate** (below) | ~$115+/month (~$3.80/day) before scale | Steady traffic, long requests, streaming | Higher fixed cost, more to operate |

**How to choose:**
- **Serverless** when traffic is low or bursty and cost while idle matters.
- **Fargate** when traffic is steady (it gets cheaper than Lambda at sustained load), when requests run longer than
  30 s (API Gateway's limit; relevant for long agent runs), or when you need long-lived connections such as streaming chat.

LLM usage is extra and usage-based on either option: about $0.005 per triage on Claude Haiku 4.5 (measured over the eval set; bigger poms up to ~$0.015)
(measured: ~3.5k tokens in and ~0.3k out across 1-2 model calls), so 1,000 triages cost roughly $5, less with cache hits. Check current Bedrock prices.

### The database on serverless (how phase 3 does it)

A Lambda that joins a VPC to reach a database loses internet access unless you add a NAT Gateway (~$33/month),
and DepGuard needs the internet to call OSV. Two ways to avoid that:
- **Aurora Serverless v2 with the RDS Data API** (what this repo does): the Lambda stays outside the VPC and runs SQL
  (including pgvector queries) over HTTPS with IAM auth. Aurora pauses at 0 capacity when idle.
  The trade-offs: no JDBC (so no connection pool and no Spring AI `PgVectorStore`), 1 MB per response, and a ~15 s
  wake-up after a pause.
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
| Monitoring | CloudWatch logs; Langfuse traces | Alarms on 5xx, latency and throttles; Langfuse alerts on cost and error rate |
| Secrets | Langfuse keys as Lambda environment variables (from `.env` via Terraform) | Secrets Manager, read at startup; no secrets in Terraform state |

### Cache: Redis (ElastiCache) or pgvector?

The phase 6 semantic cache answers "have we answered a question like this before?", which is a vector-similarity lookup.
- **pgvector in the existing Postgres:** no extra service or cost, one less thing to operate. Recommended, especially on serverless.
- **ElastiCache (Redis/Valkey):** lower latency at high request rates, and also useful for rate limiting and sessions.
  Adds about $12/month, and a Lambda must join the VPC to reach it (see "The database on serverless" above).

## Roadmap

| Phase | Adds | Capability |
|---|---|---|
| **1** ✅ | Scan API over OSV | Spring Boot Web, Java 21, API design |
| **2** ✅ | Spring AI agent on Bedrock with tools (OSV, EPSS, CISA KEV), prompt templates, citation guardrail | Agent tool calling, prompt design |
| **3** ✅ | Hybrid RAG over advisories and migration guides (pgvector + full-text + RRF), Aurora via the Data API | RAG, data |
| **4** ✅ | Golden-set evals: oracle from live OSV/EPSS/KEV, code checks + Nova Pro judge, retrieval metrics, CI workflow | Evals design |
| **5** ✅ | Langfuse traces: agent, model calls (prompt, tokens, cost), tools, retrieval; eval scores per trace | LLM observability |
| **6** ✅ | Facts in code (workflow + agent), no-model route, answer cache; prompt caching measured and dropped | Cost and performance optimization |
| **7** ✅ | 8-model benchmark: quality vs latency vs cost vs risk; Haiku 4.5 kept, routing rejected by data; hard tool-call cap | Model selection |
| 8 | Spring Security (JWT, roles), human-approved upgrade PRs, production hardening of the AWS stack | Security, guardrails, cloud |

## Data sources

All public and free:
- [OSV.dev](https://osv.dev): advisories, including the GitHub Advisory Database (CC-BY 4.0). No key.
- [FIRST EPSS](https://www.first.org/epss/): probability that a CVE is exploited in the next 30 days. No key.
- [CISA KEV](https://www.cisa.gov/known-exploited-vulnerabilities-catalog): CVEs confirmed exploited in the wild. No key.
- [OSV's Maven export](https://osv-vulnerabilities.storage.googleapis.com/Maven/all.zip): the same advisories in bulk, for the knowledge base.
- Migration guides, fetched at ingest time (not stored in this repo): the Spring Boot 3.0 and 4.0 migration guides and
  Spring Framework 6.0 and 7.0 release notes (project wikis), and [Migrating to Jackson 3](https://github.com/FasterXML/jackson/blob/main/jackson3/MIGRATING_TO_JACKSON_3.md).
- Later: the [NVD API](https://nvd.nist.gov/developers) for CVSS details (free key; deferred because OSV already covers severity).
