package dev.depguard.observability;

import io.opentelemetry.sdk.trace.SdkTracerProvider;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * On Lambda: sends this request's spans to Langfuse before the response goes back.
 *
 * <p>Normally spans are batched and exported every few seconds by a background thread. Lambda freezes the whole
 * process as soon as the response is returned, so that thread never runs and the traces would be lost (or sent
 * minutes later, on the next request). Flushing here costs a few hundred milliseconds per request, which is why
 * it's only switched on for Lambda ({@code depguard.langfuse.flush-after-request}, set by Terraform).
 *
 * <p>It is the outermost filter, so the HTTP request span has already ended when it flushes.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnBooleanProperty("depguard.langfuse.flush-after-request")
class TraceFlushFilter extends OncePerRequestFilter {

    private final SdkTracerProvider tracerProvider;

    TraceFlushFilter(SdkTracerProvider tracerProvider) {
        this.tracerProvider = tracerProvider;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            chain.doFilter(request, response);
        } finally {
            tracerProvider.forceFlush().join(5, TimeUnit.SECONDS); // bounded: tracing must never hang a request
        }
    }
}
