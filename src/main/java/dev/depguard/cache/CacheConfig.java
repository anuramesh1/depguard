package dev.depguard.cache;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

@Configuration
@EnableConfigurationProperties(AnswerCacheProperties.class)
class CacheConfig {

    @Bean
    AnswerCache answerCache(SqlRunner sqlRunner, EmbeddingModel embeddingModel, JsonMapper jsonMapper,
                            AnswerCacheProperties properties) {
        return new AnswerCache(sqlRunner, embeddingModel, jsonMapper, properties);
    }
}
