package com.translator.core.preprocessor;

import java.util.Collections;
import java.util.Set;

import com.translator.core.DialectType;
import com.translator.core.config.TranslationConfig;

/**
 * 前置处理器：将 MySQL `GROUP_CONCAT(expr SEPARATOR ',')` 规整为标准参数调用的 `GROUP_CONCAT(expr, ',')`。
 */
public class GroupConcatSeparatorPreProcessor implements SourceDialectPreProcessor {

    @Override
    public Set<DialectType> getSourceDialects() {
        return Collections.singleton(DialectType.MYSQL);
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
        return sql.replaceAll(
                "(?i)\\bGROUP_CONCAT\\s*\\(\\s*(.+?)\\s+SEPARATOR\\s+([^)]+)\\)",
                "GROUP_CONCAT($1, $2)");
    }
}
