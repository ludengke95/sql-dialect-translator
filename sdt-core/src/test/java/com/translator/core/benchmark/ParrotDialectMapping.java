package com.translator.core.benchmark;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PARROT 官方数据集方言列名到 DialectType 的映射工具。
 *
 * <p>官方 PARROT 数据集中每行包含多个方言列（mysql / postgres / oracle / tsql 等）。
 * 本类负责：
 * <ol>
 *   <li>将受支持的方言列名映射到本项目 {@code DialectType} 枚举名称</li>
 *   <li>从单行 {@link ParrotNativeRow} 展开为所有可翻译方言对的 {@link ParrotTestCase} 列表</li>
 * </ol>
 *
 * <p>当前支持的方言对应关系：
 * <pre>
 *   mysql    -> MYSQL
 *   postgres -> POSTGRESQL
 *   oracle   -> ORACLE
 *   tsql     -> SQLSERVER
 * </pre>
 */
public class ParrotDialectMapping {

    /**
     * PARROT 官方列名 -> 本项目 DialectType.name() 的映射表（有序，决定展开顺序）
     */
    private static final Map<String, String> COLUMN_TO_DIALECT;

    static {
        Map<String, String> m = new LinkedHashMap<String, String>();
        m.put("mysql", "MYSQL");
        m.put("postgres", "POSTGRESQL");
        m.put("oracle", "ORACLE");
        m.put("tsql", "SQLSERVER");
        COLUMN_TO_DIALECT = Collections.unmodifiableMap(m);
    }

    private ParrotDialectMapping() {}

    /**
     * 获取 PARROT 列名到 DialectType 名称的只读映射表。
     */
    public static Map<String, String> getColumnToDialectMap() {
        return COLUMN_TO_DIALECT;
    }

    /**
     * 将单行 {@link ParrotNativeRow} 展开为所有可翻译方言对的测试用例列表。
     *
     * <p>对于每一对不同的受支持方言 (src, tgt)，若该行在 src 和 tgt 列均有非空 SQL，
     * 且属于 DML 语句（若开启 DML 过滤），则生成一条 {@link ParrotTestCase}。
     *
     * <p>生成的用例 id 格式：{@code <原id>_<srcDialect>_<tgtDialect>}，例如
     * {@code row_001_MYSQL_POSTGRESQL}。
     *
     * @param row 官方数据集原始行
     * @return 展开后的测试用例列表（可能为空）
     */
    public static List<ParrotTestCase> expandToCases(ParrotNativeRow row) {
        if (row == null || row.getId() == null) {
            return Collections.emptyList();
        }

        boolean dmlOnly = ParrotDataLoader.isDmlOnlyEnabled();

        // 收集该行中有值的方言 -> SQL 映射
        Map<String, String> availableDialects = new LinkedHashMap<String, String>();
        String[] columns = {"mysql", "postgres", "oracle", "tsql"};
        for (String col : columns) {
            String sql = getSqlByColumn(row, col);
            if (sql != null && !sql.trim().isEmpty()) {
                if (dmlOnly && !ParrotDataLoader.isDmlQuery(sql)) {
                    continue; // 过滤非 DML 方言 SQL
                }
                String dialectName = COLUMN_TO_DIALECT.get(col);
                availableDialects.put(dialectName, sql);
            }
        }

        if (availableDialects.size() < 2) {
            // 不足两种符合条件的方言，无法生成翻译对
            return Collections.emptyList();
        }

        List<String> dialects = new ArrayList<String>(availableDialects.keySet());
        List<ParrotTestCase> cases = new ArrayList<ParrotTestCase>();

        for (String src : dialects) {
            for (String tgt : dialects) {
                if (!src.equals(tgt)) {
                    String caseId = row.getId() + "_" + src + "_TO_" + tgt;
                    cases.add(new ParrotTestCase(
                            caseId,
                            src,
                            tgt,
                            availableDialects.get(src),
                            availableDialects.get(tgt),
                            "native"));
                }
            }
        }

        return cases;
    }

    /**
     * 将多行 {@link ParrotNativeRow} 展开为测试用例列表。
     *
     * @param rows 官方数据集原始行列表
     * @return 展开后的所有测试用例列表
     */
    public static List<ParrotTestCase> expandAllRows(List<ParrotNativeRow> rows) {
        if (rows == null || rows.isEmpty()) {
            return Collections.emptyList();
        }
        List<ParrotTestCase> all = new ArrayList<ParrotTestCase>();
        for (ParrotNativeRow row : rows) {
            all.addAll(expandToCases(row));
        }
        return all;
    }

    /**
     * 根据列名从 {@link ParrotNativeRow} 中取对应字段值。
     */
    private static String getSqlByColumn(ParrotNativeRow row, String column) {
        switch (column) {
            case "mysql":
                return row.getMysql();
            case "postgres":
                return row.getPostgres();
            case "oracle":
                return row.getOracle();
            case "tsql":
                return row.getTsql();
            default:
                return null;
        }
    }
}
