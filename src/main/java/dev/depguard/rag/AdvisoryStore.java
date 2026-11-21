package dev.depguard.rag;

import java.util.List;
import java.util.Map;

/**
 * Where the knowledge base lives: Postgres with pgvector.
 *
 * <p>Two implementations run the same SQL ({@link HybridSearchSql}) over different connections:
 * {@link JdbcAdvisoryStore} for the local docker-compose Postgres, and {@link DataApiAdvisoryStore} for
 * Aurora Serverless v2 through the RDS Data API (HTTPS + IAM, so the Lambda needs no VPC).
 */
public interface AdvisoryStore {

    /** Creates the pgvector extension, table and indexes if they don't exist yet. Safe to run every time. */
    void createSchema();

    /** Content hash of every stored chunk, by ID. Ingestion uses it to skip unchanged chunks. */
    Map<String, String> contentHashes();

    /** Inserts new chunks and updates existing ones (matched by ID). */
    void upsert(List<EmbeddedChunk> chunks);

    /** Removes chunks whose source no longer has them (e.g. a withdrawn advisory). */
    void delete(List<String> ids);

    /**
     * Hybrid search: semantic (embedding distance) and keyword (Postgres full-text) searches, fused with
     * Reciprocal Rank Fusion.
     */
    List<SearchHit> search(String queryText, float[] queryEmbedding, int limit);

    /** A chunk together with its embedding, ready to store. */
    record EmbeddedChunk(KnowledgeChunk chunk, float[] embedding) {
    }
}
