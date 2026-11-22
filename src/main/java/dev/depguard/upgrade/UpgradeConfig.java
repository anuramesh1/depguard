package dev.depguard.upgrade;

import dev.depguard.agent.TriageFactsService;
import dev.depguard.cache.SqlRunner;
import dev.depguard.dependency.PomParser;
import dev.depguard.security.AuthProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

@Configuration
@EnableConfigurationProperties({GitHubProperties.class, AuthProperties.class})
class UpgradeConfig {

    @Bean
    UpgradeService upgradeService(RestClient.Builder restClient, GitHubProperties github, PomParser pomParser,
                                  TriageFactsService factsService, SqlRunner sqlRunner, JsonMapper jsonMapper,
                                  AuthProperties auth) {
        // Four eyes only make sense with real identities: locally (no login) everyone is the same anonymous user.
        return new UpgradeService(new GitHubClient(restClient, github), github, pomParser, factsService,
                new UpgradeProposalStore(sqlRunner, jsonMapper), auth.enabled());
    }
}
