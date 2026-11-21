# DepGuard: Product Spec

*An AI agent that triages vulnerable Java dependencies using live, open-source advisory data.*

---

## The problem

Dependency scanners list every known advisory, often dozens per project, and leave the hard questions to you:

> **"Which of my dependencies are actually dangerous, and what should I upgrade first?"**

**DepGuard** takes a `pom.xml` (later `build.gradle` or a public GitHub repo) and answers that question. It ranks
vulnerable dependencies by real risk, recommends safe upgrade versions, and explains what an upgrade involves,
for example that spring-web 5 → 6 also needs Java 17+ and the `javax` → `jakarta` change.

## Why this design

- **The data is real, live, open and free.** OSV.dev API (no key), NVD CVE API 2.0 (free key), the CISA Known Exploited
  Vulnerabilities feed, FIRST EPSS exploit-likelihood scores, and the GitHub Advisory Database (CC-BY 4.0).
  Advisories change daily, so answers reflect today's data.
- **Answers can be checked objectively.** "Does CVE-X affect `spring-web 5.3.20`?" has a correct answer in OSV, so the
  agent can be scored against ground truth instead of opinions.
- **It fits Java teams.** Spring Boot parsing Maven files is a natural fit, and every Java team has a `pom.xml` to try.

## Capabilities and where they live

| Capability | How DepGuard does it |
|---|---|
| **Tool calling** | Agent tools: `findVulnerabilities` (OSV), `getExploitProbability` (EPSS), `checkKnownExploited` (CISA KEV), `searchAdvisories` (RAG) |
| **Planning** | Parse dependencies → look up advisories → rank by severity, EPSS and KEV → write the upgrade plan |
| **Memory** | Each project's scan history ("what changed since last week?") |
| **Retries** | Backoff on the NVD rate limit; fall back to OSV if NVD is down |
| **Guardrails** | Never state a CVE that no tool returned (citation check); refuse off-topic questions; treat advisory text as data, not instructions |
| **Human approval** | "Open a GitHub PR with the upgrade" waits for a person to approve it |
| **RAG** | Hybrid search over every OSV Maven advisory and official migration guides: pgvector + Postgres full-text + Reciprocal Rank Fusion, in one SQL query |
| **Caching** | Semantic cache in pgvector for repeated questions; Bedrock prompt caching for the long system prompt; HTTP cache for OSV/NVD |
| **Batching** | One OSV batch query per pom; an ingest run that embeds new or changed advisories in bulk (content hashes skip the rest) |
| **Model routing** | A small model for classifying and extracting, a larger one for the final triage reasoning |
| **Model selection** | A benchmark harness runs one golden set on several models: accuracy, p95 latency, cost per query, hallucination rate |
| **Observability** | Spring AI Micrometer observations → OpenTelemetry spans → Langfuse: one trace per triage with model calls (prompt, tokens, cost), tool calls, retrieval and guardrail warnings; eval scores attached |
| **Security** | JWT auth with roles: `VIEWER` can scan, `MAINTAINER` can approve upgrade PRs |
| **Data** | Aurora Serverless v2 (Postgres + pgvector) through the RDS Data API |
| **Cloud** | API Gateway + Lambda (Java 21, SnapStart) via Terraform; about $0 while idle |

## Keeping it simple to read

**One concept per class**, one Spring Boot app, no microservices.

```text
depguard/
  dependency/   PomParser, Dependency
  osv/          OsvClient (later: NvdClient)
  exploit/      EpssClient, KevClient
  scan/         ScanService, ScanReport
  agent/        TriageAgent, tools/*, CveCitationGuard (later: ModelRouter, ApprovalService)
  rag/          HybridRetriever, AdvisoryStore (JDBC / RDS Data API), HybridSearchSql, ingest/*
  cache/        SemanticCache
  api/          ScanController, TriageController, KnowledgeController
  lambda/       StreamLambdaHandler (AWS Lambda entry point)
  security/     SecurityConfig, roles
  evals/        (test sources) TriageOracle, TriageChecks, TriageJudge, TriageEval, RetrievalEval
infra/          serverless/ (used), fargate/ (alternative)
```

## Build order

Each phase ends with something that can be demoed.

| Phase | Adds | Capability | Status |
|:---:|---|---|---|
| 1 | Scan API: parse pom, batch-query OSV, ranked report. No LLM. Serverless deploy. | REST API, Java 21, cloud | ✅ Done |
| 2 | Spring AI agent on Amazon Bedrock with tools (OSV, EPSS, CISA KEV); prompt templates in `resources/prompts/` | Tool calling, prompt design | ✅ Done |
| 3 | Hybrid RAG over advisories and migration guides; Aurora Serverless v2 via the RDS Data API | RAG, data | ✅ Done |
| 4 | Golden sets (16 triage poms, 30 retrieval queries), oracle from live data, code checks + LLM judge, CI workflow | Evals | ✅ Done |
| 5 | Langfuse traces: agent, model calls (prompt, tokens, cost), tools, retrieval; eval scores per trace | LLM observability | ✅ Done |
| 6 | Semantic cache, prompt caching, model router, with before/after numbers | Cost and performance | ⏭️ Next |
| 7 | Model benchmark: quality vs latency vs cost vs risk | Model selection | |
| 8 | Spring Security, human-approved upgrade PRs, production hardening | Security, guardrails | |

## Decisions

- **Spring AI over LangChain4j.** It is Spring-native, and its built-in Micrometer observability makes the Langfuse phase simpler. LangChain4j is a good alternative.
- **Amazon Bedrock for models.** On Lambda the function's IAM role calls Bedrock, so there is no API key to store. Claude Haiku handles tool calling; Sonnet is added for routing in phase 6.
- **Spring Boot 4.1 with Spring AI 2.1.** The project started on Boot 3.5, which stopped receiving free security patches on 30 June 2026. For a tool that flags outdated dependencies, staying on a supported line matters, so it moved to Boot 4.1 (free support until July 2027).
- **Own hybrid-search SQL instead of Spring AI's PgVectorStore.** On AWS the app reaches Aurora through the RDS Data API (HTTPS + IAM), which keeps the Lambda out of a VPC and avoids a NAT gateway; PgVectorStore needs JDBC. Writing the query by hand also keeps vector search, full-text search and Reciprocal Rank Fusion visible in one place.
- **Serverless on AWS.** Lambda with SnapStart and API Gateway costs about $0 while idle. Fargate is documented as an alternative for steady traffic or long requests.
- **Alternative domain, not chosen:** the openFDA drug-label and adverse-event APIs are also live and open, but their answers are harder to check objectively.

## Highlights so far

1. **Fast live scans.** A scan of the sample pom found 5 vulnerable dependencies and 76 advisories (including Log4Shell and Text4Shell) in about 2.2 seconds, using one batched call plus virtual threads.
2. **Why an agent.** A scanner recommends spring-web 5 → 6 but can't say that it also needs Java 17+ and the `javax` → `jakarta` change. The triage agent explains that, and ranks fixes by known exploitation (CISA KEV) and exploit probability (EPSS), not just severity.
3. **Grounded upgrade advice.** The agent looks up official migration guides (Spring Boot, Spring Framework, Jackson) through hybrid search, and the response lists the sources it read.
4. **Grounded answers.** Every CVE in an AI answer is checked in code against what the tools returned; in a live run all 11 cited CVEs were verified. A triage costs about $0.015.
5. **Measured quality.** Evals turned guesses into numbers: hybrid retrieval hit@5 0.87 vs 0.70 for vectors alone
   (after an eval exposed broken CVE-ID search), and the ranking rule followed in 100% of runs (from 97%).
6. **Secure by default.** The pom parser rejects DOCTYPE declarations (blocks XXE), and upstream errors return 503 without leaking internal details.
