package dev.depguard.rag;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

/** The knowledge base in a Postgres reached over JDBC: the local docker-compose database. */
public class JdbcAdvisoryStore implements AdvisoryStore {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcAdvisoryStore(DataSource dataSource) {
        this.jdbc = new NamedParameterJdbcTemplate(dataSource);
    }

    @Override
    public void createSchema() {
        HybridSearchSql.SCHEMA.forEach(statement -> jdbc.getJdbcTemplate().execute(statement));
    }

    @Override
    public Map<String, String> contentHashes() {
        Map<String, String> hashes = new HashMap<>();
        String after = "";
        while (true) {
            var page = new MapSqlParameterSource("after", after).addValue("pageSize", HybridSearchSql.HASH_PAGE_SIZE);
            List<String[]> rows = jdbc.query(HybridSearchSql.CONTENT_HASHES_PAGE, page,
                    (rs, i) -> new String[] {rs.getString("id"), rs.getString("content_hash")});
            rows.forEach(row -> hashes.put(row[0], row[1]));
            if (rows.size() < HybridSearchSql.HASH_PAGE_SIZE) {
                return hashes;
            }
            after = rows.getLast()[0];
        }
    }

    @Override
    public void upsert(List<EmbeddedChunk> chunks) {
        SqlParameterSource[] rows = chunks.stream().map(JdbcAdvisoryStore::parameters).toArray(SqlParameterSource[]::new);
        jdbc.batchUpdate(HybridSearchSql.UPSERT, rows);
    }

    @Override
    public void delete(List<String> ids) {
        SqlParameterSource[] rows = ids.stream().map(id -> new MapSqlParameterSource("id", id))
                .toArray(SqlParameterSource[]::new);
        jdbc.batchUpdate(HybridSearchSql.DELETE, rows);
    }

    @Override
    public List<SearchHit> search(String queryText, float[] queryEmbedding, int limit) {
        var params = new MapSqlParameterSource()
                .addValue("embedding", HybridSearchSql.toVectorLiteral(queryEmbedding))
                .addValue("query", queryText)
                .addValue("candidates", HybridSearchSql.CANDIDATES_PER_SEARCH)
                .addValue("limit", limit);
        return jdbc.query(HybridSearchSql.HYBRID_SEARCH, params, (rs, i) -> toHit(rs));
    }

    private static SqlParameterSource parameters(EmbeddedChunk embedded) {
        KnowledgeChunk chunk = embedded.chunk();
        return new MapSqlParameterSource()
                .addValue("id", chunk.id())
                .addValue("source", chunk.source())
                .addValue("title", chunk.title())
                .addValue("url", chunk.url())
                .addValue("body", chunk.text())
                .addValue("cveIds", HybridSearchSql.joinCves(chunk.cveIds()))
                .addValue("contentHash", chunk.contentHash())
                .addValue("embedding", HybridSearchSql.toVectorLiteral(embedded.embedding()));
    }

    private static SearchHit toHit(ResultSet rs) throws SQLException {
        return new SearchHit(
                rs.getString("id"),
                rs.getString("source"),
                rs.getString("title"),
                rs.getString("url"),
                rs.getString("body"),
                HybridSearchSql.splitCves(rs.getString("cve_ids")),
                rs.getObject("vector_rank", Long.class),
                rs.getObject("keyword_rank", Long.class),
                rs.getDouble("score"));
    }
}
