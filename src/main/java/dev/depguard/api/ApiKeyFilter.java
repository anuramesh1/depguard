package dev.depguard.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Requires an X-Api-Key header on /api/triage when depguard.api-key is set.
 *
 * <p>Why: the deployed URL is public, and every triage call spends model tokens. Without a key, anyone
 * who finds the URL could run up the Bedrock bill. The free /api/scans endpoint stays open.
 * Locally the key is empty, so no header is needed. Phase 8 replaces this with proper JWT auth.
 */
@Component
public class ApiKeyFilter extends OncePerRequestFilter {

    static final String HEADER = "X-Api-Key";

    private final byte[] expectedKey;

    public ApiKeyFilter(@Value("${depguard.api-key:}") String apiKey) {
        this.expectedKey = apiKey.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return expectedKey.length == 0 || !request.getRequestURI().startsWith("/api/triage");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String provided = request.getHeader(HEADER);
        // Constant-time comparison, so response timing doesn't reveal how much of the key was right.
        boolean valid = provided != null
                && MessageDigest.isEqual(expectedKey, provided.getBytes(StandardCharsets.UTF_8));
        if (!valid) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.getWriter().write("""
                    {"title":"Unauthorized","status":401,"detail":"Missing or wrong X-Api-Key header."}""");
            return;
        }
        chain.doFilter(request, response);
    }
}
