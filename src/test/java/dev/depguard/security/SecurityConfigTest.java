package dev.depguard.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/** The token rules that sit on top of signature and expiry checks. */
class SecurityConfigTest {

    private static Jwt token(Map<String, Object> claims) {
        return new Jwt("token-value", Instant.now(), Instant.now().plusSeconds(3600), Map.of("alg", "RS256"), claims);
    }

    @Test
    void cognitoGroupsBecomeRolesAndTheUsernameIsThePrincipal() {
        var authentication = SecurityConfig.cognitoRoles().convert(token(Map.of(
                "username", "anu", "cognito:groups", List.of("MAINTAINER"), "token_use", "access")));

        assertThat(authentication.getName()).isEqualTo("anu");
        // Spring Security 7 also adds FACTOR_BEARER (how the user authenticated); the role is what DepGuard checks.
        assertThat(authentication.getAuthorities()).extracting(GrantedAuthority::getAuthority).contains("ROLE_MAINTAINER")
                .doesNotContain("ROLE_VIEWER");
    }

    @Test
    void onlyOurClientsAccessTokensAreAccepted() {
        var ours = token(Map.of("client_id", "depguard-client", "token_use", "access"));
        var otherApp = token(Map.of("client_id", "some-other-app", "token_use", "access"));
        var idToken = token(Map.of("client_id", "depguard-client", "token_use", "id"));

        assertThat(SecurityConfig.clientIdValidator("depguard-client").validate(ours).hasErrors()).isFalse();
        assertThat(SecurityConfig.clientIdValidator("depguard-client").validate(otherApp).hasErrors()).isTrue();
        assertThat(SecurityConfig.accessTokensOnly().validate(idToken).hasErrors()).isTrue();
    }

    @Test
    void ssmParameterNamesBecomeDepguardProperties() {
        assertThat(SsmParametersPostProcessor.propertyName("/depguard/", "/depguard/langfuse/secret-key"))
                .isEqualTo("depguard.langfuse.secret-key");
        assertThat(SsmParametersPostProcessor.propertyName("/depguard", "/depguard/github/token"))
                .isEqualTo("depguard.github.token");
    }
}
