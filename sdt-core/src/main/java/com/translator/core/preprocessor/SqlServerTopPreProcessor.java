package com.translator.core.preprocessor;

import java.util.Collections;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.translator.core.DialectType;
import com.translator.core.config.TranslationConfig;

/**
 * 前置处理器：将 SQL Server 的 `SELECT TOP n` 转换为标准 `LIMIT n`。
 */
public class SqlServerTopPreProcessor implements SourceDialectPreProcessor {

    private static final Pattern TOP_PATTERN = Pattern.compile("(?i)\\bSELECT\\s+(TOP\\s+(\\d+)\\s+)");

    @Override
    public Set<DialectType> getSourceDialects() {
        return Collections.singleton(DialectType.SQLSERVER);
    }

    @Override
    public int getOrder() {
        return 20;
    }

    @Override
    public String process(String sql, DialectType sourceDialect, DialectType targetDialect, TranslationConfig config) {
        if (sql == null || sql.isEmpty()) {
            return sql;
        }
        Matcher matcher = TOP_PATTERN.matcher(sql);
        if (!matcher.find()) {
            return sql;
        }
        String topNum = matcher.group(2);
        String result = sql.substring(0, matcher.start(1)) + sql.substring(matcher.end(1));
        return result.trim() + " LIMIT " + topNum;
    }
}
