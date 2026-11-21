package dev.depguard.observability;

import java.util.List;
import java.util.Optional;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * On-demand prices per model, from application.yml ({@code depguard.pricing}), used to put a cost on every
 * model-call span. Langfuse can price models itself, but it matches model names, and Bedrock's IDs
 * ("us.anthropic.claude-haiku-4-5-20251001-v1:0") don't always match its list, so DepGuard sends the cost.
 *
 * @param models price per million input and output tokens, per Bedrock model ID
 */
@ConfigurationProperties("depguard.pricing")
public record ModelPricing(List<Price> models) {

    /**
     * @param cacheReadPerMillion  price of input tokens read from the prompt cache; null = the usual 0.1x of input
     * @param cacheWritePerMillion price of input tokens written to the cache (5-minute TTL); null = 1.25x of input
     */
    public record Price(String model, double inputPerMillion, double outputPerMillion, Double cacheReadPerMillion,
                        Double cacheWritePerMillion) {

        double cacheRead() {
            return cacheReadPerMillion != null ? cacheReadPerMillion : inputPerMillion * 0.1;
        }

        double cacheWrite() {
            return cacheWritePerMillion != null ? cacheWritePerMillion : inputPerMillion * 1.25;
        }
    }

    public ModelPricing {
        models = models == null ? List.of() : models;
    }

    /** Cost in USD, or empty when the model isn't in the table (better no number than a wrong one). */
    public Optional<Double> costOf(String model, long inputTokens, long outputTokens) {
        return costOf(model, inputTokens, outputTokens, 0, 0);
    }

    /**
     * Cost including prompt caching. Bedrock reports cached tokens separately from {@code inputTokens} (a cached
     * 7,277-token prompt shows inputTokens=10), so ignoring them would make cached calls look almost free.
     */
    public Optional<Double> costOf(String model, long inputTokens, long outputTokens, long cacheReadTokens,
                                   long cacheWriteTokens) {
        return models.stream()
                .filter(p -> p.model().equals(model))
                .findFirst()
                .map(p -> (inputTokens * p.inputPerMillion() + outputTokens * p.outputPerMillion()
                        + cacheReadTokens * p.cacheRead() + cacheWriteTokens * p.cacheWrite()) / 1_000_000);
    }
}
