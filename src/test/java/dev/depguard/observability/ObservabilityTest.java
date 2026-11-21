package dev.depguard.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.observation.ChatModelObservationContext;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.observation.ToolCallingObservationContext;

/** The tracing pieces, without Langfuse: what ends up on spans, and what must never end up there. */
class ObservabilityTest {

    private static final String HAIKU = "us.anthropic.claude-haiku-4-5-20251001-v1:0";
    private static final ModelPricing PRICING = new ModelPricing(List.of(new ModelPricing.Price(HAIKU, 1.0, 5.0)));

    @Test
    void propertiesBuildTheOtlpEndpointAndNeverPrintTheSecret() {
        var props = new LangfuseProperties("https://cloud.langfuse.com/", "pk-lf-1", "sk-lf-secret", true, false);

        assertThat(props.tracesEndpoint()).isEqualTo("https://cloud.langfuse.com/api/public/otel/v1/traces");
        assertThat(props.enabled()).isTrue();
        assertThat(props.toString()).doesNotContain("sk-lf-secret").contains("secretKey=***");
        assertThat(new LangfuseProperties("https://x", "", "", true, false).enabled()).isFalse();
        // Values copied from Langfuse's snippet arrive quoted from .env
        var quoted = new LangfuseProperties("\"https://us.cloud.langfuse.com\"", "\"pk-lf-1\"", "'sk-lf-2'", true, false);
        assertThat(quoted.publicKey()).isEqualTo("pk-lf-1");
        assertThat(quoted.secretKey()).isEqualTo("sk-lf-2");
        assertThat(quoted.tracesEndpoint()).isEqualTo("https://us.cloud.langfuse.com/api/public/otel/v1/traces");
    }

    @Test
    void modelCallSpansGetPromptAnswerAndCost() {
        Map<String, String> attributes = attributesOf(new ChatContentObservationFilter(PRICING, true, HAIKU).map(chatContext()));

        assertThat(attributes.get("gen_ai.prompt")).contains("[USER] Which dependencies first?");
        assertThat(attributes.get("gen_ai.completion")).contains("[ASSISTANT] Upgrade log4j-core");
        // 1,000 input tokens at $1/M + 200 output tokens at $5/M = $0.002
        assertThat(attributes.get("gen_ai.usage.cost")).isEqualTo("0.002000");
        assertThat(attributes).containsEntry("langfuse.observation.model.name", HAIKU);
    }

    @Test
    void withCaptureOffOnlyTheCostIsAdded() {
        Map<String, String> attributes = attributesOf(new ChatContentObservationFilter(PRICING, false, HAIKU).map(chatContext()));

        assertThat(attributes).doesNotContainKeys("gen_ai.prompt", "gen_ai.completion").containsKey("gen_ai.usage.cost");
    }

    @Test
    void unknownModelsGetNoCostRatherThanAWrongOne() {
        assertThat(PRICING.costOf("some.other-model", 1000, 200)).isEmpty();
    }

    @Test
    void toolCallSpansShowArgumentsAndResultAsInputAndOutput() {
        var tool = ToolCallingObservationContext.builder()
                .toolDefinition(ToolDefinition.builder().name("checkKnownExploited").description("KEV lookup")
                        .inputSchema("{}").build())
                .toolCallArguments("{\"cveIds\":[\"CVE-2021-44228\"]}")
                .toolCallResult("{\"results\":[{\"cve\":\"CVE-2021-44228\",\"knownExploited\":true}]}")
                .build();

        Map<String, String> attributes = attributesOf(new ChatContentObservationFilter(PRICING, true, HAIKU).map(tool));

        assertThat(attributes).containsEntry("langfuse.observation.type", "tool");
        assertThat(attributes.get("langfuse.observation.input")).contains("CVE-2021-44228");
        assertThat(attributes.get("langfuse.observation.output")).contains("knownExploited");
    }

    @Test
    void aiTracingRecordsTypeOutputMetadataAndWarnings() {
        List<Observation.Context> finished = new ArrayList<>();
        ObservationRegistry registry = recordingRegistry(finished);
        var tracing = new AiTracing(registry, null, new LangfuseProperties("h", "", "", true, false));

        String result = tracing.observe("depguard.triage", "agent", "the question", step -> {
            step.output("the answer");
            step.metadata("unverified_cves", 1);
            step.warning("Guardrail: unverified CVE-2099-0001");
            return "done";
        });

        assertThat(result).isEqualTo("done");
        Map<String, String> attributes = attributesOf(finished.getFirst());
        assertThat(finished.getFirst().getName()).isEqualTo("depguard.triage");
        assertThat(attributes).containsEntry("langfuse.observation.type", "agent")
                .containsEntry("langfuse.observation.input", "the question")
                .containsEntry("langfuse.observation.output", "the answer")
                .containsEntry("langfuse.observation.metadata.unverified_cves", "1")
                .containsEntry("langfuse.observation.level", "WARNING");
    }

    @Test
    void spansStartedInASessionCarryItsId() {
        List<Observation.Context> finished = new ArrayList<>();
        var tracing = new AiTracing(recordingRegistry(finished), null, new LangfuseProperties("h", "", "", true, false));

        AiTracing.inSession("eval-20261121-120000", () -> tracing.observe("depguard.triage", "agent", "q", step -> "a"));
        tracing.observe("depguard.triage", "agent", "q", step -> "a"); // outside: no session

        assertThat(attributesOf(finished.get(0))).containsEntry("langfuse.session.id", "eval-20261121-120000");
        assertThat(attributesOf(finished.get(1))).doesNotContainKey("langfuse.session.id");
    }

    @Test
    void aiTracingLeavesContentOutWhenCaptureIsOff() {
        List<Observation.Context> finished = new ArrayList<>();
        var tracing = new AiTracing(recordingRegistry(finished), null, new LangfuseProperties("h", "", "", false, false));

        tracing.observe("depguard.retrieval", "retriever", "secret-ish query", step -> {
            step.output("titles");
            step.metadata("hits", 5);
            return null;
        });

        assertThat(attributesOf(finished.getFirst()))
                .doesNotContainKeys("langfuse.observation.input", "langfuse.observation.output")
                .containsEntry("langfuse.observation.metadata.hits", "5");
    }

    private static ChatModelObservationContext chatContext() {
        var context = ChatModelObservationContext.builder()
                .prompt(new Prompt(List.of(new UserMessage("Which dependencies first?"))))
                .provider("bedrock_converse")
                .build();
        context.setResponse(new ChatResponse(List.of(new Generation(new AssistantMessage("Upgrade log4j-core first."))),
                ChatResponseMetadata.builder().model(HAIKU).usage(new DefaultUsage(1000, 200)).build()));
        return context;
    }

    private static ObservationRegistry recordingRegistry(List<Observation.Context> finished) {
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new ObservationHandler<>() {
            @Override
            public void onStop(Observation.Context context) {
                finished.add(context);
            }

            @Override
            public boolean supportsContext(Observation.Context context) {
                return true;
            }
        });
        return registry;
    }

    private static Map<String, String> attributesOf(Observation.Context context) {
        return StreamSupport.stream(context.getAllKeyValues().spliterator(), false)
                .collect(Collectors.toMap(KeyValue::getKey, KeyValue::getValue, (a, b) -> b));
    }
}
