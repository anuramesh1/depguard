package dev.depguard.cache;

import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** {@link SqlRunner} over JDBC: the local docker-compose Postgres. */
public class JdbcSqlRunner implements SqlRunner {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcSqlRunner(DataSource dataSource) {
        this.jdbc = new NamedParameterJdbcTemplate(dataSource);
    }

    @Override
    public List<Map<String, Object>> query(String sql, Map<String, Object> parameters) {
        return jdbc.queryForList(sql, parameters);
    }

    @Override
    public void update(String sql, Map<String, Object> parameters) {
        jdbc.update(sql, parameters);
    }
}
