package dev.depguard.upgrade;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings under {@code depguard.github}: where upgrade pull requests go.
 *
 * @param token      a fine-grained personal access token limited to {@code repository}, with "Contents: read and
 *                   write" and "Pull requests: read and write". Only from the environment or SSM; never logged
 * @param repository owner/name of the repository whose pom.xml DepGuard upgrades
 * @param baseBranch branch the pull requests target
 * @param pomPath    path of the pom.xml in the repository
 * @param apiUrl     GitHub REST API base URL
 */
@ConfigurationProperties("depguard.github")
public record GitHubProperties(String token, String repository, String baseBranch, String pomPath, String apiUrl) {

    public boolean hasToken() {
        return token != null && !token.isBlank();
    }

    @Override
    public String toString() {
        return "GitHubProperties[repository=" + repository + ", baseBranch=" + baseBranch + ", pomPath=" + pomPath
                + ", token=" + (hasToken() ? "***" : "none") + "]";
    }
}
