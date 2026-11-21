package dev.depguard.observability;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings under {@code depguard.langfuse}. Tracing is off unless both keys are set.
 *
 * @param host              Langfuse base URL: https://cloud.langfuse.com (EU) or https://us.cloud.langfuse.com (US)
 * @param publicKey         project public key (pk-lf-...)
 * @param secretKey         project secret key (sk-lf-...); never logged
 * @param captureContent    put prompt, answer and tool text on spans. The main value of LLM tracing, but it sends
 *                          the user's pom.xml to Langfuse: switch off where that matters
 * @param flushAfterRequest export spans before each response returns. Needed on Lambda, which freezes the
 *                          process after the response, so background batching never gets to send them
 */
@ConfigurationProperties("depguard.langfuse")
public record LangfuseProperties(String host, String publicKey, String secretKey, boolean captureContent,
                                 boolean flushAfterRequest) {

    /**
     * Langfuse's setup snippet shows quoted values (KEY="pk-lf-..."). Spring reads .env as a properties file,
     * which keeps quotes, so a copied key would be sent with quotes and rejected: strip them.
     */
    public LangfuseProperties {
        host = unquote(host);
        publicKey = unquote(publicKey);
        secretKey = unquote(secretKey);
    }

    private static String unquote(String value) {
        if (value == null) {
            return null;
        }
        String v = value.strip();
        boolean quoted = v.length() >= 2 && (v.startsWith("\"") && v.endsWith("\"") || v.startsWith("'") && v.endsWith("'"));
        return quoted ? v.substring(1, v.length() - 1).strip() : v;
    }

    public boolean enabled() {
        return publicKey != null && !publicKey.isBlank() && secretKey != null && !secretKey.isBlank();
    }

    /** Langfuse's OTLP/HTTP endpoint for traces (it accepts OpenTelemetry directly; no Langfuse SDK needed). */
    String tracesEndpoint() {
        return host.replaceAll("/+$", "") + "/api/public/otel/v1/traces";
    }

    /** Hide the secret key if the record is ever printed. */
    @Override
    public String toString() {
        return "LangfuseProperties[host=" + host + ", publicKey=" + publicKey + ", secretKey=***, captureContent="
                + captureContent + ", flushAfterRequest=" + flushAfterRequest + "]";
    }
}
