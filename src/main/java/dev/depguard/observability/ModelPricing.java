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

    public record Price(String model, double inputPerMillion, double outputPerMillion) {
    }

    public ModelPricing {
        models = models == null ? List.of() : models;
    }

    /** Cost in USD, or empty when the model isn't in the table (better no number than a wrong one). */
    public Optional<Double> costOf(String model, long inputTokens, long outputTokens) {
        return models.stream()
                .filter(p -> p.model().equals(model))
                .findFirst()
                .map(p -> (inputTokens * p.inputPerMillion() + outputTokens * p.outputPerMillion()) / 1_000_000);
    }
}
