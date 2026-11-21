package dev.depguard.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import dev.depguard.agent.tools.ExploitTools;
import dev.depguard.agent.tools.VulnerabilityTools;
import dev.depguard.dependency.PomParser;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.io.ClassPathResource;

/**
 * Tests the agent's own logic with a fake model: prompt rendering, the empty-pom shortcut and the
 * citation guardrail. No Bedrock calls, so it's fast and free. (Answer quality is phase 4's evals.)
 */
class TriageAgentTest {

    private static final String POM = """
            <project><dependencies>
              <dependency><groupId>org.apache.commons</groupId><artifactId>commons-text</artifactId><version>1.9</version></dependency>
              <dependency><groupId>org.slf4j</groupId><artifactId>slf4j-api</artifactId></dependency>
            </dependencies></project>
            """;

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

    private TriageAgent agent(FakeModel model) {
        return new TriageAgent(
                ChatClient.builder(model),
                new PomParser(),
                mock(VulnerabilityTools.class),
                mock(ExploitTools.class),
                new ClassPathResource("prompts/triage-system.st"),
                new ClassPathResource("prompts/triage-user.st"),
                "fake-model");
    }

    @Test
    void sendsDependenciesSkippedItemsAndQuestionToTheModel() {
        FakeModel model = new FakeModel("## Upgrade first\n1. commons-text");

        TriageResult result = agent(model).triage(POM, "What first?");

        String userMessage = model.prompts.getFirst().getUserMessage().getText();
        assertThat(userMessage)
                .contains("- org.apache.commons:commons-text:1.9 (compile)")
                .contains("org.slf4j:slf4j-api (version managed by a parent pom or BOM)")
                .contains("My question: What first?");
        assertThat(model.prompts.getFirst().getSystemMessage().getText()).contains("You are DepGuard");
        assertThat(result.answer()).startsWith("## Upgrade first");
        assertThat(result.model()).isEqualTo("fake-model");
    }

    @Test
    void flagsCvesTheToolsNeverReturned() {
        // The fake model never calls tools, so any CVE it mentions is unverified.
        FakeModel model = new FakeModel("Upgrade now because of CVE-2022-42889.");

        TriageResult result = agent(model).triage(POM, null);

        assertThat(result.citedCves()).containsExactly("CVE-2022-42889");
        assertThat(result.unverifiedCves()).containsExactly("CVE-2022-42889");
        assertThat(model.prompts.getFirst().getUserMessage().getText()).contains(TriageAgent.DEFAULT_QUESTION);
    }

    @Test
    void emptyPomIsAnsweredWithoutCallingTheModel() {
        FakeModel model = new FakeModel("should not be used");

        TriageResult result = agent(model).triage("<project/>", null);

        assertThat(model.prompts).isEmpty();
        assertThat(result.answer()).contains("No dependencies");
    }
}
