package dev.depguard.cache;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import software.amazon.awssdk.services.rdsdata.RdsDataClient;
import software.amazon.awssdk.services.rdsdata.model.ColumnMetadata;
import software.amazon.awssdk.services.rdsdata.model.ExecuteStatementResponse;
import software.amazon.awssdk.services.rdsdata.model.Field;
import software.amazon.awssdk.services.rdsdata.model.SqlParameter;

/** {@link SqlRunner} over the RDS Data API: Aurora on AWS, without the Lambda joining a VPC. */
public class DataApiSqlRunner implements SqlRunner {

    private final RdsDataClient client;
    private final String clusterArn;
    private final String secretArn;
    private final String database;

    public DataApiSqlRunner(RdsDataClient client, String clusterArn, String secretArn, String database) {
        this.client = client;
        this.clusterArn = clusterArn;
        this.secretArn = secretArn;
        this.database = database;
    }

    @Override
    public List<Map<String, Object>> query(String sql, Map<String, Object> parameters) {
        ExecuteStatementResponse response = execute(sql, parameters);
        List<ColumnMetadata> columns = response.columnMetadata();
        return response.records().stream().map(record -> {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 0; i < columns.size(); i++) {
                row.put(columns.get(i).name(), valueOf(record.get(i)));
            }
            return row;
        }).toList();
    }

    @Override
    public void update(String sql, Map<String, Object> parameters) {
        execute(sql, parameters);
    }

    private ExecuteStatementResponse execute(String sql, Map<String, Object> parameters) {
        List<SqlParameter> sqlParameters = parameters.entrySet().stream()
                .map(e -> SqlParameter.builder().name(e.getKey()).value(fieldOf(e.getValue())).build())
                .toList();
        return client.executeStatement(request -> request
                .resourceArn(clusterArn).secretArn(secretArn).database(database)
                .sql(sql).parameters(sqlParameters).includeResultMetadata(true));
    }

    private static Field fieldOf(Object value) {
        return switch (value) {
            case null -> Field.builder().isNull(true).build();
            case String s -> Field.builder().stringValue(s).build();
            case Integer i -> Field.builder().longValue(i.longValue()).build();
            case Long l -> Field.builder().longValue(l).build();
            case Double d -> Field.builder().doubleValue(d).build();
            case Boolean b -> Field.builder().booleanValue(b).build();
            default -> Field.builder().stringValue(value.toString()).build();
        };
    }

    private static Object valueOf(Field field) {
        if (Boolean.TRUE.equals(field.isNull())) {
            return null;
        }
        if (field.stringValue() != null) {
            return field.stringValue();
        }
        if (field.longValue() != null) {
            return field.longValue();
        }
        if (field.doubleValue() != null) {
            return field.doubleValue();
        }
        return field.booleanValue();
    }
}
