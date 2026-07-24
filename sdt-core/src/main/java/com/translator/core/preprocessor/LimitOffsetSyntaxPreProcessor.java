package com.translator.core.preprocessor;

import java.util.Collections;
import java.util.Set;

import com.translator.core.DialectType;
import com.translator.core.config.TranslationConfig;

/**
 * 前置处理器：将 MySQL 逗号分隔的 LIMIT 语法 `LIMIT offset, count`
 * 规整为 ANSI 标准 `LIMIT count OFFSET offset` 形式。
 */
public class LimitOffsetSyntaxPreProcessor implements SourceDialectPreProcessor {

    @Override
    public Set<DialectType> getSourceDialects() {
        return Collections.singleton(DialectType.MYSQL);
    }

    @Override
    public int getOrder() {
        return 25;
    }

    @Override
    public String process(String sql, DialectType sourceDialect, DialectType targetDialect, TranslationConfig config) {
        if (sql == null || sql.isEmpty()) {
            return sql;
        }
        return sql.replaceAll("(?i)\\bLIMIT\\s+(\\d+)\\s*,\\s*(\\d+)", "LIMIT $2 OFFSET $1");
    }
}
