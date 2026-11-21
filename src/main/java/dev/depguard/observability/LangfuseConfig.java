package dev.depguard.observability;

import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.micrometer.observation.ObservationFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Sends traces to Langfuse.
 *
 * <p>How tracing works here, end to end:
 * <ol>
 *   <li>Spring AI, Spring MVC and RestClient record Micrometer <em>observations</em> (model calls, tool calls,
 *       HTTP requests). DepGuard adds its own for the triage and the retrieval ({@link AiTracing}).</li>
 *   <li>Micrometer's OpenTelemetry bridge turns each observation into an OpenTelemetry <em>span</em>; spans of one
 *       request share a trace ID and form a tree.</li>
 *   <li>Spring Boot batches the spans and hands them to every {@link SpanExporter} bean: here, one that posts
 *       them over OTLP/HTTP to Langfuse, which shows each trace as a tree with tokens, cost and latency.</li>
 * </ol>
 *
 * <p>The exporter exists only when both Langfuse keys are set, so without keys DepGuard runs exactly as before.
 * Boot's own OTLP exporter is switched off in application.yml, so this is the only one.
 */
@Configuration
@EnableConfigurationProperties({LangfuseProperties.class, ModelPricing.class})
class LangfuseConfig {

    private static final Logger log = LoggerFactory.getLogger(LangfuseConfig.class);

    @Bean
    @ConditionalOnExpression("'${depguard.langfuse.public-key:}' != '' and '${depguard.langfuse.secret-key:}' != ''")
    SpanExporter langfuseSpanExporter(LangfuseProperties langfuse) {
        String credentials = langfuse.publicKey() + ":" + langfuse.secretKey();
        String basicAuth = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
        log.info("Tracing to Langfuse at {} (content capture: {})", langfuse.host(), langfuse.captureContent());
        return OtlpHttpSpanExporter.builder()
                .setEndpoint(langfuse.tracesEndpoint())
                .addHeader("Authorization", "Basic " + basicAuth)
                // Version 4 ingestion shows traces within seconds instead of up to 10 minutes.
                .addHeader("x-langfuse-ingestion-version", "4")
                .setTimeout(Duration.ofSeconds(10))
                .build();
    }

    /** Prompt, answer and cost on every model-call span (see the class for why Spring AI needs help here). */
    @Bean
    ObservationFilter chatContentObservationFilter(ModelPricing pricing, LangfuseProperties langfuse,
            @Value("${spring.ai.bedrock.converse.chat.options.model:unknown}") String defaultModel) {
        return new ChatContentObservationFilter(pricing, langfuse.captureContent(), defaultModel);
    }
}
