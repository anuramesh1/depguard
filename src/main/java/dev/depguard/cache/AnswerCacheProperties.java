package dev.depguard.cache;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings under {@code depguard.answer-cache}.
 *
 * @param enabled             off in the evals, which must measure the agent, not the cache
 * @param ttl                 how long an answer stays reusable. EPSS updates daily and DepGuard caches KEV for 6 h,
 *                            so older answers may rest on outdated exploit data
 * @param similarityThreshold minimum cosine similarity for a differently-worded question to reuse an answer.
 *                            Measured with Titan v2: paraphrases scored 0.14-0.59, different questions up to 0.32;
 *                            0.55 caught 3 of 6 paraphrases and 0 of 7 different questions. Precision over recall:
 *                            a wrong cached answer is worse than a miss
 */
@ConfigurationProperties("depguard.answer-cache")
public record AnswerCacheProperties(boolean enabled, Duration ttl, double similarityThreshold) {
}
