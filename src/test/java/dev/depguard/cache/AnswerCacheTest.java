package dev.depguard.cache;

import static org.assertj.core.api.Assertions.assertThat;

import dev.depguard.agent.TriageResult;
import dev.depguard.rag.HybridSearchSql;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.Driver;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;

/**
 * The answer cache's real SQL against pgvector in a throwaway container (skipped without Docker).
 *
 * <p>A fake embedding model maps known questions to hand-made vectors, so the test controls which questions count as
 * "similar": the default question and its paraphrase point the same way; an unrelated question points elsewhere.
 */
@Testcontainers(disabledWithoutDocker = true)
class AnswerCacheTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));

    private static final String DEFAULT_QUESTION = "Which dependencies should I upgrade first, and why?";
    private static final String PARAPHRASE = "What should I upgrade first?";
    private static final String UNRELATED = "Is junit safe to keep?";
    private static SqlRunner sql;

    /** Paraphrase ~0.99 similar to the default question; the unrelated one ~0.01. */
    static class FakeEmbeddings implements EmbeddingModel {
        @Override
        public float[] embed(String text) {
            float[] v = new float[HybridSearchSql.DIMENSIONS];
            Arrays.fill(v, 0.001f);
            v[text.contains("junit") ? 1 : 0] = 1f;
            return v;
        }

        @Override
        public float[] embed(Document document) {
            return embed(document.getText());
        }

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            throw new UnsupportedOperationException();
        }
    }

    @BeforeAll
    static void connect() {
        sql = new JdbcSqlRunner(new SimpleDriverDataSource(new Driver(), POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()));
    }

    private static AnswerCache cache(Duration ttl) {
        return new AnswerCache(sql, new FakeEmbeddings(), JsonMapper.builder().build(),
                new AnswerCacheProperties(true, ttl, 0.55));
    }

    private static TriageResult answer(String text) {
        return new TriageResult(text, List.of("CVE-2021-44228"), List.of(), List.of(), List.of(), List.of(),
                "model-x", 3000, 300, 0L, 0L, 2500, "trace-1", null);
    }

    @Test
    void exactAndSimilarQuestionsReuseTheAnswerUnrelatedOnesDoNot() {
        AnswerCache cache = cache(Duration.ofHours(6));
        String key = AnswerCache.key("v1", List.of("org.apache.logging.log4j:log4j-core:2.14.1:compile"));
        cache.store(key, DEFAULT_QUESTION, answer("Upgrade log4j-core first."));

        var exact = cache.lookup(key, "  which dependencies should I upgrade FIRST and why  ");
        assertThat(exact).hasValueSatisfying(hit -> {
            assertThat(hit.match()).isEqualTo("exact");
            assertThat(hit.result().answer()).isEqualTo("Upgrade log4j-core first.");
            assertThat(hit.result().citedCves()).containsExactly("CVE-2021-44228");
        });
        assertThat(cache.lookup(key, PARAPHRASE)).hasValueSatisfying(hit -> assertThat(hit.match()).isEqualTo("similar"));
        assertThat(cache.lookup(key, UNRELATED)).isEmpty();
    }

    @Test
    void otherDependenciesOrAnotherPromptVersionNeverHit() {
        AnswerCache cache = cache(Duration.ofHours(6));
        String key = AnswerCache.key("v1", List.of("a:b:1.0:compile"));
        cache.store(key, DEFAULT_QUESTION, answer("cached"));

        assertThat(cache.lookup(AnswerCache.key("v1", List.of("a:b:1.1:compile")), DEFAULT_QUESTION)).isEmpty();
        assertThat(cache.lookup(AnswerCache.key("v2", List.of("a:b:1.0:compile")), DEFAULT_QUESTION)).isEmpty();
        // Order of dependencies doesn't matter
        assertThat(AnswerCache.key("v1", List.of("x:1", "y:2"))).isEqualTo(AnswerCache.key("v1", List.of("y:2", "x:1")));
    }

    @Test
    void expiredAnswersAreNotReused() {
        String key = AnswerCache.key("v1", List.of("c:d:2.0:compile"));
        cache(Duration.ofHours(6)).store(key, DEFAULT_QUESTION, answer("old"));
        sql.update("UPDATE answer_cache SET created_at = now() - interval '7 hours' WHERE dependencies_key = :key",
                Map.of("key", key));

        assertThat(cache(Duration.ofHours(6)).lookup(key, DEFAULT_QUESTION)).isEmpty();
    }

    @Test
    void aBrokenDatabaseMeansAMissNotAnError() {
        SqlRunner broken = new SqlRunner() {
            @Override
            public List<Map<String, Object>> query(String s, Map<String, Object> p) {
                throw new IllegalStateException("connection refused");
            }

            @Override
            public void update(String s, Map<String, Object> p) {
                throw new IllegalStateException("connection refused");
            }
        };
        AnswerCache cache = new AnswerCache(broken, new FakeEmbeddings(), JsonMapper.builder().build(),
                new AnswerCacheProperties(true, Duration.ofHours(6), 0.55));

        assertThat(cache.lookup("k", DEFAULT_QUESTION)).isEmpty();
        cache.store("k", DEFAULT_QUESTION, answer("x")); // must not throw
    }
}
