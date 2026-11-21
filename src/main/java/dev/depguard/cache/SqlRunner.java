package dev.depguard.cache;

import java.util.List;
import java.util.Map;

/**
 * Runs SQL with {@code :name} parameters against the app's Postgres, whichever way it is reached: JDBC locally,
 * the RDS Data API on AWS. Values are strings, numbers, booleans or null.
 *
 * <p>The knowledge base has its own store with hand-tuned batching; this smaller interface is enough for simple
 * tables such as the answer cache.
 */
public interface SqlRunner {

    /** Rows as column name -> value. */
    List<Map<String, Object>> query(String sql, Map<String, Object> parameters);

    void update(String sql, Map<String, Object> parameters);
}
