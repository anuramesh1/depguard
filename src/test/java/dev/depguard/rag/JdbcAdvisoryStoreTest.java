package dev.depguard.rag;

import static org.assertj.core.api.Assertions.assertThat;

import dev.depguard.rag.AdvisoryStore.EmbeddedChunk;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.Driver;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Runs the real hybrid-search SQL against a throwaway Postgres + pgvector container.
 *
 * <p>Embeddings here are hand-made "directions" instead of real Titan vectors, so the test controls which
 * chunk is semantically closest, and can show the keyword and semantic halves finding different things.
 * Skipped automatically when Docker isn't running.
 */
@Testcontainers(disabledWithoutDocker = true)
class JdbcAdvisoryStoreTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));

    private static JdbcAdvisoryStore store;

    private static final KnowledgeChunk LOG4SHELL = chunk("GHSA-jfh8", "Log4Shell: JNDI lookup remote code execution in log4j-core",
            List.of("CVE-2021-44228"));
    private static final KnowledgeChunk JAKARTA = chunk("guide:sb3#1", "Spring Boot 3 moves from javax to the jakarta namespace",
            List.of());
    private static final KnowledgeChunk JACKSON = chunk("GHSA-jjjj", "jackson-databind polymorphic typing gadget chain",
            List.of("CVE-2020-8840"));

    @BeforeAll
    static void createSchemaAndLoad() {
        store = new JdbcAdvisoryStore(new SimpleDriverDataSource(new Driver(), POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword()));
        store.createSchema();
        store.createSchema(); // safe to run twice
        store.upsert(List.of(
                new EmbeddedChunk(LOG4SHELL, direction(0)),
                new EmbeddedChunk(JAKARTA, direction(1)),
                new EmbeddedChunk(JACKSON, direction(2))));
    }

    @Test
    void aChunkFoundByBothSearchesRanksFirst() {
        List<SearchHit> hits = store.search("jakarta namespace", direction(1), 3);

        assertThat(hits.getFirst().id()).isEqualTo(JAKARTA.id());
        assertThat(hits.getFirst().vectorRank()).isEqualTo(1L);
        assertThat(hits.getFirst().keywordRank()).isEqualTo(1L);
        assertThat(hits.getFirst().score()).isEqualTo(2.0 / (HybridSearchSql.RRF_K + 1), org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void keywordAndSemanticHalvesEachContributeResults() {
        // The words match the Log4Shell chunk; the embedding points at the Jackson chunk.
        List<SearchHit> hits = store.search("JNDI", direction(2), 3);

        SearchHit log4shell = hits.stream().filter(hit -> hit.id().equals(LOG4SHELL.id())).findFirst().orElseThrow();
        SearchHit jackson = hits.stream().filter(hit -> hit.id().equals(JACKSON.id())).findFirst().orElseThrow();
        assertThat(log4shell.keywordRank()).isEqualTo(1L);
        assertThat(jackson.vectorRank()).isEqualTo(1L);
        assertThat(jackson.keywordRank()).isNull(); // "JNDI" isn't in its text
        assertThat(log4shell.cveIds()).containsExactly("CVE-2021-44228");
    }

    @Test
    void upsertUpdatesInPlaceAndHashesAndDeletesWork() {
        KnowledgeChunk temporary = chunk("GHSA-temp", "temporary advisory", List.of());
        store.upsert(List.of(new EmbeddedChunk(temporary, direction(3))));
        KnowledgeChunk edited = chunk("GHSA-temp", "edited advisory text", List.of());
        store.upsert(List.of(new EmbeddedChunk(edited, direction(3))));

        assertThat(store.contentHashes()).containsEntry("GHSA-temp", edited.contentHash());

        store.delete(List.of("GHSA-temp"));
        assertThat(store.contentHashes()).doesNotContainKey("GHSA-temp").containsKeys(LOG4SHELL.id(), JAKARTA.id());
    }

    private static KnowledgeChunk chunk(String id, String text, List<String> cves) {
        return new KnowledgeChunk(id, KnowledgeChunk.SOURCE_OSV, id, "https://osv.dev/vulnerability/" + id, text, cves);
    }

    /** A vector pointing mostly along one axis, so each chunk is clearly closest to its own direction. */
    private static float[] direction(int axis) {
        float[] vector = new float[HybridSearchSql.DIMENSIONS];
        Arrays.fill(vector, 0.01f);
        vector[axis] = 1f;
        return vector;
    }
}
