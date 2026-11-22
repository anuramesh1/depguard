package dev.depguard.agent;

import dev.depguard.agent.tools.KnowledgeTools;
import dev.depguard.cache.AnswerCache;
import dev.depguard.dependency.Dependency;
import dev.depguard.dependency.ParsedPom;
import dev.depguard.dependency.PomParser;
import dev.depguard.observability.AiTracing;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.model.tool.ToolCallLimitExceededException;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.exception.SdkException;
import tools.jackson.databind.json.JsonMapper;

/**
 * The triage: given a pom.xml and a question, explains which dependencies to upgrade first and why.
 *
 * <p>How one request runs (a "workflow + agent" design since phase 6):
 * <ol>
 *   <li>Parse the pom in plain Java (no LLM needed to read XML).</li>
 *   <li>Same dependencies asked about recently? Reuse that answer ({@link AnswerCache}): free.</li>
 *   <li>Gather the facts in code ({@link TriageFactsService}): OSV findings, EPSS and CISA KEV per CVE, ranked
 *       most urgent first. These lookups are the same every time, so a model deciding to make them only added
 *       round trips.</li>
 *   <li>Nothing vulnerable? Answer without calling the model at all: free and instant.</li>
 *   <li>Otherwise send the facts to the model with one tool, searchAdvisories (RAG over migration guides). The
 *       model decides whether a major upgrade needs the guides, then writes the explanation.</li>
 *   <li>Check the answer's CVE IDs against the facts and search results (guardrail).</li>
 * </ol>
 *
 * <p>The whole triage is traced as one "agent" span (see {@link AiTracing}); its trace ID is in the result.
 */
@Service
public class TriageAgent {

    private static final Logger log = LoggerFactory.getLogger(TriageAgent.class);
    static final String DEFAULT_QUESTION = "Which dependencies should I upgrade first, and why?";
    /** Reported as the model when the answer needed no model call. */
    static final String NO_MODEL = "none (no vulnerable dependencies)";

    private final ChatClient chatClient;
    private final PomParser pomParser;
    private final TriageFactsService factsService;
    private final KnowledgeTools knowledgeTools;
    private final AiTracing tracing;
    private final JsonMapper jsonMapper;
    private final Resource systemPrompt;
    private final Resource userPrompt;
    private final String model;
    private final AnswerCache answerCache;
    /** Changes when the model or prompts change, so cached answers from an older setup are never reused. */
    private final String cacheVersion;

    public TriageAgent(
            ChatClient.Builder chatClientBuilder,
            PomParser pomParser,
            TriageFactsService factsService,
            KnowledgeTools knowledgeTools,
            AiTracing tracing,
            JsonMapper jsonMapper,
            @Value("classpath:prompts/triage-system.st") Resource systemPrompt,
            @Value("classpath:prompts/triage-user.st") Resource userPrompt,
            @Value("${spring.ai.bedrock.converse.chat.options.model:unknown}") String model,
            AnswerCache answerCache) {
        this.chatClient = chatClientBuilder.build();
        this.pomParser = pomParser;
        this.factsService = factsService;
        this.knowledgeTools = knowledgeTools;
        this.tracing = tracing;
        this.jsonMapper = jsonMapper;
        this.systemPrompt = systemPrompt;
        this.userPrompt = userPrompt;
        this.model = model;
        this.answerCache = answerCache;
        this.cacheVersion = AnswerCache.sha256(model + "\n" + read(systemPrompt) + "\n" + read(userPrompt));
    }

    private static String read(Resource resource) {
        try {
            return resource.getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Cannot read prompt " + resource, e);
        }
    }

    public TriageResult triage(String pomXml, String question) {
        long start = System.nanoTime();
        ParsedPom pom = pomParser.parse(pomXml);
        String userQuestion = question == null || question.isBlank() ? DEFAULT_QUESTION : question.trim();
        String dependencies = formatDependencies(pom.dependencies());

        // One "agent" span for the whole triage: the facts, model, tool and retrieval spans become its children.
        return tracing.observe("depguard.triage", "agent", userQuestion + "\n\n" + dependencies, step -> {
            if (pom.dependencies().isEmpty()) {
                return answerWithoutModel(step, "No dependencies with a known version were found in this pom.xml.",
                        pom, start);
            }

            // Asked recently about the same dependencies? Reuse that answer: no lookups, no model, no cost.
            String cacheKey = AnswerCache.key(cacheVersion, cacheLines(pom));
            Optional<AnswerCache.Hit> hit = tracing.observe("depguard.answer_cache", "span", userQuestion, cacheStep -> {
                Optional<AnswerCache.Hit> found = answerCache.lookup(cacheKey, userQuestion);
                cacheStep.metadata("hit", found.isPresent());
                found.ifPresent(h -> {
                    cacheStep.metadata("match", h.match());
                    cacheStep.metadata("similarity", String.format(java.util.Locale.ROOT, "%.3f", h.similarity()));
                });
                return found;
            });
            if (hit.isPresent()) {
                return fromCache(step, hit.get(), start);
            }

            TriageFacts facts = tracing.observe("depguard.facts", "span", dependencies, factsStep -> {
                TriageFacts gathered = factsService.gather(pom.dependencies());
                factsStep.output(toJson(gathered));
                factsStep.metadata("vulnerable", gathered.vulnerable().size());
                return gathered;
            });
            if (facts.vulnerable().isEmpty()) {
                return answerWithoutModel(step, nothingToUpgrade(facts, pom), pom, start);
            }

            TriageSession session = new TriageSession();
            session.recordCves(facts.cveIds());
            // The guide search is offered only when a major upgrade needs migration notes. Without it, the model
            // added notes about upgrades nobody needs (e.g. "Jackson 3" for a 2.x fix): found by the evals.
            boolean offerGuides = facts.anyMajorUpgrade();
            ChatClient.ChatClientRequestSpec request = chatClient.prompt()
                    .system(systemPrompt)
                    .user(user -> user.text(userPrompt)
                            .param("dependencies", dependencies)
                            .param("facts", toJson(facts))
                            .param("upgradeNotes", offerGuides
                                    ? "Some upgrades are major-version upgrades: search the guides for them."
                                    : "No upgrade is a major-version upgrade: omit the Upgrade notes section.")
                            .param("question", userQuestion))
                    .toolContext(Map.of(TriageSession.KEY, session));
            if (offerGuides) {
                request = request.tools(knowledgeTools);
            }
            // No prompt caching, by measurement (phase 6): Haiku 4.5 caches only prompts of 4,096+ tokens. The first
            // call here is ~2,200 tokens, and the second (~5,200) is the last, so nothing would ever be read back.
            // Caching pays when a long prefix is re-sent; the workflow removed exactly those re-sends.
            ChatResponse response;
            try {
                response = request.call().chatResponse();
            } catch (ToolCallLimitExceededException | SdkException e) {
                // The model looped on tool calls past the hard limit, or Bedrock failed: the facts are still known,
                // so answer from them rather than fail. (Phase 7: Nova Lite made 40 searches in one triage.)
                log.warn("Model answer unavailable, answering from facts: {}", e.getMessage());
                step.warning("Facts-only answer: " + e.getClass().getSimpleName());
                return answerWithoutModel(step, factsOnly(facts, pom), pom, start, "facts-only");
            }

            String answer = withUncheckedLine(response == null || response.getResult() == null
                    ? ""
                    : response.getResult().getOutput().getText(), pom);
            CveCitationGuard.Result citations = CveCitationGuard.check(answer, session.knownCves());
            Usage usage = response == null ? null : response.getMetadata().getUsage();

            // What Langfuse shows for this trace: the answer, and the facts you'd filter on.
            step.output(answer);
            step.metadata("route", "model");
            step.metadata("dependencies", pom.dependencies().size());
            step.metadata("vulnerable", facts.vulnerable().size());
            step.metadata("tool_calls", session.toolCalls().size());
            step.metadata("cited_cves", citations.cited().size());
            step.metadata("unverified_cves", citations.unverified().size());
            step.metadata("sources", session.sources().size());
            if (!citations.unverified().isEmpty()) {
                step.warning("Guardrail: the answer cites CVEs no tool returned: " + citations.unverified());
            }

            TriageResult result = new TriageResult(
                    answer,
                    citations.cited(),
                    citations.unverified(),
                    session.toolCalls(),
                    session.sources(),
                    pom.skipped(),
                    model,
                    usage == null ? null : usage.getPromptTokens(),
                    usage == null ? null : usage.getCompletionTokens(),
                    usage == null ? null : usage.getCacheReadInputTokens(),
                    usage == null ? null : usage.getCacheWriteInputTokens(),
                    elapsedMs(start),
                    step.traceId(),
                    null);
            if (citations.unverified().isEmpty()) {
                answerCache.store(cacheKey, userQuestion, result); // never reuse an answer the guardrail flagged
            }
            return result;
        });
    }

    /** A reused answer: this request spent no tokens, so its token counts are 0; cachedAt says when it was made. */
    private TriageResult fromCache(AiTracing.Step step, AnswerCache.Hit hit, long start) {
        TriageResult cached = hit.result();
        step.output(cached.answer());
        step.metadata("route", "cache-" + hit.match());
        return new TriageResult(cached.answer(), cached.citedCves(), cached.unverifiedCves(), cached.toolCalls(),
                cached.sources(), cached.skipped(), cached.model(), 0, 0, 0L, 0L, elapsedMs(start), step.traceId(),
                hit.createdAt());
    }

    /** What makes two triages "the same dependencies": every dependency with its scope, plus unchecked ones. */
    private static List<String> cacheLines(ParsedPom pom) {
        List<String> lines = new java.util.ArrayList<>(pom.dependencies().stream()
                .map(d -> d.groupId() + ":" + d.artifactId() + ":" + d.version() + ":" + d.scope()).toList());
        pom.skipped().forEach(s -> lines.add("skipped " + s));
        return lines;
    }

    /**
     * Lists dependencies that couldn't be checked (no version in the pom). Written by code: the model left this line
     * out in 2 of 2 eval runs, and code knows the list exactly.
     */
    static String withUncheckedLine(String answer, ParsedPom pom) {
        if (pom.skipped().isEmpty()) {
            return answer;
        }
        return answer.stripTrailing() + "\n\nNot checked (version unknown): " + pom.skipped().stream()
                .map(s -> s.replaceFirst(" \\(.*", "")).collect(Collectors.joining(", ")) + "\n";
    }

    /** Route for answers code can write by itself: no tokens spent, nothing to verify. */
    private TriageResult answerWithoutModel(AiTracing.Step step, String answer, ParsedPom pom, long start) {
        return answerWithoutModel(step, answer, pom, start, "no-model");
    }

    private TriageResult answerWithoutModel(AiTracing.Step step, String answer, ParsedPom pom, long start, String route) {
        step.output(answer);
        step.metadata("route", route);
        return new TriageResult(answer, List.of(), List.of(), List.of(), List.of(), pom.skipped(), NO_MODEL,
                0, 0, 0L, 0L, elapsedMs(start), step.traceId(), null);
    }

    /**
     * The fallback when the model can't answer: the ranked facts as a plain list. Less explanation, but every
     * vulnerable dependency, in the right order, with its fix, known exploitation and exploit probability.
     */
    static String factsOnly(TriageFacts facts, ParsedPom pom) {
        StringBuilder answer = new StringBuilder("## Upgrade first\n");
        for (TriageFacts.VulnerableDependency d : facts.vulnerable()) {
            answer.append(d.rank()).append(". ").append(d.dependency()).append(" → ")
                    .append(d.recommendedVersion() == null ? "no fixed version yet" : d.recommendedVersion())
                    .append(": ").append(d.worstSeverity()).append(", ").append(d.advisoryCount()).append(" advisories");
            if (d.knownExploited()) {
                answer.append(", known exploited (CISA KEV)");
            }
            if (d.maxEpss() != null) {
                answer.append(String.format(java.util.Locale.ROOT, ", EPSS up to %.2f%%", d.maxEpss() * 100));
            }
            answer.append(". CVEs: ").append(d.topAdvisories().stream().flatMap(a -> a.cves().stream())
                    .map(TriageFacts.Cve::id).distinct().collect(Collectors.joining(", "))).append('\n');
        }
        answer.append("\n## Can wait\nNothing else.\n\n_The AI explanation is unavailable right now; this is DepGuard's ranked "
                + "list from OSV, EPSS and CISA KEV._\n");
        return withUncheckedLine(answer.toString(), pom);
    }

    /** Same sections as a model answer, so clients and evals read both the same way. */
    static String nothingToUpgrade(TriageFacts facts, ParsedPom pom) {
        return withUncheckedLine("## Upgrade first\nNothing to upgrade: none of the " + facts.dependenciesChecked()
                + " checked dependencies has a known vulnerability in OSV.\n\n## Can wait\nNothing else.\n", pom);
    }

    private String toJson(TriageFacts facts) {
        return jsonMapper.writeValueAsString(facts);
    }

    /** One line per dependency: "groupId:artifactId:version (scope)". */
    private static String formatDependencies(List<Dependency> dependencies) {
        return dependencies.stream()
                .map(d -> "- " + d.groupId() + ":" + d.artifactId() + ":" + d.version() + " (" + d.scope() + ")")
                .collect(Collectors.joining("\n"));
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
