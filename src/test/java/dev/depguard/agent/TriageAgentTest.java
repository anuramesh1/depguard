package dev.depguard.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.depguard.agent.TriageFacts.Advisory;
import dev.depguard.agent.TriageFacts.Cve;
import dev.depguard.agent.TriageFacts.VulnerableDependency;
import dev.depguard.agent.tools.KnowledgeTools;
import dev.depguard.cache.AnswerCache;
import dev.depguard.dependency.PomParser;
import dev.depguard.observability.AiTracing;
import dev.depguard.osv.Severity;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tests the triage's own logic with a fake model: the facts in the prompt, the no-model route and the citation
 * guardrail. No Bedrock calls, so it's fast and free. (Answer quality is what the evals measure.)
 */
class TriageAgentTest {

    private static final String POM = """
            <project><dependencies>
              <dependency><groupId>org.apache.commons</groupId><artifactId>commons-text</artifactId><version>1.9</version></dependency>
              <dependency><groupId>org.slf4j</groupId><artifactId>slf4j-api</artifactId></dependency>
            </dependencies></project>
            """;

    private static final TriageFacts TEXT4SHELL = new TriageFacts(1, List.of(new VulnerableDependency(
            1, "org.apache.commons:commons-text:1.9", "compile", Severity.CRITICAL, 1, "1.10.0", false, false, false, 0.9993,
            List.of(new Advisory("GHSA-599f-7c49-w659", Severity.CRITICAL, "1.10.0", "Arbitrary code execution",
                    List.of(new Cve("CVE-2022-42889", 0.9993, false, false)))))), List.of());

    /** Returns a fixed answer and remembers the prompts it was sent. */
    static class FakeModel implements ChatModel {
        final List<Prompt> prompts = new ArrayList<>();
        final String answer;

        FakeModel(String answer) {
            this.answer = answer;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            prompts.add(prompt);
            return new ChatResponse(List.of(new Generation(new AssistantMessage(answer))));
        }
    }

    private final TriageFactsService factsService = mock(TriageFactsService.class);

    private TriageAgent agent(FakeModel model) {
        return agent(model, AnswerCache.disabled());
    }

    private TriageAgent agent(FakeModel model, AnswerCache cache) {
        return new TriageAgent(
                ChatClient.builder(model),
                new PomParser(),
                factsService,
                mock(KnowledgeTools.class),
                AiTracing.noop(),
                JsonMapper.builder().build(),
                new ClassPathResource("prompts/triage-system.st"),
                new ClassPathResource("prompts/triage-user.st"),
                "fake-model",
                cache);
    }

    @Test
    void sendsTheRankedFactsAndTheQuestionToTheModel() {
        when(factsService.gather(anyList())).thenReturn(TEXT4SHELL);
        FakeModel model = new FakeModel("## Upgrade first\n1. commons-text 1.9 → 1.10.0 (CVE-2022-42889)");

        TriageResult result = agent(model).triage(POM, "What first?");

        String userMessage = model.prompts.getFirst().getUserMessage().getText();
        assertThat(userMessage)
                .contains("- org.apache.commons:commons-text:1.9 (compile)")
                .contains("\"cves\":[{\"id\":\"CVE-2022-42889\",\"epss\":0.9993")
                .contains("My question: What first?");
        assertThat(model.prompts.getFirst().getSystemMessage().getText()).contains("You are DepGuard");
        // The CVE came from the facts, so the guardrail accepts it.
        assertThat(result.citedCves()).containsExactly("CVE-2022-42889");
        assertThat(result.unverifiedCves()).isEmpty();
        assertThat(result.model()).isEqualTo("fake-model");
        // No major upgrade: no guide search offered, and the model is told to skip upgrade notes.
        assertThat(userMessage).contains("omit the Upgrade notes section");
        // Unchecked dependencies are listed by code, not left to the model.
        assertThat(result.answer()).endsWith("Not checked (version unknown): org.slf4j:slf4j-api\n");
    }

    @Test
    void flagsCvesThatAreNotInTheFacts() {
        when(factsService.gather(anyList())).thenReturn(TEXT4SHELL);
        FakeModel model = new FakeModel("Upgrade now because of CVE-2022-42889 and CVE-2099-0001.");

        TriageResult result = agent(model).triage(POM, null);

        assertThat(result.unverifiedCves()).containsExactly("CVE-2099-0001");
        assertThat(model.prompts.getFirst().getUserMessage().getText()).contains(TriageAgent.DEFAULT_QUESTION);
    }

    @Test
    void nothingVulnerableIsAnsweredWithoutTheModel() {
        when(factsService.gather(anyList())).thenReturn(new TriageFacts(1, List.of(), List.of()));
        FakeModel model = new FakeModel("should not be used");

        TriageResult result = agent(model).triage(POM, null);

        assertThat(model.prompts).isEmpty();
        assertThat(result.model()).isEqualTo(TriageAgent.NO_MODEL);
        assertThat(result.inputTokens()).isZero();
        assertThat(result.answer()).contains("## Upgrade first", "Nothing to upgrade", "Not checked (version unknown): org.slf4j:slf4j-api");
    }

    @Test
    void aCachedAnswerIsReturnedWithoutFactsOrModel() {
        AnswerCache cache = mock(AnswerCache.class);
        TriageResult earlier = new TriageResult("## Upgrade first\n1. commons-text", List.of(), List.of(), List.of(),
                List.of(), List.of(), "fake-model", 3000, 300, 0L, 0L, 2500, "old-trace", null);
        when(cache.lookup(anyString(), anyString()))
                .thenReturn(Optional.of(new AnswerCache.Hit(earlier, "exact", 1.0, "2026-11-21 15:00:00+00")));
        FakeModel model = new FakeModel("should not be used");

        TriageResult result = agent(model, cache).triage(POM, null);

        assertThat(model.prompts).isEmpty();
        org.mockito.Mockito.verifyNoInteractions(factsService);
        assertThat(result.answer()).isEqualTo(earlier.answer());
        assertThat(result.cachedAt()).isEqualTo("2026-11-21 15:00:00+00");
        assertThat(result.inputTokens()).isZero(); // this request spent nothing
    }

    @Test
    void emptyPomIsAnsweredWithoutTheModel() {
        FakeModel model = new FakeModel("should not be used");

        TriageResult result = agent(model).triage("<project/>", null);

        assertThat(model.prompts).isEmpty();
        assertThat(result.answer()).contains("No dependencies");
    }
}
