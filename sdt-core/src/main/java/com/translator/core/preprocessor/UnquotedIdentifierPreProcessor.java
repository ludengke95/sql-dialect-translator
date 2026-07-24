package com.translator.core.preprocessor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.translator.core.DialectType;
import com.translator.core.config.TranslationConfig;

/**
 * 前置处理器：扫描未包裹双引号的独立标识符与 alias.column，
 * 自动补全双引号，并根据 config.getIdentifierCase() 规整大小写。
 */
public class UnquotedIdentifierPreProcessor implements SourceDialectPreProcessor {

    private static final Set<String> SQL_KEYWORDS = new HashSet<>(Arrays.asList(
            "SELECT",
            "FROM",
            "WHERE",
            "AND",
            "OR",
            "NOT",
            "IN",
            "AS",
            "ON",
            "JOIN",
            "LEFT",
            "RIGHT",
            "INNER",
            "OUTER",
            "CROSS",
            "FULL",
            "NATURAL",
            "ORDER",
            "BY",
            "GROUP",
            "HAVING",
            "DISTINCT",
            "UNION",
            "ALL",
            "ANY",
            "EXISTS",
            "BETWEEN",
            "LIKE",
            "IS",
            "NULL",
            "TRUE",
            "FALSE",
            "CASE",
            "WHEN",
            "THEN",
            "ELSE",
            "END",
            "ASC",
            "DESC",
            "LIMIT",
            "OFFSET",
            "SET",
            "INTO",
            "VALUES",
            "INSERT",
            "UPDATE",
            "DELETE",
            "CREATE",
            "TABLE",
            "DROP",
            "ALTER",
            "IF",
            "FOR",
            "SOME",
            "WITH",
            "USING",
            "PRIMARY",
            "KEY",
            "FOREIGN",
            "INDEX",
            "UNIQUE",
            "CHECK",
            "DEFAULT",
            "CONSTRAINT",
            "ADD",
            "COLUMN",
            "REFERENCES",
            "FETCH",
            "NEXT",
            "ROWS",
            "ONLY",
            "EXCEPT",
            "INTERSECT",
            "MINUS",
            "COUNT",
            "SUM",
            "AVG",
            "MIN",
            "MAX",
            "COALESCE",
            "CAST",
            "CONVERT",
            "NULLIF",
            "IFNULL",
            "NVL",
            "ISNULL",
            "DECODE",
            "CONCAT",
            "SUBSTRING",
            "TRIM",
            "UPPER",
            "LOWER",
            "LENGTH",
            "REPLACE",
            "SUBSTR",
            "INSTR",
            "TO_CHAR",
            "TO_DATE",
            "TO_NUMBER",
            "TO_TIMESTAMP",
            "NOW",
            "SYSDATE",
            "GETDATE",
            "CURDATE",
            "CURTIME",
            "DATE_FORMAT",
            "DATE_ADD",
            "DATE_SUB",
            "DATEDIFF",
            "EXTRACT",
            "INTERVAL",
            "YEAR",
            "MONTH",
            "DAY",
            "HOUR",
            "MINUTE",
            "SECOND",
            "CURRENT_TIMESTAMP",
            "CURRENT_DATE",
            "CURRENT_TIME",
            "CURRENT",
            "TIMESTAMP",
            "DATE",
            "TIME",
            "TOP",
            "BOTTOM",
            "BEGIN",
            "COMMIT",
            "ROLLBACK",
            "FUNCTION",
            "PROCEDURE",
            "LANGUAGE",
            "RETURNS",
            "CALL",
            "RETURNING",
            "DO",
            "WINDOW",
            "OVER",
            "PARTITION",
            "UNBOUNDED",
            "PRECEDING",
            "FOLLOWING",
            "TIES",
            "ROW",
            "ROWS",
            "GROUPS",
            "OTHERS"));

    @Override
    public int getOrder() {
        return 100;
    }

    @Override
    public String process(String sql, DialectType sourceDialect, DialectType targetDialect, TranslationConfig config) {
        if (sql == null || sql.isEmpty()) {
            return sql;
        }
        TranslationConfig.IdentifierCase identifierCase = config.getIdentifierCase();

        List<String> stringLiterals = new ArrayList<>();
        String protectedSql = protectStringLiterals(sql, stringLiterals);

        Pattern pattern = Pattern.compile(
                "(?<![\\w\"`])([a-zA-Z_][a-zA-Z0-9_]*)" + "(?:\\.(\\*|[a-zA-Z_][a-zA-Z0-9_]*))?" + "(?![\\w\"`(])");
        Matcher matcher = pattern.matcher(protectedSql);
        StringBuilder sb = new StringBuilder(protectedSql.length() + 64);
        int lastEnd = 0;

        while (matcher.find()) {
            sb.append(protectedSql, lastEnd, matcher.start());
            String part1 = matcher.group(1);
            String part2 = matcher.group(2);
            if (part2 != null) {
                sb.append('"').append(identifierCase.apply(part1)).append('"');
                if ("*".equals(part2)) {
                    sb.append(".*");
                } else {
                    sb.append('.')
                            .append('"')
                            .append(identifierCase.apply(part2))
                            .append('"');
                }
            } else {
                if (SQL_KEYWORDS.contains(part1.toUpperCase(Locale.ROOT))) {
                    sb.append(part1);
                } else {
                    sb.append('"').append(identifierCase.apply(part1)).append('"');
                }
            }
            lastEnd = matcher.end();
        }
        sb.append(protectedSql.substring(lastEnd));

        String quoted = quoteDmlTableNames(sb.toString(), identifierCase);
        return restoreStringLiterals(quoted, stringLiterals);
    }

    private static String quoteDmlTableNames(String sql, TranslationConfig.IdentifierCase identifierCase) {
        Pattern pattern = Pattern.compile(
                "(?i)\\b(INSERT\\s+INTO|UPDATE|DELETE\\s+FROM|FROM|JOIN)\\s+([a-zA-Z_][a-zA-Z0-9_]*)\\s*\\(");
        Matcher matcher = pattern.matcher(sql);
        StringBuffer sb = new StringBuffer(sql.length());
        while (matcher.find()) {
            String verb = matcher.group(1);
            String tableName = matcher.group(2);
            if (!SQL_KEYWORDS.contains(tableName.toUpperCase(Locale.ROOT))) {
                matcher.appendReplacement(sb, verb + " \"" + identifierCase.apply(tableName) + "\"(");
            }
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private static String protectStringLiterals(String sql, List<String> out) {
        StringBuilder sb = new StringBuilder(sql.length());
        int i = 0;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (c == '\'') {
                int start = i;
                i++;
                while (i < sql.length()) {
                    if (sql.charAt(i) == '\'') {
                        if (i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
                            i += 2;
                        } else {
                            i++;
                            break;
                        }
                    } else {
                        i++;
                    }
                }
                int index = out.size();
                out.add(sql.substring(start, i));
                sb.append('\0').append(index).append('\0');
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }

    private static String restoreStringLiterals(String sql, List<String> stringLiterals) {
        if (stringLiterals.isEmpty()) {
            return sql;
        }
        StringBuilder sb = new StringBuilder(sql.length());
        int i = 0;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (c == '\0') {
                int end = sql.indexOf('\0', i + 1);
                if (end > i) {
                    int index = Integer.parseInt(sql.substring(i + 1, end));
                    sb.append(stringLiterals.get(index));
                    i = end + 1;
                } else {
                    sb.append(c);
                    i++;
                }
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }
}
