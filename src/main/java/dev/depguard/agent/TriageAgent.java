package dev.depguard.agent;

import dev.depguard.agent.tools.ExploitTools;
import dev.depguard.agent.tools.KnowledgeTools;
import dev.depguard.agent.tools.VulnerabilityTools;
import dev.depguard.dependency.Dependency;
import dev.depguard.dependency.ParsedPom;
import dev.depguard.dependency.PomParser;
import dev.depguard.observability.AiTracing;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

/**
 * The triage agent: given a pom.xml and a question, decides which dependencies to upgrade first.
 *
 * <p>How one request runs:
 * <ol>
 *   <li>Parse the pom in plain Java (no LLM needed to read XML).</li>
 *   <li>Send the dependency list and question to the model, with four tools available. Spring AI runs
 *       the tool-calling loop: the model asks for a tool, Spring AI calls our Java method and sends the
 *       result back, until the model writes its answer. Typically: findVulnerabilities, then
 *       getExploitProbability + checkKnownExploited together, searchAdvisories for major upgrades (RAG),
 *       then the answer.</li>
 *   <li>Check the answer's CVE IDs against what the tools returned (guardrail).</li>
 * </ol>
 *
 * <p>The whole triage is traced as one "agent" span (see {@link AiTracing}); its trace ID is in the result.
 */
@Service
public class TriageAgent {

    static final String DEFAULT_QUESTION = "Which dependencies should I upgrade first, and why?";

    private final ChatClient chatClient;
    private final PomParser pomParser;
    private final VulnerabilityTools vulnerabilityTools;
    private final ExploitTools exploitTools;
    private final KnowledgeTools knowledgeTools;
    private final AiTracing tracing;
    private final Resource systemPrompt;
    private final Resource userPrompt;
    private final String model;

    public TriageAgent(
            ChatClient.Builder chatClientBuilder,
            PomParser pomParser,
            VulnerabilityTools vulnerabilityTools,
            ExploitTools exploitTools,
            KnowledgeTools knowledgeTools,
            AiTracing tracing,
            @Value("classpath:prompts/triage-system.st") Resource systemPrompt,
            @Value("classpath:prompts/triage-user.st") Resource userPrompt,
            @Value("${spring.ai.bedrock.converse.chat.options.model:unknown}") String model) {
        this.chatClient = chatClientBuilder.build();
        this.pomParser = pomParser;
        this.vulnerabilityTools = vulnerabilityTools;
        this.exploitTools = exploitTools;
        this.knowledgeTools = knowledgeTools;
        this.tracing = tracing;
        this.systemPrompt = systemPrompt;
        this.userPrompt = userPrompt;
        this.model = model;
    }

    public TriageResult triage(String pomXml, String question) {
        long start = System.nanoTime();
        ParsedPom pom = pomParser.parse(pomXml);
        String userQuestion = question == null || question.isBlank() ? DEFAULT_QUESTION : question.trim();
        String dependencies = formatDependencies(pom.dependencies());

        // One "agent" span for the whole triage: the model, tool and HTTP spans below become its children.
        return tracing.observe("depguard.triage", "agent", userQuestion + "\n\n" + dependencies, step -> {
            if (pom.dependencies().isEmpty()) {
                // Nothing to check: answer without spending tokens.
                String answer = "No dependencies with a known version were found in this pom.xml.";
                step.output(answer);
                return new TriageResult(answer, List.of(), List.of(), List.of(), List.of(), pom.skipped(), model,
                        0, 0, elapsedMs(start), step.traceId());
            }

            TriageSession session = new TriageSession();
            ChatResponse response = chatClient.prompt()
                    .system(systemPrompt)
                    .user(user -> user.text(userPrompt)
                            .param("dependencies", dependencies)
                            .param("skipped", pom.skipped().isEmpty() ? "none" : String.join("\n", pom.skipped()))
                            .param("question", userQuestion))
                    .tools(vulnerabilityTools, exploitTools, knowledgeTools)
                    .toolContext(Map.of(TriageSession.KEY, session))
                    .call()
                    .chatResponse();

            String answer = response == null || response.getResult() == null
                    ? ""
                    : response.getResult().getOutput().getText();
            CveCitationGuard.Result citations = CveCitationGuard.check(answer, session.knownCves());
            Usage usage = response == null ? null : response.getMetadata().getUsage();

            // What Langfuse shows for this trace: the answer, and the facts you'd filter on.
            step.output(answer);
            step.metadata("dependencies", pom.dependencies().size());
            step.metadata("tool_calls", session.toolCalls().size());
            step.metadata("cited_cves", citations.cited().size());
            step.metadata("unverified_cves", citations.unverified().size());
            step.metadata("sources", session.sources().size());
            if (!citations.unverified().isEmpty()) {
                step.warning("Guardrail: the answer cites CVEs no tool returned: " + citations.unverified());
            }

            return new TriageResult(
                    answer,
                    citations.cited(),
                    citations.unverified(),
                    session.toolCalls(),
                    session.sources(),
                    pom.skipped(),
                    model,
                    usage == null ? null : usage.getPromptTokens(),
                    usage == null ? null : usage.getCompletionTokens(),
                    elapsedMs(start),
                    step.traceId());
        });
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
