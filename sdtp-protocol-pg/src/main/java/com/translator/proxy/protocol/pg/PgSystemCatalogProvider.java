package com.translator.proxy.protocol.pg;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * PostgreSQL 系统目录（pg_catalog）合成应答提供器。
 *
 * <p>psql / libpq 在连接建立及日常操作中会查询大量 pg_catalog 元数据
 * （如 {@code version()}、{@code current_schema()}、{@code pg_type} 等）。
 * 这些查询若透传到 MySQL 后端必然失败，因此由代理直接合成应答。
 *
 * <p>匹配基于 SQL 的归一化文本（去空白、小写、去尾分号），仅覆盖最常见的探测查询；
 * 未识别的查询返回 {@code null}，交由后端执行（或返回错误）。
 */
public final class PgSystemCatalogProvider {

    private PgSystemCatalogProvider() {}

    /**
     * 尝试应答系统目录/内置函数查询。
     *
     * @param sql 原始 SQL
     * @return 合成结果；无法识别时返回 {@code null}
     */
    public static PgSyntheticResult answer(String sql) {
        if (sql == null || sql.trim().isEmpty()) {
            return null;
        }
        String norm = normalize(sql);
        if (norm.isEmpty()) {
            return null;
        }

        if (norm.contains("version()")) {
            return PgSyntheticResult.single(
                    "version", "PostgreSQL 15.0 (SDT Proxy - SQL Dialect Translator)");
        }
        if (norm.contains("current_schema()")) {
            return PgSyntheticResult.single("?column?", "public");
        }
        if (norm.contains("current_database()")) {
            return PgSyntheticResult.single("current_database", "postgres");
        }
        if (norm.contains("current_user") && (norm.contains("()") || norm.equals("select current_user"))) {
            return PgSyntheticResult.single("current_user", "postgres");
        }
        if (norm.matches("^select\\s+\\d+\\s*$")) {
            String num = norm.substring("select".length()).trim();
            return PgSyntheticResult.single("?column?", num);
        }
        if (norm.contains("pg_catalog.pg_type") || norm.contains("from pg_type")) {
            return buildPgType();
        }
        if (norm.contains("pg_catalog.pg_database") || norm.contains("from pg_database")) {
            return new PgSyntheticResult(
                    Collections.singletonList("datname"),
                    Collections.singletonList(Collections.singletonList("postgres")));
        }
        if (norm.contains("pg_catalog.pg_am") || norm.contains("from pg_am")) {
            return new PgSyntheticResult(
                    Arrays.asList("oid", "amname"),
                    Arrays.asList(
                            Arrays.asList("2", "heap"),
                            Arrays.asList("403", "btree")));
        }
        return null;
    }

    private static PgSyntheticResult buildPgType() {
        List<String> columns = Arrays.asList("oid", "typname", "typlen", "typcategory");
        List<List<String>> rows = new ArrayList<>();
        rows.add(Arrays.asList("16", "bool", "1", "B"));
        rows.add(Arrays.asList("17", "bytea", "-1", "U"));
        rows.add(Arrays.asList("20", "int8", "8", "N"));
        rows.add(Arrays.asList("21", "int2", "2", "N"));
        rows.add(Arrays.asList("23", "int4", "4", "N"));
        rows.add(Arrays.asList("25", "text", "-1", "S"));
        rows.add(Arrays.asList("700", "float4", "4", "N"));
        rows.add(Arrays.asList("701", "float8", "8", "N"));
        rows.add(Arrays.asList("1042", "bpchar", "-1", "S"));
        rows.add(Arrays.asList("1043", "varchar", "-1", "S"));
        rows.add(Arrays.asList("1082", "date", "4", "D"));
        rows.add(Arrays.asList("1114", "timestamp", "8", "D"));
        rows.add(Arrays.asList("1184", "timestamptz", "8", "D"));
        rows.add(Arrays.asList("1700", "numeric", "-1", "N"));
        return new PgSyntheticResult(columns, rows);
    }

    private static String normalize(String sql) {
        String s = sql.trim().toLowerCase();
        if (s.endsWith(";")) {
            s = s.substring(0, s.length() - 1);
        }
        // 折叠连续空白为单空格
        return s.replaceAll("\\s+", " ").trim();
    }
}
