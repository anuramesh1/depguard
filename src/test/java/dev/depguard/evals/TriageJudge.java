package dev.depguard.evals;

import dev.depguard.agent.TriageResult;
import dev.depguard.evals.GoldenSet.TriageCase;
import dev.depguard.evals.TriageOracle.DependencyFacts;
import dev.depguard.evals.TriageOracle.Expected;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import org.springframework.ai.bedrock.converse.BedrockChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ResponseEntity;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.core.io.ClassPathResource;

/**
 * LLM-as-judge: grades what code can't, such as whether the upgrade notes are correct and relevant.
 *
 * <p>Design choices that make a judge trustworthy enough to use:
 * <ul>
 *   <li><b>A different model family</b> (Amazon Nova Pro) from the agent (Claude): judges tend to prefer
 *       answers in their own style.</li>
 *   <li><b>The judge gets the facts</b> (the oracle's data), so it grades against evidence, not its memory.</li>
 *   <li><b>A rubric with anchored 1-5 scales</b> and a required list of concrete problems, in JSON
 *       (Spring AI's structured output), at temperature 0.</li>
 * </ul>
 */
final class TriageJudge {

    static final String JUDGE_MODEL = "us.amazon.nova-pro-v1:0";

    private final ChatClient chatClient;
    private final String rubric;

    TriageJudge(ChatModel chatModel) {
        this.chatClient = ChatClient.builder(chatModel).build();
        try {
            this.rubric = new ClassPathResource("evals/judge-rubric.md").getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Missing evals/judge-rubric.md", e);
        }
    }

    /** Scores 1 (bad) to 5 (excellent); see judge-rubric.md for what each means. */
    record Verdict(int factualAccuracy, int prioritization, int upgradeNotesAccuracy, int clarity, List<String> problems) {
        double average() {
            return (factualAccuracy + prioritization + upgradeNotesAccuracy + clarity) / 4.0;
        }
    }

    record Judgement(Verdict verdict, int inputTokens, int outputTokens) {
    }

    Judgement judge(TriageCase testCase, Expected expected, TriageResult result) {
        String evidence = """
                DEPENDENCIES IN THE POM:
                %s

                USER QUESTION: %s

                FACTS FROM THE TOOLS (ground truth; most urgent first by the ranking rule):
                %s
                Dependencies with no known advisories: %s
                Dependencies that could not be checked (no version): %s

                THE ASSISTANT'S ANSWER:
                %s
                """.formatted(
                String.join("\n", testCase.dependencies()),
                testCase.question() == null ? "(default) Which dependencies should I upgrade first, and why?" : testCase.question(),
                facts(expected),
                expected.clean().isEmpty() ? "none" : String.join(", ", expected.clean()),
                expected.skipped().isEmpty() ? "none" : String.join(", ", expected.skipped()),
                result.answer());

        ResponseEntity<ChatResponse, Verdict> response = chatClient.prompt()
                .system(rubric)
                .user(evidence)
                .options(BedrockChatOptions.builder().model(JUDGE_MODEL).temperature(0.0).maxTokens(800))
                .call()
                .responseEntity(Verdict.class);
        Usage usage = response.response() == null ? null : response.response().getMetadata().getUsage();
        return new Judgement(response.entity(),
                usage == null || usage.getPromptTokens() == null ? 0 : usage.getPromptTokens(),
                usage == null || usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens());
    }

    private static String facts(Expected expected) {
        if (expected.vulnerable().isEmpty()) {
            return "No vulnerable dependencies.";
        }
        return expected.vulnerable().stream().map(TriageJudge::fact).collect(Collectors.joining("\n"));
    }

    /**
     * Per-dependency and per-CVE facts. The first judge run flagged correct answers as wrong whenever the
     * evidence was coarser than the answer (one EPSS per dependency, rounded numbers, no per-CVE fixes),
     * so the judge now sees exactly what the agent's tools returned.
     */
    private static String fact(DependencyFacts d) {
        StringBuilder text = new StringBuilder(String.format(Locale.ROOT,
                "- %s (scope %s): %d advisories in total (the most severe are listed below); "
                        + "lowest version fixing all listed: %s; major-version upgrade: %s; any known exploited: %s%s%n",
                d.coordinates(), d.scope(), d.advisoryCount(),
                d.recommendedVersion() == null ? "none (at least one listed advisory has no fix)" : d.recommendedVersion(),
                d.majorUpgrade() ? "yes" : "no", d.knownExploited() ? "yes" : "no",
                d.ransomware() ? " (used in ransomware)" : ""));
        for (TriageOracle.CveFact c : d.cveFacts()) {
            text.append(String.format(Locale.ROOT, "    - %s: severity %s; fixed in %s; EPSS %s; CISA KEV: %s%n",
                    c.cve(), c.severity(), c.fixedVersion() == null ? "no fix" : c.fixedVersion(),
                    c.epss() == null ? "no score" : String.format(Locale.ROOT, "%.2f%%", c.epss() * 100),
                    c.knownExploited() ? "yes" : "no"));
        }
        return text.toString();
    }
}
