package dev.depguard.rag.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.depguard.rag.AdvisoryStore;
import dev.depguard.rag.HybridSearchSql;
import dev.depguard.rag.KnowledgeChunk;
import dev.depguard.rag.RagProperties;
import dev.depguard.rag.SearchHit;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;

/** Ingestion logic with fakes: what gets embedded, skipped, stored and deleted. No network, no database. */
class IngestServiceTest {

    private static final URI DUMP = URI.create("https://osv.example/Maven/all.zip");
    private static final RagProperties.Guide GUIDE =
            new RagProperties.Guide("sb3", "Spring Boot 3", URI.create("https://raw.example/sb3"), "https://example/sb3");

    private final GuideFeed guideFeed = mock(GuideFeed.class);
    private final OsvAdvisoryFeed osvFeed = mock(OsvAdvisoryFeed.class);
    private final EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
    private final FakeStore store = new FakeStore();

    private IngestService service(int maxAdvisories) {
        var settings = new RagProperties.Ingest(DUMP, List.of(GUIDE), maxAdvisories, 2);
        return new IngestService(settings, guideFeed, osvFeed, embeddingModel, store);
    }

    @Test
    void embedsOnlyNewOrChangedChunksAndDeletesOnesThatDisappeared() throws Exception {
        KnowledgeChunk unchanged = chunk("GHSA-1", "same text");
        KnowledgeChunk changed = chunk("GHSA-2", "new text");
        KnowledgeChunk added = chunk("guide:sb3#0", "a guide section");
        store.hashes.put(unchanged.id(), unchanged.contentHash());
        store.hashes.put(changed.id(), "old-hash");
        store.hashes.put("GHSA-withdrawn", "whatever");
        when(guideFeed.fetch(GUIDE)).thenReturn(List.of(added));
        when(osvFeed.fetch(DUMP, 0)).thenReturn(List.of(unchanged, changed));
        when(embeddingModel.embed(anyString())).thenReturn(new float[HybridSearchSql.DIMENSIONS]);

        IngestService.Report report = service(0).ingest();

        assertThat(store.upserted).extracting(e -> e.chunk().id()).containsExactlyInAnyOrder("GHSA-2", "guide:sb3#0");
        assertThat(store.deleted).containsExactly("GHSA-withdrawn");
        assertThat(report.total()).isEqualTo(3);
        assertThat(report.embedded()).isEqualTo(2);
        assertThat(report.unchanged()).isEqualTo(1);
        assertThat(report.deleted()).isEqualTo(1);
        assertThat(store.schemaCreated).isTrue();
    }

    @Test
    void countsEmbeddingFailuresAndKeepsGoing() throws Exception {
        when(guideFeed.fetch(GUIDE)).thenReturn(List.of());
        when(osvFeed.fetch(DUMP, 0)).thenReturn(List.of(chunk("GHSA-ok", "fine"), chunk("GHSA-bad", "throttled")));
        when(embeddingModel.embed(anyString())).thenAnswer(call -> {
            if (call.getArgument(0, String.class).contains("throttled")) {
                throw new IllegalStateException("ThrottlingException");
            }
            return new float[HybridSearchSql.DIMENSIONS];
        });

        IngestService.Report report = service(0).ingest();

        assertThat(report.failed()).isEqualTo(1);
        assertThat(store.upserted).extracting(e -> e.chunk().id()).containsExactly("GHSA-ok");
    }

    @Test
    void aPartialRunNeverDeletes() throws Exception {
        store.hashes.put("GHSA-not-in-this-sample", "hash");
        when(guideFeed.fetch(GUIDE)).thenReturn(List.of());
        when(osvFeed.fetch(any(), anyInt())).thenReturn(List.of());

        service(10).ingest(); // limited to 10 advisories: most stored ones are simply not in the sample

        assertThat(store.deleted).isEmpty();
        verify(embeddingModel, never()).embed(anyString());
    }

    private static KnowledgeChunk chunk(String id, String text) {
        return new KnowledgeChunk(id, KnowledgeChunk.SOURCE_OSV, id, "https://osv.dev/vulnerability/" + id, text, List.of());
    }

    /** Records what ingestion does to the store. */
    static class FakeStore implements AdvisoryStore {
        final Map<String, String> hashes = new HashMap<>();
        final List<EmbeddedChunk> upserted = new ArrayList<>();
        final List<String> deleted = new ArrayList<>();
        boolean schemaCreated;

        @Override
        public void createSchema() {
            schemaCreated = true;
        }

        @Override
        public Map<String, String> contentHashes() {
            return Map.copyOf(hashes);
        }

        @Override
        public synchronized void upsert(List<EmbeddedChunk> chunks) {
            upserted.addAll(chunks);
        }

        @Override
        public void delete(List<String> ids) {
            deleted.addAll(ids);
        }

        @Override
        public List<SearchHit> search(String queryText, float[] queryEmbedding, int limit) {
            return List.of();
        }
    }
}
