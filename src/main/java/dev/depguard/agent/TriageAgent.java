package dev.depguard.agent;

import dev.depguard.agent.tools.ExploitTools;
import dev.depguard.agent.tools.VulnerabilityTools;
import dev.depguard.dependency.Dependency;
import dev.depguard.dependency.ParsedPom;
import dev.depguard.dependency.PomParser;
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
 *   <li>Send the dependency list and question to the model, with three tools available. Spring AI runs
 *       the tool-calling loop: the model asks for a tool, Spring AI calls our Java method and sends the
 *       result back, until the model writes its answer. Typically: findVulnerabilities, then
 *       getExploitProbability + checkKnownExploited together, then the answer.</li>
 *   <li>Check the answer's CVE IDs against what the tools returned (guardrail).</li>
 * </ol>
 */
@Service
public class TriageAgent {

    static final String DEFAULT_QUESTION = "Which dependencies should I upgrade first, and why?";

    private final ChatClient chatClient;
    private final PomParser pomParser;
    private final VulnerabilityTools vulnerabilityTools;
    private final ExploitTools exploitTools;
    private final Resource systemPrompt;
    private final Resource userPrompt;
    private final String model;

    public TriageAgent(
            ChatClient.Builder chatClientBuilder,
            PomParser pomParser,
            VulnerabilityTools vulnerabilityTools,
            ExploitTools exploitTools,
            @Value("classpath:prompts/triage-system.st") Resource systemPrompt,
            @Value("classpath:prompts/triage-user.st") Resource userPrompt,
            @Value("${spring.ai.bedrock.converse.chat.options.model:unknown}") String model) {
        this.chatClient = chatClientBuilder.build();
        this.pomParser = pomParser;
        this.vulnerabilityTools = vulnerabilityTools;
        this.exploitTools = exploitTools;
        this.systemPrompt = systemPrompt;
        this.userPrompt = userPrompt;
        this.model = model;
    }

    public TriageResult triage(String pomXml, String question) {
        long start = System.nanoTime();
        ParsedPom pom = pomParser.parse(pomXml);
        if (pom.dependencies().isEmpty()) {
            // Nothing to check: answer without spending tokens.
            return new TriageResult("No dependencies with a known version were found in this pom.xml.",
                    List.of(), List.of(), List.of(), pom.skipped(), model, 0, 0, elapsedMs(start));
        }

        TriageSession session = new TriageSession();
        ChatResponse response = chatClient.prompt()
                .system(systemPrompt)
                .user(user -> user.text(userPrompt)
                        .param("dependencies", formatDependencies(pom.dependencies()))
                        .param("skipped", pom.skipped().isEmpty() ? "none" : String.join("\n", pom.skipped()))
                        .param("question", question == null || question.isBlank() ? DEFAULT_QUESTION : question.trim()))
                .tools(vulnerabilityTools, exploitTools)
                .toolContext(Map.of(TriageSession.KEY, session))
                .call()
                .chatResponse();

        String answer = response == null || response.getResult() == null
                ? ""
                : response.getResult().getOutput().getText();
        CveCitationGuard.Result citations = CveCitationGuard.check(answer, session.knownCves());
        Usage usage = response == null ? null : response.getMetadata().getUsage();

        return new TriageResult(
                answer,
                citations.cited(),
                citations.unverified(),
                session.toolCalls(),
                pom.skipped(),
                model,
                usage == null ? null : usage.getPromptTokens(),
                usage == null ? null : usage.getCompletionTokens(),
                elapsedMs(start));
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
