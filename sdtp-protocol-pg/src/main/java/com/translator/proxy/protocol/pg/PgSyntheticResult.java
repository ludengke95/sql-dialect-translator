package com.translator.proxy.protocol.pg;

import java.util.Collections;
import java.util.List;

/**
 * 系统目录合成查询结果。
 *
 * <p>用于直接应答 psql 在连接阶段发起的 pg_catalog 探测查询，
 * 避免把这类查询透传到后端（后端可能是 MySQL，无法理解 PG 系统目录）。
 */
public class PgSyntheticResult {

    private final List<String> columns;
    private final List<List<String>> rows;

    public PgSyntheticResult(List<String> columns, List<List<String>> rows) {
        this.columns = columns;
        this.rows = rows;
    }

    public List<String> getColumns() {
        return columns;
    }

    public List<List<String>> getRows() {
        return rows;
    }

    /** 单行单列的便捷构造 */
    public static PgSyntheticResult single(String column, String value) {
        return new PgSyntheticResult(
                Collections.singletonList(column), Collections.singletonList(Collections.singletonList(value)));
    }
}
