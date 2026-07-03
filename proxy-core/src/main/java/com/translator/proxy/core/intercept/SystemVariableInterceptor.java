package com.translator.proxy.core.intercept;

import java.util.LinkedHashMap;
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

        String trimmed = sql.trim();

        // SELECT @@xxx [LIMIT n]
        Matcher m = SELECT_AT_AT.matcher(trimmed);
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

        return null;
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
     * 系统变量拦截结果。
     */
    public static class InterceptResult {
        /** 列名（单列模式）或 Variable_name（双列模式） */
        public final String colName1;
        /** 列值或 Variable_value（可能为 null） */
        public final String colName2;
        /** 值 */
        public final String value1;
        /** 第二个值（双列模式） */
        public final String value2;
        /** 是否为双列模式 */
        public final boolean twoColumns;

        /** 单列结果（如 SELECT @@version） */
        InterceptResult(String colName, String value) {
            this.colName1 = colName;
            this.value1 = value;
            this.colName2 = null;
            this.value2 = null;
            this.twoColumns = false;
        }

        /** 双列结果（如 SHOW VARIABLES LIKE） */
        InterceptResult(String colName1, String colName2, String value1, String value2) {
            this.colName1 = colName1;
            this.colName2 = colName2;
            this.value1 = value1;
            this.value2 = value2;
            this.twoColumns = true;
        }
    }
}
