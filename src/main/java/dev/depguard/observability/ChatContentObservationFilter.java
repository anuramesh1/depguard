package dev.depguard.observability;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationFilter;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.observation.ChatModelObservationContext;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.observation.ToolCallingObservationContext;

/**
 * Adds what Langfuse needs to the spans Spring AI records: prompt, answer and cost on model calls, and
 * arguments and result on tool calls.
 *
 * <p>Why this exists: Spring AI 2.x's {@code log-prompt}/{@code log-completion} settings write content to the
 * application log, not onto the span, so Langfuse would show model calls without their text. This filter copies
 * the text onto the span using the OpenTelemetry GenAI attribute names Langfuse reads: {@code gen_ai.prompt},
 * {@code gen_ai.completion} and {@code gen_ai.usage.cost}.
 *
 * <p>Content is only added when capture is on; the cost always is (it reveals nothing).
 */
class ChatContentObservationFilter implements ObservationFilter {

    /** Long tool results (OSV advisories) are cut so one span can't grow huge. */
    static final int MAX_CHARS = 20_000;

    private final ModelPricing pricing;
    private final boolean captureContent;
    private final String defaultModel;

    ChatContentObservationFilter(ModelPricing pricing, boolean captureContent, String defaultModel) {
        this.pricing = pricing;
        this.captureContent = captureContent;
        this.defaultModel = defaultModel;
    }

    @Override
    public Observation.Context map(Observation.Context context) {
        if (context instanceof ToolCallingObservationContext tool) {
            return mapToolCall(tool);
        }
        if (!(context instanceof ChatModelObservationContext chat) || chat.getResponse() == null) {
            return context;
        }
        ChatResponse response = chat.getResponse();
        // Spring AI records the *request* model, which is "none" when it comes from defaults (as with Bedrock here);
        // Langfuse reads this attribute first, so it shows the real model (needed to compare models in phase 7).
        context.addHighCardinalityKeyValue(KeyValue.of("langfuse.observation.model.name", modelOf(chat)));
        if (captureContent) {
            context.addHighCardinalityKeyValue(KeyValue.of("gen_ai.prompt", prompt(chat.getRequest())));
            context.addHighCardinalityKeyValue(KeyValue.of("gen_ai.completion", completion(response)));
        }
        Usage usage = response.getMetadata() == null ? null : response.getMetadata().getUsage();
        if (usage != null && usage.getPromptTokens() != null && usage.getCompletionTokens() != null) {
            pricing.costOf(modelOf(chat), usage.getPromptTokens(), usage.getCompletionTokens(),
                            orZero(usage.getCacheReadInputTokens()), orZero(usage.getCacheWriteInputTokens()))
                    .ifPresent(cost -> context.addHighCardinalityKeyValue(KeyValue.of(
                            "gen_ai.usage.cost", String.format(Locale.ROOT, "%.6f", cost))));
        }
        return context;
    }

    /**
     * Tool calls: Spring AI records them, but under its own attribute names, which Langfuse doesn't show as
     * input/output. Mark the span as a "tool" and copy the arguments and result across.
     */
    private Observation.Context mapToolCall(ToolCallingObservationContext tool) {
        tool.addLowCardinalityKeyValue(KeyValue.of("langfuse.observation.type", "tool"));
        if (captureContent) {
            if (tool.getToolCallArguments() != null) {
                tool.addHighCardinalityKeyValue(KeyValue.of("langfuse.observation.input", truncate(tool.getToolCallArguments())));
            }
            if (tool.getToolCallResult() != null) {
                tool.addHighCardinalityKeyValue(KeyValue.of("langfuse.observation.output", truncate(tool.getToolCallResult())));
            }
        }
        return tool;
    }

    private static long orZero(Long value) {
        return value == null ? 0 : value;
    }

    private String modelOf(ChatModelObservationContext chat) {
        String fromResponse = chat.getResponse().getMetadata() == null ? null : chat.getResponse().getMetadata().getModel();
        if (fromResponse != null && !fromResponse.isBlank()) {
            return fromResponse;
        }
        Prompt prompt = chat.getRequest();
        String fromRequest = prompt == null || prompt.getOptions() == null ? null : prompt.getOptions().getModel();
        return fromRequest != null && !fromRequest.isBlank() ? fromRequest : defaultModel;
    }

    /** Every message the model received, labelled by role, including tool calls and tool results. */
    static String prompt(Prompt prompt) {
        if (prompt == null) {
            return "";
        }
        return truncate(prompt.getInstructions().stream().map(ChatContentObservationFilter::render)
                .collect(Collectors.joining("\n\n")));
    }

    static String completion(ChatResponse response) {
        return truncate(response.getResults().stream().map(g -> render(g.getOutput()))
                .collect(Collectors.joining("\n\n")));
    }

    private static String render(Message message) {
        String role = message.getMessageType().getValue().toUpperCase(Locale.ROOT);
        StringBuilder text = new StringBuilder("[").append(role).append("] ");
        if (message.getText() != null) {
            text.append(message.getText());
        }
        if (message instanceof AssistantMessage assistant && assistant.hasToolCalls()) {
            assistant.getToolCalls().forEach(call ->
                    text.append("\n→ tool call ").append(call.name()).append(' ').append(call.arguments()));
        }
        if (message instanceof ToolResponseMessage tools) {
            List<ToolResponseMessage.ToolResponse> responses = tools.getResponses();
            responses.forEach(r -> text.append("\n← ").append(r.name()).append(": ").append(r.responseData()));
        }
        return text.toString();
    }

    private static String truncate(String text) {
        return text.length() <= MAX_CHARS ? text : text.substring(0, MAX_CHARS) + " …[truncated]";
    }
}
