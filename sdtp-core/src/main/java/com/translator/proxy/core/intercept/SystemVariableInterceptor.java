package com.translator.proxy.core.intercept;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MySQL 系统变量查询拦截器。
 *
 * <p>拦截常见的 {@code SELECT @@xxx} 和 {@code SHOW VARIABLES} 语句，
 * 返回伪造的 MySQL 兼容结果，避免将这些查询发送到目标数据库（目标库可能不支持）。
 */
public final class SystemVariableInterceptor {

    private SystemVariableInterceptor() {}

    /** SELECT @@variable_name [LIMIT n] */
    private static final Pattern SELECT_AT_AT = Pattern.compile(
            "^\\s*SELECT\\s+@@(\\w+)(?:\\s+LIMIT\\s+\\d+)?\\s*$",
            Pattern.CASE_INSENSITIVE);

    /** SHOW VARIABLES LIKE 'xxx' */
    private static final Pattern SHOW_VARIABLES_LIKE = Pattern.compile(
            "^\\s*SHOW\\s+VARIABLES\\s+LIKE\\s+'([^']*)'\\s*$",
            Pattern.CASE_INSENSITIVE);

    /** SELECT DATABASE() */
    private static final Pattern SELECT_DATABASE = Pattern.compile(
            "^\\s*SELECT\\s+DATABASE\\s*\\(\\s*\\)\\s*$",
            Pattern.CASE_INSENSITIVE);

    /** SHOW WARNINGS — mysql CLI 常用，目标库可能不支持 */
    private static final Pattern SHOW_WARNINGS = Pattern.compile(
            "^\\s*SHOW\\s+WARNINGS\\s*$",
            Pattern.CASE_INSENSITIVE);

    /**
     * 多列系统变量查询：SELECT @@session.var1 AS alias1, @@var2 AS alias2, ...
     * 用于拦截 MySQL Connector/J 8.0.33 在连接建立时发送的多变量查询。
     */
    private static final Pattern SELECT_MULTI_AT_AT = Pattern.compile(
            "^\\s*SELECT\\s+@@(?:session\\.)?(\\w+)(?:\\s+AS\\s+\\w+)?(?:,\\s*@@(?:session\\.)?(\\w+)(?:\\s+AS\\s+\\w+)?)*\\s*$",
            Pattern.CASE_INSENSITIVE);

    /** 预定义的系统变量值 */
    private static final Map<String, String> SYSTEM_VARIABLES = new LinkedHashMap<>();

    static {
        SYSTEM_VARIABLES.put("version_comment", "MySQL Proxy 5.7.38");
        SYSTEM_VARIABLES.put("version", "5.7.38-proxy");
        SYSTEM_VARIABLES.put("version_compile_os", "Linux");
        SYSTEM_VARIABLES.put("version_compile_machine", "x86_64");
        SYSTEM_VARIABLES.put("character_set_client", "utf8mb4");
        SYSTEM_VARIABLES.put("character_set_connection", "utf8mb4");
        SYSTEM_VARIABLES.put("character_set_results", "utf8mb4");
        SYSTEM_VARIABLES.put("character_set_server", "utf8mb4");
        SYSTEM_VARIABLES.put("collation_connection", "utf8mb4_general_ci");
        SYSTEM_VARIABLES.put("collation_server", "utf8mb4_general_ci");
        SYSTEM_VARIABLES.put("tx_isolation", "READ-COMMITTED");
        SYSTEM_VARIABLES.put("transaction_isolation", "READ-COMMITTED");
        SYSTEM_VARIABLES.put("autocommit", "1");
        SYSTEM_VARIABLES.put("max_allowed_packet", "16777216");
        SYSTEM_VARIABLES.put("wait_timeout", "28800");
        SYSTEM_VARIABLES.put("interactive_timeout", "28800");
        SYSTEM_VARIABLES.put("sql_mode", "ONLY_FULL_GROUP_BY,STRICT_TRANS_TABLES,NO_ZERO_IN_DATE,NO_ZERO_DATE,ERROR_FOR_DIVISION_BY_ZERO,NO_AUTO_CREATE_USER,NO_ENGINE_SUBSTITUTION");
        SYSTEM_VARIABLES.put("lower_case_table_names", "1");
        SYSTEM_VARIABLES.put("license", "GPL");
        SYSTEM_VARIABLES.put("innodb_version", "5.7.38");
        SYSTEM_VARIABLES.put("protocol_version", "10");
        SYSTEM_VARIABLES.put("ssl_cipher", "");
        SYSTEM_VARIABLES.put("have_ssl", "DISABLED");
        // MySQL Connector/J 8.0 查询的系统变量
        SYSTEM_VARIABLES.put("auto_increment_increment", "1");
        SYSTEM_VARIABLES.put("net_write_timeout", "60");
        SYSTEM_VARIABLES.put("performance_schema", "0");
        // MySQL 8.0 已废弃的变量，但 Connector/J 仍会查询
        SYSTEM_VARIABLES.put("query_cache_size", "0");
        SYSTEM_VARIABLES.put("query_cache_type", "OFF");
        SYSTEM_VARIABLES.put("system_time_zone", "UTC");
        SYSTEM_VARIABLES.put("time_zone", "SYSTEM");
        SYSTEM_VARIABLES.put("init_connect", "");
    }

    /**
     * 尝试拦截系统变量查询。
     *
     * @param sql     原始 SQL
     * @param database 当前 database（可能为 null，用于 SELECT DATABASE()）
     * @return 拦截结果（包含列名和值），如果不应拦截则返回 null
     */
    public static InterceptResult intercept(String sql, String database) {
        if (sql == null) return null;

        // 去除 SQL 前面的注释（如 MySQL Connector/J 的版本注释）
        // 格式: /* mysql-connector-j-8.0.33 ... */ SELECT ...
        String trimmed = sql.trim();
        if (trimmed.startsWith("/*")) {
            // 查找注释结束位置
            int commentEnd = trimmed.indexOf("*/");
            if (commentEnd >= 0) {
                // 去除注释，保留后面的 SQL
                trimmed = trimmed.substring(commentEnd + 2).trim();
            }
        }

        // 优先检查多列系统变量查询（如 MySQL Connector/J 8.0.33 发送的查询）
        // SELECT @@session.var1 AS alias1, @@var2 AS alias2, ...
        Matcher m = SELECT_MULTI_AT_AT.matcher(trimmed);
        if (m.find()) {
            List<ColumnInfo> columns = extractMultiVariableNames(trimmed);
            // 单变量查询：走原有逻辑（已知变量返回单列结果，未知变量不拦截）
            if (columns.size() == 1) {
                ColumnInfo col = columns.get(0);
                String value = SYSTEM_VARIABLES.get(col.columnName);
                if (value != null) {
                    return new InterceptResult("@@" + col.columnName, value);
                }
                // 未知单变量，不拦截
                return null;
            }
            // 多变量查询：返回多列结果（未知变量返回空字符串）
            if (columns.size() > 1) {
                return new InterceptResult(columns);
            }
        }

        // SELECT @@xxx [LIMIT n] — 单列查询（原有逻辑，不支持 @@session. 前缀）
        m = SELECT_AT_AT.matcher(trimmed);
        if (m.find()) {
            String varName = m.group(1).toLowerCase();
            String value = SYSTEM_VARIABLES.get(varName);
            if (value != null) {
                return new InterceptResult("@@" + varName, value);
            }
        }

        // SHOW VARIABLES LIKE 'xxx'
        m = SHOW_VARIABLES_LIKE.matcher(trimmed);
        if (m.find()) {
            String varName = m.group(1).toLowerCase();
            String value = SYSTEM_VARIABLES.get(varName);
            if (value != null) {
                return new InterceptResult("Variable_name", "Value",
                        varName, value);
            }
        }

        // SELECT DATABASE()
        m = SELECT_DATABASE.matcher(trimmed);
        if (m.find()) {
            String db = database != null ? database : "NULL";
            return new InterceptResult("DATABASE()", db);
        }

        // SHOW WARNINGS — 返回空结果集（始终无警告）
        m = SHOW_WARNINGS.matcher(trimmed);
        if (m.find()) {
            // 三列空结果集（SHOW WARNINGS 有三列但始终无数据行）
            return InterceptResult.emptyThreeColumn("Level", "Code", "Message");
        }

        return null;
    }

    /**
     * 从多列系统变量查询中提取变量名和值。
     *
     * <p>支持格式：
     * <ul>
     *   <li>@@session.var_name AS alias</li>
     *   <li>@@var_name AS alias</li>
     *   <li>@@session.var_name</li>
     *   <li>@@var_name</li>
     * </ul>
     *
     * @param sql SQL 语句
     * @return 列信息列表（变量名已标准化为不含 session. 前缀）
     */
    private static List<ColumnInfo> extractMultiVariableNames(String sql) {
        List<ColumnInfo> columns = new ArrayList<>();

        // 匹配所有 @@session.var_name 或 @@var_name 模式
        Pattern varPattern = Pattern.compile(
                "@@(?:session\\.)?(\\w+)",
                Pattern.CASE_INSENSITIVE);
        Matcher m = varPattern.matcher(sql);

        while (m.find()) {
            String varName = m.group(1).toLowerCase();
            // 移除 session. 前缀（如果有）
            String normalized = varName.replace("session.", "");

            // 查找变量值，未定义变量返回空字符串
            String value = SYSTEM_VARIABLES.get(normalized);
            if (value == null) {
                value = "";
            }

            columns.add(new ColumnInfo(normalized, value));
        }

        return columns;
    }

    /**
     * 判断是否为 SET 语句（在 Proxy 侧直接生效，不转发）。
     */
    public static boolean isSetStatement(String sql) {
        if (sql == null) return false;
        String trimmed = sql.trim().toUpperCase();
        return trimmed.startsWith("SET ") || trimmed.equals("SET");
    }

    /**
     * 判断是否为 USE 语句（切换 database）。
     */
    public static String extractUseDatabase(String sql) {
        if (sql == null) return null;
        String trimmed = sql.trim();
        Pattern usePattern = Pattern.compile("^\\s*USE\\s+`?(\\w+)`?\\s*", Pattern.CASE_INSENSITIVE);
        Matcher m = usePattern.matcher(trimmed);
        return m.find() ? m.group(1) : null;
    }

    // ==================== 拦截结果 ====================

    /**
     * 列信息（用于多列结果模式）。
     */
    public static class ColumnInfo {
        /** 列名（不含 @@ 前缀，如 "version_comment"） */
        public final String columnName;
        /** 列值 */
        public final String value;

        /**
         * 构造列信息。
         *
         * @param columnName 列名
         * @param value      列值
         */
        public ColumnInfo(String columnName, String value) {
            this.columnName = columnName;
            this.value = value;
        }
    }

    /**
     * 系统变量拦截结果。
     */
    public static class InterceptResult {
        /** 列名（单列模式）或 Variable_name（双列模式） */
        public final String colName1;
        /** 列值或 Variable_value（可能为 null） */
        public String colName2;
        /** 第三个列名（三列模式，如 SHOW WARNINGS） */
        public String colName3;
        /** 值 */
        public final String value1;
        /** 第二个值（双列模式） */
        public final String value2;
        /** 是否为双列模式 */
        public boolean twoColumns;
        /** 是否无数据行（只返回列定义） */
        public boolean empty;

        // ==================== 多列模式 ====================
        /** 多列结果（如 SELECT @@var1, @@var2, ...） */
        public final List<ColumnInfo> columns;

        /** 单列结果（如 SELECT @@version） */
        InterceptResult(String colName, String value) {
            this.colName1 = colName;
            this.value1 = value;
            this.colName2 = null;
            this.colName3 = null;
            this.value2 = null;
            this.twoColumns = false;
            this.empty = false;
            this.columns = null;
        }

        /** 双列结果（如 SHOW VARIABLES LIKE） */
        InterceptResult(String colName1, String colName2, String value1, String value2) {
            this.colName1 = colName1;
            this.colName2 = colName2;
            this.colName3 = null;
            this.value1 = value1;
            this.value2 = value2;
            this.twoColumns = true;
            this.empty = false;
            this.columns = null;
        }

        /**
         * 多列结果（如 SELECT @@var1 AS alias1, @@var2 AS alias2, ...）。
         *
         * @param columns 列信息列表
         */
        InterceptResult(List<ColumnInfo> columns) {
            // 设置兼容字段（取第一列信息）
            this.colName1 = columns.isEmpty() ? "" : columns.get(0).columnName;
            this.value1 = columns.isEmpty() ? "" : columns.get(0).value;
            this.colName2 = null;
            this.colName3 = null;
            this.value2 = null;
            this.twoColumns = false;
            this.empty = false;
            this.columns = columns;
        }

        /** 三列空结果集（如 SHOW WARNINGS，有列名无数据行） */
        static InterceptResult emptyThreeColumn(String col1, String col2, String col3) {
            InterceptResult r = new InterceptResult(col1, null);
            r.colName2 = col2;
            r.colName3 = col3;
            r.twoColumns = false;
            r.empty = true;
            return r;
        }

        /**
         * 判断是否为多列模式。
         *
         * @return 如果 columns 不为 null 且包含多列，返回 true
         */
        public boolean isMultiColumn() {
            return columns != null && columns.size() > 1;
        }
    }
}
