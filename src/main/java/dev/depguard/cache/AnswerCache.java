package dev.depguard.cache;

import dev.depguard.agent.TriageResult;
import dev.depguard.rag.HybridSearchSql;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reuses a recent triage answer when the same dependencies are asked about again: no model call, no cost.
 *
 * <p>Two tiers, precision over recall (a wrong cached answer is worse than a miss):
 * <ol>
 *   <li><b>Exact</b>: same dependency set and the same question once normalised (case, spaces, punctuation). The
 *       common case: CI pipelines and most clients send no question, so they all ask the default one.</li>
 *   <li><b>Similar</b>: same dependency set, differently-worded question whose embedding is at least
 *       {@code similarityThreshold} similar (pgvector cosine). Only tried when the set has cached answers.</li>
 * </ol>
 *
 * <p>The dependency key includes a version (model + prompts), so changing either never serves old answers, and
 * entries expire after the TTL because exploit data changes. Any database problem is logged and treated as a
 * miss: the cache can make DepGuard cheaper, never unavailable.
 */
public class AnswerCache {

    private static final Logger log = LoggerFactory.getLogger(AnswerCache.class);

    static final List<String> SCHEMA = List.of(
            "CREATE EXTENSION IF NOT EXISTS vector",
            """
            CREATE TABLE IF NOT EXISTS answer_cache (
                id                 text PRIMARY KEY,
                dependencies_key   text NOT NULL,
                question           text NOT NULL,
                question_embedding vector(%d) NOT NULL,
                answer_json        text NOT NULL,
                created_at         timestamptz NOT NULL DEFAULT now()
            )""".formatted(HybridSearchSql.DIMENSIONS),
            "CREATE INDEX IF NOT EXISTS answer_cache_key_idx ON answer_cache (dependencies_key, created_at)");

    private static final String FRESH = "dependencies_key = :key AND created_at > now() - make_interval(secs => :ttl)";
    static final String EXACT = "SELECT answer_json, created_at::text AS created_at FROM answer_cache WHERE " + FRESH
            + " AND question = :question ORDER BY created_at DESC LIMIT 1";
    static final String COUNT = "SELECT count(*) AS n FROM answer_cache WHERE " + FRESH;
    static final String SIMILAR = """
            SELECT answer_json, created_at::text AS created_at,
                   CAST(1 - (question_embedding <=> CAST(:embedding AS vector)) AS double precision) AS similarity
            FROM answer_cache WHERE %s
            ORDER BY question_embedding <=> CAST(:embedding AS vector) LIMIT 1""".formatted(FRESH);
    static final String INSERT = """
            INSERT INTO answer_cache (id, dependencies_key, question, question_embedding, answer_json)
            VALUES (:id, :key, :question, CAST(:embedding AS vector), :answer)""";
    static final String EXPIRE = "DELETE FROM answer_cache WHERE created_at < now() - make_interval(secs => :ttl)";

    private final SqlRunner sql;
    private final EmbeddingModel embeddingModel;
    private final JsonMapper jsonMapper;
    private final AnswerCacheProperties properties;
    private volatile boolean schemaReady;

    public AnswerCache(SqlRunner sql, EmbeddingModel embeddingModel, JsonMapper jsonMapper,
                       AnswerCacheProperties properties) {
        this.sql = sql;
        this.embeddingModel = embeddingModel;
        this.jsonMapper = jsonMapper;
        this.properties = properties;
    }

    /** For tests and for turning the cache off: never hits, stores nothing. */
    public static AnswerCache disabled() {
        return new AnswerCache(null, null, null, new AnswerCacheProperties(false, java.time.Duration.ZERO, 1.0));
    }

    /**
     * A reused answer.
     *
     * @param match      "exact" or "similar"
     * @param similarity cosine similarity of the questions (1.0 for exact matches)
     * @param createdAt  when the reused answer was produced
     */
    public record Hit(TriageResult result, String match, double similarity, String createdAt) {
    }

    public boolean enabled() {
        return properties.enabled();
    }

    public Optional<Hit> lookup(String dependenciesKey, String question) {
        if (!enabled()) {
            return Optional.empty();
        }
        try {
            ensureSchema();
            Map<String, Object> params = Map.of("key", dependenciesKey, "ttl", properties.ttl().toSeconds(),
                    "question", normalize(question));
            List<Map<String, Object>> exact = sql.query(EXACT, params);
            if (!exact.isEmpty()) {
                return Optional.of(hit(exact.getFirst(), "exact", 1.0));
            }
            List<Map<String, Object>> count = sql.query(COUNT, params);
            if (count.isEmpty() || ((Number) count.getFirst().get("n")).longValue() == 0) {
                return Optional.empty(); // nothing cached for these dependencies: don't spend an embedding call
            }
            List<Map<String, Object>> similar = sql.query(SIMILAR, Map.of("key", dependenciesKey,
                    "ttl", properties.ttl().toSeconds(), "embedding", embedding(question)));
            if (similar.isEmpty()) {
                return Optional.empty();
            }
            double similarity = ((Number) similar.getFirst().get("similarity")).doubleValue();
            return similarity >= properties.similarityThreshold()
                    ? Optional.of(hit(similar.getFirst(), "similar", similarity))
                    : Optional.empty();
        } catch (RuntimeException e) {
            log.warn("Answer cache lookup failed; answering without it: {}", e.getMessage());
            return Optional.empty();
        }
    }

    public void store(String dependenciesKey, String question, TriageResult result) {
        if (!enabled()) {
            return;
        }
        try {
            ensureSchema();
            sql.update(INSERT, Map.of("id", UUID.randomUUID().toString(), "key", dependenciesKey,
                    "question", normalize(question), "embedding", embedding(question),
                    "answer", jsonMapper.writeValueAsString(result)));
            sql.update(EXPIRE, Map.of("ttl", properties.ttl().toSeconds()));
        } catch (RuntimeException e) {
            log.warn("Answer cache store failed: {}", e.getMessage());
        }
    }

    /**
     * Stable key for "these dependencies, asked of this model with these prompts".
     *
     * @param version          changes whenever the model or prompts change (see TriageAgent)
     * @param dependencyLines  one line per dependency, any order (sorted here)
     */
    public static String key(String version, List<String> dependencyLines) {
        return sha256(version + "\n" + String.join("\n", dependencyLines.stream().sorted().toList()));
    }

    /** "  Which dependencies should I upgrade FIRST?? " and "which dependencies should i upgrade first" match. */
    static String normalize(String question) {
        return question.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
    }

    public static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available in the JDK", e);
        }
    }

    private Hit hit(Map<String, Object> row, String match, double similarity) {
        TriageResult cached = jsonMapper.readValue((String) row.get("answer_json"), TriageResult.class);
        return new Hit(cached, match, similarity, String.valueOf(row.get("created_at")));
    }

    private String embedding(String question) {
        return HybridSearchSql.toVectorLiteral(embeddingModel.embed(normalize(question)));
    }

    private void ensureSchema() {
        if (!schemaReady) {
            synchronized (this) {
                if (!schemaReady) {
                    SCHEMA.forEach(statement -> sql.update(statement, Map.of()));
                    schemaReady = true;
                }
            }
        }
    }
}
