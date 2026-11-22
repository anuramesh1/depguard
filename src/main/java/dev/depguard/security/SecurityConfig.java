package dev.depguard.security;

import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Who may call what. Tokens are issued by Amazon Cognito; DepGuard only validates them (an OAuth2 "resource server").
 *
 * <table>
 *   <tr><th>Endpoint</th><th>Who</th><th>Why</th></tr>
 *   <tr><td>/api/scans, /actuator/health</td><td>anyone</td><td>no model cost; throttled by API Gateway</td></tr>
 *   <tr><td>/api/triage, /api/knowledge, propose upgrades</td><td>VIEWER or MAINTAINER</td><td>model cost</td></tr>
 *   <tr><td>approve / reject an upgrade</td><td>MAINTAINER</td><td>opens a pull request: a human decision</td></tr>
 * </table>
 *
 * <p>Roles come from the token's "cognito:groups" claim (Cognito user pool groups VIEWER and MAINTAINER). When
 * deployed, API Gateway checks the same tokens first (JWT authorizer): defence in depth, and rejected calls never
 * start a Lambda. Without an issuer configured (local development) everything is open, with a warning.
 */
@Configuration
// Web requests only: the ingest job runs the app without a web server, so there is no HttpSecurity to configure.
@ConditionalOnWebApplication
@EnableConfigurationProperties(AuthProperties.class)
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);
    static final String VIEWER = "VIEWER";
    static final String MAINTAINER = "MAINTAINER";

    @Bean
    SecurityFilterChain api(HttpSecurity http, AuthProperties auth) throws Exception {
        http
                // A stateless API that takes bearer tokens in a header: no cookies, so no CSRF to defend against.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, e) -> problem(response, 401, "Unauthorized",
                                "Missing or invalid bearer token."))
                        .accessDeniedHandler((request, response, e) -> problem(response, 403, "Forbidden",
                                "Your role does not allow this.")));

        if (!auth.enabled()) {
            log.warn("Authentication is OFF (depguard.auth.issuer-uri is empty): every endpoint is open. Local use only.");
            return http.authorizeHttpRequests(requests -> requests.anyRequest().permitAll()).build();
        }

        return http
                .authorizeHttpRequests(requests -> requests
                        // Only the health check is public (deploy.sh calls it; it returns just UP or DOWN).
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/upgrades/*/approve", "/api/upgrades/*/reject").hasRole(MAINTAINER)
                        .requestMatchers("/api/scans", "/api/scans/**", "/api/triage", "/api/triage/**", "/api/knowledge/**",
                                "/api/upgrades", "/api/upgrades/**")
                        .hasAnyRole(VIEWER, MAINTAINER)
                        .anyRequest().authenticated())
                .oauth2ResourceServer(server -> server.jwt(jwt -> jwt.jwtAuthenticationConverter(cognitoRoles())))
                .build();
    }

    /**
     * Validates signature (keys from the pool's JWKS endpoint), expiry, issuer and client. Built from the JWKS URL
     * so startup makes no network call; keys are fetched on the first token and cached.
     */
    @Bean
    JwtDecoder jwtDecoder(AuthProperties auth) {
        if (!auth.enabled()) {
            return token -> {
                throw new org.springframework.security.oauth2.jwt.BadJwtException("Authentication is not configured");
            };
        }
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(auth.issuerUri() + "/.well-known/jwks.json").build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(auth.issuerUri()), clientIdValidator(auth.clientId()),
                accessTokensOnly()));
        return decoder;
    }

    /** "cognito:groups": ["MAINTAINER"] → ROLE_MAINTAINER; the user is named by the "username" claim. */
    static JwtAuthenticationConverter cognitoRoles() {
        JwtGrantedAuthoritiesConverter groups = new JwtGrantedAuthoritiesConverter();
        groups.setAuthoritiesClaimName("cognito:groups");
        groups.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(groups);
        converter.setPrincipalClaimName("username");
        return converter;
    }

    static OAuth2TokenValidator<Jwt> clientIdValidator(String clientId) {
        return jwt -> clientId != null && clientId.equals(jwt.getClaimAsString("client_id"))
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Token is for another client", null));
    }

    /** Cognito also issues ID tokens; only access tokens ("token_use": "access") may call the API. */
    static OAuth2TokenValidator<Jwt> accessTokensOnly() {
        return jwt -> "access".equals(jwt.getClaimAsString("token_use"))
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Not an access token", null));
    }

    private static void problem(HttpServletResponse response, int status, String title, String detail)
            throws java.io.IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write("{\"title\":\"" + title + "\",\"status\":" + status + ",\"detail\":\"" + detail + "\"}");
    }
}
