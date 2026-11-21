package dev.depguard.rag;

import dev.depguard.observability.AiTracing;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;

/**
 * Answers "find text about X" from the knowledge base: embeds the question, then runs the hybrid query.
 *
 * <p>The same text goes to both halves of the search: its embedding to the semantic half, its words to
 * the keyword half. See {@link HybridSearchSql#HYBRID_SEARCH} for how the two are fused.
 */
@Service
public class HybridRetriever {

    static final int MAX_LIMIT = 20;

    private final EmbeddingModel embeddingModel;
    private final AdvisoryStore store;
    private final AiTracing tracing;

    public HybridRetriever(EmbeddingModel embeddingModel, AdvisoryStore store, AiTracing tracing) {
        this.embeddingModel = embeddingModel;
        this.store = store;
        this.tracing = tracing;
    }

    public List<SearchHit> search(String query, int limit) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        // A "retriever" span: Langfuse shows the query, and what came back with both ranks and the fused score.
        return tracing.observe("depguard.retrieval", "retriever", query.strip(), step -> {
            float[] queryEmbedding = embeddingModel.embed(query.strip());
            List<SearchHit> hits = store.search(query.strip(), queryEmbedding, Math.clamp(limit, 1, MAX_LIMIT));
            step.output(hits.stream()
                    .map(h -> String.format(Locale.ROOT, "%.4f (vector %s, keyword %s) %s", h.score(),
                            h.vectorRank() == null ? "-" : h.vectorRank(), h.keywordRank() == null ? "-" : h.keywordRank(),
                            h.title()))
                    .collect(Collectors.joining("\n")));
            step.metadata("hits", hits.size());
            return hits;
        });
    }
}
