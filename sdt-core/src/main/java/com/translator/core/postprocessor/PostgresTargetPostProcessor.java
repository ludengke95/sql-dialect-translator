package com.translator.core.postprocessor;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import com.translator.core.DialectType;

/**
 * PostgreSQL 目标方言 SQL 后处理器。
 * 负责：
 * <ol>
 *   <li>将时间运算算子与字符串字面量加法转换为 PostgreSQL 兼容的 INTERVAL 'N UNIT'。</li>
 *   <li>清理残留的反引号 `` ` ``，归一化为双引号。</li>
 *   <li>对 PostgreSQL 保留关键字别名（如 AS range）强制包裹双引号。</li>
 * </ol>
 */
public class PostgresTargetPostProcessor implements TargetDialectPostProcessor {

    private static final Set<String> PG_RESERVED_KEYWORDS = new HashSet<>(Arrays.asList(
            "RANGE",
            "USER",
            "KEY",
            "ALL",
            "ANALYSE",
            "ANALYZE",
            "AND",
            "ANY",
            "ARRAY",
            "AS",
            "ASC",
            "ASYMMETRIC",
            "BOTH",
            "CASE",
            "CAST",
            "CHECK",
            "COLLATE",
            "COLUMN",
            "CONSTRAINT",
            "CREATE",
            "CURRENT_CATALOG",
            "CURRENT_DATE",
            "CURRENT_ROLE",
            "CURRENT_TIME",
            "CURRENT_TIMESTAMP",
            "CURRENT_USER",
            "DEFAULT",
            "DEFERRABLE",
            "DESC",
            "DISTINCT",
            "DO",
            "ELSE",
            "END",
            "EXCEPT",
            "FALSE",
            "FETCH",
            "FOR",
            "FOREIGN",
            "FROM",
            "GRANT",
            "GROUP",
            "HAVING",
            "IN",
            "INITIALLY",
            "INTERSECT",
            "INTO",
            "LATERAL",
            "LEADING",
            "LIMIT",
            "LOCALTIME",
            "LOCALTIMESTAMP",
            "NOT",
            "NULL",
            "OFFSET",
            "ON",
            "ONLY",
            "OR",
            "ORDER",
            "PLACING",
            "PRIMARY",
            "REFERENCES",
            "RETURNING",
            "SELECT",
            "SESSION_USER",
            "SOME",
            "SYMMETRIC",
            "TABLE",
            "THEN",
            "TO",
            "TRAILING",
            "TRUE",
            "UNION",
            "UNIQUE",
            "VARIADIC",
            "WHEN",
            "WHERE",
            "WINDOW",
            "WITH"));

    @Override
    public Set<DialectType> getTargetDialects() {
        return Collections.singleton(DialectType.POSTGRESQL);
    }

    @Override
    public int getOrder() {
        return 0;
    }

    @Override
    public String process(String sql, DialectType sourceDialect) {
        if (sql == null || sql.isEmpty()) {
            return sql;
        }

        // 1. 清理残留的反引号
        String result = sql.replace('`', '"');

        // 2. 将 DATE_ADD/SUBDATE 改写后的 timestamp + 'N UNIT' 右侧字符串包装为 INTERVAL
        result = result.replaceAll(
                "\\+\\s*'(-?\\d+\\s+(?:DAYS|MONTHS|YEARS|HOURS|MINUTES|SECONDS))'", "+ INTERVAL '$1'");

        // 3. 对被脱去双引号的 PostgreSQL 敏感保留关键字别名（如 AS range -> AS "range"）进行安全保护
        result = protectPgReservedAliases(result);

        return result;
    }

    private String protectPgReservedAliases(String sql) {
        // 正则匹配 AS <keyword> （后接逗号、FROM、WHERE 或空格，排除已带双引号的）
        for (String kw : PG_RESERVED_KEYWORDS) {
            if ("AS".equals(kw) || "FROM".equals(kw) || "WHERE".equals(kw) || "SELECT".equals(kw)) {
                continue; // 排除核心 SQL 动词
            }
            // 匹配 AS range \b
            String pattern = "(?i)\\bAS\\s+" + kw + "\\b(?![\\w\"])";
            sql = sql.replaceAll(pattern, "AS \"" + kw.toLowerCase() + "\"");
        }
        return sql;
    }
}
