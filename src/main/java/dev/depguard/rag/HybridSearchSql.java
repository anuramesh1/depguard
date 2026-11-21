package dev.depguard.rag;

import java.util.Arrays;
import java.util.List;

/**
 * The SQL behind the knowledge base, shared by both stores so local and AWS behave the same.
 *
 * <p>Parameters use the {@code :name} style, which both Spring's JdbcClient and the RDS Data API accept.
 * Vectors travel as text ({@code "[0.1,0.2,...]"}) and are cast in SQL, because the Data API has no
 * vector type.
 */
public final class HybridSearchSql {

    /** Amazon Titan Text Embeddings v2 returns 1024 numbers per text. */
    public static final int DIMENSIONS = 1024;

    /** How many results each search contributes before fusion. More = better recall, slightly slower. */
    public static final int CANDIDATES_PER_SEARCH = 40;

    /** RRF's smoothing constant; 60 is the value from the original paper and the common default. */
    public static final int RRF_K = 60;

    /** One statement per entry: the RDS Data API runs a single statement per call. */
    public static final List<String> SCHEMA = List.of(
            "CREATE EXTENSION IF NOT EXISTS vector",
            """
            CREATE TABLE IF NOT EXISTS knowledge_chunk (
                id           text PRIMARY KEY,
                source       text NOT NULL,
                title        text NOT NULL,
                url          text,
                body         text NOT NULL,
                cve_ids      text NOT NULL DEFAULT '',
                content_hash text NOT NULL,
                embedding    vector(%d) NOT NULL,
                -- Postgres keeps the full-text index column up to date itself
                search_text  tsvector GENERATED ALWAYS AS (to_tsvector('english', title || ' ' || body)) STORED,
                updated_at   timestamptz NOT NULL DEFAULT now()
            )""".formatted(DIMENSIONS),
            // HNSW: approximate nearest-neighbour index for cosine distance (the <=> operator)
            "CREATE INDEX IF NOT EXISTS knowledge_chunk_embedding_idx ON knowledge_chunk USING hnsw (embedding vector_cosine_ops)",
            // GIN: inverted index for full-text search (the @@ operator)
            "CREATE INDEX IF NOT EXISTS knowledge_chunk_search_idx ON knowledge_chunk USING gin (search_text)");

    public static final String UPSERT = """
            INSERT INTO knowledge_chunk (id, source, title, url, body, cve_ids, content_hash, embedding)
            VALUES (:id, :source, :title, :url, :body, :cveIds, :contentHash, CAST(:embedding AS vector))
            ON CONFLICT (id) DO UPDATE SET
                source = EXCLUDED.source, title = EXCLUDED.title, url = EXCLUDED.url, body = EXCLUDED.body,
                cve_ids = EXCLUDED.cve_ids, content_hash = EXCLUDED.content_hash, embedding = EXCLUDED.embedding,
                updated_at = now()""";

    public static final String DELETE = "DELETE FROM knowledge_chunk WHERE id = :id";

    /** Paged, because the Data API caps one response at 1 MB. */
    public static final String CONTENT_HASHES_PAGE = """
            SELECT id, content_hash FROM knowledge_chunk WHERE id > :after ORDER BY id LIMIT :pageSize""";

    public static final int HASH_PAGE_SIZE = 2000;

    /**
     * Hybrid search with Reciprocal Rank Fusion, in one query:
     * <ol>
     *   <li>{@code semantic}: the chunks whose embeddings are closest to the question's (cosine distance).
     *       Finds meaning: "deserialization attack" matches "gadget chain RCE".</li>
     *   <li>{@code keyword}: Postgres full-text search. Finds exact terms embeddings blur: "CVE-2022-42889",
     *       "jackson-databind", "@ConstructorBinding". Words are OR-ed, and ts_rank_cd scores chunks that
     *       match more of them, and closer together, higher.</li>
     *   <li>Fusion: each chunk scores 1/(60 + rank) per list it appears in. Only ranks are used, so the two
     *       very different score scales never need to be compared.</li>
     * </ol>
     */
    public static final String HYBRID_SEARCH = """
            WITH semantic AS (
                SELECT id, ROW_NUMBER() OVER (ORDER BY embedding <=> CAST(:embedding AS vector)) AS rank
                FROM knowledge_chunk
                ORDER BY embedding <=> CAST(:embedding AS vector)
                LIMIT :candidates
            ),
            keyword AS (
                SELECT id, ROW_NUMBER() OVER (ORDER BY ts_rank_cd(search_text, query) DESC) AS rank
                FROM knowledge_chunk,
                     to_tsquery('english', replace(plainto_tsquery('english', :query)::text, ' & ', ' | ')) AS query
                WHERE search_text @@ query
                ORDER BY ts_rank_cd(search_text, query) DESC
                LIMIT :candidates
            )
            SELECT c.id, c.source, c.title, c.url, c.body, c.cve_ids,
                   semantic.rank AS vector_rank,
                   keyword.rank  AS keyword_rank,
                   CAST(COALESCE(1.0 / (%1$d + semantic.rank), 0) + COALESCE(1.0 / (%1$d + keyword.rank), 0)
                        AS double precision) AS score
            FROM semantic
            FULL OUTER JOIN keyword ON keyword.id = semantic.id
            JOIN knowledge_chunk c ON c.id = COALESCE(semantic.id, keyword.id)
            ORDER BY score DESC, c.id
            LIMIT :limit""".formatted(RRF_K);

    private HybridSearchSql() {
    }

    /** pgvector's text format: "[0.1,0.2,0.3]". */
    public static String toVectorLiteral(float[] embedding) {
        if (embedding.length != DIMENSIONS) {
            throw new IllegalArgumentException("Expected " + DIMENSIONS + " dimensions, got " + embedding.length);
        }
        StringBuilder text = new StringBuilder(embedding.length * 12).append('[');
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) {
                text.append(',');
            }
            text.append(embedding[i]);
        }
        return text.append(']').toString();
    }

    /** CVE IDs are stored as one comma-separated text column: simpler than arrays over the Data API. */
    public static String joinCves(List<String> cveIds) {
        return String.join(",", cveIds);
    }

    public static List<String> splitCves(String cveIds) {
        return cveIds == null || cveIds.isBlank()
                ? List.of()
                : Arrays.stream(cveIds.split(",")).map(String::trim).toList();
    }
}
