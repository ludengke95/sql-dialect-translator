package com.translator.metrics;

import io.prometheus.client.Counter;
import io.prometheus.client.Histogram;

/**
 * 后端执行层指标：JDBC 查询计数、耗时、错误、结果行数。
 *
 * <p>Label 说明：
 * <ul>
 *   <li>{@code backend_name} — 后端名称</li>
 *   <li>{@code query_type} — 查询类型（SELECT / DML / DDL / OTHER）</li>
 *   <li>{@code sql_state} — SQL 错误状态码</li>
 * </ul>
 */
public final class BackendMetrics {

    private BackendMetrics() {}

    /** 后端 JDBC 执行总数（按 backend_name 和 query_type 分 label） */
    public static final Counter QUERIES = Counter.build()
            .name("sdt_backend_queries_total")
            .help("Total backend JDBC queries executed")
            .labelNames("backend_name", "query_type")
            .register();

    /** 后端 JDBC 执行耗时（秒），按 backend_name 分 label */
    public static final Histogram QUERY_DURATION = Histogram.build()
            .name("sdt_backend_queries_duration_seconds")
            .help("Backend JDBC query execution duration in seconds")
            .labelNames("backend_name")
            .buckets(0.001, 0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10, 30, 60)
            .register();

    /** 后端 SQL 执行错误次数（按 backend_name 和 sql_state 分 label） */
    public static final Counter ERRORS = Counter.build()
            .name("sdt_backend_errors_total")
            .help("Total backend SQL execution errors")
            .labelNames("backend_name", "sql_state")
            .register();

    /** 查询返回行数分布 */
    public static final Histogram RESULT_ROWS = Histogram.build()
            .name("sdt_backend_result_rows")
            .help("Number of rows returned per query")
            .labelNames("backend_name")
            .buckets(0, 1, 5, 10, 50, 100, 500, 1000, 5000, 10000, 50000, 100000)
            .register();

    /** DML 影响行数分布 */
    public static final Histogram AFFECTED_ROWS = Histogram.build()
            .name("sdt_backend_affected_rows")
            .help("Number of rows affected per DML")
            .labelNames("backend_name")
            .buckets(0, 1, 5, 10, 50, 100, 500, 1000, 5000, 10000, 50000, 100000)
            .register();

    // ==================== 便捷方法 ====================

    public static void recordQuery(String backendName, String queryType) {
        QUERIES.labels(backendName, queryType).inc();
    }

    public static void recordQueryDuration(String backendName, double seconds) {
        QUERY_DURATION.labels(backendName).observe(seconds);
    }

    public static void recordError(String backendName, String sqlState) {
        ERRORS.labels(backendName, sqlState).inc();
    }

    public static void observeResultRows(String backendName, long rowCount) {
        RESULT_ROWS.labels(backendName).observe(rowCount);
    }

    public static void observeAffectedRows(String backendName, long rowCount) {
        AFFECTED_ROWS.labels(backendName).observe(rowCount);
    }

    /**
     * 根据 SQL 前缀推断查询类型。
     */
    public static String classifyQueryType(String sql) {
        if (sql == null) return "OTHER";
        String trimmed = sql.trim().toUpperCase();
        if (trimmed.startsWith("SELECT")
                || trimmed.startsWith("WITH")
                || trimmed.startsWith("SHOW")
                || trimmed.startsWith("DESCRIBE")
                || trimmed.startsWith("EXPLAIN")) {
            return "SELECT";
        }
        if (trimmed.startsWith("INSERT")
                || trimmed.startsWith("UPDATE")
                || trimmed.startsWith("DELETE")
                || trimmed.startsWith("REPLACE")
                || trimmed.startsWith("MERGE")) {
            return "DML";
        }
        if (trimmed.startsWith("CREATE")
                || trimmed.startsWith("ALTER")
                || trimmed.startsWith("DROP")
                || trimmed.startsWith("TRUNCATE")
                || trimmed.startsWith("RENAME")) {
            return "DDL";
        }
        return "OTHER";
    }

    /** 创建后端查询耗时计时器 */
    public static Timer startTimer(String backendName) {
        return new Timer(backendName);
    }

    public static class Timer implements AutoCloseable {
        private final String backendName;
        private final long startNanos;

        Timer(String backendName) {
            this.backendName = backendName;
            this.startNanos = System.nanoTime();
        }

        @Override
        public void close() {
            double seconds = (System.nanoTime() - startNanos) / 1_000_000_000.0;
            QUERY_DURATION.labels(backendName).observe(seconds);
        }
    }
}
