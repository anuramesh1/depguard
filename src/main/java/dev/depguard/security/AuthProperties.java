package dev.depguard.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings under {@code depguard.auth}. When {@code issuerUri} is empty (local development), every endpoint is open;
 * Terraform sets both values on Lambda.
 *
 * @param issuerUri the Cognito user pool's issuer, e.g. https://cognito-idp.us-east-1.amazonaws.com/us-east-1_AbC123
 * @param clientId  the Cognito app client allowed to call DepGuard. Cognito access tokens have no "aud" claim, so the
 *                  "client_id" claim is checked instead: a token from another app in the same pool is rejected
 */
@ConfigurationProperties("depguard.auth")
public record AuthProperties(String issuerUri, String clientId) {

    public boolean enabled() {
        return issuerUri != null && !issuerUri.isBlank();
    }
}
