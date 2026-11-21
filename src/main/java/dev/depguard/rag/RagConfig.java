package dev.depguard.rag;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.rdsdata.RdsDataClient;

/**
 * Picks the knowledge-base store from {@code depguard.rag.store}.
 *
 * <p>The JDBC DataSource is built here rather than by Spring Boot's auto-configuration on purpose: only the
 * local store needs it, and a Boot-managed DataSource would also add a database health check that fails on
 * Lambda (which talks to Aurora over the Data API, not JDBC).
 */
@Configuration
@EnableConfigurationProperties(RagProperties.class)
class RagConfig {

    @Bean
    AdvisoryStore advisoryStore(RagProperties properties, @Value("${spring.ai.bedrock.aws.region}") String region) {
        return switch (properties.store()) {
            case RagProperties.STORE_JDBC -> {
                RagProperties.Jdbc jdbc = properties.jdbc();
                // Opens a connection per query: fine for local use, and nothing connects until the first search.
                var dataSource = new SimpleDriverDataSource(new org.postgresql.Driver(), jdbc.url(), jdbc.username(),
                        jdbc.password());
                yield new JdbcAdvisoryStore(dataSource);
            }
            case RagProperties.STORE_DATA_API -> {
                RagProperties.DataApi dataApi = properties.dataApi();
                RdsDataClient client = RdsDataClient.builder()
                        .region(Region.of(region)) // same region as Bedrock; credentials from the default AWS chain
                        .build();
                yield new DataApiAdvisoryStore(client, dataApi.clusterArn(), dataApi.secretArn(), dataApi.database());
            }
            default -> throw new IllegalStateException(
                    "depguard.rag.store must be 'jdbc' or 'data-api', not '" + properties.store() + "'");
        };
    }
}
