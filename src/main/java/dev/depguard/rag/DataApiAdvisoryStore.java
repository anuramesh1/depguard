package dev.depguard.rag;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.rdsdata.RdsDataClient;
import software.amazon.awssdk.services.rdsdata.model.ColumnMetadata;
import software.amazon.awssdk.services.rdsdata.model.DatabaseResumingException;
import software.amazon.awssdk.services.rdsdata.model.ExecuteStatementResponse;
import software.amazon.awssdk.services.rdsdata.model.Field;
import software.amazon.awssdk.services.rdsdata.model.SqlParameter;

/**
 * The knowledge base in Aurora Serverless v2, reached through the RDS Data API.
 *
 * <p>Why the Data API: it is an HTTPS endpoint authorised by IAM, with the database password kept in
 * Secrets Manager. The Lambda therefore doesn't need to join the database's VPC, which would also force a
 * NAT gateway (~$32/month) to keep reaching OSV, EPSS and CISA on the internet. The trade-off: no JDBC,
 * so no connection pool and no Spring AI PgVectorStore. That's why the SQL is written by hand.
 */
public class DataApiAdvisoryStore implements AdvisoryStore {

    private static final Logger log = LoggerFactory.getLogger(DataApiAdvisoryStore.class);
    /** Rows per BatchExecuteStatement call: ~12 KB each (mostly the vector), well under the 4 MB request limit. */
    static final int BATCH_SIZE = 25;
    /** An auto-paused Aurora takes ~15 s to resume; schema creation (run by ingestion) waits for it. */
    private static final Duration RESUME_WAIT = Duration.ofSeconds(5);
    private static final int RESUME_ATTEMPTS = 24;

    private final RdsDataClient client;
    private final String clusterArn;
    private final String secretArn;
    private final String database;

    public DataApiAdvisoryStore(RdsDataClient client, String clusterArn, String secretArn, String database) {
        this.client = client;
        this.clusterArn = clusterArn;
        this.secretArn = secretArn;
        this.database = database;
    }

    @Override
    public void createSchema() {
        waitUntilAwake();
        HybridSearchSql.SCHEMA.forEach(statement -> execute(statement, List.of()));
    }

    @Override
    public Map<String, String> contentHashes() {
        Map<String, String> hashes = new HashMap<>();
        String after = "";
        while (true) {
            List<Map<String, Field>> rows = rows(execute(HybridSearchSql.CONTENT_HASHES_PAGE, List.of(
                    text("after", after),
                    number("pageSize", HybridSearchSql.HASH_PAGE_SIZE))));
            rows.forEach(row -> hashes.put(row.get("id").stringValue(), row.get("content_hash").stringValue()));
            if (rows.size() < HybridSearchSql.HASH_PAGE_SIZE) {
                return hashes;
            }
            after = rows.getLast().get("id").stringValue();
        }
    }

    @Override
    public void upsert(List<EmbeddedChunk> chunks) {
        for (int from = 0; from < chunks.size(); from += BATCH_SIZE) {
            List<List<SqlParameter>> parameterSets = chunks.subList(from, Math.min(from + BATCH_SIZE, chunks.size()))
                    .stream()
                    .map(DataApiAdvisoryStore::parameters)
                    .toList();
            client.batchExecuteStatement(request -> request
                    .resourceArn(clusterArn).secretArn(secretArn).database(database)
                    .sql(HybridSearchSql.UPSERT)
                    .parameterSets(parameterSets));
        }
    }

    @Override
    public void delete(List<String> ids) {
        for (int from = 0; from < ids.size(); from += BATCH_SIZE) {
            List<List<SqlParameter>> parameterSets = ids.subList(from, Math.min(from + BATCH_SIZE, ids.size()))
                    .stream()
                    .map(id -> List.of(text("id", id)))
                    .toList();
            client.batchExecuteStatement(request -> request
                    .resourceArn(clusterArn).secretArn(secretArn).database(database)
                    .sql(HybridSearchSql.DELETE)
                    .parameterSets(parameterSets));
        }
    }

    @Override
    public List<SearchHit> search(String queryText, float[] queryEmbedding, int limit) {
        ExecuteStatementResponse response = execute(HybridSearchSql.HYBRID_SEARCH, List.of(
                text("embedding", HybridSearchSql.toVectorLiteral(queryEmbedding)),
                text("query", queryText),
                number("candidates", HybridSearchSql.CANDIDATES_PER_SEARCH),
                number("limit", limit)));
        return rows(response).stream().map(DataApiAdvisoryStore::toHit).toList();
    }

    private ExecuteStatementResponse execute(String sql, List<SqlParameter> parameters) {
        return client.executeStatement(request -> request
                .resourceArn(clusterArn).secretArn(secretArn).database(database)
                .sql(sql)
                .parameters(parameters)
                .includeResultMetadata(true));
    }

    /** With min capacity 0, Aurora pauses when idle; the first call wakes it and fails until it's up. */
    private void waitUntilAwake() {
        for (int attempt = 1; ; attempt++) {
            try {
                execute("SELECT 1", List.of());
                return;
            } catch (DatabaseResumingException e) {
                if (attempt == RESUME_ATTEMPTS) {
                    throw e;
                }
                log.info("Aurora is resuming from auto-pause; waiting {} s (attempt {}/{})",
                        RESUME_WAIT.toSeconds(), attempt, RESUME_ATTEMPTS);
                sleep(RESUME_WAIT);
            }
        }
    }

    /** The Data API returns rows as lists of fields; this pairs each field with its column name. */
    private static List<Map<String, Field>> rows(ExecuteStatementResponse response) {
        List<ColumnMetadata> columns = response.columnMetadata();
        return response.records().stream()
                .map(record -> {
                    Map<String, Field> row = new HashMap<>();
                    for (int i = 0; i < columns.size(); i++) {
                        row.put(columns.get(i).name(), record.get(i));
                    }
                    return row;
                })
                .toList();
    }

    private static List<SqlParameter> parameters(EmbeddedChunk embedded) {
        KnowledgeChunk chunk = embedded.chunk();
        return List.of(
                text("id", chunk.id()),
                text("source", chunk.source()),
                text("title", chunk.title()),
                text("url", chunk.url()),
                text("body", chunk.text()),
                text("cveIds", HybridSearchSql.joinCves(chunk.cveIds())),
                text("contentHash", chunk.contentHash()),
                text("embedding", HybridSearchSql.toVectorLiteral(embedded.embedding())));
    }

    private static SearchHit toHit(Map<String, Field> row) {
        return new SearchHit(
                row.get("id").stringValue(),
                row.get("source").stringValue(),
                row.get("title").stringValue(),
                nullableText(row.get("url")),
                row.get("body").stringValue(),
                HybridSearchSql.splitCves(row.get("cve_ids").stringValue()),
                nullableLong(row.get("vector_rank")),
                nullableLong(row.get("keyword_rank")),
                toDouble(row.get("score")));
    }

    private static SqlParameter text(String name, String value) {
        Field field = value == null ? Field.builder().isNull(true).build() : Field.builder().stringValue(value).build();
        return SqlParameter.builder().name(name).value(field).build();
    }

    private static SqlParameter number(String name, long value) {
        return SqlParameter.builder().name(name).value(Field.builder().longValue(value).build()).build();
    }

    private static String nullableText(Field field) {
        return Boolean.TRUE.equals(field.isNull()) ? null : field.stringValue();
    }

    private static Long nullableLong(Field field) {
        return Boolean.TRUE.equals(field.isNull()) ? null : field.longValue();
    }

    /** Postgres numeric comes back as a string over the Data API (to keep precision); doubles as a double. */
    private static double toDouble(Field field) {
        return field.doubleValue() != null ? field.doubleValue() : Double.parseDouble(field.stringValue());
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for Aurora to resume", e);
        }
    }
}
