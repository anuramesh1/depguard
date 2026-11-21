package dev.depguard.rag;

import java.util.List;
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

    public HybridRetriever(EmbeddingModel embeddingModel, AdvisoryStore store) {
        this.embeddingModel = embeddingModel;
        this.store = store;
    }

    public List<SearchHit> search(String query, int limit) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        float[] queryEmbedding = embeddingModel.embed(query.strip());
        return store.search(query.strip(), queryEmbedding, Math.clamp(limit, 1, MAX_LIMIT));
    }
}
