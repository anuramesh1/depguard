package dev.depguard.observability;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.util.function.Function;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Records DepGuard's own steps (the triage, the retrieval) as spans, in the vocabulary Langfuse understands.
 *
 * <p>Spring AI already records each model call and tool call. What it can't know is the application's view:
 * "this whole thing was one triage, for this pom, and the guardrail found 0 unverified CVEs". Wrapping a step in
 * {@link #observe} creates a parent span for it; every model, tool and HTTP span inside becomes its child.
 *
 * <p>Langfuse reads {@code langfuse.*} span attributes: the observation type (agent, retriever...), input,
 * output, metadata and level (WARNING shows up highlighted). Content is only recorded when capture is on.
 * Without a Langfuse exporter the spans are created and dropped: negligible cost, and the code stays the same.
 */
@Component
public class AiTracing {

    /** The Langfuse session for spans started on this thread (set by the evals: one session per eval run). */
    private static final ThreadLocal<String> SESSION = new ThreadLocal<>();

    private final ObservationRegistry registry;
    private final ObjectProvider<Tracer> tracer;
    private final boolean captureContent;

    public AiTracing(ObservationRegistry registry, ObjectProvider<Tracer> tracer, LangfuseProperties langfuse) {
        this.registry = registry;
        this.tracer = tracer;
        this.captureContent = langfuse.captureContent();
    }

    /** For tests and code paths without Spring: records nothing. */
    public static AiTracing noop() {
        return new AiTracing(ObservationRegistry.NOOP, null, new LangfuseProperties("", "", "", false, false));
    }

    /**
     * Runs {@code work} with every DepGuard span started inside it tagged with a Langfuse session ID, so Langfuse
     * groups those traces (e.g. all triages of one eval run). Only needs the root span: Langfuse applies a
     * session set on any span to the whole trace.
     */
    public static <T> T inSession(String sessionId, java.util.function.Supplier<T> work) {
        String previous = SESSION.get();
        SESSION.set(sessionId);
        try {
            return work.get();
        } finally {
            if (previous == null) {
                SESSION.remove();
            } else {
                SESSION.set(previous);
            }
        }
    }

    /**
     * Runs {@code work} inside a new span.
     *
     * @param name  span name, e.g. "depguard.triage"
     * @param type  Langfuse observation type: "agent", "retriever", "tool", "span"...
     * @param input what the step received (recorded only when content capture is on)
     */
    public <T> T observe(String name, String type, String input, Function<Step, T> work) {
        Observation observation = Observation.createNotStarted(name, registry)
                .lowCardinalityKeyValue("langfuse.observation.type", type);
        if (SESSION.get() != null) {
            observation.highCardinalityKeyValue(KeyValue.of("langfuse.session.id", SESSION.get()));
        }
        Step step = new Step(observation);
        step.content("langfuse.observation.input", input);
        return observation.observe(() -> work.apply(step));
    }

    /** The step being traced: add its output, metadata or a warning before it finishes. */
    public final class Step {

        private final Observation observation;

        private Step(Observation observation) {
            this.observation = observation;
        }

        public void output(String text) {
            content("langfuse.observation.output", text);
        }

        /** Filterable metadata in Langfuse, e.g. metadata("unverified_cves", 0). Never put secrets here. */
        public void metadata(String key, Object value) {
            observation.highCardinalityKeyValue(KeyValue.of("langfuse.observation.metadata." + key, String.valueOf(value)));
        }

        /** Marks the step as WARNING in Langfuse, with a reason (e.g. the guardrail found unverified CVEs). */
        public void warning(String message) {
            observation.highCardinalityKeyValue(KeyValue.of("langfuse.observation.level", "WARNING"));
            observation.highCardinalityKeyValue(KeyValue.of("langfuse.observation.status_message", message));
        }

        /** The trace ID of the current request: returned to clients so a support question can find the trace. */
        public String traceId() {
            Tracer t = tracer == null ? null : tracer.getIfAvailable();
            Span span = t == null ? null : t.currentSpan();
            return span == null ? null : span.context().traceId();
        }

        private void content(String key, String text) {
            if (captureContent && text != null) {
                observation.highCardinalityKeyValue(KeyValue.of(key, text));
            }
        }
    }
}
